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
package com.embabel.agent.rag.ingestion

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.DefaultMaterializedContainerSection
import com.embabel.agent.rag.model.LeafSection
import com.embabel.agent.rag.model.MaterializedDocument
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*

/**
 * Tests to verify that all chunks created by ContentChunker have non-null pathFromRoot.
 */
class ContentChunkerPathFromRootTest {

    private val chunker = ContentChunker()

    @Test
    fun `test chunks from single leaf section have non-null pathFromRoot`() {
        val rootId = UUID.randomUUID().toString()
        val leafId = UUID.randomUUID().toString()

        val document = MaterializedDocument(
            id = rootId,
            uri = "test://doc",
            title = "Test Document",
            children = listOf(
                LeafSection(
                    id = leafId,
                    uri = "test://doc",
                    title = "Section 1",
                    text = "Content of section 1",
                    parentId = rootId,
                    metadata = mapOf(
                        "root_document_id" to rootId,
                        "container_section_id" to rootId,
                        "leaf_section_id" to leafId
                    )
                )
            )
        )

        val chunks = chunker.chunk(document)

        assertTrue(chunks.isNotEmpty(), "Should create at least one chunk")

        chunks.forEach { chunk ->
            val pathFromRoot = chunk.pathFromRoot
            assertNotNull(pathFromRoot, "Chunk ${chunk.id} should have non-null pathFromRoot")
            assertTrue(pathFromRoot!!.isNotEmpty(), "pathFromRoot should not be empty")
            assertEquals(rootId, pathFromRoot.first(), "First element should be root ID")
            assertEquals(chunk.id, pathFromRoot.last(), "Last element should be chunk ID")
        }
    }

    @Test
    fun `test chunks from multiple leaf sections have non-null pathFromRoot`() {
        val rootId = UUID.randomUUID().toString()
        val leaf1Id = UUID.randomUUID().toString()
        val leaf2Id = UUID.randomUUID().toString()

        val document = MaterializedDocument(
            id = rootId,
            uri = "test://doc",
            title = "Test Document",
            children = listOf(
                LeafSection(
                    id = leaf1Id,
                    uri = "test://doc",
                    title = "Section 1",
                    text = "Content of section 1",
                    parentId = rootId,
                    metadata = mapOf(
                        "root_document_id" to rootId,
                        "container_section_id" to rootId,
                        "leaf_section_id" to leaf1Id
                    )
                ),
                LeafSection(
                    id = leaf2Id,
                    uri = "test://doc",
                    title = "Section 2",
                    text = "Content of section 2",
                    parentId = rootId,
                    metadata = mapOf(
                        "root_document_id" to rootId,
                        "container_section_id" to rootId,
                        "leaf_section_id" to leaf2Id
                    )
                )
            )
        )

        val chunks = chunker.chunk(document)

        assertTrue(chunks.isNotEmpty(), "Should create at least one chunk")

        chunks.forEach { chunk ->
            val pathFromRoot = chunk.pathFromRoot
            assertNotNull(pathFromRoot, "Chunk ${chunk.id} should have non-null pathFromRoot")
            assertTrue(pathFromRoot!!.size >= 2, "pathFromRoot should have at least root and chunk ID")
            assertEquals(rootId, pathFromRoot.first(), "First element should be root ID")
        }
    }

    @Test
    fun `test chunks from large leaf section that gets split have non-null pathFromRoot`() {
        val rootId = UUID.randomUUID().toString()
        val leafId = UUID.randomUUID().toString()

        // Create a large leaf that will be split into multiple chunks
        val largeText = "This is a test paragraph. ".repeat(100)

        val document = MaterializedDocument(
            id = rootId,
            uri = "test://doc",
            title = "Test Document",
            children = listOf(
                LeafSection(
                    id = leafId,
                    uri = "test://doc",
                    title = "Large Section",
                    text = largeText,
                    parentId = rootId,
                    metadata = mapOf(
                        "root_document_id" to rootId,
                        "container_section_id" to rootId,
                        "leaf_section_id" to leafId
                    )
                )
            )
        )

        val chunks = chunker.chunk(document)

        assertTrue(chunks.size > 1, "Large content should be split into multiple chunks")

        chunks.forEachIndexed { index, chunk ->
            val pathFromRoot = chunk.pathFromRoot
            assertNotNull(pathFromRoot, "Chunk $index (${chunk.id}) should have non-null pathFromRoot")
            assertTrue(pathFromRoot!!.isNotEmpty(), "pathFromRoot should not be empty")
            assertEquals(rootId, pathFromRoot.first(), "First element should be root ID")
            assertTrue(
                pathFromRoot.contains(leafId),
                "pathFromRoot should contain the leaf section ID"
            )
        }
    }

