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

import com.embabel.common.ai.model.PerTokenPricingModel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class MistralAiModelDefinitionTest {

    @Test
    fun `should create MistralAiModelDefinition with required parameters`() {
        // Arrange & Act
        val definition = MistralAiModelDefinition(
            name = "mistral-large",
            modelId = "mistral-large-latest"
        )

        // Assert
        assertEquals("mistral-large", definition.name)
        assertEquals("mistral-large-latest", definition.modelId)
        assertNull(definition.displayName)
        assertNull(definition.knowledgeCutoffDate)
        assertNull(definition.pricingModel)
        assertEquals(32768, definition.maxTokens)
        assertEquals(1.0, definition.temperature, 0.001)
        assertNull(definition.topP)
    }

    @Test
    fun `should create MistralAiModelDefinition with all parameters`() {
        // Arrange
        val cutoffDate = LocalDate.of(2024, 1, 1)
        val pricing = PerTokenPricingModel(
            usdPer1mInputTokens = 3.0,
            usdPer1mOutputTokens = 9.0
        )

        // Act
        val definition = MistralAiModelDefinition(
            name = "mistral-large",
            modelId = "mistral-large-latest",
            displayName = "Mistral Large",
            knowledgeCutoffDate = cutoffDate,
            pricingModel = pricing,
            maxTokens = 128000,
            temperature = 0.7,
            topP = 0.9
        )

        // Assert
        assertEquals("mistral-large", definition.name)
        assertEquals("mistral-large-latest", definition.modelId)
        assertEquals("Mistral Large", definition.displayName)
        assertEquals(cutoffDate, definition.knowledgeCutoffDate)
        assertEquals(pricing, definition.pricingModel)
        assertEquals(128000, definition.maxTokens)
        assertEquals(0.7, definition.temperature, 0.001)
        assertEquals(0.9, definition.topP)
    }

    @Test
    fun `should have default maxTokens of 32768`() {
        // Arrange & Act
        val definition = MistralAiModelDefinition(
            name = "mistral-small",
            modelId = "mistral-small-latest"
        )

        // Assert
        assertEquals(32768, definition.maxTokens)
    }

    @Test
    fun `should have default temperature of 1_0`() {
        // Arrange & Act
        val definition = MistralAiModelDefinition(
            name = "mistral-small",
            modelId = "mistral-small-latest"
        )

        // Assert
        assertEquals(1.0, definition.temperature, 0.001)
    }

    @Test
    fun `should support copy with modified name`() {
        // Arrange
        val original = MistralAiModelDefinition(
            name = "original",
            modelId = "model-id"
        )

        // Act
        val modified = original.copy(name = "modified")

        // Assert
        assertEquals("modified", modified.name)
        assertEquals("original", original.name)
    }

    @Test
    fun `should support copy with modified modelId`() {
        // Arrange
        val original = MistralAiModelDefinition(
            name = "model",
            modelId = "original-id"
        )

        // Act
        val modified = original.copy(modelId = "modified-id")

        // Assert
        assertEquals("modified-id", modified.modelId)
        assertEquals("original-id", original.modelId)
    }

    @Test
    fun `should support copy with modified maxTokens`() {
        // Arrange
        val original = MistralAiModelDefinition(
            name = "model",
            modelId = "id"
        )

        // Act
        val modified = original.copy(maxTokens = 64000)

        // Assert
        assertEquals(64000, modified.maxTokens)
        assertEquals(32768, original.maxTokens)
    }

    @Test
    fun `should support copy with modified temperature`() {
        // Arrange
        val original = MistralAiModelDefinition(
            name = "model",
            modelId = "id"
        )

        // Act
        val modified = original.copy(temperature = 0.5)

        // Assert
        assertEquals(0.5, modified.temperature, 0.001)
        assertEquals(1.0, original.temperature, 0.001)
    }

    @Test
    fun `should support copy with modified topP`() {
        // Arrange
        val original = MistralAiModelDefinition(
            name = "model",
            modelId = "id"
        )

        // Act
        val modified = original.copy(topP = 0.95)

        // Assert
        assertEquals(0.95, modified.topP)
        assertNull(original.topP)
    }

    @Test
    fun `should support equality`() {
        // Arrange
        val def1 = MistralAiModelDefinition(
            name = "model",
            modelId = "id"
        )
        val def2 = MistralAiModelDefinition(
            name = "model",
            modelId = "id"
        )
        val def3 = MistralAiModelDefinition(
            name = "different",
            modelId = "id"
        )

        // Assert
        assertEquals(def1, def2)
        assertNotEquals(def1, def3)
    }

    @Test
    fun `should implement LlmAutoConfigMetadata interface`() {
        // Arrange & Act
        val definition = MistralAiModelDefinition(
            name = "model",
            modelId = "id"
        )

        // Assert
        assertTrue(definition is com.embabel.common.ai.autoconfig.LlmAutoConfigMetadata)
    }
}
