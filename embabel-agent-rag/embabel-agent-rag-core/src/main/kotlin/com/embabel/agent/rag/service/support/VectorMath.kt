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

import kotlin.math.sqrt

/**
 * Helper functions for vector math operations used in similarity search.
 */
object VectorMath {

    /**
     * Calculate cosine similarity between two vectors.
     * @return similarity score between 0.0 and 1.0, or 0.0 if vectors have different sizes
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0

        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0

        for (i in a.indices) {
            dotProduct += (a[i] * b[i]).toDouble()
            normA += (a[i] * a[i]).toDouble()
            normB += (b[i] * b[i]).toDouble()
        }

        return if (normA == 0.0 || normB == 0.0) 0.0 else dotProduct / (sqrt(normA) * sqrt(normB))
    }

    /**
     * Convert a float array to a byte array for storage.
     */
    fun floatArrayToBytes(floatArray: FloatArray): ByteArray {
        val bytes = ByteArray(floatArray.size * 4)
        var index = 0
        for (f in floatArray) {
            val bits = java.lang.Float.floatToIntBits(f)
            bytes[index++] = (bits shr 24).toByte()
            bytes[index++] = (bits shr 16).toByte()
            bytes[index++] = (bits shr 8).toByte()
            bytes[index++] = bits.toByte()
        }
        return bytes
    }

    /**
     * Convert a byte array back to a float array.
     */
    fun bytesToFloatArray(bytes: ByteArray): FloatArray {
        val floats = FloatArray(bytes.size / 4)
        var index = 0
        for (i in floats.indices) {
            val bits = ((bytes[index++].toInt() and 0xFF) shl 24) or
                    ((bytes[index++].toInt() and 0xFF) shl 16) or
                    ((bytes[index++].toInt() and 0xFF) shl 8) or
                    (bytes[index++].toInt() and 0xFF)
            floats[i] = java.lang.Float.intBitsToFloat(bits)
        }
        return floats
    }
}
