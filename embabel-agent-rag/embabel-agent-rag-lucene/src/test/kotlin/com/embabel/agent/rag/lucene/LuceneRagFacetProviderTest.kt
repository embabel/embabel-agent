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
package com.embabel.agent.rag.lucene

import com.embabel.agent.rag.RagRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse

class LuceneRagFacetProviderTest {

    private lateinit var ragService: LuceneRagFacetProvider
    private lateinit var ragServiceWithEmbedding: LuceneRagFacetProvider
    private val mockEmbeddingModel = MockEmbeddingModel()

    @BeforeEach
    fun setUp() {
        ragService = LuceneRagFacetProvider(name = "lucene-rag")
        ragServiceWithEmbedding = LuceneRagFacetProvider(
            name = "hybrid-lucene-rag",
            embeddingModel = mockEmbeddingModel,
            vectorWeight = 0.5
        )
    }

    @AfterEach
    fun tearDown() {
        ragService.close()
        ragServiceWithEmbedding.close()
    }

    @Test
    fun `should return empty results when no documents are indexed`() {
        val request = RagRequest.query("test query")
        val response = ragService.search(request)

        assertEquals("lucene-rag", response.facetName)
        assertTrue(response.results.isEmpty())
    }

    @Test
    fun `should index and search documents using accept method`() {
        // Index some test documents using accept
        val documents = listOf(
            Document("doc1", "This is a test document about machine learning", emptyMap<String, Any>()),
            Document("doc2", "Another document discussing artificial intelligence", emptyMap<String, Any>()),
            Document("doc3", "A completely different topic about cooking recipes", emptyMap<String, Any>())
        )

        ragService.accept(documents)

        // Search for documents
        val request = RagRequest.query("machine learning")
        val response = ragService.search(request)

        assertEquals("lucene-rag", response.facetName)
        assertTrue(response.results.isNotEmpty())

        // Should find the most relevant document first
        val firstResult = response.results.first()
        assertEquals("doc1", firstResult.match.id)
        assertTrue(firstResult.score > 0.0)
    }

    @Test
    fun `should respect similarity threshold using accept method`() {
        val documents = listOf(
            Document("doc1", "machine learning algorithms", emptyMap<String, Any>()),
            Document("doc2", "completely unrelated content about cooking", emptyMap<String, Any>())
        )

        ragService.accept(documents)

        // High threshold should filter out low-relevance results
        val request = RagRequest.query("machine learning")
            .withSimilarityThreshold(0.9)

        val response = ragService.search(request)

        // Should only return highly relevant documents
        response.results.forEach { result ->
            assertTrue(result.score >= 0.9)
        }
    }

    @Test
    fun `should respect topK limit using accept method`() {
        val documents = (1..10).map { i ->
            Document("doc$i", "machine learning document number $i", emptyMap<String, Any>())
        }

        ragService.accept(documents)

        val request = RagRequest.query("machine learning").withTopK(3)
        val response = ragService.search(request)

        assertTrue(response.results.size <= 3)
    }

    @Test
    fun `should handle document metadata correctly using accept method`() {
        val metadata = mapOf("author" to "John Doe", "category" to "AI")
        val documents = listOf(
            Document("doc1", "Test content", metadata)
        )

        ragService.accept(documents)

        val request = RagRequest.query("test")
            .withSimilarityThreshold(0.0)
        val response = ragService.search(request)

        assertEquals(1, response.results.size, "Expected 1 result")
        val result = response.results.first()

        assertEquals("John Doe", result.match.metadata["author"])
        assertEquals("AI", result.match.metadata["category"])
    }

    @Test
    fun `should provide meaningful info string`() {
        val infoString = ragService.infoString(verbose = false, indent = 0)
        assertTrue(infoString.contains("LuceneRagService"))
        assertTrue(infoString.contains("lucene-rag"))
        assertTrue(infoString.contains("0 documents"))

        // After adding documents using accept
        ragService.accept(listOf(Document("doc1", "test content", emptyMap<String, Any>())))
        val infoStringAfter = ragService.infoString(verbose = false, indent = 0)
        assertTrue(infoStringAfter.contains("1 documents"))
    }

