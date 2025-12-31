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
import com.embabel.agent.rag.model.LeafSection
import com.embabel.agent.rag.model.MaterializedDocument
import com.embabel.agent.rag.service.ResultExpander
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for chunk expansion functionality in LuceneSearchOperations.
 */
class LuceneExpandChunkTest {

    private lateinit var ragService: LuceneSearchOperations

    @BeforeEach
    fun setUp() {
        ragService = createTestRagService()
    }

    @AfterEach
    fun tearDown() {
        ragService.close()
    }

    @Test
    fun `should return empty list when chunk not found`() {
        val result = ragService.expandResult(
            "non-existent-chunk",
            ResultExpander.Method.SEQUENCE,
            elementsToAdd = 1
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `should return only original chunk when missing sequence metadata`() {
        val chunk = Chunk(
            id = "no-meta-chunk",
            text = "Content without metadata",
            parentId = "parent",
            metadata = emptyMap()
        )
        ragService.onNewRetrievables(listOf(chunk))
        ragService.commitChanges()

        val result = ragService.expandResult(
            "no-meta-chunk",
            ResultExpander.Method.SEQUENCE,
            elementsToAdd = 1
        )

        assertEquals(1, result.size)
        assertEquals("no-meta-chunk", result.first().id)
    }

    @Test
    fun `should expand chunk to include adjacent chunks in sequence`() {
        val containerSectionId = "section-1"
        val chunks = (0..4).map { seq ->
            Chunk(
                id = "chunk-$seq",
                text = "Content for chunk $seq",
                parentId = "parent",
                metadata = mapOf(
                    "container_section_id" to containerSectionId,
                    "sequence_number" to seq
                )
            )
        }
        ragService.onNewRetrievables(chunks)
        ragService.commitChanges()

        val result = ragService.expandResult(
            "chunk-2",
            ResultExpander.Method.SEQUENCE,
            elementsToAdd = 1
        )

        assertEquals(3, result.size)
        assertEquals(listOf("chunk-1", "chunk-2", "chunk-3"), result.map { it.id })
    }

    @Test
    fun `should handle expansion at beginning of sequence`() {
        val containerSectionId = "section-start"
        val chunks = (0..4).map { seq ->
            Chunk(
                id = "start-chunk-$seq",
                text = "Content $seq",
                parentId = "parent",
                metadata = mapOf(
                    "container_section_id" to containerSectionId,
                    "sequence_number" to seq
                )
            )
        }
        ragService.onNewRetrievables(chunks)
        ragService.commitChanges()

        val result = ragService.expandResult(
            "start-chunk-0",
            ResultExpander.Method.SEQUENCE,
            elementsToAdd = 2
        )

        assertEquals(3, result.size)
        assertEquals("start-chunk-0", result.first().id)
    }

    @Test
    fun `should handle expansion at end of sequence`() {
        val containerSectionId = "section-end"
        val chunks = (0..4).map { seq ->
            Chunk(
                id = "end-chunk-$seq",
                text = "Content $seq",
                parentId = "parent",
                metadata = mapOf(
                    "container_section_id" to containerSectionId,
                    "sequence_number" to seq
                )
            )
        }
        ragService.onNewRetrievables(chunks)
        ragService.commitChanges()

        val result = ragService.expandResult(
            "end-chunk-4",
            ResultExpander.Method.SEQUENCE,
            elementsToAdd = 2
        )

        assertEquals(3, result.size)
        assertEquals("end-chunk-4", result.last().id)
    }

    @Test
    fun `should not include chunks from different container sections`() {
        val section1Chunks = (0..2).map { seq ->
            Chunk(
                id = "s1-chunk-$seq",
                text = "Section 1 content $seq",
                parentId = "parent",
                metadata = mapOf(
                    "container_section_id" to "section-1",
                    "sequence_number" to seq
                )
            )
        }
        val section2Chunks = (0..2).map { seq ->
            Chunk(
                id = "s2-chunk-$seq",
                text = "Section 2 content $seq",
                parentId = "parent",
                metadata = mapOf(
                    "container_section_id" to "section-2",
                    "sequence_number" to seq
                )
            )
        }
        ragService.onNewRetrievables(section1Chunks + section2Chunks)
        ragService.commitChanges()

        val result = ragService.expandResult(
            "s1-chunk-1",
            ResultExpander.Method.SEQUENCE,
            elementsToAdd = 5
        )

        assertEquals(3, result.size)
        assertTrue(result.all { it.id.startsWith("s1-") })
    }

    @Test
    fun `should return chunks ordered by sequence number`() {
        val containerSectionId = "ordered-section"
        val chunks = listOf(3, 1, 4, 0, 2).map { seq ->
            Chunk(
                id = "ordered-chunk-$seq",
                text = "Content $seq",
                parentId = "parent",
                metadata = mapOf(
                    "container_section_id" to containerSectionId,
                    "sequence_number" to seq
                )
            )
        }
        ragService.onNewRetrievables(chunks)
        ragService.commitChanges()

        val result = ragService.expandResult(
            "ordered-chunk-2",
            ResultExpander.Method.SEQUENCE,
            elementsToAdd = 10
        )

        assertEquals(5, result.size)
        assertEquals(
            listOf("ordered-chunk-0", "ordered-chunk-1", "ordered-chunk-2", "ordered-chunk-3", "ordered-chunk-4"),
            result.map { it.id }
        )
    }

    @Test
    fun `should handle chunksToAdd of zero`() {
        val containerSectionId = "zero-section"
        val chunks = (0..2).map { seq ->
            Chunk(
                id = "zero-chunk-$seq",
                text = "Content $seq",
                parentId = "parent",
                metadata = mapOf(
                    "container_section_id" to containerSectionId,
                    "sequence_number" to seq
                )
            )
        }
        ragService.onNewRetrievables(chunks)
        ragService.commitChanges()

        val result = ragService.expandResult(
            "zero-chunk-1",
            ResultExpander.Method.SEQUENCE,
            elementsToAdd = 0
        )

        assertEquals(1, result.size)
        assertEquals("zero-chunk-1", result.first().id)
    }

    @Test
    fun `should work with writeAndChunkDocument`() {
        val leaf1 = LeafSection(
            id = "leaf-1",
            title = "Section One",
            text = "This is the first section with some content about programming and software development.",
            parentId = "root"
        )
        val leaf2 = LeafSection(
            id = "leaf-2",
            title = "Section Two",
            text = "This is the second section discussing databases and data storage.",
            parentId = "root"
        )
        val leaf3 = LeafSection(
            id = "leaf-3",
            title = "Section Three",
            text = "This is the third section about cloud computing and infrastructure.",
            parentId = "root"
        )

        val document = MaterializedDocument(
            id = "root",
            uri = "test://expand-test",
            title = "Test Document",
            children = listOf(leaf1, leaf2, leaf3)
        )

        val chunkIds = ragService.writeAndChunkDocument(document)
        assertTrue(chunkIds.isNotEmpty(), "Should create chunks from document")

        val firstChunkId = chunkIds.first()
        val result = ragService.expandResult(
            firstChunkId,
            ResultExpander.Method.SEQUENCE,
            elementsToAdd = 1
        )

        assertTrue(result.isNotEmpty(), "Should return expanded chunks")
        assertTrue(result.any { it.id == firstChunkId }, "Result should include the original chunk")
    }

    @Test
    fun `zoomOut should return parent LeafSection of a chunk`() {
        val leafSection = LeafSection(
            id = "parent-leaf-section",
            title = "Parent Section Title",
            text = "This is the parent section content that contains multiple paragraphs of text.",
            parentId = "document-root"
        )
        ragService.save(leafSection)

        val chunk = Chunk(
            id = "child-chunk",
            text = "This is chunked content from the parent section.",
            parentId = "parent-leaf-section",
            metadata = mapOf(
                "container_section_id" to "parent-leaf-section",
                "sequence_number" to 0
            )
        )
        ragService.onNewRetrievables(listOf(chunk))
        ragService.commitChanges()

        val result = ragService.expandResult(
            "child-chunk",
            ResultExpander.Method.ZOOM_OUT,
            elementsToAdd = 1
        )

        assertEquals(1, result.size, "ZOOM_OUT should return exactly one parent element")
        val parent = result.first()
        assertEquals("parent-leaf-section", parent.id, "Should return the parent LeafSection")
        assertTrue(parent is LeafSection, "Parent should be a LeafSection")
        assertEquals("Parent Section Title", (parent as LeafSection).title)
        assertEquals("This is the parent section content that contains multiple paragraphs of text.", parent.text)
    }
}
