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
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.Retrievable
import com.embabel.common.ai.model.EmbeddingService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [EmbeddingAwareChunkingContentElementRepository].
 *
 * Batch embedding behavior (batch sizes, failure resilience) is tested in
 * [EmbeddingBatchGeneratorTest]. These tests verify the repository's integration:
 * filtering, delegation to the generator, and persistence.
 */
class EmbeddingAwareChunkingContentElementRepositoryTest {

    private fun createChunk(id: String, text: String) = Chunk(
        id = id,
        text = text,
        metadata = emptyMap(),
        parentId = "parent"
    )

    @Test
    fun `should generate embeddings and delegate to persistChunksWithEmbeddings`() {
        val embeddingService = mockk<EmbeddingService>()
        val config = ContentChunker.Config()
        val repo = TestChunkingRepository(config, ChunkTransformer.NO_OP, embeddingService)

        val chunks = listOf(createChunk("chunk1", "Text 1"), createChunk("chunk2", "Text 2"))

        every { embeddingService.embed(any<List<String>>()) } returns
                listOf(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f))

        repo.onNewRetrievables(chunks)

        verify(exactly = 1) { embeddingService.embed(listOf("Text 1", "Text 2")) }
        assertEquals(2, repo.persistedChunks.size)
        assertEquals(2, repo.persistedEmbeddings.size)
        assertTrue(repo.persistedEmbeddings["chunk1"]!!.contentEquals(floatArrayOf(1f, 2f)))
        assertTrue(repo.persistedEmbeddings["chunk2"]!!.contentEquals(floatArrayOf(3f, 4f)))
    }

    @Test
    fun `should filter non-chunk retrievables`() {
        val embeddingService = mockk<EmbeddingService>()
        val config = ContentChunker.Config()
        val repo = TestChunkingRepository(config, ChunkTransformer.NO_OP, embeddingService)

        every { embeddingService.embed(any<List<String>>()) } returns
                listOf(floatArrayOf(1f))

        val mixedRetrievables: List<Retrievable> = listOf(
            createChunk("chunk1", "Text 1"),
        )

        repo.onNewRetrievables(mixedRetrievables)

        assertEquals(1, repo.persistedChunks.size)
    }

    @Test
    fun `should not call embedding service for empty retrievables`() {
        val embeddingService = mockk<EmbeddingService>()
        val config = ContentChunker.Config()
        val repo = TestChunkingRepository(config, ChunkTransformer.NO_OP, embeddingService)

        repo.onNewRetrievables(emptyList())

        verify(exactly = 0) { embeddingService.embed(any<List<String>>()) }
        assertTrue(repo.persistedChunks.isEmpty())
    }
}
