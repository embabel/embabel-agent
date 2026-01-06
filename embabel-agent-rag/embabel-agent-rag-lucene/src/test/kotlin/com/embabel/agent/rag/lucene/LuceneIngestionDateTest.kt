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

import com.embabel.agent.rag.model.ContentRoot
import com.embabel.agent.rag.model.DefaultMaterializedContainerSection
import com.embabel.agent.rag.model.LeafSection
import com.embabel.agent.rag.model.MaterializedDocument
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Tests for ingestion date persistence in LuceneSearchOperations.
 */
class LuceneIngestionDateTest : LuceneSearchOperationsTestBase() {

    @Test
    fun `should persist and retrieve ingestionDate for MaterializedDocument`() {
        val testTime = Instant.parse("2025-01-15T10:30:00Z")
        val document = MaterializedDocument(
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
        assertTrue(retrieved is ContentRoot)

        val contentRoot = retrieved as ContentRoot
        assertEquals(testTime, contentRoot.ingestionTimestamp)
    }

    @Test
    fun `should persist ingestionDate in propertiesToPersist`() {
        val testTime = Instant.parse("2025-02-20T15:45:30Z")
        val document = MaterializedDocument(
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
        val beforeCreation = Instant.now()
        val document = MaterializedDocument(
            id = "test-doc-3",
            uri = "test://document-default-date",
            title = "Default Date Test",
            children = emptyList()
        )
        val afterCreation = Instant.now()

        ragService.writeAndChunkDocument(document)

        val retrieved = ragService.findById("test-doc-3") as? ContentRoot
        assertNotNull(retrieved)

        // Should be between before and after creation
        assertTrue(
            retrieved!!.ingestionTimestamp >= beforeCreation && retrieved.ingestionTimestamp <= afterCreation,
            "Expected ingestionDate to be between $beforeCreation and $afterCreation but was ${retrieved.ingestionTimestamp}"
        )
    }

    @Test
    fun `should preserve different ingestionDates for multiple documents`() {
        val time1 = Instant.parse("2025-01-01T00:00:00Z")
        val time2 = Instant.parse("2025-06-15T12:00:00Z")
        val time3 = Instant.parse("2025-12-31T23:59:59Z")

        val doc1 = MaterializedDocument(
            id = "doc-1",
            uri = "test://doc1",
            title = "Document 1",
            ingestionTimestamp = time1,
            children = emptyList()
        )
        val doc2 = MaterializedDocument(
            id = "doc-2",
            uri = "test://doc2",
            title = "Document 2",
            ingestionTimestamp = time2,
            children = emptyList()
        )
        val doc3 = MaterializedDocument(
            id = "doc-3",
            uri = "test://doc3",
            title = "Document 3",
            ingestionTimestamp = time3,
            children = emptyList()
        )

        ragService.writeAndChunkDocument(doc1)
        ragService.writeAndChunkDocument(doc2)
        ragService.writeAndChunkDocument(doc3)

        val retrieved1 = ragService.findById("doc-1") as ContentRoot
        val retrieved2 = ragService.findById("doc-2") as ContentRoot
        val retrieved3 = ragService.findById("doc-3") as ContentRoot

        assertEquals(time1, retrieved1.ingestionTimestamp)
        assertEquals(time2, retrieved2.ingestionTimestamp)
        assertEquals(time3, retrieved3.ingestionTimestamp)
    }

    @Test
    fun `should persist ingestionDate for nested sections`() {
        val rootTime = Instant.parse("2025-03-01T10:00:00Z")

        val leaf = LeafSection(
            id = "leaf-1",
            title = "Leaf Section",
            text = "Content",
            parentId = "section-1"
        )

        val section = DefaultMaterializedContainerSection(
            id = "section-1",
            title = "Container Section",
            children = listOf(leaf),
            parentId = "root-1"
        )

        val document = MaterializedDocument(
            id = "root-1",
            uri = "test://nested-document",
            title = "Root Document",
            ingestionTimestamp = rootTime,
            children = listOf(section)
        )

        ragService.writeAndChunkDocument(document)

        val retrievedRoot = ragService.findById("root-1") as ContentRoot
        assertEquals(rootTime, retrievedRoot.ingestionTimestamp)
    }
}
