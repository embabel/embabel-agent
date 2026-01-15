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

import com.embabel.agent.rag.ingestion.ChunkTransformer
import com.embabel.agent.rag.ingestion.ContentChunker
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class NavigableDocumentTest {

    @Nested
    inner class WithMetadataTest {

        @Test
        fun `withMetadata adds metadata to document with no existing metadata`() {
            val document = MaterializedDocument(
                id = "doc-1",
                uri = "http://example.com/doc",
                title = "Test Document",
                children = emptyList(),
            )

            val result = document.withMetadata(mapOf("tenantId" to "tenant-1", "ownerId" to "user-1"))

            assertEquals("tenant-1", result.metadata["tenantId"])
            assertEquals("user-1", result.metadata["ownerId"])
        }

        @Test
        fun `withMetadata merges with existing metadata`() {
            val document = MaterializedDocument(
                id = "doc-1",
                uri = "http://example.com/doc",
                title = "Test Document",
                children = emptyList(),
                metadata = mapOf("existingKey" to "existingValue", "category" to "reports"),
            )

            val result = document.withMetadata(mapOf("tenantId" to "tenant-1"))

            assertEquals("existingValue", result.metadata["existingKey"])
            assertEquals("reports", result.metadata["category"])
            assertEquals("tenant-1", result.metadata["tenantId"])
        }

        @Test
        fun `withMetadata new values take precedence over existing`() {
            val document = MaterializedDocument(
                id = "doc-1",
                uri = "http://example.com/doc",
                title = "Test Document",
                children = emptyList(),
                metadata = mapOf("category" to "old-category", "version" to "1.0"),
            )

            val result = document.withMetadata(mapOf("category" to "new-category"))

            assertEquals("new-category", result.metadata["category"])
            assertEquals("1.0", result.metadata["version"])
        }

        @Test
        fun `withMetadata returns new instance and does not modify original`() {
            val document = MaterializedDocument(
                id = "doc-1",
                uri = "http://example.com/doc",
                title = "Test Document",
                children = emptyList(),
                metadata = mapOf("original" to "value"),
            )

            val result = document.withMetadata(mapOf("added" to "newValue"))

            assertNotSame(document, result)
            assertFalse(document.metadata.containsKey("added"))
            assertTrue(result.metadata.containsKey("added"))
            assertTrue(result.metadata.containsKey("original"))
        }

        @Test
        fun `withMetadata with empty map returns equivalent document`() {
            val document = MaterializedDocument(
                id = "doc-1",
                uri = "http://example.com/doc",
                title = "Test Document",
                children = emptyList(),
                metadata = mapOf("existing" to "value"),
            )

            val result = document.withMetadata(emptyMap())

            assertEquals(document.metadata, result.metadata)
            assertEquals(document.id, result.id)
            assertEquals(document.uri, result.uri)
        }

        @Test
        fun `withMetadata preserves all other document properties`() {
            val children = listOf(
                LeafSection(id = "leaf-1", title = "Section 1", text = "Content 1")
            )
            val document = MaterializedDocument(
                id = "doc-1",
                uri = "http://example.com/doc",
                title = "Test Document",
                children = children,
                metadata = mapOf("original" to "value"),
            )

            val result = document.withMetadata(mapOf("tenantId" to "tenant-1"))

            assertEquals(document.id, result.id)
            assertEquals(document.uri, result.uri)
            assertEquals(document.title, result.title)
            assertEquals(document.ingestionTimestamp, result.ingestionTimestamp)
            assertEquals(document.children, (result as MaterializedDocument).children)
        }
    }

    @Nested
    inner class MetadataPropagationToChunksTest {

        private val chunker = ContentChunker(ContentChunker.Config(), ChunkTransformer.NO_OP)

        @Test
        fun `document metadata propagates to chunks during chunking`() {
            val document = MaterializedDocument(
                id = "doc-1",
                uri = "http://example.com/doc",
                title = "Test Document",
                children = listOf(
                    LeafSection(id = "leaf-1", title = "Section", text = "Some content here")
                ),
            ).withMetadata(mapOf("tenantId" to "tenant-1", "ownerId" to "user-1"))

            val chunks = chunker.chunk(document).toList()

            assertTrue(chunks.isNotEmpty())
            chunks.forEach { chunk ->
                assertEquals("tenant-1", chunk.metadata["tenantId"])
                assertEquals("user-1", chunk.metadata["ownerId"])
            }
        }

        @Test
        fun `merged metadata propagates to chunks`() {
            val document = MaterializedDocument(
                id = "doc-1",
                uri = "http://example.com/doc",
                title = "Test Document",
                children = listOf(
                    LeafSection(id = "leaf-1", title = "Section", text = "Some content here")
                ),
                metadata = mapOf("category" to "reports"),
            ).withMetadata(mapOf("tenantId" to "tenant-1"))

            val chunks = chunker.chunk(document).toList()

            assertTrue(chunks.isNotEmpty())
            chunks.forEach { chunk ->
                assertEquals("reports", chunk.metadata["category"])
                assertEquals("tenant-1", chunk.metadata["tenantId"])
            }
        }
    }
}
