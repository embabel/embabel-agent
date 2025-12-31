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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document

/**
 * Tests for core search operations (textSearch, vectorSearch) in LuceneSearchOperations.
 */
class LuceneCoreSearchOperationsTest {

    private lateinit var ragService: LuceneSearchOperations
    private lateinit var ragServiceWithEmbedding: LuceneSearchOperations

    @BeforeEach
    fun setUp() {
        ragService = createTestRagService()
        ragServiceWithEmbedding = createTestRagServiceWithEmbedding()
    }

    @AfterEach
    fun tearDown() {
        ragService.close()
        ragServiceWithEmbedding.close()
    }

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
