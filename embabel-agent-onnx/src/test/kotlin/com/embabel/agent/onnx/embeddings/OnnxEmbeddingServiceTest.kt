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
package com.embabel.agent.onnx.embeddings

import ai.djl.huggingface.tokenizers.Encoding
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class OnnxEmbeddingServiceTest {

    @Test
    fun `meanPool computes weighted average`() {
        val tokenEmbeddings = arrayOf(
            floatArrayOf(1.0f, 2.0f, 3.0f),
            floatArrayOf(4.0f, 5.0f, 6.0f),
            floatArrayOf(0.0f, 0.0f, 0.0f), // padding token
        )
        val attentionMask = longArrayOf(1, 1, 0) // only first two tokens are real

        val result = OnnxEmbeddingService.meanPool(tokenEmbeddings, attentionMask)

        assertEquals(3, result.size)
        assertEquals(2.5f, result[0], 1e-6f)
        assertEquals(3.5f, result[1], 1e-6f)
        assertEquals(4.5f, result[2], 1e-6f)
    }

    @Test
    fun `meanPool handles single token`() {
        val tokenEmbeddings = arrayOf(
            floatArrayOf(1.0f, 2.0f),
        )
        val attentionMask = longArrayOf(1)

        val result = OnnxEmbeddingService.meanPool(tokenEmbeddings, attentionMask)

        assertEquals(1.0f, result[0], 1e-6f)
        assertEquals(2.0f, result[1], 1e-6f)
    }

    @Test
    fun `meanPool handles all-zero mask gracefully`() {
        val tokenEmbeddings = arrayOf(
            floatArrayOf(1.0f, 2.0f),
        )
        val attentionMask = longArrayOf(0)

        val result = OnnxEmbeddingService.meanPool(tokenEmbeddings, attentionMask)

        // Should return zero vector (no division by zero)
        assertEquals(0.0f, result[0])
        assertEquals(0.0f, result[1])
    }

    @Test
    fun `constants are correct`() {
        assertEquals("onnx", OnnxEmbeddingService.PROVIDER)
        assertEquals("all-MiniLM-L6-v2", OnnxEmbeddingService.DEFAULT_MODEL_NAME)
        assertEquals(384, OnnxEmbeddingService.DEFAULT_DIMENSIONS)
    }

    @Test
    fun `meanPool with uniform attention mask returns simple average`() {
        val tokenEmbeddings = arrayOf(
            floatArrayOf(2.0f, 4.0f),
            floatArrayOf(6.0f, 8.0f),
            floatArrayOf(10.0f, 12.0f),
        )
        val attentionMask = longArrayOf(1, 1, 1)

        val result = OnnxEmbeddingService.meanPool(tokenEmbeddings, attentionMask)

        assertEquals(6.0f, result[0], 1e-6f)
        assertEquals(8.0f, result[1], 1e-6f)
    }

    @Test
    fun `meanPool with partial mask ignores padding tokens`() {
        val tokenEmbeddings = arrayOf(
            floatArrayOf(10.0f),
            floatArrayOf(20.0f),
            floatArrayOf(999.0f), // padding — should be ignored
        )
        val attentionMask = longArrayOf(1, 1, 0)

        val result = OnnxEmbeddingService.meanPool(tokenEmbeddings, attentionMask)

        assertEquals(15.0f, result[0], 1e-6f)
    }

    @Nested
    inner class EmbedWithMocksTest {

        private val environment = OrtEnvironment.getEnvironment()

        private fun createService(
            session: OrtSession,
            tokenizer: HuggingFaceTokenizer,
        ): OnnxEmbeddingService {
            return OnnxEmbeddingService(
                environment = environment,
                session = session,
                tokenizer = tokenizer,
                dimensions = 3,
                name = "test-model",
            )
        }

        private fun mockEncoding(
            ids: LongArray,
            attentionMask: LongArray,
            typeIds: LongArray,
        ): Encoding {
            val encoding = mockk<Encoding>()
            every { encoding.ids } returns ids
            every { encoding.attentionMask } returns attentionMask
            every { encoding.typeIds } returns typeIds
            return encoding
        }

        @Test
        fun `embed tokenizes and runs session then mean-pools`() {
            val tokenizer = mockk<HuggingFaceTokenizer>()
            val session = mockk<OrtSession>()

            // 2 tokens, 3 dimensions
            val ids = longArrayOf(101, 102)
            val mask = longArrayOf(1, 1)
            val typeIds = longArrayOf(0, 0)
            every { tokenizer.encode("hello") } returns mockEncoding(ids, mask, typeIds)

            // last_hidden_state: batch=1, tokens=2, dims=3
            val hiddenState = arrayOf(
                arrayOf(
                    floatArrayOf(1.0f, 2.0f, 3.0f),
                    floatArrayOf(3.0f, 4.0f, 5.0f),
                )
            )
            val outputTensor = mockk<OnnxValue>()
            every { outputTensor.value } returns hiddenState

            val ortResult = mockk<OrtSession.Result>()
            every { ortResult[0] } returns outputTensor
            every { ortResult.close() } returns Unit
            every { session.run(any<Map<String, OnnxTensor>>()) } returns ortResult

            val service = createService(session, tokenizer)
            val embedding = service.embed("hello")

            assertEquals(3, embedding.size)
            // mean of [1,3]=2, [2,4]=3, [3,5]=4
            assertEquals(2.0f, embedding[0], 1e-6f)
            assertEquals(3.0f, embedding[1], 1e-6f)
            assertEquals(4.0f, embedding[2], 1e-6f)
        }

        @Test
        fun `embed batch delegates to single embed`() {
            val tokenizer = mockk<HuggingFaceTokenizer>()
            val session = mockk<OrtSession>()

            val ids = longArrayOf(101)
            val mask = longArrayOf(1)
            val typeIds = longArrayOf(0)

            every { tokenizer.encode(any<String>()) } returns mockEncoding(ids, mask, typeIds)

            val hiddenState = arrayOf(arrayOf(floatArrayOf(1.0f, 2.0f)))
            val outputTensor = mockk<OnnxValue>()
            every { outputTensor.value } returns hiddenState
            val ortResult = mockk<OrtSession.Result>()
            every { ortResult[0] } returns outputTensor
            every { ortResult.close() } returns Unit
            every { session.run(any<Map<String, OnnxTensor>>()) } returns ortResult

            val service = createService(session, tokenizer)
            val results = service.embed(listOf("a", "b", "c"))

            assertEquals(3, results.size)
            results.forEach { assertEquals(2, it.size) }
        }

        @Test
        fun `close closes session`() {
            val session = mockk<OrtSession>()
            val tokenizer = mockk<HuggingFaceTokenizer>()
            every { session.close() } returns Unit

            val service = createService(session, tokenizer)
            service.close()

            verify { session.close() }
        }

        @Test
        fun `provider returns onnx`() {
            val session = mockk<OrtSession>()
            val tokenizer = mockk<HuggingFaceTokenizer>()
            val service = createService(session, tokenizer)

            assertEquals("onnx", service.provider)
        }

        @Test
        fun `name and dimensions from constructor`() {
            val session = mockk<OrtSession>()
            val tokenizer = mockk<HuggingFaceTokenizer>()
            val service = createService(session, tokenizer)

            assertEquals("test-model", service.name)
            assertEquals(3, service.dimensions)
        }
    }
}
