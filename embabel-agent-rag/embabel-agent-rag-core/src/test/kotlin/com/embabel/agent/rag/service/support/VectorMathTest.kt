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
package com.embabel.agent.rag.service.support

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.abs

class VectorMathTest {

    @Nested
    inner class CosineSimilarityTest {

        @Test
        fun `identical vectors have similarity of 1`() {
            val a = floatArrayOf(1f, 2f, 3f)
            val b = floatArrayOf(1f, 2f, 3f)

            val similarity = VectorMath.cosineSimilarity(a, b)

            assertEquals(1.0, similarity, 0.0001)
        }

        @Test
        fun `orthogonal vectors have similarity of 0`() {
            val a = floatArrayOf(1f, 0f, 0f)
            val b = floatArrayOf(0f, 1f, 0f)

            val similarity = VectorMath.cosineSimilarity(a, b)

            assertEquals(0.0, similarity, 0.0001)
        }

        @Test
        fun `opposite vectors have similarity of -1`() {
            val a = floatArrayOf(1f, 2f, 3f)
            val b = floatArrayOf(-1f, -2f, -3f)

            val similarity = VectorMath.cosineSimilarity(a, b)

            assertEquals(-1.0, similarity, 0.0001)
        }

        @Test
        fun `similar vectors have high positive similarity`() {
            val a = floatArrayOf(1f, 0f, 0f)
            val b = floatArrayOf(0.9f, 0.1f, 0f)

            val similarity = VectorMath.cosineSimilarity(a, b)

            assertTrue(similarity > 0.9)
            assertTrue(similarity < 1.0)
        }

        @Test
        fun `different size vectors return 0`() {
            val a = floatArrayOf(1f, 2f, 3f)
            val b = floatArrayOf(1f, 2f)

            val similarity = VectorMath.cosineSimilarity(a, b)

            assertEquals(0.0, similarity)
        }

        @Test
        fun `zero vector returns 0`() {
            val a = floatArrayOf(0f, 0f, 0f)
            val b = floatArrayOf(1f, 2f, 3f)

            val similarity = VectorMath.cosineSimilarity(a, b)

            assertEquals(0.0, similarity)
        }

        @Test
        fun `both zero vectors return 0`() {
            val a = floatArrayOf(0f, 0f, 0f)
            val b = floatArrayOf(0f, 0f, 0f)

            val similarity = VectorMath.cosineSimilarity(a, b)

            assertEquals(0.0, similarity)
        }

        @Test
        fun `empty vectors return 0`() {
            val a = floatArrayOf()
            val b = floatArrayOf()

            val similarity = VectorMath.cosineSimilarity(a, b)

            // Empty vectors have norm 0, so should return 0
            assertEquals(0.0, similarity)
        }

        @Test
        fun `similarity is commutative`() {
            val a = floatArrayOf(1f, 2f, 3f, 4f)
            val b = floatArrayOf(5f, 6f, 7f, 8f)

            val similarityAB = VectorMath.cosineSimilarity(a, b)
            val similarityBA = VectorMath.cosineSimilarity(b, a)

            assertEquals(similarityAB, similarityBA, 0.0001)
        }

        @Test
        fun `high dimensional vectors work correctly`() {
            val size = 1536 // OpenAI embedding size
            val a = FloatArray(size) { 1f }
            val b = FloatArray(size) { 1f }

            val similarity = VectorMath.cosineSimilarity(a, b)

            assertEquals(1.0, similarity, 0.0001)
        }
    }

    @Nested
    inner class ByteConversionTest {

        @Test
        fun `roundtrip conversion preserves values`() {
            val original = floatArrayOf(1.5f, -2.5f, 3.14159f, 0f, Float.MAX_VALUE, Float.MIN_VALUE)

            val bytes = VectorMath.floatArrayToBytes(original)
            val restored = VectorMath.bytesToFloatArray(bytes)

            assertArrayEquals(original, restored)
        }

        @Test
        fun `empty array roundtrip works`() {
            val original = floatArrayOf()

            val bytes = VectorMath.floatArrayToBytes(original)
            val restored = VectorMath.bytesToFloatArray(bytes)

            assertArrayEquals(original, restored)
        }

        @Test
        fun `single element roundtrip works`() {
            val original = floatArrayOf(42.0f)

            val bytes = VectorMath.floatArrayToBytes(original)
            val restored = VectorMath.bytesToFloatArray(bytes)

            assertArrayEquals(original, restored)
        }

        @Test
        fun `byte array has correct size`() {
            val floats = floatArrayOf(1f, 2f, 3f)

            val bytes = VectorMath.floatArrayToBytes(floats)

            assertEquals(floats.size * 4, bytes.size)
        }

        @Test
        fun `special float values are preserved`() {
            val original = floatArrayOf(
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,
                Float.NaN,
                0f,
                -0f
            )

            val bytes = VectorMath.floatArrayToBytes(original)
            val restored = VectorMath.bytesToFloatArray(bytes)

            assertEquals(original.size, restored.size)
            assertEquals(Float.POSITIVE_INFINITY, restored[0])
            assertEquals(Float.NEGATIVE_INFINITY, restored[1])
            assertTrue(restored[2].isNaN())
            assertEquals(0f, restored[3])
        }

        @Test
        fun `high dimensional array roundtrip works`() {
            val size = 1536
            val original = FloatArray(size) { it.toFloat() / size }

            val bytes = VectorMath.floatArrayToBytes(original)
            val restored = VectorMath.bytesToFloatArray(bytes)

            assertArrayEquals(original, restored)
        }
    }
}
