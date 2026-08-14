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

class ChunkStructureTest {

    @Nested
    inner class FromMetadata {

        @Test
        fun `empty map yields empty structure`() {
            assertEquals(ChunkStructure.EMPTY, ChunkStructure.fromMetadata(emptyMap()))
            assertTrue(ChunkStructure.fromMetadata(emptyMap()).isEmpty())
        }

        @Test
        fun `extracts structural keys and coerces numeric types`() {
            val structure = ChunkStructure.fromMetadata(
                mapOf(
                    ChunkStructure.ROOT_DOCUMENT_ID to "doc-1",
                    ChunkStructure.ROOT_DOCUMENT_TITLE to "Acme 10-K",
                    ChunkStructure.CONTAINER_SECTION_ID to "sec-1",
                    ChunkStructure.CONTAINER_SECTION_TITLE to "Intro",
                    ChunkStructure.CONTAINER_SECTION_URL to "https://example.com",
                    ChunkStructure.LEAF_SECTION_ID to "leaf-1",
                    ChunkStructure.LEAF_SECTION_TITLE to "Leaf",
                    ChunkStructure.LEAF_SECTION_URL to "https://example.com#leaf",
                    ChunkStructure.CHUNK_INDEX to 2,
                    ChunkStructure.TOTAL_CHUNKS to 5L,
                    ChunkStructure.SEQUENCE_NUMBER to "7",
                    "custom" to "keep-me",
                )
            )

            assertEquals("doc-1", structure.rootDocumentId)
            assertEquals("Acme 10-K", structure.rootDocumentTitle)
            assertEquals("sec-1", structure.containerSectionId)
            assertEquals("Intro", structure.containerSectionTitle)
            assertEquals("https://example.com", structure.containerSectionUrl)
            assertEquals("leaf-1", structure.leafSectionId)
            assertEquals("Leaf", structure.leafSectionTitle)
            assertEquals("https://example.com#leaf", structure.leafSectionUrl)
            assertEquals(2, structure.chunkIndex)
            assertEquals(5, structure.totalChunks)
            assertEquals(7, structure.sequenceNumber)
            assertFalse(structure.isEmpty())
        }

        @Test
        fun `withoutStructuralKeys drops only reserved keys`() {
            val original = mapOf(
                ChunkStructure.SEQUENCE_NUMBER to 1,
                "author" to "alice",
                ChunkStructure.ROOT_DOCUMENT_ID to "doc",
                ChunkStructure.ROOT_DOCUMENT_TITLE to "Acme 10-K",
            )
            val freeform = ChunkStructure.withoutStructuralKeys(original)
            assertEquals(mapOf("author" to "alice"), freeform)
        }
    }

    @Nested
    inner class ToMetadata {

        @Test
        fun `round trips non-null fields`() {
            val structure = ChunkStructure(
                rootDocumentId = "doc-1",
                rootDocumentTitle = "Acme 10-K",
                containerSectionId = "sec-1",
                sequenceNumber = 3,
                chunkIndex = 0,
                totalChunks = 1,
            )
            val asMeta = structure.toMetadata()
            assertEquals(structure, ChunkStructure.fromMetadata(asMeta))
            assertFalse(asMeta.containsKey(ChunkStructure.LEAF_SECTION_ID))
        }

        @Test
        fun `empty structure produces empty map`() {
            assertTrue(ChunkStructure.EMPTY.toMetadata().isEmpty())
        }
    }

    @Nested
    inner class Merge {

        @Test
        fun `prefers non-null values from other`() {
            val base = ChunkStructure(rootDocumentId = "doc", rootDocumentTitle = "Acme 10-K", sequenceNumber = 1)
            val other = ChunkStructure(sequenceNumber = 9, containerSectionId = "c1")
            val merged = base.merge(other)
            assertEquals("doc", merged.rootDocumentId)
            assertEquals("Acme 10-K", merged.rootDocumentTitle)
            assertEquals(9, merged.sequenceNumber)
            assertEquals("c1", merged.containerSectionId)
        }
    }
}
