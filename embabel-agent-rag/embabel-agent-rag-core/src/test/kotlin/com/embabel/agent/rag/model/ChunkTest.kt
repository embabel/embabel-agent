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
package com.embabel.agent.rag.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ChunkTest {

    @Nested
    inner class StructureFromMetadata {

        @Test
        fun `promotes structural keys to structure and keeps metadata compat view`() {
            val chunk = Chunk(
                id = "c1",
                text = "hello",
                parentId = "parent",
                metadata = mapOf(
                    ChunkStructure.ROOT_DOCUMENT_ID to "doc-1",
                    ChunkStructure.CONTAINER_SECTION_ID to "sec-1",
                    ChunkStructure.LEAF_SECTION_ID to "leaf-1",
                    ChunkStructure.SEQUENCE_NUMBER to 2,
                    ChunkStructure.CHUNK_INDEX to 1,
                    ChunkStructure.TOTAL_CHUNKS to 4,
                    "author" to "alice",
                ),
            )

            assertEquals("doc-1", chunk.structure.rootDocumentId)
            assertEquals("sec-1", chunk.structure.containerSectionId)
            assertEquals("leaf-1", chunk.structure.leafSectionId)
            assertEquals(2, chunk.structure.sequenceNumber)
            assertEquals(1, chunk.structure.chunkIndex)
            assertEquals(4, chunk.structure.totalChunks)

            // Compat: historical string keys still resolve on metadata
            assertEquals("doc-1", chunk.metadata[ChunkStructure.ROOT_DOCUMENT_ID])
            assertEquals(2, chunk.metadata[ChunkStructure.SEQUENCE_NUMBER])
            assertEquals("alice", chunk.metadata["author"])
        }

        @Test
        fun `create factory accepts explicit structure`() {
            val structure = ChunkStructure(
                rootDocumentId = "doc",
                sequenceNumber = 5,
            )
            val chunk = Chunk.create(
                text = "body",
                parentId = "p",
                metadata = mapOf("tag" to "x"),
                structure = structure,
            )
            assertEquals(structure, chunk.structure)
            assertEquals("doc", chunk.metadata[ChunkStructure.ROOT_DOCUMENT_ID])
            assertEquals(5, chunk.metadata[ChunkStructure.SEQUENCE_NUMBER])
            assertEquals("x", chunk.metadata["tag"])
        }

        @Test
        fun `pathFromRoot uses structure fields`() {
            val chunk = Chunk(
                id = "chunk-id",
                text = "t",
                parentId = "leaf-1",
                metadata = mapOf(
                    ChunkStructure.ROOT_DOCUMENT_ID to "root",
                    ChunkStructure.CONTAINER_SECTION_ID to "container",
                    ChunkStructure.LEAF_SECTION_ID to "leaf-1",
                ),
            )
            assertEquals(listOf("root", "container", "leaf-1", "chunk-id"), chunk.pathFromRoot)
        }
    }

    @Nested
    inner class Withers {

        @Test
        fun `withText preserves structure`() {
            val chunk = Chunk(
                id = "c1",
                text = "old",
                parentId = "p",
                metadata = mapOf(ChunkStructure.SEQUENCE_NUMBER to 3),
            )
            val transformed = chunk.withText("new")
            assertEquals("new", transformed.text)
            assertEquals("old", transformed.urtext)
            assertEquals(3, transformed.structure.sequenceNumber)
        }

        @Test
        fun `withAdditionalMetadata merges freeform and structure`() {
            val chunk = Chunk(
                id = "c1",
                text = "t",
                parentId = "p",
                metadata = mapOf(
                    ChunkStructure.ROOT_DOCUMENT_ID to "doc",
                    "a" to 1,
                ),
            )
            val updated = chunk.withAdditionalMetadata(
                mapOf(
                    ChunkStructure.SEQUENCE_NUMBER to 9,
                    "b" to 2,
                )
            )
            assertEquals("doc", updated.structure.rootDocumentId)
            assertEquals(9, updated.structure.sequenceNumber)
            assertEquals(1, updated.metadata["a"])
            assertEquals(2, updated.metadata["b"])
        }

        @Test
        fun `withStructure replaces structure`() {
            val chunk = Chunk.create(
                text = "t",
                parentId = "p",
                metadata = mapOf(ChunkStructure.SEQUENCE_NUMBER to 1),
            )
            val updated = chunk.withStructure(ChunkStructure(sequenceNumber = 42, rootDocumentId = "d"))
            assertEquals(42, updated.structure.sequenceNumber)
            assertEquals("d", updated.structure.rootDocumentId)
            assertEquals(42, updated.metadata[ChunkStructure.SEQUENCE_NUMBER])
        }
    }

    @Nested
    inner class PropertiesToPersist {

        @Test
        fun `includes structural keys via metadata compat view`() {
            val chunk = Chunk(
                id = "chunk-1",
                text = "body",
                parentId = "section-1",
                metadata = mapOf(
                    "url" to "http://example.com",
                    ChunkStructure.CHUNK_INDEX to 0,
                    ChunkStructure.TOTAL_CHUNKS to 5,
                ),
            )
            val properties = chunk.propertiesToPersist()
            assertEquals(0, properties[ChunkStructure.CHUNK_INDEX])
            assertEquals(5, properties[ChunkStructure.TOTAL_CHUNKS])
            assertEquals("body", properties["text"])
        }
    }
}
