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

import com.embabel.agent.onnx.OnnxModelLoader
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * IT test that downloads the real all-MiniLM-L6-v2 model and runs inference.
 * Excluded from surefire (CI) by naming convention (*IT).
 * Runs locally where the model is cached in ~/.embabel/models/ after first download (~80MB).
 */
class OnnxEmbeddingServiceIT {

    companion object {
        private const val HF_BASE = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main"
        private const val MODEL_URI = "$HF_BASE/onnx/model.onnx"
        private const val TOKENIZER_URI = "$HF_BASE/tokenizer.json"
    }

    @Test
    fun `embed produces 384-dimensional vector`() {
        val cacheDir = Path.of(System.getProperty("user.home"), ".embabel/models/all-MiniLM-L6-v2")
        val modelPath = OnnxModelLoader.resolve(MODEL_URI, cacheDir, "model.onnx")
        val tokenizerPath = OnnxModelLoader.resolve(TOKENIZER_URI, cacheDir, "tokenizer.json")

        OnnxEmbeddingService.create(modelPath, tokenizerPath).use { service ->
            val embedding = service.embed("Hello world")
            assertEquals(384, embedding.size)
            assertTrue(embedding.any { it != 0.0f })
        }
    }

    @Test
    fun `similar texts produce similar embeddings`() {
        val cacheDir = Path.of(System.getProperty("user.home"), ".embabel/models/all-MiniLM-L6-v2")
        val modelPath = OnnxModelLoader.resolve(MODEL_URI, cacheDir, "model.onnx")
        val tokenizerPath = OnnxModelLoader.resolve(TOKENIZER_URI, cacheDir, "tokenizer.json")

        OnnxEmbeddingService.create(modelPath, tokenizerPath).use { service ->
            val a = service.embed("The cat sat on the mat")
            val b = service.embed("A cat was sitting on a mat")
            val c = service.embed("Quantum chromodynamics describes the strong force")

            val similarCosine = cosineSimilarity(a, b)
            val dissimilarCosine = cosineSimilarity(a, c)

            assertTrue(similarCosine > dissimilarCosine, "Similar sentences should have higher cosine similarity")
            assertTrue(similarCosine > 0.8f, "Similar sentences should be above 0.8 similarity")
        }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return dot / (Math.sqrt(normA.toDouble()).toFloat() * Math.sqrt(normB.toDouble()).toFloat())
    }
}
