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
package com.embabel.agent.config.models.gemini

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader
import java.io.File
import java.nio.file.Files

class GeminiModelLoaderTest {

    @Test
    fun `should load valid model definitions from default YAML file`() {
        // Arrange
        val loader = GeminiModelLoader()

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertNotNull(result)
        assertTrue(result.models.isNotEmpty(), "Should load at least one model")

        // Verify first model has required fields
        val firstModel = result.models.first()
        assertNotNull(firstModel.name)
        assertNotNull(firstModel.modelId)
        assertTrue(firstModel.name.isNotBlank(), "Model name should not be blank")
        assertTrue(firstModel.modelId.isNotBlank(), "Model ID should not be blank")
    }

    @Test
    fun `should validate all loaded models have correct default values`() {
        // Arrange
        val loader = GeminiModelLoader()

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        result.models.forEach { model ->
            // Verify defaults
            assertTrue(model.maxTokens > 0, "Max tokens should be positive for ${model.name}")
            assertTrue(model.temperature in 0.0..2.0, "Temperature should be in valid range for ${model.name}")

            // Verify optional fields when present
            model.topP?.let {
                assertTrue(it in 0.0..1.0, "Top P should be between 0 and 1 for ${model.name}")
            }
        }
    }

    @Test
    fun `should verify specific known models are loaded`() {
        // Arrange
        val loader = GeminiModelLoader()

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert - verify some known Gemini models are present
        val modelNames = result.models.map { it.name }
        assertTrue(modelNames.isNotEmpty(), "Should have loaded model names")

        // Verify at least one model has pricing info
        assertTrue(result.models.any { it.pricingModel != null },
            "At least one model should have pricing information")
    }

    @Test
    fun `should load all 6 expected Gemini models`() {
        // Arrange
        val loader = GeminiModelLoader()

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertEquals(6, result.models.size, "Should load exactly 6 Gemini models")

        val expectedModels = listOf(
            "gemini_3_pro_preview", "gemini_25_pro", "gemini_25_flash",
            "gemini_25_flash_lite", "gemini_20_flash", "gemini_20_flash_lite"
        )

        expectedModels.forEach { expectedName ->
            assertTrue(result.models.any { it.name == expectedName },
                "Should have model: $expectedName")
        }
    }

