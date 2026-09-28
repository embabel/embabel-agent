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

import com.embabel.agent.rag.model.Chunk
import com.embabel.common.ai.model.EmbeddingService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class EmbeddingBatchGeneratorTest {

    private val logger = LoggerFactory.getLogger(EmbeddingBatchGeneratorTest::class.java)

    private fun createChunk(id: String, text: String) = Chunk(
        id = id,
        text = text,
        metadata = emptyMap(),
        parentId = "parent"
    )

    @Test
    fun `should generate embeddings in batches`() {
        val embeddingService = mockk<EmbeddingService>()

        val chunks = (1..5).map { i -> createChunk("chunk$i", "Text $i") }

        every { embeddingService.embed(listOf("Text 1", "Text 2")) } returns
                listOf(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f))
        every { embeddingService.embed(listOf("Text 3", "Text 4")) } returns
                listOf(floatArrayOf(5f, 6f), floatArrayOf(7f, 8f))
        every { embeddingService.embed(listOf("Text 5")) } returns
                listOf(floatArrayOf(9f, 10f))

        val embeddings = EmbeddingBatchGenerator.generateEmbeddingsInBatches(
            embeddingService = embeddingService,
            retrievables = chunks,
            batchSize = 2,
            logger = logger,
        )

        verify(exactly = 1) { embeddingService.embed(listOf("Text 1", "Text 2")) }
        verify(exactly = 1) { embeddingService.embed(listOf("Text 3", "Text 4")) }
        verify(exactly = 1) { embeddingService.embed(listOf("Text 5")) }

        assertEquals(5, embeddings.size)
        assertTrue(embeddings["chunk1"]!!.contentEquals(floatArrayOf(1f, 2f)))
        assertTrue(embeddings["chunk5"]!!.contentEquals(floatArrayOf(9f, 10f)))
    }

    @Test
    fun `should continue processing other batches when one batch fails`() {
        val embeddingService = mockk<EmbeddingService>()

        val chunks = (1..4).map { i -> createChunk("chunk$i", "Text $i") }

        every { embeddingService.embed(any<List<String>>()) } answers {
            val texts = firstArg<List<String>>()
            if ("Text 3" in texts || "Text 4" in texts) throw RuntimeException("API error")
            texts.map { floatArrayOf(1f) }
        }

        val result = EmbeddingBatchGenerator.embedInBatches(
            embeddingService = embeddingService,
            retrievables = chunks,
            batchSize = 2,
            logger = logger,
        )

        assertEquals(setOf("chunk1", "chunk2"), result.embeddings.keys)
        assertEquals(listOf("chunk3", "chunk4"), result.missingChunkIds)
        assertEquals("API error", result.cause?.message)
    }

    @Test
    fun `a batch that fails once is retried as two halves`() {
        val embeddingService = mockk<EmbeddingService>()
        val chunks = (1..4).map { i -> createChunk("chunk$i", "Text $i") }

        every { embeddingService.embed(any<List<String>>()) } answers {
            val texts = firstArg<List<String>>()
            if (texts.size == 4) throw RuntimeException("timeout")
            texts.map { floatArrayOf(1f) }
        }

        val result = EmbeddingBatchGenerator.embedInBatches(
            embeddingService = embeddingService,
            retrievables = chunks,
            batchSize = 4,
            logger = logger,
        )

        verify(exactly = 1) { embeddingService.embed(listOf("Text 1", "Text 2")) }
        verify(exactly = 1) { embeddingService.embed(listOf("Text 3", "Text 4")) }
        assertEquals(4, result.embeddings.size)
        assertTrue(result.missingChunkIds.isEmpty())
        assertNull(result.cause)
    }

    @Test
    fun `a chunk that always fails costs only itself`() {
        val embeddingService = mockk<EmbeddingService>()
        val chunks = (1..8).map { i -> createChunk("chunk$i", "Text $i") }
        val tooLong = IllegalArgumentException("input exceeds the model's token limit")

        every { embeddingService.embed(any<List<String>>()) } answers {
            val texts = firstArg<List<String>>()
            if ("Text 3" in texts) throw tooLong
            texts.map { floatArrayOf(1f) }
        }

        val result = EmbeddingBatchGenerator.embedInBatches(
            embeddingService = embeddingService,
            retrievables = chunks,
            batchSize = 8,
            logger = logger,
        )

        assertEquals(listOf("chunk3"), result.missingChunkIds)
        assertEquals(7, result.embeddings.size)
        assertSame(tooLong, result.cause)
    }

    @Test
    fun `the cause is the failure that left a chunk missing, not a later one a retry recovered from`() {
        val embeddingService = mockk<EmbeddingService>()
        val chunks = (1..4).map { i -> createChunk("chunk$i", "Text $i") }
        val tooLong = IllegalArgumentException("input exceeds the model's token limit")
        var timedOut = false

        every { embeddingService.embed(any<List<String>>()) } answers {
            val texts = firstArg<List<String>>()
            when {
                "Text 1" in texts -> throw tooLong
                "Text 3" in texts && !timedOut -> {
                    timedOut = true
                    throw RuntimeException("timeout")
                }

                else -> texts.map { floatArrayOf(1f) }
            }
        }

        val result = EmbeddingBatchGenerator.embedInBatches(
            embeddingService = embeddingService,
            retrievables = chunks,
            batchSize = 2,
            logger = logger,
        )

        assertEquals(listOf("chunk1"), result.missingChunkIds)
        assertEquals(setOf("chunk2", "chunk3", "chunk4"), result.embeddings.keys)
        assertSame(tooLong, result.cause)
    }

    @Test
    fun `two adjacent chunks that always fail do not abandon the rest`() {
        val embeddingService = mockk<EmbeddingService>()
        val chunks = (1..8).map { i -> createChunk("chunk$i", "Text $i") }

        every { embeddingService.embed(any<List<String>>()) } answers {
            val texts = firstArg<List<String>>()
            if ("Text 1" in texts || "Text 2" in texts) throw RuntimeException("rejected")
            texts.map { floatArrayOf(1f) }
        }

        val result = EmbeddingBatchGenerator.embedInBatches(
            embeddingService = embeddingService,
            retrievables = chunks,
            batchSize = 8,
            logger = logger,
        )

        // Five failures in a row: 8, 4, 2, then chunk1 and chunk2 alone
        assertEquals(listOf("chunk1", "chunk2"), result.missingChunkIds)
        assertEquals(6, result.embeddings.size)
    }

    @Test
    fun `a dead embedding service is abandoned after a bounded number of calls`() {
        val embeddingService = mockk<EmbeddingService>()
        val chunks = (1..1000).map { i -> createChunk("chunk$i", "Text $i") }

        every { embeddingService.embed(any<List<String>>()) } throws RuntimeException("connection refused")

        val result = EmbeddingBatchGenerator.embedInBatches(
            embeddingService = embeddingService,
            retrievables = chunks,
            batchSize = 100,
            logger = logger,
        )

        // Halving 100 down to 1 takes 7 steps, so the limit is 7 + 3 consecutive failed calls
        verify(exactly = 10) { embeddingService.embed(any<List<String>>()) }
        assertTrue(result.embeddings.isEmpty())
        assertEquals(chunks.map { it.id }, result.missingChunkIds)
    }

    @Test
    fun `a response with the wrong number of vectors is a failed batch`() {
        val embeddingService = mockk<EmbeddingService>()
        val chunks = (1..2).map { i -> createChunk("chunk$i", "Text $i") }

        every { embeddingService.embed(any<List<String>>()) } answers {
            val texts = firstArg<List<String>>()
            if (texts.size == 2) listOf(floatArrayOf(1f)) else texts.map { floatArrayOf(1f) }
        }

        val result = EmbeddingBatchGenerator.embedInBatches(
            embeddingService = embeddingService,
            retrievables = chunks,
            batchSize = 2,
            logger = logger,
        )

        assertEquals(setOf("chunk1", "chunk2"), result.embeddings.keys)
        assertTrue(result.missingChunkIds.isEmpty())
    }

    @Test
    fun `should respect custom batch size`() {
        val embeddingService = mockk<EmbeddingService>()

        val chunks = (1..10).map { i -> createChunk("chunk$i", "Text $i") }

        every { embeddingService.embed(any<List<String>>()) } answers {
            val texts = firstArg<List<String>>()
            texts.map { floatArrayOf(1f) }
        }

        EmbeddingBatchGenerator.generateEmbeddingsInBatches(
            embeddingService = embeddingService,
            retrievables = chunks,
            batchSize = 10,
            logger = logger,
        )

        verify(exactly = 1) { embeddingService.embed(any<List<String>>()) }
    }

    @Test
    fun `should return empty map for empty retrievables`() {
        val embeddingService = mockk<EmbeddingService>()

        val embeddings = EmbeddingBatchGenerator.generateEmbeddingsInBatches(
            embeddingService = embeddingService,
            retrievables = emptyList(),
            batchSize = 10,
            logger = logger,
        )

        assertTrue(embeddings.isEmpty())
    }
}
