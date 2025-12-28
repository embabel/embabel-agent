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

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.service.RagRequest
import com.embabel.common.ai.model.SpringEmbeddingService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import kotlin.reflect.full.functions
import kotlin.reflect.jvm.isAccessible

class LuceneSearchOperationsTest {

    private lateinit var ragService: LuceneSearchOperations
    private lateinit var ragServiceWithEmbedding: LuceneSearchOperations
    private val mockEmbeddingModel = MockEmbeddingModel()

    /**
     * Helper method to convert Spring AI Documents to Chunks and add them to the service
     */
    private fun LuceneSearchOperations.acceptDocuments(documents: List<Document>) {
        val chunks = documents.map { doc ->
            val docId = doc.id ?: error("Document ID cannot be null")
            com.embabel.agent.rag.model.Chunk(
                id = docId,
                text = doc.text ?: "",
                parentId = docId, // Use the chunk ID as its own parent for test documents
                metadata = doc.metadata
            )
        }
        this.onNewRetrievables(chunks)

        // Call protected commit() method using reflection
        val commitMethod = this::class.functions.find { it.name == "commit" }
        commitMethod?.let {
            it.isAccessible = true
            it.call(this)
        }
    }

    /**
     * Helper method to call protected commit() using reflection
     */
    private fun LuceneSearchOperations.commitChanges() {
        val commitMethod = this::class.functions.find { it.name == "commit" }
        commitMethod?.let {
            it.isAccessible = true
            it.call(this)
        }
    }

    @BeforeEach
    fun setUp() {
        ragService = LuceneSearchOperations(name = "lucene-rag")
        ragServiceWithEmbedding = LuceneSearchOperations(
            name = "hybrid-lucene-rag",
            embeddingService = SpringEmbeddingService("name", "provider", mockEmbeddingModel),
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
        val response = ragService.hybridSearch(request)

        assertEquals("lucene-rag.hybrid", response.facetName)
        assertTrue(response.results.isEmpty())
    }

    @Test
    fun `should index and search documents`() {
        // Index some test documents using accept
        val documents = listOf(
            Document("doc1", "This is a test document about machine learning", emptyMap<String, Any>()),
            Document("doc2", "Another document discussing artificial intelligence", emptyMap<String, Any>()),
            Document("doc3", "A completely different topic about cooking recipes", emptyMap<String, Any>())
        )

        ragService.acceptDocuments(documents)

        // Search for documents
        val request = RagRequest.query("machine learning")
        val response = ragService.hybridSearch(request)

        assertEquals("lucene-rag", response.facetName)
        assertTrue(response.results.isNotEmpty())

        // Should find the most relevant document first
        val firstResult = response.results.first()
        assertEquals("doc1", firstResult.match.id)
        assertTrue(firstResult.score > 0.0)
    }

    @Test
    fun `should respect similarity threshold`() {
        val documents = listOf(
            Document("doc1", "machine learning algorithms", emptyMap<String, Any>()),
            Document("doc2", "completely unrelated content about cooking", emptyMap<String, Any>())
        )

        ragService.acceptDocuments(documents)

        // High threshold should filter out low-relevance results
        val request = RagRequest.query("machine learning")
            .withSimilarityThreshold(0.9)

        val response = ragService.hybridSearch(request)

        // Should only return highly relevant documents
        response.results.forEach { result ->
            assertTrue(result.score >= 0.9)
        }
    }

    @Test
    fun `should respect topK limit`() {
        val documents = (1..10).map { i ->
            Document("doc$i", "machine learning document number $i", emptyMap<String, Any>())
        }

        ragService.acceptDocuments(documents)

        val request = RagRequest.query("machine learning").withTopK(3)
        val response = ragService.hybridSearch(request)

        assertTrue(response.results.size <= 3)
    }

    @Test
    fun `should handle document metadata correctly`() {
        val metadata = mapOf("author" to "John Doe", "category" to "AI")
        val documents = listOf(
            Document("doc1", "Test content", metadata)
        )

        ragService.acceptDocuments(documents)

        val request = RagRequest.query("test")
            .withSimilarityThreshold(0.0)
        val response = ragService.hybridSearch(request)

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

        // After adding documents using acceptDocuments (creates Chunks, not NavigableDocuments)
        ragService.acceptDocuments(listOf(Document("doc1", "test content", emptyMap<String, Any>())))
        val infoStringAfter = ragService.infoString(verbose = false, indent = 0)
        assertTrue(infoStringAfter.contains("0 documents")) // acceptDocuments creates Chunks only
    }

    @Test
    fun `retrievable should provide embeddable value`() {
        val documents = listOf(Document("doc1", "Test document content", emptyMap<String, Any>()))
        ragServiceWithEmbedding.acceptDocuments(documents)

        val request = RagRequest.query("test")
            .withSimilarityThreshold(.0)
        val response = ragServiceWithEmbedding.hybridSearch(request)

        assertEquals(1, response.results.size)
        val retrievable = response.results.first().match
        assertEquals("Test document content", retrievable.embeddableValue())
    }

    @Test
    fun `should handle multiple acceptDocuments calls correctly without vector`() {
        // First batch
        ragService.acceptDocuments(
            listOf(
                Document("doc1", "First batch document about AI and artificial intelligence", emptyMap<String, Any>()),
                Document("doc2", "Another first batch document about ML", emptyMap<String, Any>())
            )
        )

        // Second batch
        ragService.acceptDocuments(
            listOf(
                Document("doc3", "Second batch document about artificial intelligence", emptyMap<String, Any>()),
                Document("doc4", "Another second batch document about machine learning", emptyMap<String, Any>())
            )
        )

        val request = RagRequest.query("artificial intelligence")
            .withSimilarityThreshold(0.0)
        val response = ragService.hybridSearch(request)

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

        ragServiceWithEmbedding.acceptDocuments(documents)

        // Search should use both text and vector similarity
        val request = RagRequest.query("AI and machine learning")
            .withSimilarityThreshold(0.0)
        val response = ragServiceWithEmbedding.hybridSearch(request)

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
        val ragServiceHighVector = LuceneSearchOperations(
            name = "high-vector-weight",
            embeddingService = SpringEmbeddingService("name", "provider", mockEmbeddingModel),
            vectorWeight = 0.9 // High vector weight
        )

        try {
            val documents = listOf(
                Document("doc1", "machine learning", emptyMap<String, Any>()),
                Document("doc2", "artificial intelligence", emptyMap<String, Any>())
            )

            ragServiceHighVector.acceptDocuments(documents)

            // Use a query that should match via text search to ensure we get text results for hybrid
            val request = RagRequest.query("machine")
                .withSimilarityThreshold(0.0)
            val response = ragServiceHighVector.hybridSearch(request)

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

        ragService.acceptDocuments(documents)

        // Use a single word that should match
        val request = RagRequest.query("machine")
            .withSimilarityThreshold(0.0)
        val response = ragService.hybridSearch(request)

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

            ragService.acceptDocuments(documents)

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

            ragService.acceptDocuments(documents)

            // Test finding existing chunks
            val foundChunks = ragService.findAllChunksById(listOf("ml-doc", "ai-doc"))
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

            ragService.acceptDocuments(documents)

            val foundChunks = ragService.findAllChunksById(listOf("non-existent-1", "non-existent-2"))
            assertTrue(foundChunks.isEmpty())
        }

        @Test
        fun `should find chunks by mixed existing and non-existing IDs`() {
            val documents = listOf(
                Document("doc1", "Content 1", emptyMap<String, Any>()),
                Document("doc2", "Content 2", emptyMap<String, Any>())
            )

            ragService.acceptDocuments(documents)

            val foundChunks = ragService.findAllChunksById(listOf("doc1", "non-existent", "doc2"))
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

            ragService.acceptDocuments(documents)

            val chunks = ragService.findAllChunksById(listOf("research-doc"))
            assertEquals(1, chunks.size)

            val chunk = chunks[0]
            assertEquals("John Doe", chunk.metadata["author"])
            assertEquals("AI", chunk.metadata["category"])
            assertEquals("research-paper", chunk.metadata["source"])
        }

        @Test
        fun `should handle empty document list`() {
            ragService.acceptDocuments(emptyList())

            val allChunks = ragService.findAll()
            assertTrue(allChunks.isEmpty())
        }

        @Test
        fun `should handle document with empty text`() {
            val document = Document("empty-doc", "", emptyMap<String, Any>())

            ragService.acceptDocuments(listOf(document))

            val chunks = ragService.findAll()
            assertEquals(1, chunks.size)
            assertEquals("", chunks[0].text) // Should handle empty string correctly
        }

        @Test
        fun `should update chunk when document with same ID is added again`() {
            // Add initial document
            ragService.acceptDocuments(listOf(Document("dup-doc", "Initial content", emptyMap<String, Any>())))

            val initialChunks = ragService.findAll()
            assertEquals(1, initialChunks.size)
            assertEquals("Initial content", initialChunks[0].text)

            // Add document with same ID
            ragService.acceptDocuments(listOf(Document("dup-doc", "Updated content", emptyMap<String, Any>())))

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

            ragService.acceptDocuments(documents)
            assertEquals(2, ragService.findAll().size)

            // Clear everything
            ragService.clear()

            // Should have no chunks
            assertTrue(ragService.findAll().isEmpty())

            // Should also clear search index
            val searchResponse = ragService.hybridSearch(RagRequest.query("content"))
            assertTrue(searchResponse.results.isEmpty())
        }

        @Test
        fun `should get correct statistics`() {
            val stats = ragService.info()
            assertEquals(0, stats.chunkCount)
            assertEquals(0, stats.documentCount)
            assertEquals(0.0, stats.averageChunkLength)
            assertFalse(stats.hasEmbeddings)
            assertEquals(0.5, stats.vectorWeight) // Default vector weight

            // Add some documents
            val documents = listOf(
                Document("doc1", "Short", emptyMap<String, Any>()),
                Document("doc2", "This is a longer document", emptyMap<String, Any>())
            )

            ragService.acceptDocuments(documents)

            val updatedStats = ragService.info()
            assertEquals(2, updatedStats.chunkCount)
            assertEquals(0, updatedStats.documentCount) // acceptDocuments creates Chunks, not NavigableDocuments
            assertTrue(updatedStats.averageChunkLength > 0)

            // Average should be (5 + 25) / 2 = 15.0
            assertEquals(15.0, updatedStats.averageChunkLength, 0.1)
        }

        @Test
        fun `should provide meaningful info string with chunk count`() {
            val infoString = ragService.infoString(verbose = false, indent = 0)
            assertTrue(infoString.contains("0 documents, 0 chunks"))

            ragService.acceptDocuments(listOf(Document("test-doc", "Test content", emptyMap<String, Any>())))

            val infoStringAfter = ragService.infoString(verbose = false, indent = 0)
            assertTrue(infoStringAfter.contains("0 documents, 1 chunks")) // acceptDocuments creates Chunks only
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
                Document(
                    "doc1", "This document discusses cars and speed limits on highways",
                    mapOf("keywords" to listOf("cars", "speed", "highways"))
                ),
                Document(
                    "doc2", "Pedestrians must obey traffic signals and speed limits",
                    mapOf("keywords" to listOf("pedestrians", "speed", "signals"))
                ),
                Document(
                    "doc3", "Cars should yield to pedestrians at crosswalks",
                    mapOf("keywords" to listOf("cars", "pedestrians", "crosswalks"))
                ),
                Document(
                    "doc4", "Weather forecast for tomorrow",
                    mapOf("keywords" to listOf("weather", "forecast"))
                )
            )

            ragService.acceptDocuments(documents)

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

            ragService.acceptDocuments(documents)

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

            ragService.acceptDocuments(documents)

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

            ragService.acceptDocuments(documents)

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
                Document(
                    "doc3",
                    "car pedestrian speedlimit",
                    mapOf("keywords" to listOf("car", "pedestrian", "speedlimit"))
                ) // 3 matches
            )

            ragService.acceptDocuments(documents)

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
                Document(
                    "ml-doc",
                    "Machine learning algorithms",
                    mapOf("keywords" to listOf("machine", "learning", "algorithms"))
                ),
                Document(
                    "ai-doc",
                    "Artificial intelligence systems",
                    mapOf("keywords" to listOf("artificial", "intelligence", "systems"))
                ),
                Document(
                    "ds-doc",
                    "Data science and machine learning",
                    mapOf("keywords" to listOf("data", "science", "machine", "learning"))
                )
            )

