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
import com.embabel.agent.rag.model.ContainerSection
import com.embabel.agent.rag.model.ContentRoot
import com.embabel.agent.rag.model.DefaultMaterializedContainerSection
import com.embabel.agent.rag.model.LeafSection
import com.embabel.agent.rag.model.MaterializedDocument
import com.embabel.agent.rag.model.NavigableDocument
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Tests for content element persistence and restart recovery in LuceneSearchOperations.
 */
class LuceneContentElementPersistenceTest {

    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("lucene-test-")
    }

    @AfterEach
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `chunks should survive restart`() {
        val indexPath = tempDir

        val service1 = LuceneSearchOperations(
            name = "persist-test",
            embeddingService = null,
            indexPath = indexPath
        )

        val leaf = LeafSection(
            id = "leaf-1",
            title = "Test Leaf",
            text = "Some content for testing persistence",
            parentId = "root-1"
        )

        val document = MaterializedDocument(
            id = "root-1",
            uri = "test://persistence",
            title = "Test Document",
            children = listOf(leaf)
        )

        val chunkIds = service1.writeAndChunkDocument(document)
        assertTrue(chunkIds.isNotEmpty(), "Should create chunks")

        service1.close()

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

        val service1 = LuceneSearchOperations(
            name = "persist-test",
            embeddingService = null,
            indexPath = indexPath
        )

        val leaf = LeafSection(
            id = "leaf-1",
            title = "Test Leaf",
            text = "Content",
            parentId = "root-1"
        )

        val document = MaterializedDocument(
            id = "root-1",
            uri = "test://persistence",
            title = "Test Document",
            children = listOf(leaf)
        )

        service1.writeAndChunkDocument(document)

        val rootBefore = service1.findById("root-1")
        assertNotNull(rootBefore, "Root should exist before close")

        service1.close()

        val service2 = LuceneSearchOperations(
            name = "persist-test",
            embeddingService = null,
            indexPath = indexPath
        )
        service2.loadExistingChunksFromDisk()

        val rootAfter = service2.findById("root-1")
        assertNotNull(rootAfter, "Document root should survive restart")
        assertTrue(
            rootAfter is ContentRoot,
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

        val leaf = LeafSection(
            id = "leaf-1",
            title = "Test Leaf",
            text = "Leaf section content",
            parentId = "root-1"
        )

        val document = MaterializedDocument(
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
            leafAfter is LeafSection,
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

        val leaf = LeafSection(
            id = "leaf-1",
            title = "Nested Leaf",
            text = "Nested content",
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
            sectionAfter is ContainerSection,
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

        val leaf1 = LeafSection(
            id = "leaf-1",
            title = "Leaf 1",
            text = "First leaf content",
            parentId = "section-1"
        )

        val leaf2 = LeafSection(
            id = "leaf-2",
            title = "Leaf 2",
            text = "Second leaf content",
            parentId = "section-1"
        )

        val section = DefaultMaterializedContainerSection(
            id = "section-1",
            title = "Section",
            children = listOf(leaf1, leaf2),
            parentId = "root-1"
        )

        val document = MaterializedDocument(
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

        val timestamp = Instant.parse("2025-01-15T10:30:00Z")

        val leaf = LeafSection(
            id = "leaf-1",
            title = "Test Leaf",
            text = "Content",
            parentId = "root-1",
            metadata = mapOf("customKey" to "customValue")
        )

        val document = MaterializedDocument(
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

        val rootAfter = service2.findById("root-1") as? ContentRoot
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

        val leaf1 = LeafSection(
            id = "leaf-1",
            title = "Leaf One",
            text = "First leaf content",
            parentId = "section-1"
        )

        val leaf2 = LeafSection(
            id = "leaf-2",
            title = "Leaf Two",
            text = "Second leaf content",
            parentId = "section-1"
        )

        val section = DefaultMaterializedContainerSection(
            id = "section-1",
            title = "Container Section",
            children = listOf(leaf1, leaf2),
            parentId = "root-1"
        )

        val document = MaterializedDocument(
            id = "root-1",
            uri = "test://navigation",
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

        val leafAfter = service2.findById("leaf-1") as? LeafSection
        assertNotNull(leafAfter, "Leaf should exist")
        assertEquals("section-1", leafAfter?.parentId, "Leaf should have correct parentId")

        val sectionAfter = service2.findById("section-1") as? DefaultMaterializedContainerSection
        assertNotNull(sectionAfter, "Section should exist")
        assertEquals("root-1", sectionAfter?.parentId, "Section should have correct parentId")

        val docAfter = service2.findById("root-1") as? NavigableDocument
        assertNotNull(docAfter, "Document should exist")
        assertEquals(1, docAfter?.children?.count(), "Document should have 1 child section")

        val sectionChildren = sectionAfter?.children?.toList() ?: emptyList()
        assertEquals(2, sectionChildren.size, "Section should have 2 leaf children")

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

        val leaf = LeafSection(
            id = "deep-leaf",
            title = "Deep Leaf",
            text = "Deeply nested content",
            parentId = "nested-section"
        )

        val nestedSection = DefaultMaterializedContainerSection(
            id = "nested-section",
            title = "Nested Section",
            children = listOf(leaf),
            parentId = "root-1"
        )

        val document = MaterializedDocument(
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

        val docAfter = service2.findById("root-1") as? NavigableDocument
        assertNotNull(docAfter, "Document should exist")

        val descendants = docAfter?.descendants()?.toList() ?: emptyList()
        assertEquals(2, descendants.size, "Should have 2 descendants (nested section + leaf)")

        val descendantIds = descendants.map { it.id }.toSet()
        assertTrue(descendantIds.contains("nested-section"), "Should contain nested section")
        assertTrue(descendantIds.contains("deep-leaf"), "Should contain deep leaf")

        val leaves = docAfter?.leaves()?.toList() ?: emptyList()
        assertEquals(1, leaves.size, "Should have 1 leaf")
        assertEquals("deep-leaf", leaves.first().id, "Leaf should be 'deep-leaf'")

        service2.close()
    }
}
