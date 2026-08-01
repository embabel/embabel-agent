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
package com.embabel.agent.rag.ingestion

import com.embabel.agent.rag.model.LeafSection
import com.embabel.agent.rag.model.MaterializedDocument
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Markdown tables must never be severed from their header row by chunking.
 *
 * A table row without its header is worse than a missing row: retrieval surfaces
 * the value next to the WRONG label. Observed with a financial results deck:
 * the sliding overlap window emitted `| Impairment expense | (17m) |` with the
 * division header cut off above the window boundary, and the divisional movement
 * was quoted as the group figure.
 *
 * Two paths produce mid-table chunks:
 *  1. the OVERLAP text prepended to the next chunk starts inside a table
 *     (no sentence boundary inside a table, so the word-boundary fallback fired);
 *  2. a table paragraph larger than maxChunkSize is split by "sentences",
 *     which have no meaning inside a table.
 */
class TableAwareChunkingTest {

    private val chunker = ContentChunker(
        ContentChunker.Config(),
        ChunkTransformer.NO_OP,
    )

    /** Every chunk that contains a table row must also contain a header row for it. */
    private fun assertNoOrphanRows(texts: List<String>, headerMarker: String, rowMarker: String) {
        texts.filter { it.contains(rowMarker) }.also {
            assertTrue(it.isNotEmpty()) { "the marker row must survive chunking somewhere" }
        }.forEach { text ->
            assertTrue(text.contains(headerMarker)) {
                "chunk contains table row '$rowMarker' without its header '$headerMarker':\n$text"
            }
        }
    }

    private fun chunkTexts(content: String): List<String> {
        val leaf = LeafSection(id = "leaf-1", title = "", text = content)
        val container = MaterializedDocument(
            id = "doc-1",
            title = "Results",
            children = listOf(leaf),
            metadata = emptyMap(),
            uri = "test://results",
        )
        return chunker.chunk(container).map { it.text }
    }

    @Test
    fun `overlap that lands inside a table re-attaches the table header instead of emitting orphan rows`() {
        // Sized so the first chunk ends with the table and the 200-char overlap
        // window starts inside it: prose(~1100) + table(~350, LONGER than the
        // overlap window so the header sits above it) fits maxChunkSize 1500; the
        // next paragraph forces the split, and the overlap prepended to chunk 2
        // is the table's TAIL — rows without the header.
        val prose = "The bank reported broadly favourable conditions across all operating segments this half. ".repeat(12).trim()
        val table = """
            | Division IBM             | vs prior half        |
            |--------------------------|----------------------|
            | Income from operations   | up nine percent      |
            | Expenses from operations | up five percent      |
            | Provision releases held  | broadly flat overall |
            | Impairment expense | (17m)                     |
        """.trimIndent()
        // Small enough that the overlap PASSES the fits-check and is actually prepended.
        val followingProse = "Turning to the outlook, management expects funding conditions to remain stable through the year. ".repeat(12).trim()

        val texts = chunkTexts("$prose\n\n$table\n\n$followingProse")

        assertTrue(texts.size >= 2) { "the fixture must actually split (got ${texts.size} chunk(s))" }
        assertNoOrphanRows(texts, headerMarker = "Division IBM", rowMarker = "Impairment expense")
    }

    @Test
    fun `a table larger than maxChunkSize is split by rows with the header repeated in every piece`() {
        val header = "| Metric row | 1H25 | 2H25 | 1H26 |\n|---|---|---|---|"
        val rows = (1..60).joinToString("\n") { i ->
            "| Operating metric number $i | ${1000 + i} | ${2000 + i} | ${3000 + i} |"
        }
        val texts = chunkTexts("$header\n$rows")

        assertTrue(texts.size >= 2) { "a ~3600-char table must split (got ${texts.size} chunk(s))" }
        texts.forEach { text ->
            assertTrue(text.length <= 1500) { "chunk exceeds maxChunkSize: ${text.length}" }
            assertTrue(text.contains("Metric row")) {
                "every piece of a split table must repeat the header:\n$text"
            }
        }
        // No row lost across the split.
        (1..60).forEach { i ->
            assertTrue(texts.any { it.contains("| ${3000 + i} |") }) { "row $i lost in the split" }
        }
    }

