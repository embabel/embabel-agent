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
package com.embabel.agent.rag.store

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.ContentElement
import com.embabel.agent.rag.model.LeafSection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*

/**
 * Tests for the default pathFromRoot method in ContentElementRepository.
 */
class ContentElementRepositoryPathFromRootTest {

    /**
     * Simple in-memory implementation for testing the default pathFromRoot method.
     */
    private class TestContentElementRepository : ContentElementRepository {
        private val elements = mutableMapOf<String, ContentElement>()

        override val name: String = "test-repository"

        override fun <C : ContentElement> findAll(clazz: Class<C>): Iterable<C> {
            TODO("Not yet implemented")
        }

        override fun findAllChunksById(chunkIds: List<String>): Iterable<Chunk> =
            chunkIds.mapNotNull { elements[it] as? Chunk }

        override fun findById(id: String): ContentElement? = elements[id]

        override fun save(element: ContentElement): ContentElement {
            elements[element.id] = element
            return element
        }

        override fun info(): ContentElementRepositoryInfo = object : ContentElementRepositoryInfo {
            override val chunkCount: Int = elements.values.count { it is Chunk }
            override val documentCount: Int = 0
            override val contentElementCount: Int = elements.size
            override val hasEmbeddings: Boolean = false
            override val isPersistent: Boolean = false
        }

        override fun findChunksForEntity(entityId: String): List<Chunk> =
            elements.values.filterIsInstance<Chunk>()
    }

    @Test
    fun `pathFromRoot returns single element list for root element with no parent`() {
        val repository = TestContentElementRepository()

        val rootId = UUID.randomUUID().toString()
        val root = LeafSection(
            id = rootId,
            title = "Root",
            text = "Root content",
            parentId = null
        )
        repository.save(root)

        val path = repository.pathFromRoot(root)

        assertNotNull(path)
        assertEquals(listOf(rootId), path)
    }

    @Test
    fun `pathFromRoot returns correct path for element with single parent`() {
        val repository = TestContentElementRepository()

        val rootId = UUID.randomUUID().toString()
        val childId = UUID.randomUUID().toString()

        val root = LeafSection(
            id = rootId,
            title = "Root",
            text = "Root content",
            parentId = null
        )
        val child = LeafSection(
            id = childId,
            title = "Child",
            text = "Child content",
            parentId = rootId
        )

        repository.save(root)
        repository.save(child)

        val path = repository.pathFromRoot(child)

        assertNotNull(path)
        assertEquals(listOf(rootId, childId), path)
    }

    @Test
    fun `pathFromRoot returns correct path for deeply nested element`() {
        val repository = TestContentElementRepository()

        val rootId = UUID.randomUUID().toString()
        val level1Id = UUID.randomUUID().toString()
        val level2Id = UUID.randomUUID().toString()
        val level3Id = UUID.randomUUID().toString()

        val root = LeafSection(id = rootId, title = "Root", text = "Root", parentId = null)
        val level1 = LeafSection(id = level1Id, title = "Level 1", text = "L1", parentId = rootId)
        val level2 = LeafSection(id = level2Id, title = "Level 2", text = "L2", parentId = level1Id)
        val level3 = LeafSection(id = level3Id, title = "Level 3", text = "L3", parentId = level2Id)

        repository.save(root)
        repository.save(level1)
        repository.save(level2)
        repository.save(level3)

        val path = repository.pathFromRoot(level3)

        assertNotNull(path)
        assertEquals(listOf(rootId, level1Id, level2Id, level3Id), path)
    }

    @Test
    fun `pathFromRoot returns null when parent is not found in repository`() {
        val repository = TestContentElementRepository()

        val missingParentId = UUID.randomUUID().toString()
        val childId = UUID.randomUUID().toString()

        val child = LeafSection(
            id = childId,
            title = "Orphan",
            text = "Orphan content",
            parentId = missingParentId
        )
        repository.save(child)

        val path = repository.pathFromRoot(child)

        assertNull(path, "Path should be null when parent cannot be found")
    }

    @Test
    fun `pathFromRoot returns null when parent chain is broken in the middle`() {
        val repository = TestContentElementRepository()

        val rootId = UUID.randomUUID().toString()
        val level1Id = UUID.randomUUID().toString()
        val level2Id = UUID.randomUUID().toString()

        // Root is NOT saved - simulating broken chain
        val level1 = LeafSection(id = level1Id, title = "Level 1", text = "L1", parentId = rootId)
        val level2 = LeafSection(id = level2Id, title = "Level 2", text = "L2", parentId = level1Id)

        repository.save(level1)
        repository.save(level2)

        val path = repository.pathFromRoot(level2)

        assertNull(path, "Path should be null when parent chain is broken")
    }

    @Test
    fun `pathFromRoot returns path starting with root id and ending with element id`() {
        val repository = TestContentElementRepository()

        val rootId = UUID.randomUUID().toString()
        val middleId = UUID.randomUUID().toString()
        val leafId = UUID.randomUUID().toString()

        val root = LeafSection(id = rootId, title = "Root", text = "Root", parentId = null)
        val middle = LeafSection(id = middleId, title = "Middle", text = "Middle", parentId = rootId)
        val leaf = LeafSection(id = leafId, title = "Leaf", text = "Leaf", parentId = middleId)

        repository.save(root)
        repository.save(middle)
        repository.save(leaf)

        val path = repository.pathFromRoot(leaf)

        assertNotNull(path)
        assertEquals(rootId, path!!.first(), "First element should be root ID")
        assertEquals(leafId, path.last(), "Last element should be the element's own ID")
    }

    @Test
    fun `pathFromRoot works with Chunk elements`() {
        val repository = TestContentElementRepository()

        val rootId = UUID.randomUUID().toString()
        val sectionId = UUID.randomUUID().toString()
        val chunkId = UUID.randomUUID().toString()

        val root = LeafSection(id = rootId, title = "Root", text = "Root", parentId = null)
        val section = LeafSection(id = sectionId, title = "Section", text = "Section", parentId = rootId)
        val chunk = Chunk(
            id = chunkId,
            text = "Chunk content",
            metadata = mapOf("root_document_id" to rootId),
            parentId = sectionId
        )

        repository.save(root)
        repository.save(section)
        repository.save(chunk)

        val path = repository.pathFromRoot(chunk)

        assertNotNull(path)
        assertEquals(listOf(rootId, sectionId, chunkId), path)
    }

    @Test
    fun `pathFromRoot returns correct path when parent is non-hierarchical content element`() {
        val repository = TestContentElementRepository()

        val rootId = UUID.randomUUID().toString()
        val childId = UUID.randomUUID().toString()

        // Use a simple non-hierarchical content element as parent
        val nonHierarchicalParent = object : ContentElement {
            override val id: String = rootId
            override val uri: String? = null
            override val metadata: Map<String, Any?> = emptyMap()
        }

        val child = LeafSection(
            id = childId,
            title = "Child",
            text = "Child content",
            parentId = rootId
        )

        repository.save(nonHierarchicalParent)
        repository.save(child)

        val path = repository.pathFromRoot(child)

        // Should return null because parent is not a HierarchicalContentElement
        assertNull(path, "Path should be null when parent is not HierarchicalContentElement")
    }
}
