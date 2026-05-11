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
package com.embabel.common.test.ai

import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingOptions
import org.springframework.ai.embedding.EmbeddingRequest
import kotlin.test.assertEquals

/**
 * Unit tests for [FakeEmbeddingModel].
 */
class FakeEmbeddingModelTest {

    @Test
    fun `embed document returns vector with configured dimensions`() {
        // Arrange
        val model = FakeEmbeddingModel(dimensions = 8)

        // Act
        val embedding = model.embed(Document("hello"))

        // Assert
        assertEquals(8, embedding.size)
    }

    @Test
    fun `embed texts returns one vector per input with configured dimensions`() {
        // Arrange
        val model = FakeEmbeddingModel(dimensions = 8)

        // Act
        val embeddings = model.embed(listOf("alpha", "beta", "gamma"))

        // Assert
        assertEquals(3, embeddings.size)
        embeddings.forEach { assertEquals(8, it.size) }
    }

    @Test
    fun `call returns one indexed embedding per instruction`() {
        // Arrange
        val model = FakeEmbeddingModel(dimensions = 8)
        val request = EmbeddingRequest(listOf("alpha", "beta"), EmbeddingOptions.builder().build())

        // Act
        val response = model.call(request)

        // Assert
        assertEquals(2, response.results.size)
        assertEquals(0, response.results[0].index)
        assertEquals(1, response.results[1].index)
        response.results.forEach { assertEquals(8, it.output.size) }
    }
}
