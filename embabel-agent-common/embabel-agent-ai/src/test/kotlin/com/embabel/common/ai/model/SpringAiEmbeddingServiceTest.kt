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
package com.embabel.common.ai.model

import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import kotlin.test.assertEquals

class SpringAiEmbeddingServiceTest {

    /**
     * EmbeddingModel that tracks whether dimensions() was called
     * and throws if it is, simulating an unreachable endpoint.
     */
    private class UnreachableEmbeddingModel : EmbeddingModel {
        var dimensionsCalled = false

        override fun dimensions(): Int {
            dimensionsCalled = true
            throw RuntimeException("Embedding endpoint not available (simulated 404)")
        }

        override fun embed(document: Document): FloatArray =
            throw UnsupportedOperationException("Not implemented")

        override fun embed(texts: List<String>): MutableList<FloatArray> =
            throw UnsupportedOperationException("Not implemented")

        override fun call(request: EmbeddingRequest): EmbeddingResponse =
            throw UnsupportedOperationException("Not implemented")
    }

    /**
     * Simple EmbeddingModel that returns a known dimension.
     */
    private class FixedDimensionEmbeddingModel(private val dims: Int) : EmbeddingModel {
        override fun dimensions(): Int = dims

        override fun embed(document: Document): FloatArray =
            throw UnsupportedOperationException("Not implemented")

        override fun embed(texts: List<String>): MutableList<FloatArray> =
            throw UnsupportedOperationException("Not implemented")

        override fun call(request: EmbeddingRequest): EmbeddingResponse =
            throw UnsupportedOperationException("Not implemented")
    }

    @Test
    fun `dimensions uses configured value when provided`() {
        val model = UnreachableEmbeddingModel()
        val service = SpringAiEmbeddingService(
            name = "test-model",
            model = model,
            provider = "TestProvider",
            configuredDimensions = 1536,
        )

        assertEquals(1536, service.dimensions)
        assertEquals(false, model.dimensionsCalled)
    }

    @Test
    fun `dimensions falls back to model when not configured`() {
        val service = SpringAiEmbeddingService(
            name = "test-model",
            model = FixedDimensionEmbeddingModel(3072),
            provider = "TestProvider",
        )

        assertEquals(3072, service.dimensions)
    }
}
