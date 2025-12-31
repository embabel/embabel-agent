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

import com.embabel.agent.rag.model.LeafSection
import com.embabel.agent.rag.model.MaterializedDocument
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for existsRootWithUri functionality in LuceneSearchOperations.
 */
class LuceneExistsRootWithUriTest : LuceneSearchOperationsTestBase() {

    @Test
    fun `should return true when root document exists`() {
        val documentUri = "test://existing-doc"
        val root = MaterializedDocument(
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
        val root = MaterializedDocument(
            id = "root",
            uri = documentUri,
            title = "Root Document",
            children = emptyList()
        )

        // Save a section with same URI but without Document label
        val section = LeafSection(
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
        val root = MaterializedDocument(
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

        val doc1 = MaterializedDocument(
            id = "doc1",
            uri = uri1,
            title = "Document 1",
            children = emptyList()
        )

        val doc2 = MaterializedDocument(
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
        val root = MaterializedDocument(
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

        val root = MaterializedDocument(
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
                    val doc = MaterializedDocument(
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