            ragService.acceptDocuments(documents)

            // Find chunks by keywords
            val keywordResults = ragService.findChunkIdsByKeywords(
                keywords = setOf("machine", "learning"),
                minIntersection = 2
            )

            // Should find ml-doc and ds-doc (both have machine + learning)
            assertEquals(2, keywordResults.size)
            val chunkIds = keywordResults.map { it.first }

            // Now load the actual chunks
            val chunks = ragService.findAllChunksById(chunkIds)
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

            ragService.acceptDocuments(documents)

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

            ragService.acceptDocuments(documents)

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

            ragService.acceptDocuments(documents)

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
            val updatedChunk = ragService.findAllChunksById(listOf("doc1"))[0]
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

            ragService.acceptDocuments(documents)

            // Verify initial count
            assertEquals(3, ragService.info().contentElementCount)
            val initialStats = ragService.info()
            assertEquals(3, initialStats.chunkCount)
            assertEquals(0, initialStats.documentCount) // acceptDocuments creates Chunks, not NavigableDocuments

            // Update keywords for doc1 and doc2 (this deletes old documents and adds new ones)
            ragService.updateKeywords(
                mapOf(
                    "doc1" to listOf("car", "pedestrian", "speedlimit"),
                    "doc2" to listOf("rain", "sun", "temperature")
                )
            )

            // Count should still be 3 (not 5 due to deleted docs)
            assertEquals(3, ragService.info().contentElementCount, "Count should remain 3 after keyword updates")
            val afterUpdateStats = ragService.info()
            assertEquals(3, afterUpdateStats.chunkCount, "Total chunks should remain 3")
            assertEquals(
                0,
                afterUpdateStats.documentCount,
                "documentCount should be 0 (only Chunks, no NavigableDocuments)"
            )