    @Test
    fun `retrievable should provide embeddable value using accept method`() {
        val documents = listOf(Document("doc1", "Test document content", emptyMap<String, Any>()))
        ragServiceWithEmbedding.accept(documents)

        val request = RagRequest.query("test")
            .withSimilarityThreshold(.0)
        val response = ragServiceWithEmbedding.search(request)

        assertEquals(1, response.results.size)
        val retrievable = response.results.first().match
        assertEquals("Test document content", retrievable.embeddableValue())
    }

    @Test
    fun `should handle multiple accept calls correctly without vector`() {
        // First batch
        ragService.accept(
            listOf(
                Document("doc1", "First batch document about AI and artificial intelligence", emptyMap<String, Any>()),
                Document("doc2", "Another first batch document about ML", emptyMap<String, Any>())
            )
        )

        // Second batch
        ragService.accept(
            listOf(
                Document("doc3", "Second batch document about artificial intelligence", emptyMap<String, Any>()),
                Document("doc4", "Another second batch document about machine learning", emptyMap<String, Any>())
            )
        )

        val request = RagRequest.query("artificial intelligence")
            .withSimilarityThreshold(0.0)
        val response = ragService.search(request)

        assertTrue(response.results.isNotEmpty())
        // Should find documents from both batches
        assertTrue(
            response.results.any { it.match.id == "doc1" },
            "Should contain doc3: ids were ${response.results.map { it.match.id }}"
        )
        assertTrue(response.results.any { it.match.id == "doc3" })
    }

    @Test
    fun `should perform hybrid search with embeddings`() {
        val documents = listOf(
            Document("doc1", "machine learning algorithms for data science", emptyMap<String, Any>()),
            Document("doc2", "cooking recipes and kitchen techniques", emptyMap<String, Any>()),
            Document("doc3", "artificial intelligence and neural networks", emptyMap<String, Any>())
        )

        ragServiceWithEmbedding.accept(documents)

        // Search should use both text and vector similarity
        val request = RagRequest.query("AI and machine learning")
            .withSimilarityThreshold(0.0)
        val response = ragServiceWithEmbedding.search(request)

        assertEquals("hybrid-lucene-rag", response.facetName)
        assertTrue(response.results.isNotEmpty())

        // Should find AI/ML related documents with higher scores due to hybrid search
        val aiMlDocs = response.results.filter {
            it.match.id == "doc1" || it.match.id == "doc3"
        }
        assertTrue(aiMlDocs.isNotEmpty(), "Should find AI/ML related documents")
        assertTrue(aiMlDocs.all { it.score > 0.0 }, "AI/ML documents should have positive scores")
    }

    @Test
    fun `should weight vector similarity appropriately`() {
        val ragServiceHighVector = LuceneRagFacetProvider(
            name = "high-vector-weight",
            embeddingModel = mockEmbeddingModel,
            vectorWeight = 0.9 // High vector weight
        )

        try {
            val documents = listOf(
                Document("doc1", "machine learning", emptyMap<String, Any>()),
                Document("doc2", "artificial intelligence", emptyMap<String, Any>())
            )

            ragServiceHighVector.accept(documents)

            // Use a query that should match via text search to ensure we get text results for hybrid
            val request = RagRequest.query("machine")
                .withSimilarityThreshold(0.0)
            val response = ragServiceHighVector.search(request)

            assertTrue(
                response.results.isNotEmpty(),
                "Should have results from vector search, got: ${response.results.size} results"
            )
        } finally {
            ragServiceHighVector.close()
        }
    }

    @Test
    fun `should fallback to text search when no embedding model`() {
        val documents = listOf(
            Document("doc1", "machine learning algorithms", emptyMap<String, Any>()),
            Document("doc2", "cooking recipes", emptyMap<String, Any>())
        )

        ragService.accept(documents)

        // Use a single word that should match
        val request = RagRequest.query("machine")
            .withSimilarityThreshold(0.0)
        val response = ragService.search(request)

        assertTrue(
            response.results.isNotEmpty(),
            "Should have results for text match. Results: ${response.results.map { it.match.id }}"
        )
        assertEquals("doc1", response.results.first().match.id)
    }

