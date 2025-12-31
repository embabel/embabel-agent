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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document

/**
 * Tests for chunk repository functionality in LuceneSearchOperations.
 */
class LuceneChunkRepositoryTest : LuceneSearchOperationsTestBase() {

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
