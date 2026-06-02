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
package com.embabel.agent.config.models.mistralai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MistralAiModelDefinitionsTest {

    @Test
    fun `should create MistralAiModelDefinitions with empty models`() {
        // Arrange & Act
        val definitions = MistralAiModelDefinitions()

        // Assert
        assertTrue(definitions.models.isEmpty())
    }

    @Test
    fun `should create MistralAiModelDefinitions with models list`() {
        // Arrange
        val model1 = MistralAiModelDefinition(
            name = "mistral-large",
            modelId = "mistral-large-latest"
        )
        val model2 = MistralAiModelDefinition(
            name = "mistral-small",
            modelId = "mistral-small-latest"
        )

        // Act
        val definitions = MistralAiModelDefinitions(
            models = listOf(model1, model2)
        )

        // Assert
        assertEquals(2, definitions.models.size)
        assertEquals("mistral-large", definitions.models[0].name)
        assertEquals("mistral-small", definitions.models[1].name)
    }

    @Test
    fun `should support copy with modified models`() {
        // Arrange
        val original = MistralAiModelDefinitions(models = emptyList())
        val model = MistralAiModelDefinition(
            name = "new-model",
            modelId = "new-id"
        )

        // Act
        val modified = original.copy(models = listOf(model))

        // Assert
        assertEquals(1, modified.models.size)
        assertTrue(original.models.isEmpty())
    }

    @Test
    fun `should support equality`() {
        // Arrange
        val model = MistralAiModelDefinition(
            name = "model",
            modelId = "id"
        )
        val defs1 = MistralAiModelDefinitions(models = listOf(model))
        val defs2 = MistralAiModelDefinitions(models = listOf(model))
        val defs3 = MistralAiModelDefinitions(models = emptyList())

        // Assert
        assertEquals(defs1, defs2)
        assertNotEquals(defs1, defs3)
    }

    @Test
    fun `should implement LlmAutoConfigProvider interface`() {
        // Arrange & Act
        val definitions = MistralAiModelDefinitions()

        // Assert
        assertTrue(definitions is com.embabel.common.ai.autoconfig.LlmAutoConfigProvider<*>)
    }

    @Test
    fun `should support adding models to empty definitions`() {
        // Arrange
        val original = MistralAiModelDefinitions()
        val model = MistralAiModelDefinition(
            name = "model",
            modelId = "id"
        )

        // Act
        val updated = original.copy(models = original.models + model)

        // Assert
        assertEquals(1, updated.models.size)
        assertEquals("model", updated.models[0].name)
    }

    @Test
    fun `should support multiple models with different configurations`() {
        // Arrange
        val model1 = MistralAiModelDefinition(
            name = "mistral-large",
            modelId = "mistral-large-latest",
            maxTokens = 128000,
            temperature = 0.7
        )
        val model2 = MistralAiModelDefinition(
            name = "mistral-small",
            modelId = "mistral-small-latest",
            maxTokens = 32768,
            temperature = 1.0
        )

        // Act
        val definitions = MistralAiModelDefinitions(
            models = listOf(model1, model2)
        )

        // Assert
        assertEquals(2, definitions.models.size)
        assertEquals(128000, definitions.models[0].maxTokens)
        assertEquals(32768, definitions.models[1].maxTokens)
    }
}
