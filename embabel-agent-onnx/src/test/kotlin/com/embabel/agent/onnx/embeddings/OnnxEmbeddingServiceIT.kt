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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.measureTimeMillis
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * IT test that downloads the real all-MiniLM-L6-v2 model and runs inference.
 * Excluded from surefire (CI) by naming convention (*IT).
 *
 * Clears the cache and downloads fresh on each run to verify the full
 * download-and-cache pipeline end to end, including HTTP redirect handling.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OnnxEmbeddingServiceIT {

    companion object {
        private const val HF_BASE = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main"
        private const val MODEL_URI = "$HF_BASE/onnx/model.onnx"
        private const val TOKENIZER_URI = "$HF_BASE/tokenizer.json"
        private val cacheDir = Path.of(System.getProperty("user.home"), ".embabel/models/all-MiniLM-L6-v2")
    }

    @BeforeAll
    fun clearCache() {
        if (Files.exists(cacheDir)) {
            Files.walk(cacheDir).use { paths ->
                paths.sorted(Comparator.reverseOrder())
                    .forEach(Files::delete)
            }
        }
    }

    @Test
    fun `download, cache, and embed produces 384-dimensional vector`() {
        val firstMs = measureTimeMillis {
            OnnxModelLoader.resolve(MODEL_URI, cacheDir, "model.onnx")
        }
        val secondMs = measureTimeMillis {
            OnnxModelLoader.resolve(MODEL_URI, cacheDir, "model.onnx")
        }
        assertTrue(secondMs < firstMs / 10, "Cached resolve ($secondMs ms) should be <10x faster than download ($firstMs ms)")

        val modelPath = cacheDir.resolve("model.onnx")
        val tokenizerPath = OnnxModelLoader.resolve(TOKENIZER_URI, cacheDir, "tokenizer.json")

        OnnxEmbeddingService.create(modelPath, tokenizerPath).use { service ->
            val embedding = service.embed("Hello world")
            assertEquals(384, embedding.size)
            assertTrue(embedding.any { it != 0.0f })
        }
    }

    @Test
    fun `similar texts produce similar embeddings`() {
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