    @Nested
    inner class ChunkRepositoryTests {

        @Test
        fun `should store chunks in memory when accepting documents`() {
            // Initially no chunks
            assertTrue(ragService.findAll().isEmpty())

            val documents = listOf(
                Document("doc1", "Test document 1", emptyMap<String, Any>()),
                Document("doc2", "Test document 2", emptyMap<String, Any>())
            )

            ragService.accept(documents)

            // Should have chunks stored
            val allChunks = ragService.findAll()
            assertEquals(2, allChunks.size)

            val chunkIds = allChunks.map { it.id }.toSet()
            assertEquals(setOf("doc1", "doc2"), chunkIds)
        }

        @Test
        fun `should find chunks by ID`() {
            val documents = listOf(
                Document("ml-doc", "Machine learning content", emptyMap<String, Any>()),
                Document("ai-doc", "AI content", emptyMap<String, Any>()),
                Document("ds-doc", "Data science content", emptyMap<String, Any>())
            )

            ragService.accept(documents)

            // Test finding existing chunks
            val foundChunks = ragService.findChunksById(listOf("ml-doc", "ai-doc"))
            assertEquals(2, foundChunks.size)

            val chunkIds = foundChunks.map { it.id }.toSet()
            assertEquals(setOf("ml-doc", "ai-doc"), chunkIds)

            // Verify chunk content
            val mlChunk = foundChunks.find { it.id == "ml-doc" }
            assertNotNull(mlChunk)
            assertEquals("Machine learning content", mlChunk!!.text)
        }

        @Test
        fun `should find chunks by non-existing IDs returns empty list`() {
            val documents = listOf(
                Document("existing-doc", "Test content", emptyMap<String, Any>())
            )

            ragService.accept(documents)

            val foundChunks = ragService.findChunksById(listOf("non-existent-1", "non-existent-2"))
            assertTrue(foundChunks.isEmpty())
        }

        @Test
        fun `should find chunks by mixed existing and non-existing IDs`() {
            val documents = listOf(
                Document("doc1", "Content 1", emptyMap<String, Any>()),
                Document("doc2", "Content 2", emptyMap<String, Any>())
            )

            ragService.accept(documents)

            val foundChunks = ragService.findChunksById(listOf("doc1", "non-existent", "doc2"))
            assertEquals(2, foundChunks.size)

            val chunkIds = foundChunks.map { it.id }.toSet()
            assertEquals(setOf("doc1", "doc2"), chunkIds)
        }

        @Test
        fun `should store chunk metadata correctly`() {
            val metadata = mapOf(
                "author" to "John Doe",
                "category" to "AI",
                "source" to "research-paper"
            )

            val documents = listOf(
                Document("research-doc", "Research content", metadata)
            )

            ragService.accept(documents)

            val chunks = ragService.findChunksById(listOf("research-doc"))
            assertEquals(1, chunks.size)

            val chunk = chunks[0]
            assertEquals("John Doe", chunk.metadata["author"])
            assertEquals("AI", chunk.metadata["category"])
            assertEquals("research-paper", chunk.metadata["source"])

            // Should also have service-added metadata
            assertNotNull(chunk.metadata["indexed_at"])
            assertEquals("lucene-rag", chunk.metadata["service"])
        }

        @Test
        fun `should handle empty document list`() {
            ragService.accept(emptyList())

            val allChunks = ragService.findAll()
            assertTrue(allChunks.isEmpty())
        }

        @Test
        fun `should handle document with empty text`() {
            val document = Document("empty-doc", "", emptyMap<String, Any>())

            ragService.accept(listOf(document))

            val chunks = ragService.findAll()
            assertEquals(1, chunks.size)
            assertEquals("", chunks[0].text) // Should handle empty string correctly
        }

        @Test
        fun `should update chunk when document with same ID is added again`() {
            // Add initial document
            ragService.accept(listOf(Document("dup-doc", "Initial content", emptyMap<String, Any>())))

            val initialChunks = ragService.findAll()
            assertEquals(1, initialChunks.size)
            assertEquals("Initial content", initialChunks[0].text)

            // Add document with same ID
            ragService.accept(listOf(Document("dup-doc", "Updated content", emptyMap<String, Any>())))

            val updatedChunks = ragService.findAll()
            assertEquals(1, updatedChunks.size) // Should still have only 1 chunk
            assertEquals("Updated content", updatedChunks[0].text) // Should be updated
        }

        @Test
        fun `should clear all chunks and index when clear is called`() {
            val documents = listOf(
                Document("doc1", "Content 1", emptyMap<String, Any>()),
                Document("doc2", "Content 2", emptyMap<String, Any>())
            )

            ragService.accept(documents)
            assertEquals(2, ragService.findAll().size)

            // Clear everything
            ragService.clear()

            // Should have no chunks
            assertTrue(ragService.findAll().isEmpty())

            // Should also clear search index
            val searchResponse = ragService.search(RagRequest.query("content"))
            assertTrue(searchResponse.results.isEmpty())
        }

        @Test
        fun `should get correct statistics`() {
            val stats = ragService.getStatistics()
            assertEquals(0, stats.totalChunks)
            assertEquals(0, stats.totalDocuments)
            assertEquals(0.0, stats.averageChunkLength)
            assertFalse(stats.hasEmbeddings)
            assertEquals(0.5, stats.vectorWeight) // Default vector weight

            // Add some documents
            val documents = listOf(
                Document("doc1", "Short", emptyMap<String, Any>()),
                Document("doc2", "This is a longer document", emptyMap<String, Any>())
            )

            ragService.accept(documents)

            val updatedStats = ragService.getStatistics()
            assertEquals(2, updatedStats.totalChunks)
            assertEquals(2, updatedStats.totalDocuments)
            assertTrue(updatedStats.averageChunkLength > 0)

            // Average should be (5 + 25) / 2 = 15.0
            assertEquals(15.0, updatedStats.averageChunkLength, 0.1)
        }

        @Test
        fun `should provide meaningful info string with chunk count`() {
            val infoString = ragService.infoString(verbose = false, indent = 0)
            assertTrue(infoString.contains("0 documents, 0 chunks"))

            ragService.accept(listOf(Document("test-doc", "Test content", emptyMap<String, Any>())))

            val infoStringAfter = ragService.infoString(verbose = false, indent = 0)
            assertTrue(infoStringAfter.contains("1 documents, 1 chunks"))
        }

        @Test
        fun `should provide verbose info string`() {
            val infoString = ragService.infoString(verbose = true, indent = 0)
            assertTrue(infoString.contains("text-only"))
            assertFalse(infoString.contains("with embeddings"))

            val embeddingServiceInfo = ragServiceWithEmbedding.infoString(verbose = true, indent = 0)
            assertTrue(embeddingServiceInfo.contains("with embeddings"))
            assertTrue(embeddingServiceInfo.contains("vector weight: 0.5"))
        }
    }

