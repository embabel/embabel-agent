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
package com.embabel.common.ai.autoconfig

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RegisteredModelTest {

    @Test
    fun `should create RegisteredModel with beanName and modelId`() {
        // Arrange & Act
        val model = RegisteredModel(
            beanName = "gpt4Bean",
            modelId = "gpt-4"
        )

        // Assert
        assertEquals("gpt4Bean", model.beanName)
        assertEquals("gpt-4", model.modelId)
    }

    @Test
    fun `should support copy with modified beanName`() {
        // Arrange
        val original = RegisteredModel(
            beanName = "originalBean",
            modelId = "model-1"
        )

        // Act
        val modified = original.copy(beanName = "modifiedBean")

        // Assert
        assertEquals("modifiedBean", modified.beanName)
        assertEquals("originalBean", original.beanName)
        assertEquals("model-1", modified.modelId)
    }

    @Test
    fun `should support copy with modified modelId`() {
        // Arrange
        val original = RegisteredModel(
            beanName = "modelBean",
            modelId = "model-1"
        )

        // Act
        val modified = original.copy(modelId = "model-2")

        // Assert
        assertEquals("model-2", modified.modelId)
        assertEquals("model-1", original.modelId)
        assertEquals("modelBean", modified.beanName)
    }

    @Test
    fun `should support equality`() {
        // Arrange
        val model1 = RegisteredModel(
            beanName = "bean1",
            modelId = "model-id-1"
        )
        val model2 = RegisteredModel(
            beanName = "bean1",
            modelId = "model-id-1"
        )
        val model3 = RegisteredModel(
            beanName = "bean2",
            modelId = "model-id-1"
        )

        // Assert
        assertEquals(model1, model2)
        assertNotEquals(model1, model3)
    }

    @Test
    fun `should handle different naming conventions for bean names`() {
        // Arrange & Act
        val camelCase = RegisteredModel("myModelBean", "model-1")
        val snakeCase = RegisteredModel("my_model_bean", "model-2")
        val kebabCase = RegisteredModel("my-model-bean", "model-3")

        // Assert
        assertEquals("myModelBean", camelCase.beanName)
        assertEquals("my_model_bean", snakeCase.beanName)
        assertEquals("my-model-bean", kebabCase.beanName)
    }

    @Test
    fun `should handle different model ID formats`() {
        // Arrange & Act
        val withDashes = RegisteredModel("bean1", "gpt-4-turbo")
        val withDots = RegisteredModel("bean2", "claude.3.5")
        val withUnderscores = RegisteredModel("bean3", "llama_3_70b")

        // Assert
        assertEquals("gpt-4-turbo", withDashes.modelId)
        assertEquals("claude.3.5", withDots.modelId)
        assertEquals("llama_3_70b", withUnderscores.modelId)
    }

    @Test
    fun `should handle long model IDs`() {
        // Arrange
        val longModelId = "very-long-model-identifier-with-many-segments-and-version-numbers-v1.2.3"

        // Act
        val model = RegisteredModel("bean", longModelId)

        // Assert
        assertEquals(longModelId, model.modelId)
    }

    @Test
    fun `should create model for LLM service`() {
        // Arrange & Act
        val llmModel = RegisteredModel(
            beanName = "openaiGpt4",
            modelId = "gpt-4-1106-preview"
        )

        // Assert
        assertEquals("openaiGpt4", llmModel.beanName)
        assertEquals("gpt-4-1106-preview", llmModel.modelId)
    }

    @Test
    fun `should create model for embedding service`() {
        // Arrange & Act
        val embeddingModel = RegisteredModel(
            beanName = "openaiEmbedding",
            modelId = "text-embedding-ada-002"
        )

        // Assert
        assertEquals("openaiEmbedding", embeddingModel.beanName)
        assertEquals("text-embedding-ada-002", embeddingModel.modelId)
    }

    @Test
    fun `should handle models from different providers`() {
        // Arrange & Act
        val openai = RegisteredModel("openaiGpt4", "gpt-4")
        val anthropic = RegisteredModel("anthropicClaude", "claude-3-opus")
        val mistral = RegisteredModel("mistralLarge", "mistral-large-latest")

        // Assert
        assertEquals("gpt-4", openai.modelId)
        assertEquals("claude-3-opus", anthropic.modelId)
        assertEquals("mistral-large-latest", mistral.modelId)
    }

    @Test
    fun `should create model with special characters in bean name`() {
        // Arrange & Act
        val model = RegisteredModel(
            beanName = "model_with_underscores_123",
            modelId = "model-id"
        )

        // Assert
        assertEquals("model_with_underscores_123", model.beanName)
    }
}
