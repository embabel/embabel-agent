/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
import com.embabel.agent.rag.service.*
import com.embabel.common.core.types.SimpleSimilaritySearchResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ToolishRagTest {

    private fun createChunk(
        id: String,
        text: String,
    ): Chunk =
        Chunk(id = id, text = text, parentId = "parent", metadata = emptyMap())

    @Nested
    inner class ToolInstancesTests {

        @Test
        fun `should add VectorSearchTools when searchOperations is VectorSearch`() {
            val vectorSearch = mockk<VectorSearch>()

            val toolishRag = ToolishRag(
                name = "test-rag",
                description = "Test RAG",
                searchOperations = vectorSearch
            )

            val toolInstances = toolishRag.toolInstances()

            assertEquals(1, toolInstances.size)
            assertTrue(toolInstances[0] is VectorSearchTools)
        }

        @Test
        fun `should add TextSearchTools when searchOperations is TextSearch`() {
            val textSearch = mockk<TextSearch>()

            val toolishRag = ToolishRag(
                name = "test-rag",
                description = "Test RAG",
                searchOperations = textSearch
            )

            val toolInstances = toolishRag.toolInstances()

            assertEquals(1, toolInstances.size)
            assertTrue(toolInstances[0] is TextSearchTools)
        }

        @Test
        fun `should add both tools when searchOperations is CoreSearchOperations`() {
            val coreSearch = mockk<CoreSearchOperations>()

            val toolishRag = ToolishRag(
                name = "test-rag",
                description = "Test RAG",
                searchOperations = coreSearch
            )

            val toolInstances = toolishRag.toolInstances()

            assertEquals(2, toolInstances.size)
            assertTrue(toolInstances.any { it is VectorSearchTools })
            assertTrue(toolInstances.any { it is TextSearchTools })
        }
    }

    @Nested
    inner class NotesTests {

        @Test
        fun `should include default goal in notes`() {
            val vectorSearch = mockk<VectorSearch>()

            val toolishRag = ToolishRag(
                name = "test-rag",
                description = "Test RAG",
                searchOperations = vectorSearch
            )

            val notes = toolishRag.notes()

            assertTrue(notes.contains("Search acceptance criteria:"))
            assertTrue(notes.contains("Continue search until the question is answered"))
        }

        @Test
        fun `should include lucene syntax notes in notes`() {
            val textSearch = mockk<TextSearch>()
            val support = "basic +, -, and quotes for phrases"
            every {
                textSearch.luceneSyntaxNotes
            } returns support

            val toolishRag = ToolishRag(
                name = "test-rag",
                description = "Test RAG",
                searchOperations = textSearch
            )

            val notes = toolishRag.notes()

            assertTrue(notes.contains("Lucene search syntax support: $support"))
        }

        @Test
        fun `should include custom goal in notes`() {
            val vectorSearch = mockk<VectorSearch>()
            val customGoal = "Find all relevant documents about Kotlin"

            val toolishRag = ToolishRag(
                name = "test-rag",
                description = "Test RAG",
                searchOperations = vectorSearch,
                goal = customGoal
            )

            val notes = toolishRag.notes()

            assertTrue(notes.contains(customGoal))
        }
    }

    @Nested
    inner class VectorSearchToolsTests {

        @Test
        fun `vectorSearch should delegate to VectorSearch with correct parameters`() {
            val vectorSearch = mockk<VectorSearch>()
            val chunk = createChunk("chunk1", "Test content")

            every {
                vectorSearch.vectorSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns listOf(SimpleSimilaritySearchResult(match = chunk, score = 0.9))

            val tools = VectorSearchTools(vectorSearch)
            val result = tools.vectorSearch("test query", 10, 0.5)

            verify {
                vectorSearch.vectorSearch(
                    match<TextSimilaritySearchRequest> { request ->
                        request.query == "test query" &&
                                request.topK == 10 &&
                                request.similarityThreshold == 0.5
                    },
                    Chunk::class.java
                )
            }

            assertTrue(result.contains("1 results:"))
            assertTrue(result.contains("Test content"))
            assertTrue(result.contains("0.90"))
        }

        @Test
        fun `vectorSearch should return formatted empty results`() {
            val vectorSearch = mockk<VectorSearch>()

            every {
                vectorSearch.vectorSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns emptyList()

            val tools = VectorSearchTools(vectorSearch)
            val result = tools.vectorSearch("test query", 10, 0.5)

            assertEquals("0 results:", result)
        }

        @Test
        fun `vectorSearch should handle multiple results`() {
            val vectorSearch = mockk<VectorSearch>()
            val chunk1 = createChunk("chunk1", "First content")
            val chunk2 = createChunk("chunk2", "Second content")

            every {
                vectorSearch.vectorSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns listOf(
                SimpleSimilaritySearchResult(match = chunk1, score = 0.9),
                SimpleSimilaritySearchResult(match = chunk2, score = 0.8)
            )

            val tools = VectorSearchTools(vectorSearch)
            val result = tools.vectorSearch("test query", 10, 0.5)

            assertTrue(result.contains("2 results:"))
            assertTrue(result.contains("First content"))
            assertTrue(result.contains("Second content"))
        }
    }

    @Nested
    inner class TextSearchToolsTests {

        @Test
        fun `textSearch should delegate to TextSearch with correct parameters`() {
            val textSearch = mockk<TextSearch>()
            val chunk = createChunk("chunk1", "Test content")

            every {
                textSearch.textSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns listOf(SimpleSimilaritySearchResult(match = chunk, score = 0.85))

            val tools = TextSearchTools(textSearch)
            val result = tools.textSearch("+kotlin +coroutines", 5, 0.7)

            verify {
                textSearch.textSearch(
                    match<TextSimilaritySearchRequest> { request ->
                        request.query == "+kotlin +coroutines" &&
                                request.topK == 5 &&
                                request.similarityThreshold == 0.7
                    },
                    Chunk::class.java
                )
            }

            assertTrue(result.contains("1 results:"))
            assertTrue(result.contains("Test content"))
        }

        @Test
        fun `textSearch should return formatted empty results`() {
            val textSearch = mockk<TextSearch>()

            every {
                textSearch.textSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns emptyList()

            val tools = TextSearchTools(textSearch)
            val result = tools.textSearch("nonexistent", 10, 0.5)

            assertEquals("0 results:", result)
        }

        @Test
        fun `regexSearch should delegate to TextSearch with correct parameters`() {
            val textSearch = mockk<TextSearch>()
            val chunk = createChunk("chunk1", "Error E001 occurred")

            every {
                textSearch.regexSearch(any<Regex>(), any(), Chunk::class.java)
            } returns listOf(SimpleSimilaritySearchResult(match = chunk, score = 1.0))

            val tools = TextSearchTools(textSearch)
            val result = tools.regexSearch("E\\d{3}", 10)

            verify {
                textSearch.regexSearch(
                    match<Regex> { it.pattern == "E\\d{3}" },
                    10,
                    Chunk::class.java
                )
            }

            assertTrue(result.contains("1 results:"))
            assertTrue(result.contains("Error E001 occurred"))
        }

        @Test
        fun `regexSearch should return formatted empty results`() {
            val textSearch = mockk<TextSearch>()

            every {
                textSearch.regexSearch(any<Regex>(), any(), Chunk::class.java)
            } returns emptyList()

            val tools = TextSearchTools(textSearch)
            val result = tools.regexSearch("nonexistent pattern", 10)

            assertEquals("0 results:", result)
        }

        @Test
        fun `regexSearch should handle multiple matches`() {
            val textSearch = mockk<TextSearch>()
            val chunk1 = createChunk("chunk1", "Error E001")
            val chunk2 = createChunk("chunk2", "Error E002")

            every {
                textSearch.regexSearch(any<Regex>(), any(), Chunk::class.java)
            } returns listOf(
                SimpleSimilaritySearchResult(match = chunk1, score = 1.0),
                SimpleSimilaritySearchResult(match = chunk2, score = 1.0)
            )

            val tools = TextSearchTools(textSearch)
            val result = tools.regexSearch("E\\d{3}", 10)

            assertTrue(result.contains("2 results:"))
            assertTrue(result.contains("Error E001"))
            assertTrue(result.contains("Error E002"))
        }
    }

    @Nested
    inner class IntegrationTests {

        @Test
        fun `toolInstances should return working tools that delegate correctly`() {
            val coreSearch = mockk<CoreSearchOperations>()
            val chunk = createChunk("chunk1", "Integration test content")

            every {
                coreSearch.vectorSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns listOf(SimpleSimilaritySearchResult(match = chunk, score = 0.95))

            every {
                coreSearch.textSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns listOf(SimpleSimilaritySearchResult(match = chunk, score = 0.85))

            val toolishRag = ToolishRag(
                name = "integration-test",
                description = "Integration Test RAG",
                searchOperations = coreSearch
            )

            val toolInstances = toolishRag.toolInstances()
            assertEquals(2, toolInstances.size)

            // Get and use VectorSearchTools
            val vectorTools = toolInstances.filterIsInstance<VectorSearchTools>().first()
            val vectorResult = vectorTools.vectorSearch("test", 5, 0.5)
            assertTrue(vectorResult.contains("Integration test content"))

            // Get and use TextSearchTools
            val textTools = toolInstances.filterIsInstance<TextSearchTools>().first()
            val textResult = textTools.textSearch("test", 5, 0.5)
            assertTrue(textResult.contains("Integration test content"))

            verify(exactly = 1) { coreSearch.vectorSearch(any(), Chunk::class.java) }
            verify(exactly = 1) { coreSearch.textSearch(any(), Chunk::class.java) }
        }
    }

    @Nested
    inner class ConstructorTests {

        @Test
        fun `should use default goal when not specified`() {
            val vectorSearch = mockk<VectorSearch>()

            val toolishRag = ToolishRag(
                name = "test",
                description = "Test",
                searchOperations = vectorSearch
            )

            assertEquals(ToolishRag.DEFAULT_GOAL, toolishRag.goal)
        }

        @Test
        fun `should use default formatter when not specified`() {
            val vectorSearch = mockk<VectorSearch>()

            val toolishRag = ToolishRag(
                name = "test",
                description = "Test",
                searchOperations = vectorSearch
            )

            assertEquals(SimpleRetrievableResultsFormatter, toolishRag.formatter)
        }

        @Test
        fun `should accept custom formatter`() {
            val vectorSearch = mockk<VectorSearch>()
            val customFormatter = mockk<RetrievableResultsFormatter>()

            val toolishRag = ToolishRag(
                name = "test",
                description = "Test",
                searchOperations = vectorSearch,
                formatter = customFormatter
            )

            assertEquals(customFormatter, toolishRag.formatter)
        }

        @Test
        fun `should expose name and description`() {
            val vectorSearch = mockk<VectorSearch>()

            val toolishRag = ToolishRag(
                name = "my-rag",
                description = "My RAG description",
                searchOperations = vectorSearch
            )

            assertEquals("my-rag", toolishRag.name)
            assertEquals("My RAG description", toolishRag.description)
        }
    }
}