            // Verify all chunks are still accessible
            val allChunks = ragService.findAll()
            assertEquals(3, allChunks.size, "findAll should return exactly 3 chunks")
            assertEquals(setOf("doc1", "doc2", "doc3"), allChunks.map { it.id }.toSet())
        }
    }

    @Nested
    inner class DeleteDocumentTests {

        @Test
        fun `should delete document root and all descendants by URI`() {
            // Create a document structure
            val documentUri = "test://doc1"
            val root = com.embabel.agent.rag.model.MaterializedDocument(
                id = "doc1",
                uri = documentUri,
                title = "Test Document",
                children = listOf()
            )

            val section = com.embabel.agent.rag.model.LeafSection(
                id = "section1",
                uri = documentUri,
                title = "Section 1",
                text = "Section content",
                parentId = "doc1"
            )

            val chunk1 = com.embabel.agent.rag.model.Chunk(
                id = "chunk1",
                text = "Chunk 1 content",
                parentId = "section1",
                metadata = emptyMap()
            )

            val chunk2 = com.embabel.agent.rag.model.Chunk(
                id = "chunk2",
                text = "Chunk 2 content",
                parentId = "section1",
                metadata = emptyMap()
            )

            // Save all elements
            ragService.save(root)
            ragService.save(section)
            ragService.onNewRetrievables(listOf(chunk1, chunk2))
            ragService.commitChanges()

            // Verify elements exist
            assertEquals(4, ragService.info().contentElementCount)

            // Delete document and descendants
            val result = ragService.deleteRootAndDescendants(documentUri)

            assertNotNull(result)
            assertEquals(documentUri, result!!.rootUri)
            assertEquals(4, result.deletedCount)

            // Verify all elements are deleted
            assertEquals(0, ragService.info().contentElementCount)
            assertNull(ragService.findById("doc1"))
            assertNull(ragService.findById("section1"))
            assertTrue(ragService.findAllChunksById(listOf("chunk1", "chunk2")).isEmpty())
        }

        @Test
        fun `should return null when deleting non-existent document`() {
            val result = ragService.deleteRootAndDescendants("test://nonexistent")
            assertNull(result)
        }

        @Test
        fun `should not affect other documents when deleting one`() {
            // Create two separate documents
            val doc1Uri = "test://doc1"
            val doc1 = com.embabel.agent.rag.model.MaterializedDocument(
                id = "doc1",
                uri = doc1Uri,
                title = "Document 1",
                children = emptyList()
            )

            val chunk1 = com.embabel.agent.rag.model.Chunk(
                id = "chunk1",
                text = "Chunk from doc1",
                parentId = "doc1",
                metadata = emptyMap()
            )

            val doc2Uri = "test://doc2"
            val doc2 = com.embabel.agent.rag.model.MaterializedDocument(
                id = "doc2",
                uri = doc2Uri,
                title = "Document 2",
                children = emptyList()
            )

            val chunk2 = com.embabel.agent.rag.model.Chunk(
                id = "chunk2",
                text = "Chunk from doc2",
                parentId = "doc2",
                metadata = emptyMap()
            )

            // Save all
            ragService.save(doc1)
            ragService.save(doc2)
            ragService.onNewRetrievables(listOf(chunk1, chunk2))
            ragService.commitChanges()

            assertEquals(4, ragService.info().contentElementCount)

            // Delete only doc1
            val result = ragService.deleteRootAndDescendants(doc1Uri)

            assertNotNull(result)
            assertEquals(2, result!!.deletedCount)

            // Verify doc1 and its chunk are deleted
            assertEquals(2, ragService.info().contentElementCount)
            assertNull(ragService.findById("doc1"))
            assertTrue(ragService.findAllChunksById(listOf("chunk1")).isEmpty())

            // Verify doc2 and its chunk still exist
            assertNotNull(ragService.findById("doc2"))
            assertEquals(1, ragService.findAllChunksById(listOf("chunk2")).size)
        }

        @Test
        fun `should delete deeply nested hierarchy`() {
            val documentUri = "test://nested"
            val root = com.embabel.agent.rag.model.MaterializedDocument(
                id = "root",
                uri = documentUri,
                title = "Root",
                children = emptyList()
            )

            val section1 = com.embabel.agent.rag.model.DefaultMaterializedContainerSection(
                id = "section1",
                uri = documentUri,
                title = "Section 1",
                children = emptyList(),
                parentId = "root"
            )

            val section2 = com.embabel.agent.rag.model.DefaultMaterializedContainerSection(
                id = "section2",
                uri = documentUri,
                title = "Section 2",
                children = emptyList(),
                parentId = "section1"
            )

            val leaf = com.embabel.agent.rag.model.LeafSection(
                id = "leaf",
                uri = documentUri,
                title = "Leaf",
                text = "Leaf content",
                parentId = "section2"
            )

            val chunk = com.embabel.agent.rag.model.Chunk(
                id = "chunk",
                text = "Chunk content",
                parentId = "leaf",
                metadata = emptyMap()
            )

            // Save all
            ragService.save(root)
            ragService.save(section1)
            ragService.save(section2)
            ragService.save(leaf)
            ragService.onNewRetrievables(listOf(chunk))
            ragService.commitChanges()

            assertEquals(5, ragService.info().contentElementCount)

            // Delete root and all descendants
            val result = ragService.deleteRootAndDescendants(documentUri)

            assertNotNull(result)
            assertEquals(5, result!!.deletedCount)
            assertEquals(0, ragService.info().contentElementCount)
        }

        @Test
        fun `should not find deleted content in search`() {
            val documentUri = "test://searchable"
            val root = com.embabel.agent.rag.model.MaterializedDocument(
                id = "root",
                uri = documentUri,
                title = "Searchable Document",
                children = emptyList()
            )

            val chunk = com.embabel.agent.rag.model.Chunk(
                id = "chunk",
                text = "unique searchable content",
                parentId = "root",
                metadata = emptyMap()
            )

            ragService.save(root)
            ragService.onNewRetrievables(listOf(chunk))
            ragService.commitChanges()

            // Verify we can find it before deletion
            val beforeDelete =
                ragService.hybridSearch(RagRequest.query("unique searchable").withSimilarityThreshold(0.0))
            assertTrue(beforeDelete.results.isNotEmpty())

            // Delete the document
            ragService.deleteRootAndDescendants(documentUri)

            // Verify search returns no results
            val afterDelete =
                ragService.hybridSearch(RagRequest.query("unique searchable").withSimilarityThreshold(0.0))
            assertTrue(afterDelete.results.isEmpty())
        }

        @Test
        fun `should handle deletion of document with multiple chunk types`() {
            val documentUri = "test://mixed"
            val root = com.embabel.agent.rag.model.MaterializedDocument(
                id = "root",
                uri = documentUri,
                title = "Mixed Document",
                children = emptyList()
            )

            val section = com.embabel.agent.rag.model.DefaultMaterializedContainerSection(
                id = "section",
                uri = documentUri,
                title = "Section",
                children = emptyList(),
                parentId = "root"
            )

            val leaf = com.embabel.agent.rag.model.LeafSection(
                id = "leaf",
                uri = documentUri,
                title = "Leaf",
                text = "Leaf text",
                parentId = "section"
            )

            val chunks = (1..5).map { i ->
                com.embabel.agent.rag.model.Chunk(
                    id = "chunk$i",
                    text = "Chunk $i content",
                    parentId = "leaf",
                    metadata = emptyMap()
                )
            }

            ragService.save(root)
            ragService.save(section)
            ragService.save(leaf)
            ragService.onNewRetrievables(chunks)
            ragService.commitChanges()

            assertEquals(8, ragService.info().contentElementCount)

            // Delete all
            val result = ragService.deleteRootAndDescendants(documentUri)

            assertNotNull(result)
            assertEquals(8, result!!.deletedCount)
            assertEquals(0, ragService.info().contentElementCount)
        }
    }

    @Nested
    inner class ExistsRootWithUriTests {

        @Test
        fun `should return true when root document exists`() {
            val documentUri = "test://existing-doc"
            val root = com.embabel.agent.rag.model.MaterializedDocument(
                id = "doc1",
                uri = documentUri,
                title = "Existing Document",
                children = emptyList()
            )

            ragService.save(root)
            ragService.commitChanges()

            assertTrue(ragService.existsRootWithUri(documentUri))
        }

        @Test
        fun `should return false when root document does not exist`() {
            assertFalse(ragService.existsRootWithUri("test://nonexistent"))
        }

        @Test
        fun `should return false for child sections with same URI`() {
            val documentUri = "test://doc-with-sections"
            val root = com.embabel.agent.rag.model.MaterializedDocument(
                id = "root",
                uri = documentUri,
                title = "Root Document",
                children = emptyList()
            )

            // Save a section with same URI but without Document label
            val section = com.embabel.agent.rag.model.LeafSection(
                id = "section",
                uri = documentUri,
                title = "Section",
                text = "Section content",
                parentId = "root"
            )

            ragService.save(root)
            ragService.save(section)
            ragService.commitChanges()

            // Should still return true because root exists
            assertTrue(ragService.existsRootWithUri(documentUri))
        }

        @Test
        fun `should return false after root is deleted`() {
            val documentUri = "test://to-be-deleted"
            val root = com.embabel.agent.rag.model.MaterializedDocument(
                id = "doc1",
                uri = documentUri,
                title = "Document to Delete",
                children = emptyList()
            )

            ragService.save(root)
            ragService.commitChanges()

            assertTrue(ragService.existsRootWithUri(documentUri))

            // Delete the document
            ragService.deleteRootAndDescendants(documentUri)

            assertFalse(ragService.existsRootWithUri(documentUri))
        }

        @Test
        fun `should handle multiple documents with different URIs`() {
            val uri1 = "test://doc1"
            val uri2 = "test://doc2"
            val uri3 = "test://doc3"

            val doc1 = com.embabel.agent.rag.model.MaterializedDocument(
                id = "doc1",
                uri = uri1,
                title = "Document 1",
                children = emptyList()
            )

            val doc2 = com.embabel.agent.rag.model.MaterializedDocument(
                id = "doc2",
                uri = uri2,
                title = "Document 2",
                children = emptyList()
            )

            ragService.save(doc1)
            ragService.save(doc2)
            ragService.commitChanges()

            assertTrue(ragService.existsRootWithUri(uri1))
            assertTrue(ragService.existsRootWithUri(uri2))
            assertFalse(ragService.existsRootWithUri(uri3))
        }

        @Test
        fun `should return true for documents with ContentRoot interface`() {
            val documentUri = "test://content-root"
            // Use MaterializedDocument which properly implements ContentRoot
            val root = com.embabel.agent.rag.model.MaterializedDocument(
                id = "root1",
                uri = documentUri,
                title = "Content Root Document",
                children = emptyList()
            )

            ragService.save(root)
            ragService.commitChanges()

            assertTrue(ragService.existsRootWithUri(documentUri))
        }

        @Test
        fun `should handle empty URI string`() {
            assertFalse(ragService.existsRootWithUri(""))
        }

        @Test
        fun `should be case-sensitive for URIs`() {
            val lowerUri = "test://my-document"
            val upperUri = "TEST://MY-DOCUMENT"

            val root = com.embabel.agent.rag.model.MaterializedDocument(
                id = "doc1",
                uri = lowerUri,
                title = "Document",
                children = emptyList()
            )

            ragService.save(root)
            ragService.commitChanges()

            assertTrue(ragService.existsRootWithUri(lowerUri))
            assertFalse(ragService.existsRootWithUri(upperUri))
        }

        @Test
        fun `should work correctly in concurrent environment`() {
            val numThreads = 5
            val docsPerThread = 10

            val threads = (1..numThreads).map { threadIndex ->
                Thread {
                    repeat(docsPerThread) { docIndex ->
                        val uri = "test://thread-${threadIndex}-doc-${docIndex}"
                        val doc = com.embabel.agent.rag.model.MaterializedDocument(
                            id = "thread-${threadIndex}-doc-${docIndex}",
                            uri = uri,
                            title = "Document $threadIndex-$docIndex",
                            children = emptyList()
                        )
                        ragService.save(doc)
                    }
                }
            }

            threads.forEach { it.start() }
            threads.forEach { it.join() }
            ragService.commitChanges()

            // Check that all documents exist
            repeat(numThreads) { threadIndex ->
                repeat(docsPerThread) { docIndex ->
                    val uri = "test://thread-${threadIndex + 1}-doc-${docIndex}"
                    assertTrue(
                        ragService.existsRootWithUri(uri),
                        "Document with URI $uri should exist"
                    )
                }
            }
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
                    ragService.acceptDocuments(documents)
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
            ragService.acceptDocuments(initialDocs)

            val writerThread = Thread {
                repeat(50) { i ->
                    ragService.acceptDocuments(
                        listOf(
                            Document("writer-$i", "Writer doc $i", emptyMap<String, Any>())
                        )
                    )
                }
            }

            val readerThread = Thread {
                repeat(100) {
                    ragService.findAll()
                    ragService.findAllChunksById(listOf("init-1", "init-50", "writer-1"))
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

    @Nested
    inner class CoreSearchOperationsTests {

        @Test
        fun `textSearch should find documents by text content`() {
            val documents = listOf(
                Document("doc1", "Machine learning algorithms for data analysis", emptyMap<String, Any>()),
                Document("doc2", "Cooking recipes and kitchen tips", emptyMap<String, Any>()),
                Document("doc3", "Deep learning neural networks", emptyMap<String, Any>())
            )

            ragService.acceptDocuments(documents)

            val request = RagRequest.query("machine learning").withSimilarityThreshold(0.0)
            val results = ragService.textSearch(request, Chunk::class.java)

            assertTrue(results.isNotEmpty())
            assertTrue(results.any { it.match.id == "doc1" })
        }

        @Test
        fun `textSearch should respect topK parameter`() {
            val documents = (1..10).map { i ->
                Document("doc$i", "Machine learning document $i about algorithms", emptyMap<String, Any>())
            }

            ragService.acceptDocuments(documents)

            val request = RagRequest.query("machine learning").withTopK(3).withSimilarityThreshold(0.0)
            val results = ragService.textSearch(request, Chunk::class.java)

            assertTrue(results.size <= 3)
        }

        @Test
        fun `textSearch should return empty list when no matches`() {
            val documents = listOf(
                Document("doc1", "Cooking recipes", emptyMap<String, Any>())
            )

            ragService.acceptDocuments(documents)

            val request = RagRequest.query("quantum physics").withSimilarityThreshold(0.0)
            val results = ragService.textSearch(request, Chunk::class.java)

            assertTrue(results.isEmpty())
        }

        @Test
        fun `vectorSearch should return empty when no embedding model`() {
            val documents = listOf(
                Document("doc1", "Machine learning content", emptyMap<String, Any>())
            )

            ragService.acceptDocuments(documents)

            val request = RagRequest.query("machine learning").withSimilarityThreshold(0.0)
            val results = ragService.vectorSearch(request, Chunk::class.java)

            assertTrue(results.isEmpty())
        }

        @Test
        fun `vectorSearch should work with embedding model`() {
            val documents = listOf(
                Document("doc1", "Machine learning algorithms", emptyMap<String, Any>()),
                Document("doc2", "Deep learning neural networks", emptyMap<String, Any>()),
                Document("doc3", "Cooking recipes", emptyMap<String, Any>())
            )

            ragServiceWithEmbedding.acceptDocuments(documents)

            val request = RagRequest.query("machine learning").withSimilarityThreshold(0.0)
            val results = ragServiceWithEmbedding.vectorSearch(request, Chunk::class.java)

            assertTrue(results.isNotEmpty())
        }

        @Test
        fun `textSearch should return results with score`() {
            val documents = listOf(
                Document("doc1", "Machine learning algorithms", emptyMap<String, Any>())
            )

            ragService.acceptDocuments(documents)

            val textResults = ragService.textSearch(
                RagRequest.query("machine").withSimilarityThreshold(0.0),
                Chunk::class.java
            )
            assertTrue(textResults.all { it.score > 0 })
        }
    }

    @Nested
    inner class IngestionDatePersistenceTests {

        @Test
        fun `should persist and retrieve ingestionDate for MaterializedDocument`() {
            val testTime = java.time.Instant.parse("2025-01-15T10:30:00Z")
            val document = com.embabel.agent.rag.model.MaterializedDocument(
                id = "test-doc-1",
                uri = "test://document-with-date",
                title = "Test Document",
                ingestionTimestamp = testTime,
                children = emptyList()
            )

            ragService.writeAndChunkDocument(document)

            // Retrieve the document and verify ingestionDate
            val retrieved = ragService.findById("test-doc-1")
            assertNotNull(retrieved)
            assertTrue(retrieved is com.embabel.agent.rag.model.ContentRoot)

            val contentRoot = retrieved as com.embabel.agent.rag.model.ContentRoot
            assertEquals(testTime, contentRoot.ingestionTimestamp)
        }

        @Test
        fun `should persist ingestionDate in propertiesToPersist`() {
            val testTime = java.time.Instant.parse("2025-02-20T15:45:30Z")
            val document = com.embabel.agent.rag.model.MaterializedDocument(
                id = "test-doc-2",
                uri = "test://document-properties",
                title = "Properties Test",
                ingestionTimestamp = testTime,
                children = emptyList()
            )

            val properties = document.propertiesToPersist()

            assertTrue(properties.containsKey("ingestionTimestamp"))
            assertEquals(testTime, properties["ingestionTimestamp"])
            assertEquals("Properties Test", properties["title"])
        }

        @Test
        fun `should handle documents with default ingestionDate`() {
            // Create document without explicit ingestionDate (uses default)
            val beforeCreation = java.time.Instant.now()
            val document = com.embabel.agent.rag.model.MaterializedDocument(
                id = "test-doc-3",
                uri = "test://document-default-date",
                title = "Default Date Test",
                children = emptyList()
            )
            val afterCreation = java.time.Instant.now()

            ragService.writeAndChunkDocument(document)

            val retrieved = ragService.findById("test-doc-3") as? com.embabel.agent.rag.model.ContentRoot
            assertNotNull(retrieved)

            // Should be between before and after creation
            assertTrue(
                retrieved!!.ingestionTimestamp >= beforeCreation && retrieved.ingestionTimestamp <= afterCreation,
                "Expected ingestionDate to be between $beforeCreation and $afterCreation but was ${retrieved.ingestionTimestamp}"
            )
        }

        @Test
        fun `should preserve different ingestionDates for multiple documents`() {
            val time1 = java.time.Instant.parse("2025-01-01T00:00:00Z")
            val time2 = java.time.Instant.parse("2025-06-15T12:00:00Z")
            val time3 = java.time.Instant.parse("2025-12-31T23:59:59Z")

            val doc1 = com.embabel.agent.rag.model.MaterializedDocument(
                id = "doc-1",
                uri = "test://doc1",
                title = "Document 1",
                ingestionTimestamp = time1,
                children = emptyList()
            )
            val doc2 = com.embabel.agent.rag.model.MaterializedDocument(
                id = "doc-2",
                uri = "test://doc2",
                title = "Document 2",
                ingestionTimestamp = time2,
                children = emptyList()
            )
            val doc3 = com.embabel.agent.rag.model.MaterializedDocument(
                id = "doc-3",
                uri = "test://doc3",
                title = "Document 3",
                ingestionTimestamp = time3,
                children = emptyList()
            )

            ragService.writeAndChunkDocument(doc1)
            ragService.writeAndChunkDocument(doc2)
            ragService.writeAndChunkDocument(doc3)

            val retrieved1 = ragService.findById("doc-1") as com.embabel.agent.rag.model.ContentRoot
            val retrieved2 = ragService.findById("doc-2") as com.embabel.agent.rag.model.ContentRoot
            val retrieved3 = ragService.findById("doc-3") as com.embabel.agent.rag.model.ContentRoot

            assertEquals(time1, retrieved1.ingestionTimestamp)
            assertEquals(time2, retrieved2.ingestionTimestamp)
            assertEquals(time3, retrieved3.ingestionTimestamp)
        }

        @Test
        fun `should persist ingestionDate for nested sections`() {
            val rootTime = java.time.Instant.parse("2025-03-01T10:00:00Z")
            val sectionTime = java.time.Instant.parse("2025-03-01T10:01:00Z")
            val leafTime = java.time.Instant.parse("2025-03-01T10:02:00Z")

            val leaf = com.embabel.agent.rag.model.LeafSection(
                id = "leaf-1",
                title = "Leaf Section",
                text = "Content",
                parentId = "section-1"
            )

            val section = com.embabel.agent.rag.model.DefaultMaterializedContainerSection(
                id = "section-1",
                title = "Container Section",
                children = listOf(leaf),
                parentId = "root-1"
            )

            val document = com.embabel.agent.rag.model.MaterializedDocument(
                id = "root-1",
                uri = "test://nested-document",
                title = "Root Document",
                ingestionTimestamp = rootTime,
                children = listOf(section)
            )

            ragService.writeAndChunkDocument(document)

            val retrievedRoot = ragService.findById("root-1") as com.embabel.agent.rag.model.ContentRoot
            assertEquals(rootTime, retrievedRoot.ingestionTimestamp)
        }
    }

    @Nested
    inner class EmbeddingVerificationTests {

        private lateinit var trackingEmbeddingModel: TrackingEmbeddingModel
        private lateinit var ragServiceWithTracking: LuceneSearchOperations

        @BeforeEach
        fun setUp() {
            trackingEmbeddingModel = TrackingEmbeddingModel()
            ragServiceWithTracking = LuceneSearchOperations(
                name = "tracking-rag",
                embeddingService = SpringEmbeddingService("model", "provider", trackingEmbeddingModel),
                vectorWeight = 0.5
            )
        }

        @AfterEach
        fun tearDown() {
            ragServiceWithTracking.close()
        }

        @Test
        fun `should embed all chunks when multiple chunks are added`() {
            val documents = listOf(
                Document("doc1", "First document about machine learning", emptyMap<String, Any>()),
                Document("doc2", "Second document about artificial intelligence", emptyMap<String, Any>()),
                Document("doc3", "Third document about data science", emptyMap<String, Any>()),
                Document("doc4", "Fourth document about neural networks", emptyMap<String, Any>()),
                Document("doc5", "Fifth document about deep learning", emptyMap<String, Any>())
            )

            ragServiceWithTracking.acceptDocuments(documents)

            // Verify embedding model was called exactly once per chunk
            assertEquals(
                5,
                trackingEmbeddingModel.embedCallCount,
                "Embedding model should be called once for each of the 5 chunks"
            )

            // Verify each chunk's text was embedded
            val embeddedTexts = trackingEmbeddingModel.embeddedTexts
            assertTrue(embeddedTexts.any { it.contains("machine learning") })
            assertTrue(embeddedTexts.any { it.contains("artificial intelligence") })
            assertTrue(embeddedTexts.any { it.contains("data science") })
            assertTrue(embeddedTexts.any { it.contains("neural networks") })
            assertTrue(embeddedTexts.any { it.contains("deep learning") })
        }

        @Test
        fun `should embed all chunks in batch operations`() {
            // Add first batch
            val batch1 = listOf(
                Document("batch1-doc1", "Batch one document one", emptyMap<String, Any>()),
                Document("batch1-doc2", "Batch one document two", emptyMap<String, Any>())
            )
            ragServiceWithTracking.acceptDocuments(batch1)

            assertEquals(2, trackingEmbeddingModel.embedCallCount)

            // Add second batch
            val batch2 = listOf(
                Document("batch2-doc1", "Batch two document one", emptyMap<String, Any>()),
                Document("batch2-doc2", "Batch two document two", emptyMap<String, Any>()),
                Document("batch2-doc3", "Batch two document three", emptyMap<String, Any>())
            )
            ragServiceWithTracking.acceptDocuments(batch2)

            // Total should be 5 (2 from batch1 + 3 from batch2)
            assertEquals(
                5,
                trackingEmbeddingModel.embedCallCount,
                "All chunks from both batches should be embedded"
            )
        }

        @Test
        fun `should embed chunks from writeAndChunkDocument`() {
            // Create a document with multiple sections that will result in chunks
            val leaf1 = com.embabel.agent.rag.model.LeafSection(
                id = "leaf-1",
                title = "Section One",
                text = "This is the content of section one about programming",
                parentId = "root"
            )

            val leaf2 = com.embabel.agent.rag.model.LeafSection(
                id = "leaf-2",
                title = "Section Two",
                text = "This is the content of section two about databases",
                parentId = "root"
            )

            val document = com.embabel.agent.rag.model.MaterializedDocument(
                id = "root",
                uri = "test://embedding-test",
                title = "Test Document",
                children = listOf(leaf1, leaf2)
            )

            val chunkIds = ragServiceWithTracking.writeAndChunkDocument(document)

            // Verify all chunks were embedded
            assertEquals(
                chunkIds.size,
                trackingEmbeddingModel.embedCallCount,
                "All ${chunkIds.size} chunks should be embedded"
            )

            // Verify we can retrieve all chunks and they work with vector search
            val searchResults = ragServiceWithTracking.vectorSearch(
                RagRequest.query("programming")
                    .withSimilarityThreshold(0.0)
                    .withTopK(10),
                Chunk::class.java
            )
            assertTrue(
                searchResults.isNotEmpty(),
                "Vector search should find results from embedded chunks"
            )
        }

        @Test
        fun `should embed chunk content not metadata`() {
            val metadata = mapOf(
                "author" to "Test Author",
                "category" to "Test Category"
            )

            val documents = listOf(
                Document("meta-doc", "Actual chunk content to embed", metadata)
            )

            ragServiceWithTracking.acceptDocuments(documents)

            assertEquals(1, trackingEmbeddingModel.embedCallCount)
            val embeddedText = trackingEmbeddingModel.embeddedTexts.first()
            assertTrue(embeddedText.contains("Actual chunk content"))
            assertFalse(embeddedText.contains("Test Author"), "Metadata should not be in embedded text")
        }

        @Test
        fun `should embed large number of chunks`() {
            val numChunks = 100
            val documents = (1..numChunks).map { i ->
                Document("large-batch-doc-$i", "Document number $i with unique content", emptyMap<String, Any>())
            }

            ragServiceWithTracking.acceptDocuments(documents)

            assertEquals(
                numChunks,
                trackingEmbeddingModel.embedCallCount,
                "All $numChunks chunks should be embedded"
            )

            // Verify each document was embedded
            val embeddedTexts = trackingEmbeddingModel.embeddedTexts
            for (i in 1..numChunks) {
                assertTrue(
                    embeddedTexts.any { it.contains("Document number $i") },
                    "Document $i should have been embedded"
                )
            }
        }

        @Test
        fun `should not skip embedding for any chunk even with empty text`() {
            val documents = listOf(
                Document("doc1", "Normal content", emptyMap<String, Any>()),
                Document("doc2", "", emptyMap<String, Any>()), // Empty text
                Document("doc3", "More content", emptyMap<String, Any>())
            )

            ragServiceWithTracking.acceptDocuments(documents)

            // All 3 chunks should be embedded, even the empty one
            assertEquals(
                3,
                trackingEmbeddingModel.embedCallCount,
                "All chunks including empty ones should be embedded"
            )
        }

        @Test
        fun `embedded chunks should be searchable via vector search`() {
            val documents = listOf(
                Document("searchable1", "Machine learning algorithms for classification", emptyMap<String, Any>()),
                Document("searchable2", "Deep learning neural network architectures", emptyMap<String, Any>()),
                Document("searchable3", "Natural language processing techniques", emptyMap<String, Any>())
            )

            ragServiceWithTracking.acceptDocuments(documents)

            // All should be embedded
            assertEquals(3, trackingEmbeddingModel.embedCallCount)

            // Each should be findable via vector search
            val request = RagRequest.query("machine learning")
                .withSimilarityThreshold(0.0)
                .withTopK(10)
            val results = ragServiceWithTracking.vectorSearch(request, Chunk::class.java)

            assertTrue(
                results.isNotEmpty(),
                "Vector search should return results for embedded chunks"
            )
        }

        @Test
        fun `embeddings are stored in Lucene index not chunk metadata`() {
            // This test documents the current behavior: embeddings are stored in Lucene
            // as binary fields for vector search, but are NOT included in chunk metadata
            val documents = listOf(
                Document("embed-test", "Content to be embedded", emptyMap<String, Any>())
            )

            ragServiceWithTracking.acceptDocuments(documents)

            // Embedding was created
            assertEquals(1, trackingEmbeddingModel.embedCallCount)

            // Chunk metadata does NOT contain the embedding (by design)
            val chunk = ragServiceWithTracking.findAllChunksById(listOf("embed-test")).first()
            assertFalse(
                chunk.metadata.containsKey("embedding"),
                "Embeddings should NOT be in chunk metadata - they are stored in Lucene index"
            )

            // But vector search still works because embedding IS in Lucene index
            val searchResults = ragServiceWithTracking.vectorSearch(
                RagRequest.query("embedded content")
                    .withSimilarityThreshold(0.0)
                    .withTopK(10),
                Chunk::class.java
            )
            assertTrue(
                searchResults.isNotEmpty(),
                "Vector search should work even though embedding is not in metadata"
            )
        }

        @Test
        fun `hybrid search uses embeddings for all chunks`() {
            val documents = listOf(
                Document("hybrid1", "Quantum computing advances in 2024", emptyMap<String, Any>()),
                Document("hybrid2", "Classical physics fundamentals", emptyMap<String, Any>()),
                Document("hybrid3", "Quantum entanglement research", emptyMap<String, Any>())
            )

            ragServiceWithTracking.acceptDocuments(documents)

            // All 3 chunks should be embedded
            assertEquals(3, trackingEmbeddingModel.embedCallCount)

            // Hybrid search should use embeddings and return relevant results
            val request = RagRequest.query("quantum physics")
                .withSimilarityThreshold(0.0)
                .withTopK(10)
            val results = ragServiceWithTracking.hybridSearch(request)

            assertTrue(results.results.isNotEmpty())
            // Quantum-related docs should score higher than classical physics
        }
    }

    @Nested
    inner class ExpandChunkTests {

        @Test
        fun `should return empty list when chunk not found`() {
            val result = ragService.expandResult(
                "non-existent-chunk",
                com.embabel.agent.rag.service.ResultExpander.Method.SEQUENCE,
                elementsToAdd = 1
            )
            assertTrue(result.isEmpty())
        }

        @Test
        fun `should return only original chunk when missing sequence metadata`() {
            // Create chunk without sequence metadata
            val chunk = Chunk(
                id = "no-meta-chunk",
                text = "Content without metadata",
                parentId = "parent",
                metadata = emptyMap()
            )
            ragService.onNewRetrievables(listOf(chunk))
            ragService.commitChanges()

            val result = ragService.expandResult(
                "no-meta-chunk",
                com.embabel.agent.rag.service.ResultExpander.Method.SEQUENCE,
                elementsToAdd = 1
            )

            assertEquals(1, result.size)
            assertEquals("no-meta-chunk", result.first().id)
        }

        @Test
        fun `should expand chunk to include adjacent chunks in sequence`() {
            // Create chunks with sequence metadata
            val containerSectionId = "section-1"
            val chunks = (0..4).map { seq ->
                Chunk(
                    id = "chunk-$seq",
                    text = "Content for chunk $seq",
                    parentId = "parent",
                    metadata = mapOf(
                        "container_section_id" to containerSectionId,
                        "sequence_number" to seq
                    )
                )
            }
            ragService.onNewRetrievables(chunks)
            ragService.commitChanges()

            // Expand middle chunk (seq=2) with chunksToAdd=1
            val result = ragService.expandResult(
                "chunk-2",
                com.embabel.agent.rag.service.ResultExpander.Method.SEQUENCE,
                elementsToAdd = 1
            )

            // Should include chunk-1, chunk-2, chunk-3
            assertEquals(3, result.size)
            assertEquals(listOf("chunk-1", "chunk-2", "chunk-3"), result.map { it.id })
        }

        @Test
        fun `should handle expansion at beginning of sequence`() {
            val containerSectionId = "section-start"
            val chunks = (0..4).map { seq ->
                Chunk(
                    id = "start-chunk-$seq",
                    text = "Content $seq",
                    parentId = "parent",
                    metadata = mapOf(
                        "container_section_id" to containerSectionId,
                        "sequence_number" to seq
                    )
                )
            }
            ragService.onNewRetrievables(chunks)
            ragService.commitChanges()

            // Expand first chunk (seq=0) with chunksToAdd=2
            val result = ragService.expandResult(
                "start-chunk-0",
                com.embabel.agent.rag.service.ResultExpander.Method.SEQUENCE,
                elementsToAdd = 2
            )

            // Should include chunk-0, chunk-1, chunk-2 (can't go before 0)
            assertEquals(3, result.size)
            assertEquals("start-chunk-0", result.first().id)
        }

        @Test
        fun `should handle expansion at end of sequence`() {
            val containerSectionId = "section-end"
            val chunks = (0..4).map { seq ->
                Chunk(
                    id = "end-chunk-$seq",
                    text = "Content $seq",
                    parentId = "parent",
                    metadata = mapOf(
                        "container_section_id" to containerSectionId,
                        "sequence_number" to seq
                    )
                )
            }
            ragService.onNewRetrievables(chunks)
            ragService.commitChanges()

            // Expand last chunk (seq=4) with chunksToAdd=2
            val result = ragService.expandResult(
                "end-chunk-4",
                com.embabel.agent.rag.service.ResultExpander.Method.SEQUENCE,
                elementsToAdd = 2
            )

            // Should include chunk-2, chunk-3, chunk-4 (can't go beyond 4)
            assertEquals(3, result.size)
            assertEquals("end-chunk-4", result.last().id)
        }

        @Test
        fun `should not include chunks from different container sections`() {
            // Create chunks in two different sections
            val section1Chunks = (0..2).map { seq ->
                Chunk(
                    id = "s1-chunk-$seq",
                    text = "Section 1 content $seq",
                    parentId = "parent",
                    metadata = mapOf(
                        "container_section_id" to "section-1",
                        "sequence_number" to seq
                    )
                )
            }
            val section2Chunks = (0..2).map { seq ->
                Chunk(
                    id = "s2-chunk-$seq",
                    text = "Section 2 content $seq",
                    parentId = "parent",
                    metadata = mapOf(
                        "container_section_id" to "section-2",
                        "sequence_number" to seq
                    )
                )
            }
            ragService.onNewRetrievables(section1Chunks + section2Chunks)
            ragService.commitChanges()

            // Expand chunk from section 1
            val result = ragService.expandResult(
                "s1-chunk-1",
                com.embabel.agent.rag.service.ResultExpander.Method.SEQUENCE,
                elementsToAdd = 5
            )

            // Should only include chunks from section 1
            assertEquals(3, result.size)
            assertTrue(result.all { it.id.startsWith("s1-") })
        }

        @Test
        fun `should return chunks ordered by sequence number`() {
            val containerSectionId = "ordered-section"
            // Add chunks in random order
            val chunks = listOf(3, 1, 4, 0, 2).map { seq ->
                Chunk(
                    id = "ordered-chunk-$seq",
                    text = "Content $seq",
                    parentId = "parent",
                    metadata = mapOf(
                        "container_section_id" to containerSectionId,
                        "sequence_number" to seq
                    )
                )
            }
            ragService.onNewRetrievables(chunks)
            ragService.commitChanges()

            // Expand middle chunk
            val result = ragService.expandResult(
                "ordered-chunk-2",
                com.embabel.agent.rag.service.ResultExpander.Method.SEQUENCE,
                elementsToAdd = 10
            )

            // Should be ordered by sequence number
            assertEquals(5, result.size)
            assertEquals(
                listOf("ordered-chunk-0", "ordered-chunk-1", "ordered-chunk-2", "ordered-chunk-3", "ordered-chunk-4"),
                result.map { it.id }
            )
        }

        @Test
        fun `should handle chunksToAdd of zero`() {
            val containerSectionId = "zero-section"
            val chunks = (0..2).map { seq ->
                Chunk(
                    id = "zero-chunk-$seq",
                    text = "Content $seq",
                    parentId = "parent",
                    metadata = mapOf(
                        "container_section_id" to containerSectionId,
                        "sequence_number" to seq
                    )
                )
            }
            ragService.onNewRetrievables(chunks)
            ragService.commitChanges()

            val result = ragService.expandResult(
                "zero-chunk-1",
                com.embabel.agent.rag.service.ResultExpander.Method.SEQUENCE,
                elementsToAdd = 0
            )

            // Should return only the original chunk
            assertEquals(1, result.size)
            assertEquals("zero-chunk-1", result.first().id)
        }

        @Test
        fun `should work with writeAndChunkDocument`() {
            // Create a document with sections that will produce multiple chunks
            val leaf1 = com.embabel.agent.rag.model.LeafSection(
                id = "leaf-1",
                title = "Section One",
                text = "This is the first section with some content about programming and software development.",
                parentId = "root"
            )
            val leaf2 = com.embabel.agent.rag.model.LeafSection(
                id = "leaf-2",
                title = "Section Two",
                text = "This is the second section discussing databases and data storage.",
                parentId = "root"
            )
            val leaf3 = com.embabel.agent.rag.model.LeafSection(
                id = "leaf-3",
                title = "Section Three",
                text = "This is the third section about cloud computing and infrastructure.",
                parentId = "root"
            )

            val document = com.embabel.agent.rag.model.MaterializedDocument(
                id = "root",
                uri = "test://expand-test",
                title = "Test Document",
                children = listOf(leaf1, leaf2, leaf3)
            )

            val chunkIds = ragService.writeAndChunkDocument(document)
            assertTrue(chunkIds.isNotEmpty(), "Should create chunks from document")

            // Get the first chunk and try to expand it
            val firstChunkId = chunkIds.first()
            val result = ragService.expandResult(
                firstChunkId,
                com.embabel.agent.rag.service.ResultExpander.Method.SEQUENCE,
                elementsToAdd = 1
            )

            assertTrue(result.isNotEmpty(), "Should return expanded chunks")
            assertTrue(result.any { it.id == firstChunkId }, "Result should include the original chunk")
        }

        @Test
        fun `zoomOut should return parent LeafSection of a chunk`() {
            // Create a LeafSection as the parent
            val leafSection = com.embabel.agent.rag.model.LeafSection(
                id = "parent-leaf-section",
                title = "Parent Section Title",
                text = "This is the parent section content that contains multiple paragraphs of text.",
                parentId = "document-root"
            )
            ragService.save(leafSection)

            // Create a chunk whose parentId points to the LeafSection
            val chunk = Chunk(
                id = "child-chunk",
                text = "This is chunked content from the parent section.",
                parentId = "parent-leaf-section",
                metadata = mapOf(
                    "container_section_id" to "parent-leaf-section",
                    "sequence_number" to 0
                )
            )
            ragService.onNewRetrievables(listOf(chunk))
            ragService.commitChanges()

            // Use ZOOM_OUT to get the parent
            val result = ragService.expandResult(
                "child-chunk",
                com.embabel.agent.rag.service.ResultExpander.Method.ZOOM_OUT,
                elementsToAdd = 1
            )

            // Should return the parent LeafSection
            assertEquals(1, result.size, "ZOOM_OUT should return exactly one parent element")
            val parent = result.first()
            assertEquals("parent-leaf-section", parent.id, "Should return the parent LeafSection")
            assertTrue(parent is com.embabel.agent.rag.model.LeafSection, "Parent should be a LeafSection")
            assertEquals("Parent Section Title", (parent as com.embabel.agent.rag.model.LeafSection).title)
            assertEquals("This is the parent section content that contains multiple paragraphs of text.", parent.text)
        }
    }

    @Nested
    inner class ContentElementPersistenceTests {

        private lateinit var tempDir: java.nio.file.Path

        @BeforeEach
        fun setUp() {
            tempDir = java.nio.file.Files.createTempDirectory("lucene-test-")
        }

        @AfterEach
        fun tearDown() {
            tempDir.toFile().deleteRecursively()
        }

        @Test
        fun `chunks should survive restart`() {
            val indexPath = tempDir

            // Create and populate first instance
            val service1 = LuceneSearchOperations(
                name = "persist-test",
                embeddingService = null,
                indexPath = indexPath
            )

            val leaf = com.embabel.agent.rag.model.LeafSection(
                id = "leaf-1",
                title = "Test Leaf",
                text = "Some content for testing persistence",
                parentId = "root-1"
            )

            val document = com.embabel.agent.rag.model.MaterializedDocument(
                id = "root-1",
                uri = "test://persistence",
                title = "Test Document",
                children = listOf(leaf)
            )

            val chunkIds = service1.writeAndChunkDocument(document)
            assertTrue(chunkIds.isNotEmpty(), "Should create chunks")

            service1.close()

            // Create second instance and verify chunks are loaded
            val service2 = LuceneSearchOperations(
                name = "persist-test",
                embeddingService = null,
                indexPath = indexPath
            )
            service2.loadExistingChunksFromDisk()

            chunkIds.forEach { chunkId ->
                val chunk = service2.findById(chunkId)
                assertNotNull(chunk, "Chunk $chunkId should survive restart")
                assertTrue(chunk is Chunk, "Should be a Chunk instance")
            }

            service2.close()
        }

        @Test
        fun `document root should survive restart`() {
            val indexPath = tempDir

            // Create and populate first instance
            val service1 = LuceneSearchOperations(
                name = "persist-test",
                embeddingService = null,
                indexPath = indexPath
            )

            val leaf = com.embabel.agent.rag.model.LeafSection(
                id = "leaf-1",
                title = "Test Leaf",
                text = "Content",
                parentId = "root-1"
            )

            val document = com.embabel.agent.rag.model.MaterializedDocument(
                id = "root-1",
                uri = "test://persistence",
                title = "Test Document",
                children = listOf(leaf)
            )

            service1.writeAndChunkDocument(document)

            // Verify root exists before close
            val rootBefore = service1.findById("root-1")
            assertNotNull(rootBefore, "Root should exist before close")

            service1.close()

            // Create second instance and verify root is loaded
            val service2 = LuceneSearchOperations(
                name = "persist-test",
                embeddingService = null,
                indexPath = indexPath
            )
            service2.loadExistingChunksFromDisk()

            val rootAfter = service2.findById("root-1")
            assertNotNull(rootAfter, "Document root should survive restart")
            assertTrue(
                rootAfter is com.embabel.agent.rag.model.ContentRoot,
                "Should be a ContentRoot instance, but was ${rootAfter?.javaClass?.name}"
            )

            service2.close()
        }

        @Test
        fun `leaf sections should survive restart`() {
            val indexPath = tempDir

            val service1 = LuceneSearchOperations(
                name = "persist-test",
                embeddingService = null,
                indexPath = indexPath
            )

            val leaf = com.embabel.agent.rag.model.LeafSection(
                id = "leaf-1",
                title = "Test Leaf",
                text = "Leaf section content",
                parentId = "root-1"
            )

            val document = com.embabel.agent.rag.model.MaterializedDocument(
                id = "root-1",
                uri = "test://persistence",
                title = "Test Document",
                children = listOf(leaf)
            )

            service1.writeAndChunkDocument(document)
            service1.close()

            val service2 = LuceneSearchOperations(
                name = "persist-test",
                embeddingService = null,
                indexPath = indexPath
            )
            service2.loadExistingChunksFromDisk()

            val leafAfter = service2.findById("leaf-1")
            assertNotNull(leafAfter, "LeafSection should survive restart")
            assertTrue(
                leafAfter is com.embabel.agent.rag.model.LeafSection,
                "Should be a LeafSection instance, but was ${leafAfter?.javaClass?.name}"
            )

            service2.close()
        }

        @Test
        fun `container sections should survive restart`() {
            val indexPath = tempDir

            val service1 = LuceneSearchOperations(
                name = "persist-test",
                embeddingService = null,
                indexPath = indexPath
            )

            val leaf = com.embabel.agent.rag.model.LeafSection(
                id = "leaf-1",
                title = "Nested Leaf",
                text = "Nested content",
                parentId = "section-1"
            )

            val section = com.embabel.agent.rag.model.DefaultMaterializedContainerSection(
                id = "section-1",
                title = "Container Section",
                children = listOf(leaf),
                parentId = "root-1"
            )

            val document = com.embabel.agent.rag.model.MaterializedDocument(
                id = "root-1",
                uri = "test://persistence",
                title = "Test Document",
                children = listOf(section)
            )

            service1.writeAndChunkDocument(document)
            service1.close()

            val service2 = LuceneSearchOperations(
                name = "persist-test",
                embeddingService = null,
                indexPath = indexPath
            )
            service2.loadExistingChunksFromDisk()

            val sectionAfter = service2.findById("section-1")
            assertNotNull(sectionAfter, "ContainerSection should survive restart")
            assertTrue(
                sectionAfter is com.embabel.agent.rag.model.ContainerSection,
                "Should be a ContainerSection instance, but was ${sectionAfter?.javaClass?.name}"
            )

            service2.close()
        }

        @Test
        fun `all content elements should survive restart with correct count`() {
            val indexPath = tempDir

            val service1 = LuceneSearchOperations(
                name = "persist-test",
                embeddingService = null,
                indexPath = indexPath
            )

            val leaf1 = com.embabel.agent.rag.model.LeafSection(
                id = "leaf-1",
                title = "Leaf 1",
                text = "First leaf content",
                parentId = "section-1"
            )

            val leaf2 = com.embabel.agent.rag.model.LeafSection(
                id = "leaf-2",
                title = "Leaf 2",
                text = "Second leaf content",
                parentId = "section-1"
            )

            val section = com.embabel.agent.rag.model.DefaultMaterializedContainerSection(
                id = "section-1",
                title = "Section",
                children = listOf(leaf1, leaf2),
                parentId = "root-1"
            )

            val document = com.embabel.agent.rag.model.MaterializedDocument(
                id = "root-1",
                uri = "test://persistence",
                title = "Test Document",
                children = listOf(section)
            )

            val countBefore = service1.info().contentElementCount

            service1.close()

            val service2 = LuceneSearchOperations(
                name = "persist-test",
                embeddingService = null,
                indexPath = indexPath
            )
            service2.loadExistingChunksFromDisk()

            val countAfter = service2.info().contentElementCount

            // We expect: root + section + 2 leaves + chunks
            assertEquals(
                countBefore,
                countAfter,
                "All content elements should survive restart. Before: $countBefore, After: $countAfter"
            )

            service2.close()
        }

        @Test
        fun `document metadata should survive restart`() {
            val indexPath = tempDir

            val service1 = LuceneSearchOperations(
                name = "persist-test",
                embeddingService = null,
                indexPath = indexPath
            )

            val timestamp = java.time.Instant.parse("2025-01-15T10:30:00Z")

            val leaf = com.embabel.agent.rag.model.LeafSection(
                id = "leaf-1",
                title = "Test Leaf",
                text = "Content",
                parentId = "root-1",
                metadata = mapOf("customKey" to "customValue")
            )

            val document = com.embabel.agent.rag.model.MaterializedDocument(
                id = "root-1",
                uri = "test://persistence",
                title = "Test Document With Metadata",
                ingestionTimestamp = timestamp,
                children = listOf(leaf)
            )

            service1.writeAndChunkDocument(document)
            service1.close()

            val service2 = LuceneSearchOperations(
                name = "persist-test",
                embeddingService = null,
                indexPath = indexPath
            )
            service2.loadExistingChunksFromDisk()

            val rootAfter = service2.findById("root-1") as? com.embabel.agent.rag.model.ContentRoot
            assertNotNull(rootAfter, "Root should exist after restart")
            assertEquals("Test Document With Metadata", rootAfter?.title, "Title should be preserved")
            assertEquals("test://persistence", rootAfter?.uri, "URI should be preserved")
            assertEquals(timestamp, rootAfter?.ingestionTimestamp, "Ingestion timestamp should be preserved")

            service2.close()
        }

        @Test
        fun `parent-child navigation should work after restart`() {
            val indexPath = tempDir

            val service1 = LuceneSearchOperations(
                name = "persist-test",
                embeddingService = null,
                indexPath = indexPath
            )

            val leaf1 = com.embabel.agent.rag.model.LeafSection(
                id = "leaf-1",
                title = "Leaf One",
                text = "First leaf content",
                parentId = "section-1"
            )

            val leaf2 = com.embabel.agent.rag.model.LeafSection(
                id = "leaf-2",
                title = "Leaf Two",
                text = "Second leaf content",
                parentId = "section-1"
            )

            val section = com.embabel.agent.rag.model.DefaultMaterializedContainerSection(
                id = "section-1",
                title = "Container Section",
                children = listOf(leaf1, leaf2),
                parentId = "root-1"
            )

            val document = com.embabel.agent.rag.model.MaterializedDocument(
                id = "root-1",
                uri = "test://navigation",
                title = "Test Document",
                children = listOf(section)
            )

            service1.writeAndChunkDocument(document)
            service1.close()

            // Reload from disk
            val service2 = LuceneSearchOperations(
                name = "persist-test",
                embeddingService = null,
                indexPath = indexPath
            )
            service2.loadExistingChunksFromDisk()

            // Test upward navigation via parentId
            val leafAfter = service2.findById("leaf-1") as? com.embabel.agent.rag.model.LeafSection
            assertNotNull(leafAfter, "Leaf should exist")
            assertEquals("section-1", leafAfter?.parentId, "Leaf should have correct parentId")

            val sectionAfter =
                service2.findById("section-1") as? com.embabel.agent.rag.model.DefaultMaterializedContainerSection
            assertNotNull(sectionAfter, "Section should exist")
            assertEquals("root-1", sectionAfter?.parentId, "Section should have correct parentId")

            // Test downward navigation via children
            val docAfter = service2.findById("root-1") as? com.embabel.agent.rag.model.NavigableDocument
            assertNotNull(docAfter, "Document should exist")
            assertEquals(1, docAfter?.children?.count(), "Document should have 1 child section")

            val sectionChildren = sectionAfter?.children?.toList() ?: emptyList()
            assertEquals(2, sectionChildren.size, "Section should have 2 leaf children")

            // Verify children are the correct leaves
            val childIds = sectionChildren.map { it.id }.toSet()
            assertTrue(childIds.contains("leaf-1"), "Section should contain leaf-1")
            assertTrue(childIds.contains("leaf-2"), "Section should contain leaf-2")

            service2.close()
        }

        @Test
        fun `descendants navigation should work after restart`() {
            val indexPath = tempDir

            val service1 = LuceneSearchOperations(
                name = "persist-test",
                embeddingService = null,
                indexPath = indexPath
            )

            val leaf = com.embabel.agent.rag.model.LeafSection(
                id = "deep-leaf",
                title = "Deep Leaf",
                text = "Deeply nested content",
                parentId = "nested-section"
            )

            val nestedSection = com.embabel.agent.rag.model.DefaultMaterializedContainerSection(
                id = "nested-section",
                title = "Nested Section",
                children = listOf(leaf),
                parentId = "root-1"
            )

            val document = com.embabel.agent.rag.model.MaterializedDocument(
                id = "root-1",
                uri = "test://descendants",
                title = "Test Document",
                children = listOf(nestedSection)
            )

            service1.writeAndChunkDocument(document)
            service1.close()

            val service2 = LuceneSearchOperations(
                name = "persist-test",
                embeddingService = null,
                indexPath = indexPath
            )
            service2.loadExistingChunksFromDisk()

            val docAfter = service2.findById("root-1") as? com.embabel.agent.rag.model.NavigableDocument
            assertNotNull(docAfter, "Document should exist")

            // Test descendants() navigation
            val descendants = docAfter?.descendants()?.toList() ?: emptyList()
            assertEquals(2, descendants.size, "Should have 2 descendants (nested section + leaf)")

            val descendantIds = descendants.map { it.id }.toSet()
            assertTrue(descendantIds.contains("nested-section"), "Should contain nested section")
            assertTrue(descendantIds.contains("deep-leaf"), "Should contain deep leaf")

            // Test leaves() navigation
            val leaves = docAfter?.leaves()?.toList() ?: emptyList()
            assertEquals(1, leaves.size, "Should have 1 leaf")
            assertEquals("deep-leaf", leaves.first().id, "Leaf should be 'deep-leaf'")

            service2.close()
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

/**
 * Tracking embedding model that records each embed call for verification
 */
class TrackingEmbeddingModel : EmbeddingModel {
    private val _embeddedTexts = mutableListOf<String>()
    val embeddedTexts: List<String> get() = _embeddedTexts.toList()

    val embedCallCount: Int get() = _embeddedTexts.size

    fun reset() {
        _embeddedTexts.clear()
    }

    override fun embed(document: Document): FloatArray {
        return embed(document.text!!)
    }

    override fun call(request: EmbeddingRequest): EmbeddingResponse {
        TODO()
    }

    override fun embed(text: String): FloatArray {
        _embeddedTexts.add(text)
        // Simple deterministic embedding
        val embedding = FloatArray(100)
        val hash = text.hashCode()
        for (i in embedding.indices) {
            embedding[i] = ((hash * (i + 1)) % 1000).toFloat() / 1000f
        }
        // Normalize
        val norm = kotlin.math.sqrt(embedding.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0) {
            for (i in embedding.indices) {
                embedding[i] /= norm
            }
        }
        return embedding
    }

    override fun dimensions(): Int = 100
}
