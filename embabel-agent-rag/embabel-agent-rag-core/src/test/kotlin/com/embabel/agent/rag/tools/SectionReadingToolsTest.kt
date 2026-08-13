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
            Chunk(id = "a1", text = "Nevada law", parentId = "p", metadata = mapOf("root_document_title" to "Acme License")),
            Chunk(id = "b1", text = "Ontario law", parentId = "p", metadata = mapOf("root_document_title" to "Widget License")),
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