    @Test
    fun `should return empty definitions when file does not exist`() {
        // Arrange
        val loader = GeminiModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "classpath:nonexistent-file.yml"
        )

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertNotNull(result)
        assertTrue(result.models.isEmpty(), "Should return empty list when file not found")
    }

    @Test
    fun `should handle invalid YAML gracefully`() {
        // Arrange
        val tempFile = Files.createTempFile("invalid", ".yml").toFile()
        tempFile.writeText("invalid: yaml: content: ][")
        tempFile.deleteOnExit()

        val loader = GeminiModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertNotNull(result)
        assertTrue(result.models.isEmpty(), "Should return empty list on parse error")
    }

    @Test
    fun `should validate model with invalid maxTokens`() {
        // Arrange
        val tempFile = createTempYamlFile("""
            models:
              - name: test-model
                model_id: gemini-test
                max_tokens: -100
        """.trimIndent())

        val loader = GeminiModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act & Assert
        val result = loader.loadAutoConfigMetadata()
        assertTrue(result.models.isEmpty(), "Should fail validation for negative maxTokens")
    }

    @Test
    fun `should validate model with invalid temperature`() {
        // Arrange
        val tempFile = createTempYamlFile("""
            models:
              - name: test-model
                model_id: gemini-test
                temperature: 3.0
        """.trimIndent())

        val loader = GeminiModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act & Assert
        val result = loader.loadAutoConfigMetadata()
        assertTrue(result.models.isEmpty(), "Should fail validation for temperature out of range")
    }

    @Test
    fun `should validate model with invalid topP`() {
        // Arrange
        val tempFile = createTempYamlFile("""
            models:
              - name: test-model
                model_id: gemini-test
                top_p: 1.5
        """.trimIndent())

        val loader = GeminiModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act & Assert
        val result = loader.loadAutoConfigMetadata()
        assertTrue(result.models.isEmpty(), "Should fail validation for topP out of range")
    }

    @Test
    fun `should validate model with blank name`() {
        // Arrange
        val tempFile = createTempYamlFile("""
            models:
              - name: ""
                model_id: gemini-test
        """.trimIndent())

        val loader = GeminiModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act & Assert
        val result = loader.loadAutoConfigMetadata()
        assertTrue(result.models.isEmpty(), "Should fail validation for blank name")
    }

    @Test
    fun `should load valid model with all optional fields`() {
        // Arrange
        val tempFile = createTempYamlFile("""
            models:
              - name: test-model
                model_id: gemini-test
                display_name: Test Model
                max_tokens: 4096
                temperature: 0.7
                top_p: 0.9
                knowledge_cutoff_date: 2024-01-01
                pricing_model:
                  usd_per1m_input_tokens: 10.0
                  usd_per1m_output_tokens: 20.0
        """.trimIndent())

        val loader = GeminiModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertEquals(1, result.models.size)
        val model = result.models.first()
        assertEquals("test-model", model.name)
        assertEquals("gemini-test", model.modelId)
        assertEquals("Test Model", model.displayName)
        assertEquals(4096, model.maxTokens)
        assertEquals(0.7, model.temperature)
        assertEquals(0.9, model.topP)
        assertNotNull(model.knowledgeCutoffDate)
        assertNotNull(model.pricingModel)
        assertEquals(10.0, model.pricingModel?.usdPer1mInputTokens)
        assertEquals(20.0, model.pricingModel?.usdPer1mOutputTokens)
    }

    @Test
    fun `should load multiple models correctly`() {
        // Arrange
        val tempFile = createTempYamlFile("""
            models:
              - name: model-1
                model_id: gemini-1
                max_tokens: 2000
              - name: model-2
                model_id: gemini-2
                max_tokens: 4000
        """.trimIndent())

        val loader = GeminiModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertEquals(2, result.models.size)
        assertEquals("model-1", result.models[0].name)
        assertEquals("model-2", result.models[1].name)
        assertEquals(2000, result.models[0].maxTokens)
        assertEquals(4000, result.models[1].maxTokens)
    }

    @Test
    fun `should load model with minimal fields`() {
        // Arrange
        val tempFile = createTempYamlFile("""
            models:
              - name: minimal-model
                model_id: gemini-minimal
        """.trimIndent())

        val loader = GeminiModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertEquals(1, result.models.size)
        val model = result.models.first()
        assertEquals("minimal-model", model.name)
        assertEquals("gemini-minimal", model.modelId)
        assertNull(model.displayName)
        assertEquals(8192, model.maxTokens) // Default value
        assertEquals(1.0, model.temperature) // Default value
        assertNull(model.topP)
        assertNull(model.pricingModel)
    }

    @Test
    fun `should verify pricing models are present for all models`() {
        // Arrange
        val loader = GeminiModelLoader()

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        result.models.forEach { model ->
            assertNotNull(model.pricingModel,
                "Model ${model.name} should have pricing information")
            model.pricingModel?.let { pricing ->
                assertTrue(pricing.usdPer1mInputTokens > 0,
                    "Input token price should be positive for ${model.name}")
                assertTrue(pricing.usdPer1mOutputTokens > 0,
                    "Output token price should be positive for ${model.name}")
            }
        }
    }

    @Test
    fun `should verify model IDs match expected format`() {
        // Arrange
        val loader = GeminiModelLoader()

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        result.models.forEach { model ->
            assertTrue(model.modelId.startsWith("gemini-"),
                "Model ID should start with 'gemini-' for ${model.name}")
        }
    }

    private fun createTempYamlFile(content: String): File {
        val tempFile = Files.createTempFile("test-gemini", ".yml").toFile()
        tempFile.writeText(content)
        tempFile.deleteOnExit()
        return tempFile
    }
}
