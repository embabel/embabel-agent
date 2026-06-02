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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NoSuitableModelExceptionTest {

    @Test
    fun `should create exception with criteria and model names`() {
        // Arrange
        val criteria = ByNameModelSelectionCriteria("gpt-4")
        val modelNames = listOf("gpt-3.5", "claude-2", "llama-2")

        // Act
        val exception = NoSuitableModelException(criteria, modelNames)

        // Assert
        assertNotNull(exception.message)
        assertTrue(exception.message!!.contains("No suitable model found"))
        assertTrue(exception.message!!.contains("ByNameModelSelectionCriteria"))
        assertTrue(exception.message!!.contains("3 models available"))
    }

    @Test
    fun `should include model names in exception message`() {
        // Arrange
        val criteria = ByRoleModelSelectionCriteria("assistant")
        val modelNames = listOf("model1", "model2")

        // Act
        val exception = NoSuitableModelException(criteria, modelNames)

        // Assert
        assertTrue(exception.message!!.contains("model1"))
        assertTrue(exception.message!!.contains("model2"))
    }

    @Test
    fun `should handle empty model list`() {
        // Arrange
        val criteria = ByNameModelSelectionCriteria("nonexistent")
        val modelNames = emptyList<String>()

        // Act
        val exception = NoSuitableModelException(criteria, modelNames)

        // Assert
        assertNotNull(exception.message)
        assertTrue(exception.message!!.contains("0 models available"))
    }

    @Test
    fun `should handle single model in list`() {
        // Arrange
        val criteria = ByNameModelSelectionCriteria("gpt-4")
        val modelNames = listOf("gpt-3.5")

        // Act
        val exception = NoSuitableModelException(criteria, modelNames)

        // Assert
        assertTrue(exception.message!!.contains("1 models available"))
        assertTrue(exception.message!!.contains("gpt-3.5"))
    }

    @Test
    fun `should create exception from ModelMetadata list using forModels`() {
        // Arrange
        val criteria = ByNameModelSelectionCriteria("target-model")
        val metadata1 = LlmMetadata.create(
            name = "model1",
            provider = "TestProvider",
            pricingModel = PricingModel.ALL_YOU_CAN_EAT
        )
        val metadata2 = LlmMetadata.create(
            name = "model2",
            provider = "TestProvider",
            pricingModel = PricingModel.ALL_YOU_CAN_EAT
        )
        val models = listOf(metadata1, metadata2)

        // Act
        val exception = NoSuitableModelException.forModels(criteria, models)

        // Assert
        assertNotNull(exception.message)
        assertTrue(exception.message!!.contains("model1"))
        assertTrue(exception.message!!.contains("model2"))
        assertTrue(exception.message!!.contains("2 models available"))
    }

    @Test
    fun `should be RuntimeException`() {
        // Arrange
        val criteria = ByNameModelSelectionCriteria("test")
        val exception = NoSuitableModelException(criteria, emptyList())

        // Assert
        assertTrue(exception is RuntimeException)
    }

    @Test
    fun `should handle different criteria types in message`() {
        // Arrange
        val byName = ByNameModelSelectionCriteria("gpt-4")
        val byRole = ByRoleModelSelectionCriteria("assistant")
        val random = RandomByNameModelSelectionCriteria(listOf("model1", "model2"))

        // Act
        val exception1 = NoSuitableModelException(byName, emptyList())
        val exception2 = NoSuitableModelException(byRole, emptyList())
        val exception3 = NoSuitableModelException(random, emptyList())

        // Assert
        assertTrue(exception1.message!!.contains("ByNameModelSelectionCriteria"))
        assertTrue(exception2.message!!.contains("ByRoleModelSelectionCriteria"))
        assertTrue(exception3.message!!.contains("RandomByNameModelSelectionCriteria"))
    }

    @Test
    fun `forModels should extract model names from metadata`() {
        // Arrange
        val criteria = ByNameModelSelectionCriteria("test")
        val metadata = LlmMetadata.create(
            name = "extracted-model-name",
            provider = "TestProvider",
            pricingModel = PricingModel.ALL_YOU_CAN_EAT
        )

        // Act
        val exception = NoSuitableModelException.forModels(criteria, listOf(metadata))

        // Assert
        assertTrue(exception.message!!.contains("extracted-model-name"))
    }
}
