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
            metadata = mapOf("source" to "test"),
            uri = "foo",
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
            metadata = mapOf("source" to "test"),
            uri = "foo",
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
            children = emptyList(),
            uri = "foo",
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
            children = listOf(leaf1, leaf2, leaf3),
            uri = "foo",
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
            uri = "foo",
            id = "container-1",
            title = "Document 1",
            children = listOf(
                LeafSection(id = "l1", title = "Title 1", text = "Content 1")
            )
        )

        val container2 = MaterializedDocument(
            uri = "foo",
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
        val config = ContentChunker.DefaultConfig(
            maxChunkSize = 100,
            overlapSize = 20,
        )
        val customSplitter = ContentChunker(config)

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
            uri = "custom-container",
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
            ContentChunker.DefaultConfig(maxChunkSize = 0)
        }

        assertThrows(IllegalArgumentException::class.java) {
            ContentChunker.DefaultConfig(overlapSize = -1)
        }

        assertThrows(IllegalArgumentException::class.java) {
            ContentChunker.DefaultConfig(maxChunkSize = 100)
        }

        assertThrows(IllegalArgumentException::class.java) {
            ContentChunker.DefaultConfig(maxChunkSize = 100, overlapSize = 150)
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
            uri = "mc",
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
            children = listOf(largeLeaf),
            uri = "sentence-container"
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
        val chunker = ContentChunker(
            ContentChunker.DefaultConfig(
                maxChunkSize = 5000,
                overlapSize = 200,
            )
        )

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

        val leaf = LeafSection(
            id = "medium-leaf",
            title = "Medium Section",
            text = mediumContent
        )

        val container = MaterializedDocument(
            id = "medium-container",
            title = "Medium Document",
            children = listOf(leaf),
            uri = "medium-container",
        )

        val chunks = chunker.chunk(container)

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
        val chunker = ContentChunker(
            ContentChunker.DefaultConfig(
                maxChunkSize = 8000,
                overlapSize = 400,
            )
        )

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

        val leaf = LeafSection(
            id = "large-leaf",
            title = "Large Section",
            text = largeContent
        )

        val container = MaterializedDocument(
            id = "large-container",
            title = "Large Document",
            children = listOf(leaf),
            uri = "large-container",
        )

        val chunks = chunker.chunk(container)

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
        val chunker = ContentChunker(
            ContentChunker.DefaultConfig(
                maxChunkSize = 5000,
                overlapSize = 200,
            )
        )

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

        val container = MaterializedDocument(
            id = "multi-medium-container",
            title = "Multiple Medium Sections",
            children = leaves,
            uri = "multi-medium-container",
        )

        val chunks = chunker.chunk(container)

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
        val chunker = ContentChunker(
            ContentChunker.DefaultConfig(maxChunkSize = 5000, overlapSize = 200)
        )

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

        val container = MaterializedDocument(
            id = "over-chunk-test",
            title = "Should Be One Chunk",
            children = leaves,
            uri = "over-chunk-test",
        )

        val chunks = chunker.chunk(container)

        // FIXED: Content that fits in maxChunkSize should now create a single chunk
        assertTrue(totalLength <= 5000, "Total content should fit in one chunk")
        assertEquals(1, chunks.size, "Fixed implementation should create 1 chunk for content that fits in maxChunkSize")

        val chunk = chunks.first()
        assertTrue(chunk.text.length <= 5000, "Chunk should not exceed max size")
        assertTrue(chunk.text.contains("Section 1"), "Should contain all sections")
        assertTrue(chunk.text.contains("Section 2"), "Should contain all sections")
        assertTrue(chunk.text.contains("Section 3"), "Should contain all sections")
    }

    @Test
    fun `test section title is included in chunk when config is true`() {
        val chunker = ContentChunker(
            ContentChunker.DefaultConfig(
                maxChunkSize = 2000,
                overlapSize = 100,
                includeSectionTitleInChunk = true
            )
        )

        val leaf = LeafSection(
            id = "leaf-1",
            title = "Leaf Title",
            text = "This is the leaf content."
        )

        val container = MaterializedDocument(
            id = "container-1",
            title = "Container Title",
            children = listOf(leaf),
            uri = "test-uri",
        )

        val chunks = chunker.chunk(container)

        assertEquals(1, chunks.size)
        val chunk = chunks.first()

        // Verify section title is prepended with FROM: prefix
        assertTrue(
            chunk.text.startsWith("FROM: Container Title\n\n"),
            "Chunk should start with 'FROM: ' and container title"
        )
        assertTrue(chunk.text.contains("Leaf Title"), "Chunk should contain leaf title")
        assertTrue(chunk.text.contains("This is the leaf content."), "Chunk should contain leaf content")
    }

    @Test
    fun `test section title is not included when config is false`() {
        val chunker = ContentChunker(
            ContentChunker.DefaultConfig(
                maxChunkSize = 2000,
                overlapSize = 100,
                includeSectionTitleInChunk = false
            )
        )

        val leaf = LeafSection(
            id = "leaf-1",
            title = "Leaf Title",
            text = "This is the leaf content."
        )

        val container = MaterializedDocument(
            id = "container-1",
            title = "Container Title",
            children = listOf(leaf),
            uri = "test-uri",
        )

        val chunks = chunker.chunk(container)

        assertEquals(1, chunks.size)
        val chunk = chunks.first()

        // Verify section title is NOT prepended (chunk starts with leaf title instead)
        assertFalse(chunk.text.startsWith("Container Title\n\n"), "Chunk should not start with container title")
        assertTrue(chunk.text.startsWith("Leaf Title"), "Chunk should start with leaf title")
    }

    @Test
    fun `test section title not included when it would exceed maxChunkSize`() {
        val chunker = ContentChunker(
            ContentChunker.DefaultConfig(
                maxChunkSize = 100,
                overlapSize = 20,
                includeSectionTitleInChunk = true
            )
        )

        val leaf = LeafSection(
            id = "leaf-1",
            title = "Short",
            text = "This content is exactly sized to be near the limit when combined with a long container title."
        )

        val container = MaterializedDocument(
            id = "container-1",
            title = "Very Long Container Title That Would Cause Chunk To Exceed MaxChunkSize",
            children = listOf(leaf),
            uri = "test-uri",
        )

        val chunks = chunker.chunk(container)

        // All chunks should respect maxChunkSize
        chunks.forEach { chunk ->
            assertTrue(chunk.text.length <= 100, "Chunk length ${chunk.text.length} should not exceed maxChunkSize 100")
        }

        // Section title should NOT be included because it would exceed the limit
        assertFalse(
            chunks.first().text.startsWith("Very Long Container"),
            "Title should not be prepended when it would exceed maxChunkSize"
        )
    }

    @Test
    fun `test idsFromRoot for single chunk from container`() {
        val leaf = LeafSection(
            id = "leaf-1",
            title = "Introduction",
            text = "Small content"
        )

        val container = MaterializedDocument(
            id = "doc-root",
            title = "Test Document",
            children = listOf(leaf),
            uri = "test-uri",
        )

        val chunks = chunker.chunk(container)
        assertEquals(1, chunks.size)

        val chunk = chunks.first()
        val idsFromRoot = chunk.pathFromRoot

        assertNotNull(idsFromRoot, "idsFromRoot should not be null")
        assertEquals(2, idsFromRoot!!.size, "Should have root and chunk IDs")
        assertEquals("doc-root", idsFromRoot[0], "First ID should be root document")
        assertEquals(chunk.id, idsFromRoot.last(), "Last ID should be chunk ID")
    }

    @Test
    fun `test idsFromRoot for chunk from leaf section`() {
        val leaf1 = LeafSection(
            id = "leaf-1",
            title = "Section A",
            text = "Content A"
        )
        val leaf2 = LeafSection(
            id = "leaf-2",
            title = "Section B",
            text = "Content B"
        )

        val container = MaterializedDocument(
            id = "doc-root",
            title = "Multi-Section Doc",
            children = listOf(leaf1, leaf2),
            uri = "test-uri",
        )

        val chunks = chunker.chunk(container)
        assertEquals(1, chunks.size)

        val chunk = chunks.first()
        val idsFromRoot = chunk.pathFromRoot

        assertNotNull(idsFromRoot, "idsFromRoot should not be null")
        assertEquals(2, idsFromRoot!!.size, "Should have root and chunk IDs")
        assertEquals("doc-root", idsFromRoot[0], "First ID should be root document")
        assertEquals(chunk.id, idsFromRoot.last(), "Last ID should be chunk ID")
    }

    @Test
    fun `test idsFromRoot for split chunks maintains hierarchy`() {
        // Create large leaf that will be split
        val largeContent = buildString {
            repeat(10) { paragraphIndex ->
                appendLine("Paragraph $paragraphIndex: This is a substantial amount of content.")
                appendLine("It contains multiple sentences to ensure splitting occurs.")
                appendLine("Each paragraph is designed to create a sizable chunk.")
                if (paragraphIndex < 9) appendLine()
            }
        }

        val largeLeaf = LeafSection(
            id = "leaf-large",
            title = "Large Section",
            text = largeContent
        )

        val container = MaterializedDocument(
            id = "doc-root",
            title = "Document",
            children = listOf(largeLeaf),
            uri = "test-uri",
        )

        val chunks = chunker.chunk(container)
        assertTrue(chunks.size > 1, "Large content should create multiple chunks")

        // All chunks should have idsFromRoot with same hierarchy structure
        chunks.forEach { chunk ->
            val idsFromRoot = chunk.pathFromRoot
            assertNotNull(idsFromRoot, "Each chunk should have idsFromRoot")
            assertTrue(idsFromRoot!!.size >= 3, "Should have at least root, container, and chunk")
            assertEquals("doc-root", idsFromRoot[0], "First ID should be root")
            assertEquals(chunk.id, idsFromRoot.last(), "Last ID should be chunk ID")
        }

        // Verify sequence numbers are different but hierarchy is same structure
        val paths = chunks.map { it.pathFromRoot!! }
        val firstPath = paths[0]
        paths.forEach { path ->
            assertEquals(firstPath.size, path.size, "All paths should have same length")
            // All but last element (chunk ID) should match the hierarchy
            assertEquals(
                firstPath.dropLast(1),
                path.dropLast(1),
                "All chunks should share same parent hierarchy"
            )
        }
    }

    @Test
    fun `test idsFromRoot with nested container sections`() {
        val leaf = LeafSection(
            id = "leaf-1",
            title = "Content",
            text = "Some content"
        )

        val nestedContainer = DefaultMaterializedContainerSection(
            id = "nested-section",
            title = "Nested",
            children = listOf(leaf),
            parentId = "doc-root",
            metadata = mapOf("root_document_id" to "doc-root")
        )

        val chunks = chunker.chunk(nestedContainer)
        assertEquals(1, chunks.size)

        val chunk = chunks.first()
        val idsFromRoot = chunk.pathFromRoot

        assertNotNull(idsFromRoot, "idsFromRoot should not be null")
        assertEquals("doc-root", idsFromRoot!![0], "First ID should be root document")
        assertEquals("nested-section", idsFromRoot[1], "Second ID should be nested container")
        assertEquals(chunk.id, idsFromRoot.last(), "Last ID should be chunk ID")
    }

    @Test
    fun `test idsFromRoot returns null when root_document_id missing`() {
        // Create a chunk manually without root_document_id in metadata
        val chunk = Chunk(
            id = "chunk-1",
            text = "Test content",
            metadata = mapOf("container_section_id" to "container-1"),
            parentId = "container-1"
        )

        assertNull(chunk.pathFromRoot, "idsFromRoot should return null when root_document_id is missing")
    }
}
