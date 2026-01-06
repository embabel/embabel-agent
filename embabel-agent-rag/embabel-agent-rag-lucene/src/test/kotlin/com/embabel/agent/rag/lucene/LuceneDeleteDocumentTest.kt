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

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.DefaultMaterializedContainerSection
import com.embabel.agent.rag.model.LeafSection
import com.embabel.agent.rag.model.MaterializedDocument
import com.embabel.agent.rag.service.RagRequest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for document deletion functionality in LuceneSearchOperations.
 */
class LuceneDeleteDocumentTest : LuceneSearchOperationsTestBase() {

    @Test
    fun `should delete document root and all descendants by URI`() {
        // Create a document structure
        val documentUri = "test://doc1"
        val root = MaterializedDocument(
            id = "doc1",
            uri = documentUri,
            title = "Test Document",
            children = listOf()
        )

        val section = LeafSection(
            id = "section1",
            uri = documentUri,
            title = "Section 1",
            text = "Section content",
            parentId = "doc1"
        )

        val chunk1 = Chunk(
            id = "chunk1",
            text = "Chunk 1 content",
            parentId = "section1",
            metadata = emptyMap()
        )

        val chunk2 = Chunk(
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
        val doc1 = MaterializedDocument(
            id = "doc1",
            uri = doc1Uri,
            title = "Document 1",
            children = emptyList()
        )

        val chunk1 = Chunk(
            id = "chunk1",
            text = "Chunk from doc1",
            parentId = "doc1",
            metadata = emptyMap()
        )

        val doc2Uri = "test://doc2"
        val doc2 = MaterializedDocument(
            id = "doc2",
            uri = doc2Uri,
            title = "Document 2",
            children = emptyList()
        )

        val chunk2 = Chunk(
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
        val root = MaterializedDocument(
            id = "root",
            uri = documentUri,
            title = "Root",
            children = emptyList()
        )

        val section1 = DefaultMaterializedContainerSection(
            id = "section1",
            uri = documentUri,
            title = "Section 1",
            children = emptyList(),
            parentId = "root"
        )

        val section2 = DefaultMaterializedContainerSection(
            id = "section2",
            uri = documentUri,
            title = "Section 2",
            children = emptyList(),
            parentId = "section1"
        )

        val leaf = LeafSection(
            id = "leaf",
            uri = documentUri,
            title = "Leaf",
            text = "Leaf content",
            parentId = "section2"
        )

        val chunk = Chunk(
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
        val root = MaterializedDocument(
            id = "root",
            uri = documentUri,
            title = "Searchable Document",
            children = emptyList()
        )

        val chunk = Chunk(
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
        val root = MaterializedDocument(
            id = "root",
            uri = documentUri,
            title = "Mixed Document",
            children = emptyList()
        )

        val section = DefaultMaterializedContainerSection(
            id = "section",
            uri = documentUri,
            title = "Section",
            children = emptyList(),
            parentId = "root"
        )

        val leaf = LeafSection(
            id = "leaf",
            uri = documentUri,
            title = "Leaf",
            text = "Leaf text",
            parentId = "section"
        )

        val chunks = (1..5).map { i ->
            Chunk(
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