    @Test
    fun `test chunks from nested container sections have non-null pathFromRoot`() {
        val rootId = UUID.randomUUID().toString()
        val containerId = UUID.randomUUID().toString()
        val leafId = UUID.randomUUID().toString()

        val containerSection = DefaultMaterializedContainerSection(
            id = containerId,
            uri = "test://doc",
            title = "Container Section",
            parentId = rootId,
            children = listOf(
                LeafSection(
                    id = leafId,
                    uri = "test://doc",
                    title = "Nested Leaf",
                    text = "Content of nested leaf",
                    parentId = containerId,
                    metadata = mapOf(
                        "root_document_id" to rootId,
                        "container_section_id" to containerId,
                        "leaf_section_id" to leafId
                    )
                )
            ),
            metadata = mapOf(
                "root_document_id" to rootId
            )
        )

        val chunks = chunker.chunk(containerSection)

        assertTrue(chunks.isNotEmpty(), "Should create at least one chunk")

        chunks.forEach { chunk ->
            val pathFromRoot = chunk.pathFromRoot
            assertNotNull(pathFromRoot, "Chunk ${chunk.id} should have non-null pathFromRoot")
            assertTrue(pathFromRoot!!.size >= 3, "pathFromRoot should include root, container, and chunk")
            assertEquals(rootId, pathFromRoot.first(), "First element should be root ID")
            assertTrue(
                pathFromRoot.contains(containerId),
                "pathFromRoot should contain the container section ID"
            )
        }
    }

    @Test
    fun `test all chunks maintain proper hierarchy in pathFromRoot`() {
        val rootId = UUID.randomUUID().toString()
        val container1Id = UUID.randomUUID().toString()
        val container2Id = UUID.randomUUID().toString()
        val leaf1Id = UUID.randomUUID().toString()
        val leaf2Id = UUID.randomUUID().toString()

        val document = MaterializedDocument(
            id = rootId,
            uri = "test://doc",
            title = "Root Document",
            children = listOf(
                DefaultMaterializedContainerSection(
                    id = container1Id,
                    uri = "test://doc",
                    title = "Container 1",
                    parentId = rootId,
                    children = listOf(
                        LeafSection(
                            id = leaf1Id,
                            uri = "test://doc",
                            title = "Leaf 1",
                            text = "Content 1",
                            parentId = container1Id,
                            metadata = mapOf(
                                "root_document_id" to rootId,
                                "container_section_id" to container1Id,
                                "leaf_section_id" to leaf1Id
                            )
                        )
                    ),
                    metadata = mapOf("root_document_id" to rootId)
                ),
                LeafSection(
                    id = leaf2Id,
                    uri = "test://doc",
                    title = "Leaf 2",
                    text = "Content 2",
                    parentId = rootId,
                    metadata = mapOf(
                        "root_document_id" to rootId,
                        "container_section_id" to rootId,
                        "leaf_section_id" to leaf2Id
                    )
                )
            )
        )

        val allChunks = chunker.splitSections(listOf(document))

        assertTrue(allChunks.isNotEmpty(), "Should create chunks")

        allChunks.forEach { chunk ->
            val pathFromRoot = chunk.pathFromRoot
            assertNotNull(pathFromRoot, "Chunk ${chunk.id} should have non-null pathFromRoot")

            // Verify path structure
            assertEquals(rootId, pathFromRoot!!.first(), "Path should start with root")
            assertEquals(chunk.id, pathFromRoot.last(), "Path should end with chunk ID")

            // Verify path has reasonable length (not too short, not too long)
            assertTrue(pathFromRoot.size >= 2, "Path should have at least root and chunk")
            assertTrue(pathFromRoot.size <= 5, "Path should not be unreasonably long")
        }
    }

    @Test
    fun `test chunk with minimal valid metadata has non-null pathFromRoot`() {
        val rootId = UUID.randomUUID().toString()
        val chunkId = UUID.randomUUID().toString()

        val chunk = Chunk(
            id = chunkId,
            text = "Test content",
            metadata = mapOf(
                "root_document_id" to rootId
                // container_section_id and leaf_section_id are optional
            ),
            parentId = rootId
        )

        val pathFromRoot = chunk.pathFromRoot
        assertNotNull(pathFromRoot, "Chunk should have non-null pathFromRoot with minimal metadata")
        assertEquals(listOf(rootId, chunkId), pathFromRoot)
    }

    @Test
    fun `test chunk without root_document_id has null pathFromRoot`() {
        val chunkId = UUID.randomUUID().toString()

        val chunk = Chunk(
            id = chunkId,
            text = "Test content",
            metadata = emptyMap(), // Missing root_document_id
            parentId = UUID.randomUUID().toString()
        )

        val pathFromRoot = chunk.pathFromRoot
        assertNull(pathFromRoot, "Chunk without root_document_id should have null pathFromRoot")
    }
}
