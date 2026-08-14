/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.agent.rag.tools

import com.embabel.agent.rag.ingestion.ChunkTransformer
import com.embabel.agent.rag.ingestion.ContentChunker
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.LeafSection
import com.embabel.agent.rag.model.MaterializedDocument
import com.embabel.agent.rag.service.SectionSummary
import com.embabel.agent.rag.service.SectionReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The anti-blending guard, exercised over chunks the REAL chunker produced.
 *
 * The unit tests in [SectionReadingToolsTest] hand the guard its provenance. That is exactly how
 * the defect this covers survived: the guard read metadata keys no chunker wrote, so it passed a
 * test that supplied them and did nothing at all against a store. Here nothing is hand-written —
 * two contracts are ingested through [ContentChunker], and the store below matches section titles
 * the way a real [SectionReader] must. If chunker and guard ever stop agreeing on where document
 * provenance lives, these fail.
 */
class SectionReadingProvenanceTest {

    private val chunker = ContentChunker(ContentChunker.Config(), ChunkTransformer.NO_OP)

    /**
     * The minimum a store must do to satisfy [SectionReader]: match a section title
     * case-insensitively across everything it holds, in ingestion order. Deliberately NOT
     * document-aware — telling the documents apart is the tools' job, which is what is under test.
     */
    private class IngestedSections(chunks: List<Chunk>) : SectionReader {

        private val chunks: List<Chunk> = chunks.sortedBy { it.structure.sequenceNumber ?: 0 }

        override fun listSections(documentTitle: String?): List<SectionSummary> = chunks
            .filter { it.matchesDocument(documentTitle) }
            .groupBy { (it.structure.rootDocumentTitle ?: "") to it.sectionTitle() }
            .map { (key, group) -> SectionSummary(key.second, key.first, group.size) }

        override fun readSection(sectionTitle: String, documentTitle: String?): List<Chunk> = chunks
            .filter { it.sectionTitle().equals(sectionTitle, ignoreCase = true) }
            .filter { it.matchesDocument(documentTitle) }

        /** A chunk is filed under its leaf section where it has one, else its container. */
        private fun Chunk.sectionTitle(): String =
            structure.leafSectionTitle ?: structure.containerSectionTitle ?: ""

        private fun Chunk.matchesDocument(documentTitle: String?): Boolean =
            documentTitle == null || structure.rootDocumentTitle.orEmpty().contains(documentTitle, true)
    }

    /** Long enough that the chunker takes its per-leaf path rather than folding the document into one chunk. */
    private fun clause(text: String): String = text + " " + "The parties acknowledge the foregoing. ".repeat(30)

    private fun contract(title: String, governingLaw: String) = MaterializedDocument(
        id = UUID.randomUUID().toString(),
        uri = "test://${title.replace(' ', '-')}",
        title = title,
        children = listOf(
            LeafSection(
                id = UUID.randomUUID().toString(),
                uri = "test://${title.replace(' ', '-')}#gl",
                title = "Governing Law",
                text = clause(governingLaw),
                parentId = title,
            ),
            LeafSection(
                id = UUID.randomUUID().toString(),
                uri = "test://${title.replace(' ', '-')}#term",
                title = "Term and Termination",
                text = clause("This agreement runs for three years."),
                parentId = title,
            ),
        ),
    )

    private fun ingest(vararg documents: MaterializedDocument) =
        IngestedSections(documents.flatMap { chunker.chunk(it) })

    @Test
    fun `two ingested contracts sharing a section name are not concatenated`() {
        val store = ingest(
            contract("Acme License", "This agreement is governed by the laws of Nevada."),
            contract("Widget License", "This agreement is governed by the laws of Ontario."),
        )

        val out = SectionReadingTools(store).readSection("Governing Law")

        // The measured CUAD failure: one contract's clause quoted as the other's.
        assertFalse(out.contains("Nevada"), "must not return one contract's clause: $out")
        assertFalse(out.contains("Ontario"), "must not return the other contract's clause: $out")
        assertTrue(out.contains("2 documents"), out)
        assertTrue(out.contains("Acme License"), out)
        assertTrue(out.contains("Widget License"), out)
    }

    @Test
    fun `naming the document reads that one whole`() {
        val store = ingest(
            contract("Acme License", "This agreement is governed by the laws of Nevada."),
            contract("Widget License", "This agreement is governed by the laws of Ontario."),
        )

        val out = SectionReadingTools(store).readSection("Governing Law", documentTitle = "Acme")

        assertTrue(out.contains("Nevada"), out)
        assertFalse(out.contains("Ontario"), out)
    }

    @Test
    fun `a section of one ingested document is read whole`() {
        val store = ingest(contract("Acme License", "This agreement is governed by the laws of Nevada."))

        val out = SectionReadingTools(store).readSection("Governing Law")

        assertTrue(out.contains("Nevada"), out)
        assertFalse(out.contains("2 documents"), out)
    }

    @Test
    fun `listSections attributes each section to the document it came from`() {
        val store = ingest(contract("Acme License", "Nevada."), contract("Widget License", "Ontario."))

        val out = SectionReadingTools(store).listSections()

        assertTrue(out.contains("Document: Acme License"), out)
        assertTrue(out.contains("Document: Widget License"), out)
    }

    @Test
    fun `the chunker is the only source of provenance in this test`() {
        // Guards the guard: if the chunker stops writing the title, the ambiguity check above
        // silently degrades to "one unattributed document" and would blend again.
        val chunks = chunker.chunk(contract("Acme License", "This agreement is governed by the laws of Nevada."))
            .toList()

        assertTrue(chunks.isNotEmpty())
        chunks.forEach {
            assertEquals("Acme License", it.structure.rootDocumentTitle, "chunk ${it.id}")
        }
        assertEquals(
            setOf("Governing Law", "Term and Termination"),
            chunks.mapNotNull { it.structure.leafSectionTitle }.toSet(),
        )
    }
}
