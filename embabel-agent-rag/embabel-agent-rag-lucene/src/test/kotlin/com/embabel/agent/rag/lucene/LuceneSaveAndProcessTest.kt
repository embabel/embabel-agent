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

/**
 * Tests for saveAndProcess functionality in LuceneSearchOperations.
 */
class LuceneSaveAndProcessTest {

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
    fun `saveAndProcess should store chunk and make it searchable via text search`() {
        val chunk = Chunk(
            id = "test-chunk-1",
            text = "Machine learning algorithms for data science applications",
            parentId = "test-chunk-1",
            metadata = mapOf("source" to "test")
        )

        val result = ragService.saveAndProcess(chunk)

        assertEquals(chunk.id, result.id)
        assertEquals(chunk.text, result.text)

        ragService.commitChanges()

        val foundChunks = ragService.findAllChunksById(listOf("test-chunk-1"))
        assertEquals(1, foundChunks.size)
        assertEquals("Machine learning algorithms for data science applications", foundChunks.first().text)

        val request = RagRequest.query("machine learning")
            .withSimilarityThreshold(0.0)
        val response = ragService.hybridSearch(request)

        assertTrue(response.results.isNotEmpty(), "Should find chunk via text search")
        assertEquals("test-chunk-1", response.results.first().match.id)
    }

    @Test
    fun `saveAndProcess should generate embeddings and enable vector search`() {
        val chunk = Chunk(
            id = "vector-chunk-1",
            text = "Neural networks and deep learning for image recognition",
            parentId = "vector-chunk-1",
            metadata = emptyMap()
        )

        ragServiceWithEmbedding.saveAndProcess(chunk)
        ragServiceWithEmbedding.commitChanges()

        val request = RagRequest.query("deep learning neural networks")
            .withSimilarityThreshold(0.0)
        val response = ragServiceWithEmbedding.hybridSearch(request)

        assertTrue(response.results.isNotEmpty(), "Should find chunk via hybrid search with embeddings")
        assertEquals("vector-chunk-1", response.results.first().match.id)
        assertTrue(response.results.first().score > 0.0, "Score should be positive")
    }

    @Test
    fun `saveAndProcess should handle multiple chunks sequentially`() {
        val chunks = listOf(
            Chunk(
                id = "seq-chunk-1",
                text = "First chunk about artificial intelligence",
                parentId = "seq-chunk-1",
                metadata = mapOf("order" to 1)
            ),
            Chunk(
                id = "seq-chunk-2",
                text = "Second chunk about machine learning models",
                parentId = "seq-chunk-2",
                metadata = mapOf("order" to 2)
            ),
            Chunk(
                id = "seq-chunk-3",
                text = "Third chunk about data science pipelines",
                parentId = "seq-chunk-3",
                metadata = mapOf("order" to 3)
            )
        )

        chunks.forEach { chunk ->
            ragService.saveAndProcess(chunk)
        }
        ragService.commitChanges()

        val allChunks = ragService.findAll()
        assertEquals(3, allChunks.size)

        val request = RagRequest.query("chunk")
            .withSimilarityThreshold(0.0)
        val response = ragService.hybridSearch(request)

        assertEquals(3, response.results.size, "Should find all 3 chunks")
    }

