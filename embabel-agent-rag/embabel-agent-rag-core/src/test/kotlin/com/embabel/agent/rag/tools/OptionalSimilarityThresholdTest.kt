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

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.service.CoreSearchOperations
import com.embabel.agent.rag.service.TextSearch
import com.embabel.agent.rag.service.VectorSearch
import com.embabel.common.core.types.SimpleSimilaritySearchResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * `threshold` is optional on the search tools.
 *
 * A model asked to supply an absolute similarity cutoff has no feedback signal to
 * choose one, and cosine scores are not calibrated across embedding models. Models
 * guessed high (0.7-0.8), got nothing back, and told the user the corpus did not
 * cover the question — with the answer sitting in the index.
 */
class OptionalSimilarityThresholdTest {

    private fun chunk(id: String, text: String): Chunk =
        Chunk.create(id = id, text = text, parentId = "parent")

    @Nested
    inner class VectorSearchThreshold {

        @Test
        fun `omitted threshold falls back to the configured floor`() {
            val vectorSearch = mockk<VectorSearch>()
            every {
                vectorSearch.vectorSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns listOf(SimpleSimilaritySearchResult(match = chunk("c1", "content"), score = 0.4))

            val tools = VectorSearchTools(
                vectorSearch,
                searchDefaults = SearchDefaults(vectorSimilarityThreshold = 0.15),
            )
            tools.vectorSearch("a query", 10)

            verify {
                vectorSearch.vectorSearch(
                    match<TextSimilaritySearchRequest> { it.similarityThreshold == 0.15 },
                    Chunk::class.java,
                )
            }
        }

        @Test
        fun `an explicit threshold still wins`() {
            val vectorSearch = mockk<VectorSearch>()
            every {
                vectorSearch.vectorSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns emptyList()

            val tools = VectorSearchTools(
                vectorSearch,
                searchDefaults = SearchDefaults(vectorSimilarityThreshold = 0.15),
            )
            tools.vectorSearch("a query", 10, 0.6)

            verify {
                vectorSearch.vectorSearch(
                    match<TextSimilaritySearchRequest> { it.similarityThreshold == 0.6 },
                    Chunk::class.java,
                )
            }
        }
    }

    @Nested
    inner class TextSearchThreshold {

        private fun toolsReturningOneChunk(defaults: SearchDefaults): TextSearchTools {
            val textSearch = mockk<TextSearch>()
            every { textSearch.luceneSyntaxNotes } returns "Full Lucene syntax supported"
            every {
                textSearch.textSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns listOf(SimpleSimilaritySearchResult(match = chunk("c1", "content"), score = 0.7))
            return TextSearchTools(textSearch, searchDefaults = defaults)
        }

        @Test
        fun `a call omitting threshold succeeds instead of erroring`() {
            val tools = toolsReturningOneChunk(SearchDefaults(textSimilarityThreshold = 0.0))

            val result = tools.call("""{"query": "kotlin", "topK": 5}""")

            assertTrue(result is Tool.Result.Text, "omitting the optional threshold must not be an error, was $result")
            assertTrue((result as Tool.Result.Text).content.contains("content"))
        }

        @Test
        fun `a JSON null threshold falls back to the floor rather than erroring`() {
            val tools = toolsReturningOneChunk(SearchDefaults(textSimilarityThreshold = 0.0))

            val result = tools.call("""{"query": "kotlin", "topK": 5, "threshold": null}""")

            assertTrue(result is Tool.Result.Text, "a null threshold must fall back to the floor, was $result")
        }

        @Test
        fun `query and topK remain required`() {
            val tools = toolsReturningOneChunk(SearchDefaults())

            assertTrue(tools.call("""{"topK": 5}""") is Tool.Result.Error, "query must still be required")
            assertTrue(tools.call("""{"query": "kotlin"}""") is Tool.Result.Error, "topK must still be required")
        }

        @Test
        fun `the declared schema marks threshold optional and the others required`() {
            val tools = toolsReturningOneChunk(SearchDefaults())
            val parameters = tools.definition.inputSchema.parameters.associateBy { it.name }

            assertEquals(false, parameters.getValue("threshold").required)
            assertEquals(true, parameters.getValue("query").required)
            assertEquals(true, parameters.getValue("topK").required)
        }
    }

    @Nested
    inner class Wiring {

        @Test
        fun `ToolishRag propagates its search defaults to the search tools`() {
            val searchOperations = mockk<CoreSearchOperations>()
            every { searchOperations.luceneSyntaxNotes } returns ""
            every {
                searchOperations.vectorSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns emptyList()

            val toolishRag = ToolishRag(
                name = "docs",
                description = "docs",
                searchOperations = searchOperations,
            ).withSearchDefaults(SearchDefaults(vectorSimilarityThreshold = 0.42))

            val vectorSearchTool = toolishRag.tools().single { it.definition.name.endsWith("vectorSearch") }
            vectorSearchTool.call("""{"query": "a query", "topK": 3}""")

            verify {
                searchOperations.vectorSearch(
                    match<TextSimilaritySearchRequest> { it.similarityThreshold == 0.42 },
                    Chunk::class.java,
                )
            }
        }
    }
}
