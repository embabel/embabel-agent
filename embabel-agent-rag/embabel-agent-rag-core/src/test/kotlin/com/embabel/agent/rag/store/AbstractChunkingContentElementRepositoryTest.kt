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

import com.embabel.agent.rag.ingestion.ChunkTransformer
import com.embabel.agent.rag.ingestion.ContentChunker
import com.embabel.agent.rag.model.DefaultMaterializedContainerSection
import com.embabel.agent.rag.model.LeafSection
import com.embabel.agent.rag.model.MaterializedDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AbstractChunkingContentElementRepositoryTest {

    @Nested
    inner class DocumentMetadataPropagationTests {

        @Test
        fun `writeAndChunkDocument propagates root metadata to chunks`() {
            val config = ContentChunker.Config()
            val repo = TestTextOnlyChunkingRepository(config, ChunkTransformer.NO_OP)

            val document = MaterializedDocument(
                id = "doc-1",
                uri = "http://example.com/doc",
                title = "Test Document",
                children = listOf(
                    LeafSection(id = "leaf-1", title = "Section", text = "Some content here")
                ),
            ).withMetadata(mapOf("context" to "user1_personal", "ingestedBy" to "user1"))

            repo.writeAndChunkDocument(document)

            assertTrue(repo.persistedChunks.isNotEmpty(), "Should have chunks")
            repo.persistedChunks.forEach { chunk ->
                assertEquals("user1_personal", chunk.metadata["context"],
                    "Chunk should inherit root document context metadata")
                assertEquals("user1", chunk.metadata["ingestedBy"],
                    "Chunk should inherit root document ingestedBy metadata")
            }
        }

        @Test
        fun `writeAndChunkDocument propagates root metadata to chunks with nested sections`() {
            // Use a small maxChunkSize to force individual leaf chunking (not the combined container path)
            val config = ContentChunker.Config(maxChunkSize = 300, overlapSize = 50)
            val repo = TestTextOnlyChunkingRepository(config, ChunkTransformer.NO_OP)

            val longContent = "This is a detailed paragraph about the topic. ".repeat(20)
            val document = MaterializedDocument(
                id = "doc-1",
                uri = "http://example.com/doc",
                title = "Test Document",
                children = listOf(
                    DefaultMaterializedContainerSection(
                        id = "section-1",
                        title = "Chapter 1",
                        children = listOf(
                            LeafSection(id = "leaf-1", title = "Subsection A", text = longContent),
                            LeafSection(id = "leaf-2", title = "Subsection B", text = longContent),
                        ),
                    )
                ),
            ).withMetadata(mapOf("context" to "user1_personal", "ingestedBy" to "user1"))

            repo.writeAndChunkDocument(document)

            assertTrue(repo.persistedChunks.size > 1, "Should have multiple chunks due to size limit")
            repo.persistedChunks.forEach { chunk ->
                assertEquals("user1_personal", chunk.metadata["context"],
                    "Chunk from nested section should inherit root document context metadata")
                assertEquals("user1", chunk.metadata["ingestedBy"],
                    "Chunk from nested section should inherit root document ingestedBy metadata")
            }
        }

        @Test
        fun `chunk-specific metadata takes precedence over root metadata`() {
            val config = ContentChunker.Config()
            val repo = TestTextOnlyChunkingRepository(config, ChunkTransformer.NO_OP)

            val document = MaterializedDocument(
                id = "doc-1",
                uri = "http://example.com/doc",
                title = "Test Document",
                children = listOf(
                    LeafSection(id = "leaf-1", title = "Section", text = "Some content here")
                ),
            ).withMetadata(mapOf("context" to "user1_personal"))

            repo.writeAndChunkDocument(document)

            assertTrue(repo.persistedChunks.isNotEmpty(), "Should have chunks")
            repo.persistedChunks.forEach { chunk ->
                // chunk_index is set by the chunker and should not be overridden by root metadata
                assertNotNull(chunk.metadata["chunk_index"],
                    "Chunk-specific metadata like chunk_index should be preserved")
                assertEquals("user1_personal", chunk.metadata["context"],
                    "Root metadata should be present")
            }
        }
    }

}
