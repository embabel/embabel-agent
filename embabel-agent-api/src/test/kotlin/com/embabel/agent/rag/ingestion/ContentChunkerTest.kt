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

import com.embabel.agent.rag.LeafSection
import com.embabel.agent.rag.MaterializedContentRoot
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ContentChunkerTest {

    private val chunker = ContentChunker()

    @Test
    fun `test single chunk for container with small total content`() {
        val leaf1 = LeafSection(
            id = "leaf-1",
            title = "Introduction",
            content = "This is a short introduction section."
        )
        val leaf2 = LeafSection(
            id = "leaf-2",
            title = "Overview",
            content = "This is a brief overview section."
        )

        val container = MaterializedContentRoot(
            id = "container-1",
            title = "Small Document",
            children = listOf(leaf1, leaf2),
            metadata = mapOf("source" to "test")
        )

        val chunks = chunker.chunk(container)

        assertEquals(1, chunks.size)
        val chunk = chunks.first()
        assertTrue(chunk.text.contains("Introduction"))
        assertTrue(chunk.text.contains("This is a short introduction section."))
        assertTrue(chunk.text.contains("Overview"))
        assertTrue(chunk.text.contains("This is a brief overview section."))
        assertEquals("container-1", chunk.parentId)
        assertEquals(0, chunk.metadata["chunk_index"])
        assertEquals(1, chunk.metadata["total_chunks"])
        assertEquals("Small Document", chunk.metadata["container_section_title"])
        assertEquals("test", chunk.metadata["source"])
    }

    @Test
    fun `test individual leaf processing for container with large total content`() {
        // Create one small leaf and one large leaf
        val smallLeaf = LeafSection(
            id = "leaf-small",
            title = "Small Section",
            content = "This is small content that won't be split."
        )

        // Create a large leaf that will be split
        val largeContent = buildString {
            repeat(10) { paragraphIndex ->
                appendLine("This is paragraph number $paragraphIndex of a very long leaf section that will definitely exceed the minimum chunk size threshold.")
                appendLine("It contains multiple sentences and should be split appropriately. The content is designed to test the paragraph-based splitting logic.")
                appendLine("Each paragraph has substantial content to ensure we reach the minimum threshold for splitting.")
                appendLine("The splitter should handle this gracefully and create multiple chunks with proper overlap and metadata preservation.")
                appendLine("This ensures we have comprehensive test coverage for the splitting functionality.")
                if (paragraphIndex < 9) appendLine() // Add paragraph break except for last one
            }
        }

        val largeLeaf = LeafSection(
            id = "leaf-large",
            title = "Large Section",
            content = largeContent,
            metadata = mapOf("category" to "long")
        )

        val container = MaterializedContentRoot(
            id = "container-2",
            title = "Mixed Document",
            children = listOf(smallLeaf, largeLeaf),
            metadata = mapOf("source" to "test")
        )

        val chunks = chunker.chunk(container)

        // Should have at least 2 chunks: 1 for small leaf, multiple for large leaf
        assertTrue(chunks.size >= 2, "Should create multiple chunks for mixed content")

        // Find the small leaf chunk
        val smallLeafChunks = chunks.filter { it.metadata["leaf_section_id"] == "leaf-small" }
        assertEquals(1, smallLeafChunks.size, "Small leaf should create exactly 1 chunk")
        val smallChunk = smallLeafChunks.first()
        assertTrue(smallChunk.text.contains("Small Section"))
        assertTrue(smallChunk.text.contains("This is small content"))
        assertEquals("leaf-small", smallChunk.parentId)

        // Find the large leaf chunks
        val largeLeafChunks = chunks.filter { it.metadata["leaf_section_id"] == "leaf-large" }
        assertTrue(largeLeafChunks.size > 1, "Large leaf should be split into multiple chunks")

        // Verify metadata for all chunks
        chunks.forEach { chunk ->
            assertEquals("container-2", chunk.metadata["container_section_id"])
            assertEquals("Mixed Document", chunk.metadata["container_section_title"])
            assertTrue(chunk.text.length <= 1500, "Chunk should not exceed max size: ${chunk.text.length}")
            assertNotNull(chunk.id)
            assertTrue(chunk.text.isNotBlank())
        }
    }

    @Test
    fun `test empty container handling`() {
        val container = MaterializedContentRoot(
            id = "empty-container",
            title = "Empty Document",
            children = emptyList()
        )

        val chunks = chunker.chunk(container)

        assertEquals(1, chunks.size)
        val chunk = chunks.first()
        assertTrue(chunk.text.trim().isEmpty())
        assertEquals("empty-container", chunk.parentId)
        assertEquals("Empty Document", chunk.metadata["container_section_title"])
    }

    @Test
    fun `test multiple leaf sections in container`() {
        val leaf1 = LeafSection(
            id = "leaf-1",
            title = "Section A",
            content = "Content for section A."
        )

        val leaf2 = LeafSection(
            id = "leaf-2",
            title = "Section B",
            content = "Content for section B."
        )

        val leaf3 = LeafSection(
            id = "leaf-3",
            title = "Section C",
            content = "Content for section C."
        )

        val rootContainer = MaterializedContentRoot(
            id = "root-1",
            title = "Multi-Section Document",
            children = listOf(leaf1, leaf2, leaf3)
        )

        val chunks = chunker.chunk(rootContainer)

        assertEquals(1, chunks.size) // Small total content should create single chunk
        val chunk = chunks.first()
        assertTrue(chunk.text.contains("Section A"))
        assertTrue(chunk.text.contains("Content for section A."))
        assertTrue(chunk.text.contains("Section B"))
        assertTrue(chunk.text.contains("Content for section B."))
        assertTrue(chunk.text.contains("Section C"))
        assertTrue(chunk.text.contains("Content for section C."))
        assertEquals("root-1", chunk.parentId)
    }

    @Test
    fun `test multiple containers processing`() {
        val container1 = MaterializedContentRoot(
            id = "container-1",
            title = "Document 1",
            children = listOf(
                LeafSection(id = "l1", title = "Title 1", content = "Content 1")
            )
        )

        val container2 = MaterializedContentRoot(
            id = "container-2",
            title = "Document 2",
            children = listOf(
                LeafSection(id = "l2", title = "Title 2", content = "Content 2")
            )
        )

        val chunks = chunker.splitSections(listOf(container1, container2))

        assertEquals(2, chunks.size)
        assertTrue(chunks.any { it.text.contains("Content 1") })
        assertTrue(chunks.any { it.text.contains("Content 2") })
        assertEquals("container-1", chunks[0].parentId)
        assertEquals("container-2", chunks[1].parentId)
    }

    @Test
    fun `test custom splitter configuration`() {
        val config = ContentChunker.SplitterConfig(
            maxChunkSize = 100,
            overlapSize = 20,
            minChunkSize = 150
        )
        val customSplitter = ContentChunker.withConfig(config)

        // Create content longer than minChunkSize (150) in a single leaf
        val content = buildString {
            repeat(10) {
                append("This is sentence number $it that should be split with the custom configuration settings. ")
            }
        }

        val largeLeaf = LeafSection(
            id = "large-leaf",
            title = "Large Leaf",
            content = content
        )

        val container = MaterializedContentRoot(
            id = "custom-container",
            title = "Custom Config Test",
            children = listOf(largeLeaf)
        )

        val chunks = customSplitter.chunk(container)

        assertTrue(chunks.size > 1, "Should create multiple chunks with custom config")
        chunks.forEach { chunk ->
            assertTrue(chunk.text.length <= 100, "Chunk should respect custom max size")
        }
    }

    @Test
    fun `test configuration validation`() {
        // Test invalid configurations
        assertThrows(IllegalArgumentException::class.java) {
            ContentChunker.SplitterConfig(maxChunkSize = 0)
        }

        assertThrows(IllegalArgumentException::class.java) {
            ContentChunker.SplitterConfig(overlapSize = -1)
        }

        assertThrows(IllegalArgumentException::class.java) {
            ContentChunker.SplitterConfig(maxChunkSize = 100, minChunkSize = 50)
        }

        assertThrows(IllegalArgumentException::class.java) {
            ContentChunker.SplitterConfig(maxChunkSize = 100, overlapSize = 150)
        }
    }

    @Test
    fun `test metadata preservation from leaves`() {
        val leaf1 = LeafSection(
            id = "metadata-leaf-1",
            title = "First Section",
            content = "First content",
            metadata = mapOf("author" to "John", "type" to "intro")
        )

        val leaf2 = LeafSection(
            id = "metadata-leaf-2",
            title = "Second Section",
            content = "Second content",
            metadata = mapOf("author" to "Jane", "type" to "body")
        )

        val container = MaterializedContentRoot(
            id = "metadata-container",
            title = "Metadata Test",
            children = listOf(leaf1, leaf2),
            metadata = mapOf("document" to "test", "version" to "1.0")
        )

        val chunks = chunker.chunk(container)

        assertEquals(1, chunks.size) // Small content should be combined
        val chunk = chunks.first()

        // Container metadata should be preserved
        assertEquals("test", chunk.metadata["document"])
        assertEquals("1.0", chunk.metadata["version"])
        assertEquals("metadata-container", chunk.metadata["container_section_id"])
        assertEquals("Metadata Test", chunk.metadata["container_section_title"])

        // Leaf sections info should be tracked
        val leafSections = chunk.metadata["leaf_sections"] as List<*>
        assertEquals(2, leafSections.size)
    }

    @Test
    fun `test chunk boundaries respect sentence endings`() {
        val longContent = buildString {
            repeat(100) {
                append("This is a test sentence. ")
            }
        }

        val largeLeaf = LeafSection(
            id = "sentence-test",
            title = "Sentence Test",
            content = longContent
        )

        val container = MaterializedContentRoot(
            id = "sentence-container",
            title = "Sentence Boundary Test",
            children = listOf(largeLeaf)
        )

        val chunks = chunker.chunk(container)

        assertTrue(chunks.size > 1, "Should create multiple chunks")

        // Most chunks should end with sentence endings (allowing for some overlap cases)
        val chunksEndingWithPeriod = chunks.count { it.text.trim().endsWith('.') }
        assertTrue(
            chunksEndingWithPeriod >= chunks.size * 0.8,
            "Most chunks should end at sentence boundaries"
        )
    }
}
