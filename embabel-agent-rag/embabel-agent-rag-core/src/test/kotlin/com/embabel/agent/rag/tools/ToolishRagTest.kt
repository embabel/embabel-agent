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
import com.embabel.agent.rag.model.SimpleEntityData
import com.embabel.agent.rag.service.*
import com.embabel.common.core.types.SimpleSimilaritySearchResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ToolishRagTest {

    @Nested
    inner class TryHyDETests {

        @Test
        fun `contribution should include context and word count`() {
            val hyde = TryHyDE(context = "The history of the Roman Empire", maxWords = 100)

            val contribution = hyde.contribution()

            assertTrue(contribution.contains("The history of the Roman Empire"))
            assertTrue(contribution.contains("100 words"))
        }

        @Test
        fun `should use default word count of 50`() {
            val hyde = TryHyDE(context = "Test context")

            assertEquals(50, hyde.maxWords)
            assertTrue(hyde.contribution().contains("50 words"))
        }

        @Test
        fun `contribution should include HyDE instructions`() {
            val hyde = TryHyDE(context = "Quantum computing basics")

            val contribution = hyde.contribution()

            assertTrue(contribution.contains("hypothetical document"))
            assertTrue(contribution.contains("vector search"))
        }
    }

    @Nested
    inner class HintsTests {

        @Test
        fun `withHint should add hint to ToolishRag`() {
            val vectorSearch = mockk<VectorSearch>()
            val hint = TryHyDE(context = "Test context")

            val toolishRag = ToolishRag(
                name = "test-rag",
                description = "Test RAG",
                searchOperations = vectorSearch
            ).withHint(hint)

            assertEquals(1, toolishRag.hints.size)
            assertTrue(toolishRag.hints[0] is TryHyDE)
        }

        @Test
        fun `withHint should accumulate multiple hints`() {
            val vectorSearch = mockk<VectorSearch>()
            val hint1 = TryHyDE(context = "Context 1")
            val hint2 = TryHyDE(context = "Context 2")

            val toolishRag = ToolishRag(
                name = "test-rag",
                description = "Test RAG",
                searchOperations = vectorSearch
            ).withHint(hint1).withHint(hint2)

            assertEquals(2, toolishRag.hints.size)
        }

        @Test
        fun `notes should include hint contributions`() {
            val vectorSearch = mockk<VectorSearch>()
            val hint = TryHyDE(context = "Roman Empire history", maxWords = 75)

            val toolishRag = ToolishRag(
                name = "test-rag",
                description = "Test RAG",
                searchOperations = vectorSearch,
                hints = listOf(hint)
            )

            val notes = toolishRag.notes()

            assertTrue(notes.contains("Roman Empire history"))
            assertTrue(notes.contains("75 words"))
        }

        @Test
        fun `should have empty hints by default`() {
            val vectorSearch = mockk<VectorSearch>()

            val toolishRag = ToolishRag(
                name = "test-rag",
                description = "Test RAG",
                searchOperations = vectorSearch
            )

            assertTrue(toolishRag.hints.isEmpty())
        }
    }

    private fun createChunk(
        id: String,
        text: String,
    ): Chunk =
        Chunk(id = id, text = text, parentId = "parent", metadata = emptyMap())

    private fun createEntity(
        id: String,
        name: String,
    ): SimpleEntityData =
        SimpleEntityData(
            id = id,
            labels = setOf("Person"),
            properties = mapOf("name" to name),
        )

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

            assertEquals(2, toolInstances.size)
            assertTrue(toolInstances.any { it is TypeRetrievalTools })
            assertTrue(toolInstances.any { it is VectorSearchTools })
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

            assertEquals(2, toolInstances.size)
            assertTrue(toolInstances.any { it is TextSearchTools })
            assertTrue(toolInstances.any { it is TypeRetrievalTools })
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

            assertEquals(3, toolInstances.size)
            assertTrue(toolInstances.any { it is TypeRetrievalTools })
            assertFalse(toolInstances.any { it is FinderTools })
            assertTrue(toolInstances.any { it is VectorSearchTools })
            assertTrue(toolInstances.any { it is TextSearchTools })
        }

        @Test
        fun `should add RetrievalTools when searchOperations is RetrievalOperations`() {
            val retrievalOps = mockk<FinderOperations>()

            val toolishRag = ToolishRag(
                name = "test-rag",
                description = "Test RAG",
                searchOperations = retrievalOps
            )

            val toolInstances = toolishRag.toolInstances()

            assertEquals(2, toolInstances.size)
            assertTrue(toolInstances.any { it is TypeRetrievalTools })
            assertTrue(toolInstances.any { it is FinderTools })
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

    }

    @Nested
    inner class RegexSearchToolsTests {

        @Test
        fun `regexSearch should delegate to RegexSearchOperations with correct parameters`() {
            val regexSearch = mockk<RegexSearchOperations>()
            val chunk = createChunk("chunk1", "Error E001 occurred")

            every {
                regexSearch.regexSearch(any<Regex>(), any(), Chunk::class.java)
            } returns listOf(SimpleSimilaritySearchResult(match = chunk, score = 1.0))

            val tools = RegexSearchTools(regexSearch)
            val result = tools.regexSearch("E\\d{3}", 10)

            verify {
                regexSearch.regexSearch(
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
            val regexSearch = mockk<RegexSearchOperations>()

            every {
                regexSearch.regexSearch(any<Regex>(), any(), Chunk::class.java)
            } returns emptyList()

            val tools = RegexSearchTools(regexSearch)
            val result = tools.regexSearch("nonexistent pattern", 10)

            assertEquals("0 results:", result)
        }

        @Test
        fun `regexSearch should handle multiple matches`() {
            val regexSearch = mockk<RegexSearchOperations>()
            val chunk1 = createChunk("chunk1", "Error E001")
            val chunk2 = createChunk("chunk2", "Error E002")

            every {
                regexSearch.regexSearch(any<Regex>(), any(), Chunk::class.java)
            } returns listOf(
                SimpleSimilaritySearchResult(match = chunk1, score = 1.0),
                SimpleSimilaritySearchResult(match = chunk2, score = 1.0)
            )

            val tools = RegexSearchTools(regexSearch)
            val result = tools.regexSearch("E\\d{3}", 10)

            assertTrue(result.contains("2 results:"))
            assertTrue(result.contains("Error E001"))
            assertTrue(result.contains("Error E002"))
        }
    }

    @Nested
    inner class ResultsListenerTests {

        @Test
        fun `vectorSearch should publish event with non-negative running time`() {
            val vectorSearch = mockk<VectorSearch>()
            val chunk = createChunk("chunk1", "Test content")
            var capturedEvent: ResultsEvent? = null

            every {
                vectorSearch.vectorSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns listOf(SimpleSimilaritySearchResult(match = chunk, score = 0.9))

            val listener = ResultsListener { event -> capturedEvent = event }
            val tools = VectorSearchTools(vectorSearch, resultsListener = listener)
            tools.vectorSearch("test query", 10, 0.5)

            assertTrue(capturedEvent != null)
            assertTrue(capturedEvent!!.runningTime >= java.time.Duration.ZERO)
            assertTrue(capturedEvent!!.timestamp <= java.time.Instant.now())
            assertEquals("test query", capturedEvent!!.query)
            assertEquals(1, capturedEvent!!.results.size)
        }

        @Test
        fun `textSearch should publish event with non-negative running time`() {
            val textSearch = mockk<TextSearch>()
            val chunk = createChunk("chunk1", "Test content")
            var capturedEvent: ResultsEvent? = null

            every {
                textSearch.textSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns listOf(SimpleSimilaritySearchResult(match = chunk, score = 0.85))

            val listener = ResultsListener { event -> capturedEvent = event }
            val tools = TextSearchTools(textSearch, resultsListener = listener)
            tools.textSearch("+kotlin", 5, 0.7)

            assertTrue(capturedEvent != null)
            assertTrue(capturedEvent!!.runningTime >= java.time.Duration.ZERO)
            assertTrue(capturedEvent!!.timestamp <= java.time.Instant.now())
            assertEquals("+kotlin", capturedEvent!!.query)
            assertEquals(1, capturedEvent!!.results.size)
        }

        @Test
        fun `regexSearch should publish event with non-negative running time`() {
            val regexSearch = mockk<RegexSearchOperations>()
            val chunk = createChunk("chunk1", "Error E001")
            var capturedEvent: ResultsEvent? = null

            every {
                regexSearch.regexSearch(any<Regex>(), any(), Chunk::class.java)
            } returns listOf(SimpleSimilaritySearchResult(match = chunk, score = 1.0))

            val listener = ResultsListener { event -> capturedEvent = event }
            val tools = RegexSearchTools(regexSearch, filter = null, resultsListener = listener)
            tools.regexSearch("E\\d{3}", 10)

            assertTrue(capturedEvent != null)
            assertTrue(capturedEvent!!.runningTime >= java.time.Duration.ZERO)
            assertTrue(capturedEvent!!.timestamp <= java.time.Instant.now())
            assertEquals("E\\d{3}", capturedEvent!!.query)
            assertEquals(1, capturedEvent!!.results.size)
        }

        @Test
        fun `timestamp should represent start time calculated from running time`() {
            val vectorSearch = mockk<VectorSearch>()
            val chunk = createChunk("chunk1", "Test content")
            var capturedEvent: ResultsEvent? = null

            every {
                vectorSearch.vectorSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } answers {
                Thread.sleep(50) // Simulate 50ms search time
                listOf(SimpleSimilaritySearchResult(match = chunk, score = 0.9))
            }

            val beforeSearch = java.time.Instant.now()
            val listener = ResultsListener { event -> capturedEvent = event }
            val tools = VectorSearchTools(vectorSearch, resultsListener = listener)
            tools.vectorSearch("test query", 10, 0.5)
            val afterSearch = java.time.Instant.now()

            assertTrue(capturedEvent != null)
            assertTrue(capturedEvent!!.runningTime >= java.time.Duration.ofMillis(50))
            assertTrue(capturedEvent!!.runningTime < java.time.Duration.ofSeconds(1))
            // Timestamp should be the start time (before or at beforeSearch)
            assertTrue(capturedEvent!!.timestamp >= beforeSearch.minusMillis(10))
            assertTrue(capturedEvent!!.timestamp <= afterSearch.minus(capturedEvent!!.runningTime).plusMillis(10))
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
            assertEquals(3, toolInstances.size)

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

    @Nested
    inner class MultiTypeVectorSearchTests {

        @Test
        fun `vectorSearch should search for all types in searchFor list`() {
            val vectorSearch = mockk<VectorSearch>()
            val chunk = createChunk("chunk1", "Chunk content")
            val entity = createEntity("entity1", "Entity name")

            every {
                vectorSearch.vectorSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns listOf(SimpleSimilaritySearchResult(match = chunk, score = 0.9))

            every {
                vectorSearch.vectorSearch(any<TextSimilaritySearchRequest>(), SimpleEntityData::class.java)
            } returns listOf(SimpleSimilaritySearchResult(match = entity, score = 0.8))

            val tools = VectorSearchTools(
                vectorSearch,
                searchFor = listOf(Chunk::class.java, SimpleEntityData::class.java)
            )
            val result = tools.vectorSearch("test query", 10, 0.5)

            verify { vectorSearch.vectorSearch(any(), Chunk::class.java) }
            verify { vectorSearch.vectorSearch(any(), SimpleEntityData::class.java) }

            assertTrue(result.contains("2 results:"))
            assertTrue(result.contains("Chunk content"))
            assertTrue(result.contains("Entity"))
        }

        @Test
        fun `vectorSearch should deduplicate results by id keeping highest score`() {
            val vectorSearch = mockk<VectorSearch>()
            val chunkLowScore = createChunk("shared-id", "Chunk content")
            val chunkHighScore = createChunk("shared-id", "Chunk content")

            every {
                vectorSearch.vectorSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns listOf(SimpleSimilaritySearchResult(match = chunkLowScore, score = 0.7))

            every {
                vectorSearch.vectorSearch(any<TextSimilaritySearchRequest>(), SimpleEntityData::class.java)
            } returns listOf(
                SimpleSimilaritySearchResult(
                    match = createEntity("shared-id", "Entity name"),
                    score = 0.9
                )
            )

            val tools = VectorSearchTools(
                vectorSearch,
                searchFor = listOf(Chunk::class.java, SimpleEntityData::class.java)
            )
            val result = tools.vectorSearch("test query", 10, 0.5)

            assertTrue(result.contains("1 results:"))
            assertTrue(result.contains("0.90"))
        }

        @Test
        fun `vectorSearch should return results sorted by score descending`() {
            val vectorSearch = mockk<VectorSearch>()
            val chunk1 = createChunk("chunk1", "First chunk")
            val chunk2 = createChunk("chunk2", "Second chunk")
            val entity = createEntity("entity1", "Entity")

            every {
                vectorSearch.vectorSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns listOf(
                SimpleSimilaritySearchResult(match = chunk1, score = 0.5),
                SimpleSimilaritySearchResult(match = chunk2, score = 0.9)
            )

            every {
                vectorSearch.vectorSearch(any<TextSimilaritySearchRequest>(), SimpleEntityData::class.java)
            } returns listOf(SimpleSimilaritySearchResult(match = entity, score = 0.7))

            val tools = VectorSearchTools(
                vectorSearch,
                searchFor = listOf(Chunk::class.java, SimpleEntityData::class.java)
            )
            val result = tools.vectorSearch("test query", 10, 0.5)

            assertTrue(result.contains("3 results:"))
            val score09Index = result.indexOf("0.90")
            val score07Index = result.indexOf("0.70")
            val score05Index = result.indexOf("0.50")
            assertTrue(score09Index < score07Index)
            assertTrue(score07Index < score05Index)
        }

        @Test
        fun `vectorSearch should publish event with deduplicated results`() {
            val vectorSearch = mockk<VectorSearch>()
            val chunk = createChunk("shared-id", "Content")
            val entity = createEntity("shared-id", "Entity")
            var capturedEvent: ResultsEvent? = null

            every {
                vectorSearch.vectorSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns listOf(SimpleSimilaritySearchResult(match = chunk, score = 0.6))

            every {
                vectorSearch.vectorSearch(any<TextSimilaritySearchRequest>(), SimpleEntityData::class.java)
            } returns listOf(SimpleSimilaritySearchResult(match = entity, score = 0.8))

            val listener = ResultsListener { event -> capturedEvent = event }
            val tools = VectorSearchTools(
                vectorSearch,
                searchFor = listOf(Chunk::class.java, SimpleEntityData::class.java),
                resultsListener = listener
            )
            tools.vectorSearch("test query", 10, 0.5)

            assertTrue(capturedEvent != null)
            assertEquals(1, capturedEvent!!.results.size)
            assertEquals(0.8, capturedEvent!!.results[0].score)
        }
    }

    @Nested
    inner class MultiTypeTextSearchTests {

        @Test
        fun `textSearch should search for all types in searchFor list`() {
            val textSearch = mockk<TextSearch>()
            val chunk = createChunk("chunk1", "Chunk content")
            val entity = createEntity("entity1", "Entity name")

            every {
                textSearch.textSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns listOf(SimpleSimilaritySearchResult(match = chunk, score = 0.85))

            every {
                textSearch.textSearch(any<TextSimilaritySearchRequest>(), SimpleEntityData::class.java)
            } returns listOf(SimpleSimilaritySearchResult(match = entity, score = 0.75))

            val tools = TextSearchTools(
                textSearch,
                searchFor = listOf(Chunk::class.java, SimpleEntityData::class.java)
            )
            val result = tools.textSearch("+kotlin", 5, 0.7)

            verify { textSearch.textSearch(any(), Chunk::class.java) }
            verify { textSearch.textSearch(any(), SimpleEntityData::class.java) }

            assertTrue(result.contains("2 results:"))
            assertTrue(result.contains("Chunk content"))
            assertTrue(result.contains("Entity"))
        }

        @Test
        fun `textSearch should deduplicate results by id keeping highest score`() {
            val textSearch = mockk<TextSearch>()
            val chunk = createChunk("shared-id", "Chunk content")
            val entity = createEntity("shared-id", "Entity name")

            every {
                textSearch.textSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns listOf(SimpleSimilaritySearchResult(match = chunk, score = 0.6))

            every {
                textSearch.textSearch(any<TextSimilaritySearchRequest>(), SimpleEntityData::class.java)
            } returns listOf(SimpleSimilaritySearchResult(match = entity, score = 0.9))

            val tools = TextSearchTools(
                textSearch,
                searchFor = listOf(Chunk::class.java, SimpleEntityData::class.java)
            )
            val result = tools.textSearch("+test", 10, 0.5)

            assertTrue(result.contains("1 results:"))
            assertTrue(result.contains("0.90"))
        }

        @Test
        fun `textSearch should return results sorted by score descending`() {
            val textSearch = mockk<TextSearch>()
            val chunk1 = createChunk("chunk1", "First chunk")
            val chunk2 = createChunk("chunk2", "Second chunk")
            val entity = createEntity("entity1", "Entity")

            every {
                textSearch.textSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns listOf(
                SimpleSimilaritySearchResult(match = chunk1, score = 0.4),
                SimpleSimilaritySearchResult(match = chunk2, score = 0.8)
            )

            every {
                textSearch.textSearch(any<TextSimilaritySearchRequest>(), SimpleEntityData::class.java)
            } returns listOf(SimpleSimilaritySearchResult(match = entity, score = 0.6))

            val tools = TextSearchTools(
                textSearch,
                searchFor = listOf(Chunk::class.java, SimpleEntityData::class.java)
            )
            val result = tools.textSearch("+test", 10, 0.3)

            assertTrue(result.contains("3 results:"))
            val score08Index = result.indexOf("0.80")
            val score06Index = result.indexOf("0.60")
            val score04Index = result.indexOf("0.40")
            assertTrue(score08Index < score06Index)
            assertTrue(score06Index < score04Index)
        }

        @Test
        fun `textSearch should publish event with deduplicated results`() {
            val textSearch = mockk<TextSearch>()
            val chunk = createChunk("shared-id", "Content")
            val entity = createEntity("shared-id", "Entity")
            var capturedEvent: ResultsEvent? = null

            every {
                textSearch.textSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns listOf(SimpleSimilaritySearchResult(match = chunk, score = 0.5))

            every {
                textSearch.textSearch(any<TextSimilaritySearchRequest>(), SimpleEntityData::class.java)
            } returns listOf(SimpleSimilaritySearchResult(match = entity, score = 0.9))

            val listener = ResultsListener { event -> capturedEvent = event }
            val tools = TextSearchTools(
                textSearch,
                searchFor = listOf(Chunk::class.java, SimpleEntityData::class.java),
                resultsListener = listener
            )
            tools.textSearch("+kotlin", 5, 0.4)

            assertTrue(capturedEvent != null)
            assertEquals(1, capturedEvent!!.results.size)
            assertEquals(0.9, capturedEvent!!.results[0].score)
        }
    }

    @Nested
    inner class TypeRetrievalToolsTests {

        @Test
        fun `isTypeSupported should return supported when type is supported`() {
            val typeRetrievalOps = mockk<TypeRetrievalOperations>()

            every { typeRetrievalOps.supportsType("Chunk") } returns true

            val tools = TypeRetrievalTools(typeRetrievalOps)
            val result = tools.isTypeSupported("Chunk")

            assertEquals("Type 'Chunk' is supported", result)
        }

        @Test
        fun `isTypeSupported should return not supported when type is not supported`() {
            val typeRetrievalOps = mockk<TypeRetrievalOperations>()

            every { typeRetrievalOps.supportsType("Chunk") } returns false

            val tools = TypeRetrievalTools(typeRetrievalOps)
            val result = tools.isTypeSupported("Chunk")

            assertEquals("Type 'Chunk' is not supported by this store", result)
        }
    }

    @Nested
    inner class FinderToolsTests {

        @Test
        fun `findById should return formatted result when found`() {
            val retrievalOps = mockk<FinderOperations>()
            val chunk = createChunk("chunk1", "Test content")

            every { retrievalOps.supportsType("Chunk") } returns true
            every { retrievalOps.findById<Retrievable>("chunk1", "Chunk") } returns chunk

            val tools = FinderTools(retrievalOps)
            val result = tools.findById("chunk1", "Chunk")

            assertTrue(result.contains("Found"))
            assertTrue(result.contains("chunk1"))
            assertTrue(result.contains("chunk: Test content"))
        }

        @Test
        fun `findById should return not found message when item not found`() {
            val retrievalOps = mockk<FinderOperations>()

            every { retrievalOps.supportsType("Chunk") } returns true
            every { retrievalOps.findById<Retrievable>("nonexistent", "Chunk") } returns null

            val tools = FinderTools(retrievalOps)
            val result = tools.findById("nonexistent", "Chunk")

            assertEquals("No item found with id 'nonexistent' of type 'Chunk'", result)
        }

        @Test
        fun `findById should return error when type not supported`() {
            val retrievalOps = mockk<FinderOperations>()

            every { retrievalOps.supportsType("Chunk") } returns false

            val tools = FinderTools(retrievalOps)
            val result = tools.findById("id1", "Chunk")

            assertTrue(result.contains("Type 'Chunk' is not supported"))
        }

        @Test
        fun `findById should work with entity types`() {
            val retrievalOps = mockk<FinderOperations>()
            val entity = createEntity("entity1", "Test Entity")

            every { retrievalOps.supportsType("SimpleEntityData") } returns true
            every { retrievalOps.findById<Retrievable>("entity1", "SimpleEntityData") } returns entity

            val tools = FinderTools(retrievalOps)
            val result = tools.findById("entity1", "SimpleEntityData")

            assertTrue(result.contains("Found SimpleEntityData with id 'entity1'"))
        }
    }
}