    @Nested
    inner class KeywordSearchTests {

        @Test
        fun `should find chunks by keyword intersection with provided keywords`() {
            val documents = listOf(
                Document("doc1", "This document discusses cars and speed limits on highways",
                    mapOf("keywords" to listOf("cars", "speed", "highways"))),
                Document("doc2", "Pedestrians must obey traffic signals and speed limits",
                    mapOf("keywords" to listOf("pedestrians", "speed", "signals"))),
                Document("doc3", "Cars should yield to pedestrians at crosswalks",
                    mapOf("keywords" to listOf("cars", "pedestrians", "crosswalks"))),
                Document("doc4", "Weather forecast for tomorrow",
                    mapOf("keywords" to listOf("weather", "forecast")))
            )

            ragService.accept(documents)

            // Search for documents with keywords: cars, pedestrians, speed
            val results = ragService.findChunkIdsByKeywords(
                keywords = setOf("cars", "pedestrians", "speed"),
                minIntersection = 1
            )

            // All documents with at least one keyword should be found
            assertTrue(results.isNotEmpty())

            // doc2 should match (pedestrians + speed = 2)
            val doc2Result = results.find { it.first == "doc2" }
            assertNotNull(doc2Result)
            assertEquals(2, doc2Result!!.second, "doc2 should match 2 keywords")

            // doc3 should match (cars + pedestrians = 2)
            val doc3Result = results.find { it.first == "doc3" }
            assertNotNull(doc3Result)
            assertEquals(2, doc3Result!!.second, "doc3 should match 2 keywords")

            // doc4 should not be in results with minIntersection=2
            val resultsMin2 = ragService.findChunkIdsByKeywords(
                keywords = setOf("cars", "pedestrians", "speed"),
                minIntersection = 2
            )
            val doc4InResults = resultsMin2.any { it.first == "doc4" }
            assertFalse(doc4InResults, "doc4 should not match 2+ keywords")
        }

        @Test
        fun `should find chunks by provided keywords in metadata`() {
            val documents = listOf(
                Document(
                    "doc1",
                    "Some content about automotive safety",
                    mapOf("keywords" to listOf("car", "safety", "speedlimit"))
                ),
                Document(
                    "doc2",
                    "Different content about traffic",
                    mapOf("keywords" to listOf("pedestrian", "crosswalk", "speedlimit"))
                ),
                Document(
                    "doc3",
                    "Another topic entirely",
                    mapOf("keywords" to listOf("weather", "forecast"))
                )
            )

            ragService.accept(documents)

            // Search for documents with specific keywords
            val results = ragService.findChunkIdsByKeywords(
                keywords = setOf("car", "pedestrian", "speedlimit"),
                minIntersection = 1
            )

            // doc1 and doc2 should be found
            assertTrue(results.size >= 2)
            val foundIds = results.map { it.first }.toSet()
            assertTrue(foundIds.contains("doc1"))
            assertTrue(foundIds.contains("doc2"))

            // Check match counts
            val doc1Match = results.find { it.first == "doc1" }
            assertEquals(2, doc1Match!!.second) // car + speedlimit

            val doc2Match = results.find { it.first == "doc2" }
            assertEquals(2, doc2Match!!.second) // pedestrian + speedlimit
        }

        @Test
        fun `should return empty list when no keywords match`() {
            val documents = listOf(
                Document("doc1", "Content about something", emptyMap<String, Any>())
            )

            ragService.accept(documents)

            val results = ragService.findChunkIdsByKeywords(
                keywords = setOf("nonexistent", "keywords", "here"),
                minIntersection = 1
            )

            assertTrue(results.isEmpty())
        }

        @Test
        fun `should respect maxResults parameter`() {
            val documents = (1..20).map { i ->
                Document(
                    "doc$i",
                    "This document is about cars and transportation",
                    emptyMap<String, Any>()
                )
            }

            ragService.accept(documents)

            val results = ragService.findChunkIdsByKeywords(
                keywords = setOf("cars", "transportation"),
                minIntersection = 1,
                maxResults = 5
            )

            assertTrue(results.size <= 5)
        }

        @Test
        fun `should sort results by match count descending`() {
            val documents = listOf(
                Document("doc1", "car", mapOf("keywords" to "car")), // 1 match
                Document("doc2", "car pedestrian", mapOf("keywords" to listOf("car", "pedestrian"))), // 2 matches
                Document("doc3", "car pedestrian speedlimit", mapOf("keywords" to listOf("car", "pedestrian", "speedlimit"))) // 3 matches
            )

            ragService.accept(documents)

            val results = ragService.findChunkIdsByKeywords(
                keywords = setOf("car", "pedestrian", "speedlimit"),
                minIntersection = 1
            )

            // Results should be sorted by match count descending
            assertEquals("doc3", results[0].first)
            assertEquals(3, results[0].second)

            assertEquals("doc2", results[1].first)
            assertEquals(2, results[1].second)

            assertEquals("doc1", results[2].first)
            assertEquals(1, results[2].second)
        }

        @Test
        fun `should retrieve chunks by IDs from keyword search`() {
            val documents = listOf(
                Document("ml-doc", "Machine learning algorithms", mapOf("keywords" to listOf("machine", "learning", "algorithms"))),
                Document("ai-doc", "Artificial intelligence systems", mapOf("keywords" to listOf("artificial", "intelligence", "systems"))),
                Document("ds-doc", "Data science and machine learning", mapOf("keywords" to listOf("data", "science", "machine", "learning")))
            )

            ragService.accept(documents)

            // Find chunks by keywords
            val keywordResults = ragService.findChunkIdsByKeywords(
                keywords = setOf("machine", "learning"),
                minIntersection = 2
            )

            // Should find ml-doc and ds-doc (both have machine + learning)
            assertEquals(2, keywordResults.size)
            val chunkIds = keywordResults.map { it.first }

            // Now load the actual chunks
            val chunks = ragService.findChunksById(chunkIds)
            assertEquals(2, chunks.size)

            val chunkTexts = chunks.map { it.text }
            assertTrue(chunkTexts.any { it.contains("Machine learning algorithms") })
            assertTrue(chunkTexts.any { it.contains("Data science and machine learning") })
        }

        @Test
        fun `should handle empty keyword set`() {
            val documents = listOf(
                Document("doc1", "Some content", emptyMap<String, Any>())
            )

            ragService.accept(documents)

            val results = ragService.findChunkIdsByKeywords(
                keywords = emptySet(),
                minIntersection = 1
            )

            assertTrue(results.isEmpty())
        }

        @Test
        fun `should not find chunks without keywords in metadata`() {
            val documents = listOf(
                Document("doc1", "Machine learning is a subset of artificial intelligence", emptyMap<String, Any>())
            )

            ragService.accept(documents)

            // Search for keywords that were not provided
            val results = ragService.findChunkIdsByKeywords(
                keywords = setOf("machine", "learning", "artificial"),
                minIntersection = 1
            )

            assertTrue(results.isEmpty(), "Should not find chunks without keywords in metadata")
        }

        @Test
        fun `should update keywords for existing chunks`() {
            val documents = listOf(
                Document("doc1", "Traffic content", mapOf("keywords" to listOf("traffic", "roads"))),
                Document("doc2", "Weather content", mapOf("keywords" to listOf("weather", "forecast")))
            )

            ragService.accept(documents)

            // Verify initial keywords work
            val initialResults = ragService.findChunkIdsByKeywords(
                keywords = setOf("traffic"),
                minIntersection = 1
            )
            assertEquals(1, initialResults.size)
            assertEquals("doc1", initialResults[0].first)

            // Update keywords for doc1
            ragService.updateKeywords(
                mapOf("doc1" to listOf("car", "pedestrian", "speedlimit"))
            )

            // Old keywords should not find doc1
            val afterUpdateOldKeywords = ragService.findChunkIdsByKeywords(
                keywords = setOf("traffic"),
                minIntersection = 1
            )
            assertTrue(afterUpdateOldKeywords.isEmpty(), "Old keywords should not match after update")

            // New keywords should find doc1
            val afterUpdateNewKeywords = ragService.findChunkIdsByKeywords(
                keywords = setOf("car", "pedestrian"),
                minIntersection = 1
            )
            assertEquals(1, afterUpdateNewKeywords.size)
            assertEquals("doc1", afterUpdateNewKeywords[0].first)
            assertEquals(2, afterUpdateNewKeywords[0].second)

            // Verify keywords are in chunk metadata
            val updatedChunk = ragService.findChunksById(listOf("doc1"))[0]
            val keywords = updatedChunk.metadata["keywords"]
            assertNotNull(keywords)
            @Suppress("UNCHECKED_CAST")
            val keywordList = keywords as List<String>
            assertEquals(setOf("car", "pedestrian", "speedlimit"), keywordList.toSet())
        }

        @Test
        fun `should handle updating non-existent chunk keywords gracefully`() {
            ragService.updateKeywords(
                mapOf("non-existent-id" to listOf("keyword1", "keyword2"))
            )

            // Should not throw, just log warning
            val results = ragService.findChunkIdsByKeywords(
                keywords = setOf("keyword1"),
                minIntersection = 1
            )
            assertTrue(results.isEmpty())
        }

        @Test
        fun `should maintain correct count after updates that create deleted documents`() {
            // Add initial documents
            val documents = listOf(
                Document("doc1", "Traffic content", mapOf("keywords" to listOf("traffic", "roads"))),
                Document("doc2", "Weather content", mapOf("keywords" to listOf("weather", "forecast"))),
                Document("doc3", "Sports content", mapOf("keywords" to listOf("sports", "football")))
            )

            ragService.accept(documents)

            // Verify initial count
            assertEquals(3, ragService.count())
            val initialStats = ragService.getStatistics()
            assertEquals(3, initialStats.totalChunks)
            assertEquals(3, initialStats.totalDocuments)

            // Update keywords for doc1 and doc2 (this deletes old documents and adds new ones)
            ragService.updateKeywords(
                mapOf(
                    "doc1" to listOf("car", "pedestrian", "speedlimit"),
                    "doc2" to listOf("rain", "sun", "temperature")
                )
            )

            // Count should still be 3 (not 5 due to deleted docs)
            assertEquals(3, ragService.count(), "Count should remain 3 after keyword updates")
            val afterUpdateStats = ragService.getStatistics()
            assertEquals(3, afterUpdateStats.totalChunks, "Total chunks should remain 3")
            assertEquals(3, afterUpdateStats.totalDocuments, "Total documents should remain 3")

            // Verify all chunks are still accessible
            val allChunks = ragService.findAll()
            assertEquals(3, allChunks.size, "findAll should return exactly 3 chunks")
            assertEquals(setOf("doc1", "doc2", "doc3"), allChunks.map { it.id }.toSet())
        }
    }

