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

import com.embabel.agent.filter.PropertyFilter
import com.embabel.agent.rag.filter.EntityFilter
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.Retrievable
import com.embabel.agent.rag.service.FilteringVectorSearch
import com.embabel.agent.rag.service.ResultExpander
import com.embabel.agent.rag.service.SearchOperations
import com.embabel.agent.rag.service.VectorSearch
import com.embabel.common.core.types.SimpleSimilaritySearchResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ToolishRagEagerSearchTest {

    private interface ExpandableVectorSearch : VectorSearch, ResultExpander

    private fun createChunk(id: String, text: String): Chunk =
        Chunk(id = id, text = text, parentId = "parent", metadata = emptyMap())

    @Nested
    inner class DefaultBehaviorUnchanged {

        @Test
        fun `ToolishRag without eager search has same notes as before`() {
            val vectorSearch = mockk<VectorSearch>()
            val rag = ToolishRag(
                name = "test-rag",
                description = "Test RAG",
                searchOperations = vectorSearch,
            )
            val notes = rag.notes()
            assertTrue(notes.contains("Search acceptance criteria:"))
            assertTrue(notes.contains("Continue search until the question is answered"))
            assertFalse(notes.contains("Preloaded"))
        }

        @Test
        fun `ToolishRag without eager search has same tools as before`() {
            val vectorSearch = mockk<VectorSearch>()
            val rag = ToolishRag(
                name = "test-rag",
                description = "Test RAG",
                searchOperations = vectorSearch,
            )
            val toolNames = rag.tools().map { it.definition.name }
            assertTrue(toolNames.any { it == "test_rag_vectorSearch" })
        }

        @Test
        fun `ToolishRag without eager search has empty hints`() {
            val vectorSearch = mockk<VectorSearch>()
            val rag = ToolishRag(
                name = "test-rag",
                description = "Test RAG",
                searchOperations = vectorSearch,
            )
            assertTrue(rag.hints.isEmpty())
        }

        @Test
        fun `ToolishRag without eager search has same contribution as before`() {
            val vectorSearch = mockk<VectorSearch>()
            val rag = ToolishRag(
                name = "test-rag",
                description = "Test RAG",
                searchOperations = vectorSearch,
            )
            val contribution = rag.contribution()
            assertTrue(contribution.contains("Reference: test-rag"))
            assertTrue(contribution.contains("Description: Test RAG"))
            assertFalse(contribution.contains("Preloaded"))
        }
    }

    @Nested
    inner class UnsupportedSearchOperations {

        @Test
        fun `throws UnsupportedOperationException when searchOperations has no VectorSearch`() {
            val plainOps = mockk<SearchOperations>()
            val rag = ToolishRag(
                name = "test-rag",
                description = "Test RAG",
                searchOperations = plainOps,
            )
            assertThrows<UnsupportedOperationException> {
                rag.withEagerSearchAbout("test query", 5)
            }
        }

        @Test
        fun `exception message mentions VectorSearch`() {
            val plainOps = mockk<SearchOperations>()
            val rag = ToolishRag(
                name = "test-rag",
                description = "Test RAG",
                searchOperations = plainOps,
            )
            val ex = assertThrows<UnsupportedOperationException> {
                rag.withEagerSearchAbout("test query", 5)
            }
            assertTrue(ex.message!!.contains("VectorSearch"))
        }
    }

    @Nested
    inner class EagerSearchWithVectorSearch {

        @Test
        fun `withEagerSearchAbout performs vector search and adds results to hints`() {
            val vectorSearch = mockk<VectorSearch>()
            val chunk = createChunk("chunk1", "Relevant content")

            every {
                vectorSearch.vectorSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns listOf(SimpleSimilaritySearchResult(match = chunk, score = 0.9))

            val rag = ToolishRag(
                name = "test-rag",
                description = "Test RAG",
                searchOperations = vectorSearch,
            )
            val eagerRag = rag.withEagerSearchAbout("test query", 5)

            verify {
                vectorSearch.vectorSearch(
                    match<TextSimilaritySearchRequest> {
                        it.query == "test query" && it.topK == 5
                    },
                    Chunk::class.java,
                )
            }
            assertTrue(eagerRag.hints.isNotEmpty())
        }

        @Test
        fun `eager search applies filters natively when supported`() {
            val vectorSearch = mockk<FilteringVectorSearch>()
            val metadataFilter = PropertyFilter.eq("ownerId", "alice")
            val entityFilter = EntityFilter.hasAnyLabel("Person")
            val chunk = createChunk("chunk1", "Alice's content")
            every {
                vectorSearch.vectorSearchWithFilter(
                    any<TextSimilaritySearchRequest>(),
                    Chunk::class.java,
                    metadataFilter,
                    entityFilter,
                )
            } returns listOf(SimpleSimilaritySearchResult(match = chunk, score = 0.9))
            val rag = ToolishRag(
                name = "test-rag",
                description = "Test RAG",
                searchOperations = vectorSearch,
                metadataFilter = metadataFilter,
                entityFilter = entityFilter,
            )

            val eagerRag = rag.withEagerSearchAbout("test query", 5)

            verify(exactly = 1) {
                vectorSearch.vectorSearchWithFilter(
                    any<TextSimilaritySearchRequest>(),
                    Chunk::class.java,
                    metadataFilter,
                    entityFilter,
                )
            }
            assertTrue(eagerRag.notes().contains("Alice's content"))
        }

        @Test
        fun `eager search expands results before notifying listener`() {
            val vectorSearch = mockk<ExpandableVectorSearch>()
            val hit = createChunk("hit", "Matching content")
            val neighbour = createChunk("neighbour", "Neighbouring content")
            every {
                vectorSearch.vectorSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns listOf(SimpleSimilaritySearchResult(match = hit, score = 0.9))
            every {
                vectorSearch.expandResult("hit", ResultExpander.Method.SEQUENCE, 1)
            } returns listOf(neighbour)
            var event: ResultsEvent? = null
            val rag = ToolishRag(
                name = "test-rag",
                description = "Test RAG",
                searchOperations = vectorSearch,
                listener = ResultsListener { event = it },
                searchDefaults = SearchDefaults(expandNeighbours = 1),
            )

            val eagerRag = rag.withEagerSearchAbout("test query", 5)

            assertTrue(eagerRag.notes().contains("Matching content"))
            assertTrue(eagerRag.notes().contains("Neighbouring content"))
            assertEquals(listOf("hit", "neighbour"), event?.results?.map { it.match.id })
            assertEquals("test query", event?.query)
        }

        @Test
        fun `eager search post-filters metadata when native filtering is unavailable`() {
            val vectorSearch = mockk<VectorSearch>()
            val included = Chunk(
                id = "included",
                text = "Alice's content",
                parentId = "parent",
                metadata = mapOf("ownerId" to "alice"),
            )
            val excluded = Chunk(
                id = "excluded",
                text = "Bob's content",
                parentId = "parent",
                metadata = mapOf("ownerId" to "bob"),
            )
            every {
                vectorSearch.vectorSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns listOf(
                SimpleSimilaritySearchResult(match = included, score = 0.9),
                SimpleSimilaritySearchResult(match = excluded, score = 0.8),
            )
            val rag = ToolishRag(
                name = "test-rag",
                description = "Test RAG",
                searchOperations = vectorSearch,
                metadataFilter = PropertyFilter.eq("ownerId", "alice"),
            )

            val notes = rag.withEagerSearchAbout("test query", 5).notes()

            assertTrue(notes.contains("Alice's content"))
            assertFalse(notes.contains("Bob's content"))
        }

        @Test
        fun `eager search results appear in notes`() {
            val vectorSearch = mockk<VectorSearch>()
            val chunk = createChunk("chunk1", "The quick brown fox")

            every {
                vectorSearch.vectorSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns listOf(SimpleSimilaritySearchResult(match = chunk, score = 0.95))

            val rag = ToolishRag(
                name = "test-rag",
                description = "Test RAG",
                searchOperations = vectorSearch,
            )
            val eagerRag = rag.withEagerSearchAbout("fox query", 3)
            val notes = eagerRag.notes()

            assertTrue(notes.contains("Preloaded"))
            assertTrue(notes.contains("The quick brown fox"))
        }

        @Test
        fun `eager search results appear in contribution`() {
            val vectorSearch = mockk<VectorSearch>()
            val chunk = createChunk("chunk1", "Important info")

            every {
                vectorSearch.vectorSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns listOf(SimpleSimilaritySearchResult(match = chunk, score = 0.8))

            val rag = ToolishRag(
                name = "test-rag",
                description = "Test RAG",
                searchOperations = vectorSearch,
            )
            val eagerRag = rag.withEagerSearchAbout("info query", 5)
            val contribution = eagerRag.contribution()

            assertTrue(contribution.contains("Important info"))
        }

        @Test
        fun `original ToolishRag is unchanged after eager search`() {
            val vectorSearch = mockk<VectorSearch>()

            every {
                vectorSearch.vectorSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns listOf(
                SimpleSimilaritySearchResult(match = createChunk("c1", "Content"), score = 0.9),
            )

            val original = ToolishRag(
                name = "test-rag",
                description = "Test RAG",
                searchOperations = vectorSearch,
            )
            val originalNotes = original.notes()
            original.withEagerSearchAbout("query", 5)

            assertEquals(originalNotes, original.notes())
            assertTrue(original.hints.isEmpty())
        }

        @Test
        fun `withEagerSearchAbout with TextSimilaritySearchRequest`() {
            val vectorSearch = mockk<VectorSearch>()
            val chunk = createChunk("chunk1", "Result content")

            every {
                vectorSearch.vectorSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns listOf(SimpleSimilaritySearchResult(match = chunk, score = 0.85))

            val rag = ToolishRag(
                name = "test-rag",
                description = "Test RAG",
                searchOperations = vectorSearch,
            )
            val request = TextSimilaritySearchRequest(query = "custom query", similarityThreshold = 0.7, topK = 10)
            val eagerRag = rag.withEagerSearchAbout(request)

            verify {
                vectorSearch.vectorSearch(
                    match<TextSimilaritySearchRequest> {
                        it.query == "custom query" && it.topK == 10 && it.similarityThreshold == 0.7
                    },
                    Chunk::class.java,
                )
            }
            assertTrue(eagerRag.notes().contains("Result content"))
        }

        @Test
        fun `tools are preserved after eager search`() {
            val vectorSearch = mockk<VectorSearch>()

            every {
                vectorSearch.vectorSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns emptyList()

            val rag = ToolishRag(
                name = "test-rag",
                description = "Test RAG",
                searchOperations = vectorSearch,
            )
            val eagerRag = rag.withEagerSearchAbout("query", 5)
            val toolNames = eagerRag.tools().map { it.definition.name }

            assertTrue(toolNames.any { it == "test_rag_vectorSearch" })
        }

        @Test
        fun `eager search with empty results adds hint with zero results`() {
            val vectorSearch = mockk<VectorSearch>()

            every {
                vectorSearch.vectorSearch(any<TextSimilaritySearchRequest>(), Chunk::class.java)
            } returns emptyList()

            val rag = ToolishRag(
                name = "test-rag",
                description = "Test RAG",
                searchOperations = vectorSearch,
            )
            val eagerRag = rag.withEagerSearchAbout("obscure query", 5)

            assertTrue(eagerRag.hints.isNotEmpty())
            assertTrue(eagerRag.notes().contains("0 results:"))
        }
    }
}
