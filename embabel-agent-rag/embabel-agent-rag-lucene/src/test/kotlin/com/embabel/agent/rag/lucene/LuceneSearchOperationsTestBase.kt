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
package com.embabel.agent.rag.lucene

import com.embabel.common.ai.model.SpringAiEmbeddingService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import kotlin.reflect.full.functions
import kotlin.reflect.jvm.isAccessible

/**
 * Base class for LuceneSearchOperations tests providing common setup and helper methods.
 */
abstract class LuceneSearchOperationsTestBase {

    protected lateinit var ragService: LuceneSearchOperations
    protected lateinit var ragServiceWithEmbedding: LuceneSearchOperations
    protected val mockEmbeddingModel = MockEmbeddingModel()

    @BeforeEach
    open fun setUp() {
        ragService = LuceneSearchOperations(name = "lucene-rag")
        ragServiceWithEmbedding = LuceneSearchOperations(
            name = "hybrid-lucene-rag",
            embeddingService = SpringAiEmbeddingService("name", "provider", mockEmbeddingModel),
            vectorWeight = 0.5
        )
    }

    @AfterEach
    open fun tearDown() {
        ragService.close()
        ragServiceWithEmbedding.close()
    }
}

/**
 * Helper method to convert Spring AI Documents to Chunks and add them to the service
 */
fun LuceneSearchOperations.acceptDocuments(documents: List<Document>) {
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
fun LuceneSearchOperations.commitChanges() {
    val commitMethod = this::class.functions.find { it.name == "commit" }
    commitMethod?.let {
        it.isAccessible = true
        it.call(this)
    }
}

/**
 * Mock embedding model for testing
 */
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

    override fun embed(texts: MutableList<String>): MutableList<FloatArray> {
        return texts.map { embed(it) }.toMutableList()
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
        val words = text.lowercase().split(" ")
        val embedding = FloatArray(100)

        words.forEach { word ->
            val hash = word.hashCode()
            for (i in embedding.indices) {
                embedding[i] += (hash * (i + 1)).toFloat() / 1000000f
            }
        }

        val norm = kotlin.math.sqrt(embedding.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0) {
            for (i in embedding.indices) {
                embedding[i] /= norm
            }
        } else {
            // For empty text, return a valid unit vector (Lucene COSINE requires non-zero vectors)
            embedding[0] = 1.0f
        }

        return embedding
    }

    override fun embed(texts: MutableList<String>): MutableList<FloatArray> {
        return texts.map { embed(it) }.toMutableList()
    }

    override fun dimensions(): Int = 100
}
