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

import org.junit.jupiter.api.Assertions.*
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
}