    @Nested
    inner class ConcurrencyTests {

        @Test
        fun `should handle concurrent chunk storage operations`() {
            val numThreads = 10
            val documentsPerThread = 50

            val threads = (1..numThreads).map { threadIndex ->
                Thread {
                    val documents = (1..documentsPerThread).map { docIndex ->
                        Document(
                            "thread-${threadIndex}-doc-${docIndex}",
                            "Content for thread $threadIndex document $docIndex",
                            emptyMap<String, Any>()
                        )
                    }
                    ragService.accept(documents)
                }
            }

            threads.forEach { it.start() }
            threads.forEach { it.join() }

            val allChunks = ragService.findAll()
            assertEquals(numThreads * documentsPerThread, allChunks.size)

            // Verify all chunks are present and unique
            val chunkIds = allChunks.map { it.id }.toSet()
            assertEquals(numThreads * documentsPerThread, chunkIds.size) // Should be all unique
        }

        @Test
        fun `should handle concurrent read and write operations`() {
            // Pre-populate with some data
            val initialDocs = (1..100).map {
                Document("init-$it", "Initial doc $it", emptyMap<String, Any>())
            }
            ragService.accept(initialDocs)

            val writerThread = Thread {
                repeat(50) { i ->
                    ragService.accept(
                        listOf(
                            Document("writer-$i", "Writer doc $i", emptyMap<String, Any>())
                        )
                    )
                }
            }

            val readerThread = Thread {
                repeat(100) {
                    ragService.findAll()
                    ragService.findChunksById(listOf("init-1", "init-50", "writer-1"))
                }
            }

            writerThread.start()
            readerThread.start()

            writerThread.join()
            readerThread.join()

            // Should have initial + writer documents
            val finalChunks = ragService.findAll()
            assertTrue(finalChunks.size >= 100) // At least the initial documents
        }
    }


}

// Mock embedding model for testing
class MockEmbeddingModel : EmbeddingModel {

    override fun embed(document: Document): FloatArray {
        return embed(document.text!!)
    }

    override fun call(request: EmbeddingRequest): EmbeddingResponse {
        TODO()
    }

    override fun embed(text: String): FloatArray {
        // Simple deterministic embedding based on text content for testing
        val words = text.lowercase().split(" ")
        val embedding = FloatArray(100) // 100-dimensional embedding

        // Create deterministic embeddings based on word content
        words.forEach { word ->
            val hash = word.hashCode()
            for (i in embedding.indices) {
                embedding[i] += (hash * (i + 1)).toFloat() / 1000000f
            }
        }

        // Normalize
        val norm = kotlin.math.sqrt(embedding.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0) {
            for (i in embedding.indices) {
                embedding[i] /= norm
            }
        }

        return embedding.map { it.toFloat() }.toFloatArray()
    }

    override fun dimensions(): Int = 100
}
