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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ContentChunkerTest {

    private val chunker = ContentChunker()

    @Test
    fun `test single chunk for container with small total content`() {
        val leaf1 = LeafSection(
            id = "leaf-1",
            title = "Introduction",
            text = "This is a short introduction section."
        )
        val leaf2 = LeafSection(
            id = "leaf-2",
            title = "Overview",
            text = "This is a brief overview section."
        )

        val container = MaterializedDocument(
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
            text = "This is small content that won't be split."
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
            text = largeContent,
            metadata = mapOf("category" to "long")
        )

        val container = MaterializedDocument(
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
        val container = MaterializedDocument(
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
            text = "Content for section A."
        )

        val leaf2 = LeafSection(
            id = "leaf-2",
            title = "Section B",
            text = "Content for section B."
        )

        val leaf3 = LeafSection(
            id = "leaf-3",
            title = "Section C",
            text = "Content for section C."
        )

        val rootContainer = MaterializedDocument(
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
        val container1 = MaterializedDocument(
            id = "container-1",
            title = "Document 1",
            children = listOf(
                LeafSection(id = "l1", title = "Title 1", text = "Content 1")
            )
        )

        val container2 = MaterializedDocument(
            id = "container-2",
            title = "Document 2",
            children = listOf(
                LeafSection(id = "l2", title = "Title 2", text = "Content 2")
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
            text = content
        )

        val container = MaterializedDocument(
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
            text = "First content",
            metadata = mapOf("author" to "John", "type" to "intro")
        )

        val leaf2 = LeafSection(
            id = "metadata-leaf-2",
            title = "Second Section",
            text = "Second content",
            metadata = mapOf("author" to "Jane", "type" to "body")
        )

        val container = MaterializedDocument(
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
            text = longContent
        )

        val container = MaterializedDocument(
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

    @Test
    fun `test chunking too fine with large chunk sizes`() {
        // Create a chunker with larger chunk sizes that should create fewer, larger chunks
        val chunker = ContentChunker(maxChunkSize = 5000, overlapSize = 200, minChunkSize = 1500)

        // Create medium-sized content that could reasonably fit in one chunk
        val mediumContent = buildString {
            repeat(20) { paragraphIndex ->
                appendLine("This is paragraph $paragraphIndex containing reasonable amounts of text content.")
                appendLine("Each paragraph has enough substance to be meaningful but not overwhelming.")
                appendLine("The goal is to test whether the chunker creates appropriately sized chunks.")
                appendLine("With a max chunk size of 5000 characters, this should not be overly fragmented.")
                appendLine("")
            }
        }

        println("Medium content length: ${mediumContent.length}")

        val leaf = LeafSection(
            id = "medium-leaf",
            title = "Medium Section",
            text = mediumContent
        )

        val container = MaterializedDocument(
            id = "medium-container",
            title = "Medium Document",
            children = listOf(leaf)
        )

        val chunks = chunker.chunk(container)

        println("Number of chunks created: ${chunks.size}")
        chunks.forEachIndexed { index, chunk ->
            println("Chunk $index size: ${chunk.text.length}")
        }

        // With content around 2000-3000 chars and maxChunkSize=5000, should create fewer chunks
        assertTrue(
            chunks.size <= 2,
            "Should create at most 2 chunks for medium content with large max chunk size, but got ${chunks.size}"
        )

        // Verify chunks are reasonably sized
        chunks.forEach { chunk ->
            assertTrue(chunk.text.length <= 5000, "Chunk should not exceed max size: ${chunk.text.length}")
            assertTrue(chunk.text.length >= 500, "Chunk should be reasonably substantial: ${chunk.text.length}")
        }
    }

    @Test
    fun `test large content with generous chunk settings`() {
        // Even more generous settings
        val chunker = ContentChunker(maxChunkSize = 8000, overlapSize = 400, minChunkSize = 2000)

        // Create larger content that should still result in reasonably few chunks
        val largeContent = buildString {
            repeat(50) { paragraphIndex ->
                appendLine("This is substantial paragraph number $paragraphIndex in a comprehensive document.")
                appendLine("Each paragraph contains detailed information and explanations that provide value.")
                appendLine("The content is designed to test chunking behavior with generous size limits.")
                appendLine("We want to ensure that the chunker doesn't over-fragment content needlessly.")
                appendLine("Good chunking should balance between size constraints and content coherence.")
                appendLine("")
            }
        }

        println("Large content length: ${largeContent.length}")

        val leaf = LeafSection(
            id = "large-leaf",
            title = "Large Section",
            text = largeContent
        )

        val container = MaterializedDocument(
            id = "large-container",
            title = "Large Document",
            children = listOf(leaf)
        )

        val chunks = chunker.chunk(container)

        println("Number of chunks for large content: ${chunks.size}")
        chunks.forEachIndexed { index, chunk ->
            println("Large chunk $index size: ${chunk.text.length}")
        }

        // Should create reasonable number of chunks, not over-fragment
        val expectedMaxChunks = (largeContent.length / 6000) + 2 // Rough estimate with some buffer
        assertTrue(
            chunks.size <= expectedMaxChunks,
            "Should not over-fragment large content. Expected max: $expectedMaxChunks, got: ${chunks.size}"
        )

        chunks.forEach { chunk ->
            assertTrue(chunk.text.length <= 8000, "Chunk should not exceed max size: ${chunk.text.length}")
        }
    }

    @Test
    fun `test multiple medium leaves should not over-fragment`() {
        val chunker = ContentChunker(maxChunkSize = 5000, overlapSize = 200, minChunkSize = 1500)

        // Create several medium-sized leaves
        val leaves = (1..3).map { leafNum ->
            val content = buildString {
                repeat(10) { paraNum ->
                    appendLine("Leaf $leafNum paragraph $paraNum with moderate content length.")
                    appendLine("This paragraph provides sufficient detail without being excessive.")
                    appendLine("The content should be chunked efficiently without over-fragmentation.")
                    appendLine("")
                }
            }

            LeafSection(
                id = "leaf-$leafNum",
                title = "Section $leafNum",
                text = content
            )
        }

        val totalContentLength = leaves.sumOf { it.content.length }
        println("Total content length for multiple leaves: $totalContentLength")

        val container = MaterializedDocument(
            id = "multi-medium-container",
            title = "Multiple Medium Sections",
            children = leaves
        )

        val chunks = chunker.chunk(container)

        println("Number of chunks for multiple medium sections: ${chunks.size}")
        chunks.forEachIndexed { index, chunk ->
            println("Multi-medium chunk $index size: ${chunk.text.length}")
        }

        // Should create fewer chunks by intelligently grouping leaves
        assertTrue(chunks.size >= 1, "Should create at least one chunk")
        assertTrue(chunks.size < leaves.size, "Should create fewer chunks than leaves by grouping them intelligently")
        assertTrue(chunks.size <= 3, "Should not create excessive chunks for medium content")

        chunks.forEach { chunk ->
            assertTrue(chunk.text.length <= 5000, "Chunk should not exceed max size: ${chunk.text.length}")
        }
    }

    @Test
    fun `demonstrate over-chunking issue with large chunk sizes`() {
        // This test specifically shows the over-chunking problem
        val chunker = ContentChunker(maxChunkSize = 5000, overlapSize = 200, minChunkSize = 1500)

        // Create content that SHOULD fit in a single large chunk but gets split unnecessarily
        val content1 = "Section 1 content that is substantial but not huge. ".repeat(30) // ~1600 chars
        val content2 = "Section 2 content with different but related information. ".repeat(30) // ~1740 chars
        val content3 = "Section 3 content that complements the other sections. ".repeat(30) // ~1650 chars

        val leaves = listOf(
            LeafSection(id = "s1", title = "Section 1", text = content1),
            LeafSection(id = "s2", title = "Section 2", text = content2),
            LeafSection(id = "s3", title = "Section 3", text = content3)
        )

        val totalLength = leaves.sumOf { it.content.length + it.title.length + 1 } // +1 for newline after title
        println("Total combined length: $totalLength")

        val container = MaterializedDocument(
            id = "over-chunk-test",
            title = "Should Be One Chunk",
            children = leaves
        )

        val chunks = chunker.chunk(container)

        println("Over-chunking demo:")
        println("- Total content length: $totalLength characters")
        println("- Max chunk size: 5000 characters")
        println("- Min chunk size: 1500 characters")
        println("- Content could easily fit in 1 chunk, but got: ${chunks.size} chunks")

        chunks.forEachIndexed { index, chunk ->
            println("  Chunk $index: ${chunk.text.length} characters")
        }

        // FIXED: Content that fits in maxChunkSize should now create a single chunk
        assertTrue(totalLength <= 5000, "Total content should fit in one chunk")
        assertEquals(1, chunks.size, "Fixed implementation should create 1 chunk for content that fits in maxChunkSize")

        val chunk = chunks.first()
        assertTrue(chunk.text.length <= 5000, "Chunk should not exceed max size")
        assertTrue(chunk.text.contains("Section 1"), "Should contain all sections")
        assertTrue(chunk.text.contains("Section 2"), "Should contain all sections")
        assertTrue(chunk.text.contains("Section 3"), "Should contain all sections")

        println("\n*** CHUNKING ISSUE FIXED ***")
        println("Content of $totalLength chars now creates ${chunks.size} optimal chunk(s)")
        println("This improves retrieval effectiveness and reduces storage/processing overhead")
    }
}
