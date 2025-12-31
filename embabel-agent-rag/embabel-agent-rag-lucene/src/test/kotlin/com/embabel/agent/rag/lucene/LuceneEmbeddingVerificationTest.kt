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
import com.embabel.agent.rag.model.LeafSection
import com.embabel.agent.rag.model.MaterializedDocument
import com.embabel.agent.rag.service.RagRequest
import com.embabel.common.ai.model.SpringEmbeddingService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document

/**
 * Tests for embedding verification functionality in LuceneSearchOperations.
 */
class LuceneEmbeddingVerificationTest {

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

        assertEquals(
            5,
            trackingEmbeddingModel.embedCallCount,
            "Embedding model should be called once for each of the 5 chunks"
        )

        val embeddedTexts = trackingEmbeddingModel.embeddedTexts
        assertTrue(embeddedTexts.any { it.contains("machine learning") })
        assertTrue(embeddedTexts.any { it.contains("artificial intelligence") })
        assertTrue(embeddedTexts.any { it.contains("data science") })
        assertTrue(embeddedTexts.any { it.contains("neural networks") })
        assertTrue(embeddedTexts.any { it.contains("deep learning") })
    }

    @Test
    fun `should embed all chunks in batch operations`() {
        val batch1 = listOf(
            Document("batch1-doc1", "Batch one document one", emptyMap<String, Any>()),
            Document("batch1-doc2", "Batch one document two", emptyMap<String, Any>())
        )
        ragServiceWithTracking.acceptDocuments(batch1)

        assertEquals(2, trackingEmbeddingModel.embedCallCount)

        val batch2 = listOf(
            Document("batch2-doc1", "Batch two document one", emptyMap<String, Any>()),
            Document("batch2-doc2", "Batch two document two", emptyMap<String, Any>()),
            Document("batch2-doc3", "Batch two document three", emptyMap<String, Any>())
        )
        ragServiceWithTracking.acceptDocuments(batch2)

        assertEquals(
            5,
            trackingEmbeddingModel.embedCallCount,
            "All chunks from both batches should be embedded"
        )
    }

    @Test
    fun `should embed chunks from writeAndChunkDocument`() {
        val leaf1 = LeafSection(
            id = "leaf-1",
            title = "Section One",
            text = "This is the content of section one about programming",
            parentId = "root"
        )

        val leaf2 = LeafSection(
            id = "leaf-2",
            title = "Section Two",
            text = "This is the content of section two about databases",
            parentId = "root"
        )

        val document = MaterializedDocument(
            id = "root",
            uri = "test://embedding-test",
            title = "Test Document",
            children = listOf(leaf1, leaf2)
        )

        val chunkIds = ragServiceWithTracking.writeAndChunkDocument(document)

        assertEquals(
            chunkIds.size,
            trackingEmbeddingModel.embedCallCount,
            "All ${chunkIds.size} chunks should be embedded"
        )

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
            Document("doc2", "", emptyMap<String, Any>()),
            Document("doc3", "More content", emptyMap<String, Any>())
        )

        ragServiceWithTracking.acceptDocuments(documents)

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

        assertEquals(3, trackingEmbeddingModel.embedCallCount)

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
        val documents = listOf(
            Document("embed-test", "Content to be embedded", emptyMap<String, Any>())
        )

        ragServiceWithTracking.acceptDocuments(documents)

        assertEquals(1, trackingEmbeddingModel.embedCallCount)

        val chunk = ragServiceWithTracking.findAllChunksById(listOf("embed-test")).first()
        assertFalse(
            chunk.metadata.containsKey("embedding"),
            "Embeddings should NOT be in chunk metadata - they are stored in Lucene index"
        )

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

        assertEquals(3, trackingEmbeddingModel.embedCallCount)

        val request = RagRequest.query("quantum physics")
            .withSimilarityThreshold(0.0)
            .withTopK(10)
        val results = ragServiceWithTracking.hybridSearch(request)

        assertTrue(results.results.isNotEmpty())
    }
}