    @Test
    fun `saveAndProcess should preserve chunk metadata`() {
        val metadata = mapOf(
            "author" to "Jane Smith",
            "category" to "Technology",
            "tags" to listOf("AI", "ML", "Data"),
            "priority" to 5
        )

        val chunk = Chunk(
            id = "metadata-chunk",
            text = "Content with rich metadata",
            parentId = "metadata-chunk",
            metadata = metadata
        )

        ragService.saveAndProcess(chunk)
        ragService.commitChanges()

        val foundChunks = ragService.findAllChunksById(listOf("metadata-chunk"))
        assertEquals(1, foundChunks.size)

        val foundChunk = foundChunks.first()
        assertEquals("Jane Smith", foundChunk.metadata["author"])
        assertEquals("Technology", foundChunk.metadata["category"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf("AI", "ML", "Data"), foundChunk.metadata["tags"] as List<String>)
        assertEquals(5, foundChunk.metadata["priority"])
    }

    @Test
    fun `saveAndProcess should update existing chunk with same ID`() {
        val originalChunk = Chunk(
            id = "update-chunk",
            text = "Original content",
            parentId = "update-chunk",
            metadata = mapOf("version" to 1)
        )

        ragService.saveAndProcess(originalChunk)
        ragService.commitChanges()

        var foundChunks = ragService.findAllChunksById(listOf("update-chunk"))
        assertEquals("Original content", foundChunks.first().text)

        val updatedChunk = Chunk(
            id = "update-chunk",
            text = "Updated content with new information",
            parentId = "update-chunk",
            metadata = mapOf("version" to 2)
        )

        ragService.saveAndProcess(updatedChunk)
        ragService.commitChanges()

        val allChunks = ragService.findAll()
        assertEquals(1, allChunks.size)

        foundChunks = ragService.findAllChunksById(listOf("update-chunk"))
        assertEquals("Updated content with new information", foundChunks.first().text)
        assertEquals(2, foundChunks.first().metadata["version"])
    }

    @Test
    fun `saveAndProcess should work with empty text`() {
        val chunk = Chunk(
            id = "empty-text-chunk",
            text = "",
            parentId = "empty-text-chunk",
            metadata = emptyMap()
        )

        ragService.saveAndProcess(chunk)
        ragService.commitChanges()

        val foundChunks = ragService.findAllChunksById(listOf("empty-text-chunk"))
        assertEquals(1, foundChunks.size)
        assertEquals("", foundChunks.first().text)
    }

    @Test
    fun `saveAndProcess should update statistics correctly`() {
        assertEquals(0, ragService.info().chunkCount)

        val chunk1 = Chunk(
            id = "stats-chunk-1",
            text = "First chunk",
            parentId = "stats-chunk-1",
            metadata = emptyMap()
        )

        ragService.saveAndProcess(chunk1)
        ragService.commitChanges()

        assertEquals(1, ragService.info().chunkCount)

        val chunk2 = Chunk(
            id = "stats-chunk-2",
            text = "Second chunk with more text",
            parentId = "stats-chunk-2",
            metadata = emptyMap()
        )

        ragService.saveAndProcess(chunk2)
        ragService.commitChanges()

        assertEquals(2, ragService.info().chunkCount)

        val avgLength = ragService.info().averageChunkLength
        assertTrue(avgLength > 0, "Average chunk length should be positive")
    }

    @Test
    fun `saveAndProcess with embeddings should enable hybrid search scoring`() {
        val chunks = listOf(
            Chunk(
                id = "hybrid-chunk-1",
                text = "Machine learning for predictive analytics",
                parentId = "hybrid-chunk-1",
                metadata = emptyMap()
            ),
            Chunk(
                id = "hybrid-chunk-2",
                text = "Cooking recipes for Italian cuisine",
                parentId = "hybrid-chunk-2",
                metadata = emptyMap()
            ),
            Chunk(
                id = "hybrid-chunk-3",
                text = "Deep learning and neural network architectures",
                parentId = "hybrid-chunk-3",
                metadata = emptyMap()
            )
        )

        chunks.forEach { chunk ->
            ragServiceWithEmbedding.saveAndProcess(chunk)
        }
        ragServiceWithEmbedding.commitChanges()

        val request = RagRequest.query("machine learning neural networks")
            .withSimilarityThreshold(0.0)
        val response = ragServiceWithEmbedding.hybridSearch(request)

        assertTrue(response.results.isNotEmpty())

        val mlChunks = response.results.filter {
            it.match.id == "hybrid-chunk-1" || it.match.id == "hybrid-chunk-3"
        }
        val cookingChunk = response.results.find { it.match.id == "hybrid-chunk-2" }

        if (cookingChunk != null && mlChunks.isNotEmpty()) {
            val maxMlScore = mlChunks.maxOf { it.score }
            assertTrue(
                maxMlScore > cookingChunk.score,
                "ML chunks should score higher than cooking chunk"
            )
        }
    }

    @Test
    fun `saveAndProcess should make chunk findable by findById`() {
        val chunk = Chunk(
            id = "findable-chunk",
            text = "This chunk should be findable by ID",
            parentId = "findable-chunk",
            metadata = emptyMap()
        )

        ragService.saveAndProcess(chunk)
        ragService.commitChanges()

        val found = ragService.findById("findable-chunk")
        assertNotNull(found, "Chunk should be findable by ID")
        assertTrue(found is Chunk, "Found element should be a Chunk")
        assertEquals("This chunk should be findable by ID", (found as Chunk).text)
    }

    @Test
    fun `saveAndProcess should work without embedding service for text-only search`() {
        assertFalse(ragService.info().hasEmbeddings)

        val chunk = Chunk(
            id = "text-only-chunk",
            text = "Artificial intelligence and natural language processing",
            parentId = "text-only-chunk",
            metadata = emptyMap()
        )

        ragService.saveAndProcess(chunk)
        ragService.commitChanges()

        val request = RagRequest.query("artificial intelligence")
            .withSimilarityThreshold(0.0)
        val response = ragService.hybridSearch(request)

        assertTrue(response.results.isNotEmpty(), "Text-only search should work")
        assertEquals("text-only-chunk", response.results.first().match.id)
    }
}