    @Test
    fun `a row wider than maxChunkSize survives WHOLE as an oversized chunk — never a character cut`() {
        // Observed with real financial registers (wide tables, verbose headers): the
        // header plus a SINGLE row exceeds the budget. The emergency character cut
        // then severed rows from headers and even split numeric tokens in half,
        // leaving values retrievable next to the wrong label or not at all.
        val wideChunker = ContentChunker(
            ContentChunker.Config(maxChunkSize = 800, overlapSize = 100),
            ChunkTransformer.NO_OP,
        )
        val headerCells = (1..11).joinToString(" | ") { "Reporting column heading number $it" }
        val header = "| $headerCells |\n|${"---------------------------------|".repeat(11)}"
        val rows = (1..5).joinToString("\n") { i ->
            val cells = (1..10).joinToString(" | ") { c -> "value ${i * 100 + c}".padEnd(30) }
            "| Facility register row $i | $cells | ${i},200,00$i |"
        }
        val leaf = LeafSection(id = "leaf-1", title = "", text = "Intro prose.\n\n$header\n$rows\n\nClosing prose.")
        val container = MaterializedDocument(
            id = "doc-1", title = "Register", children = listOf(leaf), metadata = emptyMap(), uri = "test://register",
        )
        val texts = wideChunker.chunk(container).map { it.text }

        (1..5).forEach { i ->
            val carrying = texts.filter { it.contains("${i},200,00$i") }
            assertTrue(carrying.isNotEmpty()) { "row $i's value must survive chunking INTACT" }
            carrying.forEach { text ->
                assertTrue(text.contains("Facility register row $i")) {
                    "value severed from its row label:\n$text"
                }
                assertTrue(text.contains("Reporting column heading number 1")) {
                    "row emitted without its table header:\n$text"
                }
            }
        }
    }

    @Test
    fun `a heading glued to a table by single newlines still splits by rows — never by fake sentences`() {
        // Observed with a real quarterly report: the section parser serialized
        // "Appendix" and its reconciliation table into ONE paragraph (single
        // newlines). The whole block fell through to sentence splitting, which cut
        // at "excl." and even INSIDE the numeric token "5,120" — the value became
        // unfindable by exact match in every chunk.
        val tightChunker = ContentChunker(
            ContentChunker.Config(maxChunkSize = 800, overlapSize = 100),
            ChunkTransformer.NO_OP,
        )
        val header = "| Key financials reconciliation                            | 2H25 | Qtr avg | vs 1Q25 | vs 2H25 |\n" +
            "|----------------------------------------------------------|------|---------|---------|---------|"
        val rows = listOf(
            "| Total operating income                                   | 14,368 | 7,184 | 6% | 3% |",
            "| Operating expenses excl. restructuring and notable items | (6,494) | (3,247) | 7% | 4% |",
            "| Restructuring and notable items                          | (130) | (65) | n/a | 88% |",
            "| Total operating expenses                                 | (6,624) | (3,312) | 11% | 5% |",
            "| Operating performance                                    | 7,744 | 3,872 | 2% | 1% |",
            "| Loan impairment expense                                  | (406) | (203) | 38% | 8% |",
            "| Cash NPAT from continuing operations                     | 5,120 | 2,560 | 2% | 1% |",
        ).joinToString("\n")
        val leaf = LeafSection(id = "leaf-1", title = "", text = "Appendix\n$header\n$rows\nFootnotes follow.")
        val container = MaterializedDocument(
            id = "doc-1", title = "Update", children = listOf(leaf), metadata = emptyMap(), uri = "test://update",
        )
        val texts = tightChunker.chunk(container).map { it.text }

        val carrying = texts.filter { it.contains("5,120") }
        assertTrue(carrying.isNotEmpty()) { "the value must survive INTACT (not cut into '5' + ',120')" }
        carrying.forEach { text ->
            assertTrue(text.contains("Cash NPAT from continuing operations")) {
                "value severed from its row label:\n$text"
            }
            assertTrue(text.contains("Key financials reconciliation")) {
                "row emitted without its table header:\n$text"
            }
        }
    }

    @Test
    fun `a table that fits is left intact — no header duplication, no re-slicing`() {
        val table = """
            | Metric | Value |
            |--------|-------|
            | Margin | 2.04% |
        """.trimIndent()
        val texts = chunkTexts(table)

        assertTrue(texts.size == 1)
        assertTrue(texts.single().lines().count { it.contains("| Metric | Value |") } == 1)
    }
}
