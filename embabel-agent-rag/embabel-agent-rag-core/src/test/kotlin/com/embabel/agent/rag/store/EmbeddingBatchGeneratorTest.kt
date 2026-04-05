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

        every { embeddingService.embed(listOf("Text 1", "Text 2")) } returns
                listOf(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f))
        every { embeddingService.embed(listOf("Text 3", "Text 4")) } throws
                RuntimeException("API error")

        val embeddings = EmbeddingBatchGenerator.generateEmbeddingsInBatches(
            embeddingService = embeddingService,
            retrievables = chunks,
            batchSize = 2,
            logger = logger,
        )

        assertEquals(2, embeddings.size)
        assertTrue(embeddings.containsKey("chunk1"))
        assertTrue(embeddings.containsKey("chunk2"))
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
