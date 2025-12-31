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

import com.embabel.agent.rag.service.RagRequest
import com.embabel.common.ai.model.SpringEmbeddingService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document

/**
 * Basic search operation tests for LuceneSearchOperations.
 *
 * For more specific test coverage, see:
 * - [LuceneChunkRepositoryTest] - Chunk storage and retrieval
 * - [LuceneKeywordSearchTest] - Keyword-based search
 * - [LuceneDeleteDocumentTest] - Document deletion
 * - [LuceneExistsRootWithUriTest] - URI existence checks
 * - [LuceneConcurrencyTest] - Concurrent operations
 * - [LuceneCoreSearchTest] - Text and vector search
 * - [LuceneIngestionDateTest] - Ingestion date persistence
 * - [LuceneEmbeddingVerificationTest] - Embedding functionality
 * - [LuceneExpandChunkTest] - Chunk expansion
 * - [LuceneContentElementPersistenceTest] - Content element persistence
 */
class LuceneSearchOperationsTest : LuceneSearchOperationsTestBase() {

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
}
