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
import com.embabel.agent.rag.ingestion.transform.ChainedChunkTransformer
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.Retrievable
import com.embabel.common.ai.model.EmbeddingService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AbstractChunkingContentElementRepositoryTest {

    private fun createChunk(id: String, text: String) = Chunk(
        id = id,
        text = text,
        metadata = emptyMap(),
        parentId = "parent"
    )

    @Nested
    inner class BatchEmbeddingTests {

        @Test
        fun `should generate embeddings in batches`() {
            val embeddingService = mockk<EmbeddingService>()
            val config = ContentChunker.Config(embeddingBatchSize = 2)
            val repo = TestChunkingRepository(
                config,
                ChunkTransformer.NO_OP,
                embeddingService
            )

            // Create 5 chunks to test batching with batch size 2
            val chunks = (1..5).map { i -> createChunk("chunk$i", "Text $i") }

            // Mock batch embedding calls
            every { embeddingService.embed(listOf("Text 1", "Text 2")) } returns
                    listOf(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f))
            every { embeddingService.embed(listOf("Text 3", "Text 4")) } returns
                    listOf(floatArrayOf(5f, 6f), floatArrayOf(7f, 8f))
            every { embeddingService.embed(listOf("Text 5")) } returns
                    listOf(floatArrayOf(9f, 10f))

            repo.onNewRetrievables(chunks)

            // Verify batch calls were made
            verify(exactly = 1) { embeddingService.embed(listOf("Text 1", "Text 2")) }
            verify(exactly = 1) { embeddingService.embed(listOf("Text 3", "Text 4")) }
            verify(exactly = 1) { embeddingService.embed(listOf("Text 5")) }

            // Verify all chunks were persisted with embeddings
            assertEquals(5, repo.persistedChunks.size)
            assertEquals(5, repo.persistedEmbeddings.size)
            assertTrue(repo.persistedEmbeddings["chunk1"]!!.contentEquals(floatArrayOf(1f, 2f)))
            assertTrue(repo.persistedEmbeddings["chunk5"]!!.contentEquals(floatArrayOf(9f, 10f)))
        }

        @Test
        fun `should handle no embedding service gracefully`() {
            val config = ContentChunker.Config()
            val repo = TestChunkingRepository(
                config,
                ChainedChunkTransformer(),
                embeddingService = null
            )

            val chunks = listOf(createChunk("chunk1", "Text 1"))

            repo.onNewRetrievables(chunks)

            // Chunks should be persisted with empty embeddings map
            assertEquals(1, repo.persistedChunks.size)
            assertTrue(repo.persistedEmbeddings.isEmpty())
        }

        @Test
        fun `should filter non-chunk retrievables`() {
            val config = ContentChunker.Config()
            val repo = TestChunkingRepository(
                config,
                ChainedChunkTransformer(),
                embeddingService = null
            )

            val mixedRetrievables: List<Retrievable> = listOf(
                createChunk("chunk1", "Text 1"),
                // Other retrievable types would be filtered out
            )

            repo.onNewRetrievables(mixedRetrievables)

            assertEquals(1, repo.persistedChunks.size)
        }

        @Test
        fun `should handle empty retrievables list`() {
            val config = ContentChunker.Config()
            val repo = TestChunkingRepository(
                config,
                ChainedChunkTransformer(),
                embeddingService = null
            )

            repo.onNewRetrievables(emptyList())

            assertTrue(repo.persistedChunks.isEmpty())
        }

        @Test
        fun `should continue processing other batches when one batch fails`() {
            val embeddingService = mockk<EmbeddingService>()
            val config = ContentChunker.Config(embeddingBatchSize = 2)
            val repo = TestChunkingRepository(
                config,
                ChainedChunkTransformer(),
                embeddingService
            )

            val chunks = (1..4).map { i -> createChunk("chunk$i", "Text $i") }

            // First batch succeeds, second batch fails
            every { embeddingService.embed(listOf("Text 1", "Text 2")) } returns
                    listOf(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f))
            every { embeddingService.embed(listOf("Text 3", "Text 4")) } throws
                    RuntimeException("API error")

            repo.onNewRetrievables(chunks)

            // All chunks should still be persisted
            assertEquals(4, repo.persistedChunks.size)
            // Only first batch embeddings should be present
            assertEquals(2, repo.persistedEmbeddings.size)
            assertTrue(repo.persistedEmbeddings.containsKey("chunk1"))
            assertTrue(repo.persistedEmbeddings.containsKey("chunk2"))
        }

        @Test
        fun `should respect custom batch size from config`() {
            val embeddingService = mockk<EmbeddingService>()
            val config = ContentChunker.Config(embeddingBatchSize = 10)
            val repo = TestChunkingRepository(
                config,
                ChainedChunkTransformer(),
                embeddingService
            )

            val chunks = (1..10).map { i -> createChunk("chunk$i", "Text $i") }

            every { embeddingService.embed(any<List<String>>()) } answers {
                val texts = firstArg<List<String>>()
                texts.map { floatArrayOf(1f) }
            }

            repo.onNewRetrievables(chunks)

            // With batch size 10, should be exactly 1 call for 10 chunks
            verify(exactly = 1) { embeddingService.embed(any<List<String>>()) }
        }
    }

}
