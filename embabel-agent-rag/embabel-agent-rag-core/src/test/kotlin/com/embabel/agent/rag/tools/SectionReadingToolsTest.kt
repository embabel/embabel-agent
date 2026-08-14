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

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.ChunkStructure
import com.embabel.agent.rag.model.Retrievable
import com.embabel.agent.rag.service.SectionReader
import com.embabel.agent.rag.service.SectionSummary
import com.embabel.agent.rag.service.VectorSearch
import com.embabel.common.core.types.SimilarityResult
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SectionReadingToolsTest {

    private fun chunk(id: String, text: String): Chunk =
        Chunk(id = id, text = text, parentId = "parent", metadata = emptyMap())

    private fun chunkOf(id: String, text: String, structure: ChunkStructure): Chunk =
        Chunk.create(text = text, parentId = "parent", id = id, structure = structure)

    private class FakeSectionReader(
        private val sections: List<SectionSummary> = emptyList(),
        private val chunksByTitle: Map<String, List<Chunk>> = emptyMap(),
    ) : SectionReader {
        var lastDocumentTitle: String? = null

        override fun listSections(documentTitle: String?): List<SectionSummary> {
            lastDocumentTitle = documentTitle
            return sections
        }

        override fun readSection(sectionTitle: String, documentTitle: String?): List<Chunk> {
            lastDocumentTitle = documentTitle
            return chunksByTitle[sectionTitle] ?: emptyList()
        }
    }

    @Test
    fun `listSections groups by document with chunk counts`() {
        val tools = SectionReadingTools(
            FakeSectionReader(
                sections = listOf(
                    SectionSummary("Balance Sheets", "Acme 10-K", 10),
                    SectionSummary("Statements of Operations", "Acme 10-K", 7),
                    SectionSummary("Overview", "Widget Deck", 3),
                ),
            ),
        )
        val out = tools.listSections(null)
        assertTrue(out.contains("Document: Acme 10-K"), out)
        assertTrue(out.contains("- Balance Sheets (10 chunks)"), out)
        assertTrue(out.contains("Document: Widget Deck"), out)
    }

    @Test
    fun `listSections with no sections says so and names the filter`() {
        val out = SectionReadingTools(FakeSectionReader()).listSections("annual report")
        assertTrue(out.contains("No sections found"), out)
        assertTrue(out.contains("annual report"), out)
    }

    @Test
    fun `readSection renders chunks in given order and reports them to the listener at full score`() {
        var observed: List<SimilarityResult<out Retrievable>>? = null
        var observedQuery: String? = null
        val tools = SectionReadingTools(
            FakeSectionReader(
                chunksByTitle = mapOf(
                    "Balance Sheets" to listOf(chunk("c1", "header row"), chunk("c2", "cash 2,366")),
                ),
            ),
            resultsListener = { event ->
                observed = event.results
                observedQuery = event.query
            },
        )
        val out = tools.readSection("Balance Sheets")
        assertTrue(out.indexOf("header row") < out.indexOf("cash 2,366"), out)
        assertTrue(out.contains("Chunk ID: c1"), out)
        assertEquals(2, observed?.size)
        assertTrue(observed!!.all { it.score == 1.0 })
        assertEquals("readSection: Balance Sheets", observedQuery)
    }

    @Test
    fun `readSection for an unknown title points at listSections and fires no event`() {
        var fired = false
        val tools = SectionReadingTools(FakeSectionReader(), resultsListener = { fired = true })
        val out = tools.readSection("Nope", "Acme")
        assertTrue(out.contains("No section titled 'Nope'"), out)
        assertTrue(out.contains("listSections"), out)
        assertTrue(out.contains("Acme"), out)
        assertFalse(fired)
    }

    @Test
    fun `readSection refuses to blend documents - ambiguity returns a choice, not merged content`() {
        var fired = false
        val chunks = listOf(
            chunkOf("a1", "Nevada law", ChunkStructure(rootDocumentId = "doc-a", rootDocumentTitle = "Acme License")),
            chunkOf("b1", "Ontario law", ChunkStructure(rootDocumentId = "doc-b", rootDocumentTitle = "Widget License")),
        )
        val tools = SectionReadingTools(
            FakeSectionReader(chunksByTitle = mapOf("Governing Law" to chunks)),
            resultsListener = { fired = true },
        )
        val out = tools.readSection("Governing Law")
        assertFalse(out.contains("Nevada"), "content must not be returned on ambiguity: $out")
        assertTrue(out.contains("2 documents"), out)
        assertTrue(out.contains("Acme License"), out)
        assertTrue(out.contains("documentTitle"), out)
        assertFalse(fired, "ambiguous reads are not evidence")
    }

    @Test
    fun `provenance the chunker writes as metadata reaches the guard as structure`() {
        // The chunker writes root_document_title into metadata; the Chunk factory lifts it into
        // ChunkStructure. If the two ever stop agreeing, the guard silently stops firing.
        val chunks = listOf(
            Chunk(
                id = "a1", text = "Nevada law", parentId = "p",
                metadata = mapOf(ChunkStructure.ROOT_DOCUMENT_TITLE to "Acme License"),
            ),
            Chunk(
                id = "b1", text = "Ontario law", parentId = "p",
                metadata = mapOf(ChunkStructure.ROOT_DOCUMENT_TITLE to "Widget License"),
            ),
        )
        assertEquals("Acme License", chunks.first().structure.rootDocumentTitle)
        val out = SectionReadingTools(FakeSectionReader(chunksByTitle = mapOf("Governing Law" to chunks)))
            .readSection("Governing Law")
        assertTrue(out.contains("2 documents"), out)
    }

    @Test
    fun `unknown provenance fails closed - a chunk we cannot attribute is not blended in`() {
        val chunks = listOf(
            chunkOf("a1", "Nevada law", ChunkStructure(rootDocumentId = "doc-a", rootDocumentTitle = "Acme License")),
            chunk("b1", "Ontario law"),
        )
        val out = SectionReadingTools(FakeSectionReader(chunksByTitle = mapOf("Governing Law" to chunks)))
            .readSection("Governing Law")
        assertFalse(out.contains("Nevada"), "unattributed chunks must not be blended in: $out")
        assertTrue(out.contains("2 documents"), out)
        assertTrue(out.contains("unattributed"), out)
    }

    @Test
    fun `an already-filtered ambiguous read says the filter was not specific enough`() {
        val chunks = listOf(
            chunkOf("a1", "Nevada law", ChunkStructure(rootDocumentId = "a", rootDocumentTitle = "Acme License 2024")),
            chunkOf("b1", "Ontario law", ChunkStructure(rootDocumentId = "b", rootDocumentTitle = "Acme License 2025")),
        )
        val out = SectionReadingTools(FakeSectionReader(chunksByTitle = mapOf("Governing Law" to chunks)))
            .readSection("Governing Law", documentTitle = "Acme")
        assertTrue(out.contains("still matches more than one"), out)
        assertTrue(out.contains("Acme"), out)
    }

    @Test
    fun `chunks a single document owns are read whole, not treated as ambiguous`() {
        val chunks = listOf(
            chunkOf("a1", "header row", ChunkStructure(rootDocumentId = "doc-a", rootDocumentTitle = "Acme 10-K")),
            chunkOf("a2", "cash 2,366", ChunkStructure(rootDocumentId = "doc-a", rootDocumentTitle = "Acme 10-K")),
        )
        val out = SectionReadingTools(FakeSectionReader(chunksByTitle = mapOf("Balance Sheets" to chunks)))
            .readSection("Balance Sheets")
        assertTrue(out.contains("header row"), out)
        assertTrue(out.contains("cash 2,366"), out)
    }

    @Test
    fun `readSection output over the cap is truncated with guidance, in order`() {
        val big = (1..50).map { chunk("c$it", "x".repeat(1000)) }
        val tools = SectionReadingTools(
            FakeSectionReader(chunksByTitle = mapOf("Big" to big)),
            maxReadSectionChars = 5_000,
        )
        val out = tools.readSection("Big")
        assertTrue(out.length < 6_000, "capped: ${out.length}")
        assertTrue(out.contains("TRUNCATED"), out)
        assertTrue(out.contains("document order"), out)
    }

    @Test
    fun `only the chunks that survive truncation are reported as evidence`() {
        // A chunk the model never saw must not be observable evidence: an attribution check
        // would then read a figure that only exists in the cut-away text as grounded.
        var observed: List<SimilarityResult<out Retrievable>>? = null
        val big = (1..50).map { chunk("c$it", "x".repeat(1000)) }
        val tools = SectionReadingTools(
            FakeSectionReader(chunksByTitle = mapOf("Big" to big)),
            resultsListener = { observed = it.results },
            maxReadSectionChars = 5_000,
        )
        val out = tools.readSection("Big")
        val reported = observed!!.map { it.match.id }
        assertTrue(reported.size < big.size, "must not report all 50: ${reported.size}")
        reported.forEach { id ->
            assertTrue(out.contains("Chunk ID: $id"), "reported $id but it is not in the output")
        }
        assertTrue(out.contains("showing ${reported.size} of 50 chunks"), out)
    }

    @Test
    fun `a single chunk larger than the cap is still cut, and says so`() {
        val tools = SectionReadingTools(
            FakeSectionReader(chunksByTitle = mapOf("Huge" to listOf(chunk("c1", "x".repeat(10_000))))),
            maxReadSectionChars = 5_000,
        )
        val out = tools.readSection("Huge")
        assertTrue(out.contains("TRUNCATED"), out)
        assertTrue(out.contains("cut at 5000"), out)
    }

    @Test
    fun `document title filter is passed through to the store`() {
        val reader = FakeSectionReader()
        SectionReadingTools(reader).listSections("acme")
        assertEquals("acme", reader.lastDocumentTitle)
        SectionReadingTools(reader).readSection("Balance Sheets", "widget")
        assertEquals("widget", reader.lastDocumentTitle)
    }

    @Test
    fun `ToolishRag exposes section tools only when the store implements SectionReader`() {
        val withSections = ToolishRag(
            name = "with",
            description = "store with sections",
            searchOperations = FakeSectionReader(),
        ).tools().map { it.definition.name }
        // Tool names are prefixed with the rag name by the UnfoldingTool machinery.
        assertTrue(withSections.any { it.endsWith("listSections") }, withSections.toString())
        assertTrue(withSections.any { it.endsWith("readSection") }, withSections.toString())

        val without = ToolishRag(
            name = "without",
            description = "plain vector store",
            searchOperations = mockk<VectorSearch>(relaxed = true),
        ).tools().map { it.definition.name }
        assertNull(without.find { it.endsWith("listSections") }, without.toString())
    }
}
