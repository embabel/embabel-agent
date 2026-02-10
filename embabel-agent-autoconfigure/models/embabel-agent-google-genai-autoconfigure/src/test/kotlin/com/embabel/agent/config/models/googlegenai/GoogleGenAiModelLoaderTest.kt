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
package com.embabel.agent.config.models.googlegenai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader
import java.io.File
import java.nio.file.Files

class GoogleGenAiModelLoaderTest {

    @Test
    fun `should load valid model definitions from default YAML file`() {
        // Arrange
        val loader = GoogleGenAiModelLoader()

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertNotNull(result)
        assertTrue(result.models.isNotEmpty(), "Should load at least one LLM model")
        assertTrue(result.embeddingModels.isNotEmpty(), "Should load at least one embedding model")

        // Verify first model has required fields
        val firstModel = result.models.first()
        assertNotNull(firstModel.name)
        assertNotNull(firstModel.modelId)
        assertTrue(firstModel.name.isNotBlank(), "Model name should not be blank")
        assertTrue(firstModel.modelId.isNotBlank(), "Model ID should not be blank")

        // Verify first embedding model has required fields
        val firstEmbedding = result.embeddingModels.first()
        assertTrue(firstEmbedding.name.isNotBlank(), "Embedding name should not be blank")
        assertTrue(firstEmbedding.modelId.isNotBlank(), "Embedding model ID should not be blank")
    }

    @Test
    fun `should validate all loaded models have correct default values`() {
        // Arrange
        val loader = GoogleGenAiModelLoader()

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        result.models.forEach { model ->
            // Verify defaults
            assertTrue(model.maxOutputTokens > 0, "Max output tokens should be positive for ${model.name}")
            assertTrue(
                model.temperature in 0.0..2.0,
                "Temperature should be in valid range for ${model.name}"
            )

            // Verify optional fields when present
            model.topP?.let {
                assertTrue(it in 0.0..1.0, "Top P should be between 0 and 1 for ${model.name}")
            }
            model.topK?.let {
                assertTrue(it > 0, "Top K should be positive for ${model.name}")
            }
            model.thinkingBudget?.let {
                assertTrue(it > 0, "Thinking budget should be positive for ${model.name}")
            }
        }
    }

    @Test
    fun `should verify specific known models are loaded`() {
        // Arrange
        val loader = GoogleGenAiModelLoader()

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert - verify some known Google GenAI models are present
        val modelNames = result.models.map { it.name }
        assertTrue(modelNames.isNotEmpty(), "Should have loaded model names")

        // Verify at least one model has pricing info
        assertTrue(
            result.models.any { it.pricingModel != null },
            "At least one model should have pricing information"
        )
    }

    @Test
    fun `should return empty definitions when file does not exist`() {
        // Arrange
        val loader = GoogleGenAiModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "classpath:nonexistent-file.yml"
        )

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertNotNull(result)
        assertTrue(result.models.isEmpty(), "Should return empty LLM list when file not found")
        assertTrue(result.embeddingModels.isEmpty(), "Should return empty embedding list when file not found")
    }

    @Test
    fun `should handle invalid YAML gracefully`() {
        // Arrange
        val tempFile = Files.createTempFile("invalid", ".yml").toFile()
        tempFile.writeText("invalid: yaml: content: ][")
        tempFile.deleteOnExit()

        val loader = GoogleGenAiModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertNotNull(result)
        assertTrue(result.models.isEmpty(), "Should return empty list on parse error")
        assertTrue(result.embeddingModels.isEmpty(), "Should return empty embedding list on parse error")
    }

    @Test
    fun `should validate model with invalid maxOutputTokens`() {
        // Arrange
        val tempFile = createTempYamlFile(
            """
            models:
              - name: test-model
                model_id: gemini-test
                max_output_tokens: -100
        """.trimIndent()
        )

        val loader = GoogleGenAiModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act & Assert
        val result = loader.loadAutoConfigMetadata()
        assertTrue(result.models.isEmpty(), "Should fail validation for negative maxOutputTokens")
    }

    @Test
    fun `should validate model with invalid temperature`() {
        // Arrange
        val tempFile = createTempYamlFile(
            """
            models:
              - name: test-model
                model_id: gemini-test
                temperature: 3.0
        """.trimIndent()
        )

        val loader = GoogleGenAiModelLoader(
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
        val tempFile = createTempYamlFile(
            """
            models:
              - name: test-model
                model_id: gemini-test
                top_p: 1.5
        """.trimIndent()
        )

        val loader = GoogleGenAiModelLoader(
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
        val tempFile = createTempYamlFile(
            """
            models:
              - name: ""
                model_id: gemini-test
        """.trimIndent()
        )

        val loader = GoogleGenAiModelLoader(
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
        val tempFile = createTempYamlFile(
            """
            models:
              - name: test-model
                model_id: gemini-test
                display_name: Test Model
                max_output_tokens: 4096
                temperature: 0.7
                top_p: 0.9
                top_k: 50
                thinking_budget: 1000
                pricing_model:
                  usd_per1m_input_tokens: 10.0
                  usd_per1m_output_tokens: 20.0
        """.trimIndent()
        )

        val loader = GoogleGenAiModelLoader(
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
        assertEquals(4096, model.maxOutputTokens)
        assertEquals(0.7, model.temperature)
        assertEquals(0.9, model.topP)
        assertEquals(50, model.topK)
        assertEquals(1000, model.thinkingBudget)
        assertNotNull(model.pricingModel)
        assertEquals(10.0, model.pricingModel?.usdPer1mInputTokens)
        assertEquals(20.0, model.pricingModel?.usdPer1mOutputTokens)
    }

    @Test
    fun `should load multiple models correctly`() {
        // Arrange
        val tempFile = createTempYamlFile(
            """
            models:
              - name: model-1
                model_id: gemini-1
                max_output_tokens: 2000
              - name: model-2
                model_id: gemini-2
                max_output_tokens: 4000
        """.trimIndent()
        )

        val loader = GoogleGenAiModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertEquals(2, result.models.size)
        assertEquals("model-1", result.models[0].name)
        assertEquals("model-2", result.models[1].name)
        assertEquals(2000, result.models[0].maxOutputTokens)
        assertEquals(4000, result.models[1].maxOutputTokens)
    }

    @Test
    fun `should validate model with invalid topK`() {
        // Arrange
        val tempFile = createTempYamlFile(
            """
            models:
              - name: test-model
                model_id: gemini-test
                top_k: -5
        """.trimIndent()
        )

        val loader = GoogleGenAiModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act & Assert
        val result = loader.loadAutoConfigMetadata()
        assertTrue(result.models.isEmpty(), "Should fail validation for negative topK")
    }

    @Test
    fun `should validate model with invalid thinking budget`() {
        // Arrange
        val tempFile = createTempYamlFile(
            """
            models:
              - name: test-model
                model_id: gemini-test
                thinking_budget: -1000
        """.trimIndent()
        )

        val loader = GoogleGenAiModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act & Assert
        val result = loader.loadAutoConfigMetadata()
        assertTrue(result.models.isEmpty(), "Should fail validation for negative thinking budget")
    }

    @Test
    fun `should load model with minimal fields`() {
        // Arrange
        val tempFile = createTempYamlFile(
            """
            models:
              - name: minimal-model
                model_id: gemini-minimal
        """.trimIndent()
        )

        val loader = GoogleGenAiModelLoader(
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
        assertEquals(8192, model.maxOutputTokens) // Default value
        assertEquals(0.7, model.temperature) // Default value
        assertNull(model.topP)
        assertNull(model.topK)
        assertNull(model.thinkingBudget)
        assertNull(model.pricingModel)
    }

    @Test
    fun `should load Gemini 3 Pro preview model`() {
        // Arrange
        val loader = GoogleGenAiModelLoader()

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert - verify Gemini 3 Pro preview is present
        val gemini3ProPreview = result.models.find { it.modelId == "gemini-3-pro-preview" }
        assertNotNull(gemini3ProPreview, "Gemini 3 Pro preview should be loaded")
        assertEquals("gemini_3_pro_preview", gemini3ProPreview?.name)
        assertEquals("gemini-3-pro-preview", gemini3ProPreview?.modelId)
    }

    /**
     * Gemini 3 Flash Preview - Fast model with thinking capabilities
     * Added as part of the Gemini 3 family support
     */
    @Test
    fun `should load Gemini 3 Flash preview model`() {
        // Arrange
        val loader = GoogleGenAiModelLoader()

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert - verify Gemini 3 Flash preview is present
        val gemini3FlashPreview = result.models.find { it.modelId == "gemini-3-flash-preview" }
        assertNotNull(gemini3FlashPreview, "Gemini 3 Flash preview should be loaded")
        assertEquals("gemini_3_flash_preview", gemini3FlashPreview?.name)
        assertEquals("gemini-3-flash-preview", gemini3FlashPreview?.modelId)
    }

    @Test
    fun `should load Gemini 25 Flash model`() {
        // Arrange
        val loader = GoogleGenAiModelLoader()

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert - verify Gemini 2.5 Flash is present
        val gemini25Flash = result.models.find { it.modelId == "gemini-2.5-flash" }
        assertNotNull(gemini25Flash, "Gemini 2.5 Flash should be loaded")
        assertEquals("gemini_25_flash", gemini25Flash?.name)
    }

    // ========================================
    // Embedding model tests
    // ========================================

    @Test
    fun `should verify embedding models are loaded`() {
        // Arrange
        val loader = GoogleGenAiModelLoader()

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertTrue(result.embeddingModels.isNotEmpty(), "Should load at least one embedding model")

        result.embeddingModels.forEach { embedding ->
            assertNotNull(embedding.name)
            assertNotNull(embedding.modelId)
            assertTrue(embedding.name.isNotBlank(), "Embedding name should not be blank")
            assertTrue(embedding.modelId.isNotBlank(), "Embedding model ID should not be blank")
        }
    }

    @Test
    fun `should validate embedding model dimensions and pricing`() {
        // Arrange
        val loader = GoogleGenAiModelLoader()

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        result.embeddingModels.forEach { embedding ->
            embedding.dimensions?.let { dims ->
                assertTrue(dims > 0, "Dimensions should be positive for ${embedding.name}")
            }

            embedding.pricingModel?.let { pricing ->
                assertTrue(
                    pricing.usdPer1mTokens >= 0.0,
                    "Pricing should be non-negative for ${embedding.name}"
                )
            }
        }
    }

    @Test
    fun `should load Gemini Embedding 001 model`() {
        // Arrange
        val loader = GoogleGenAiModelLoader()

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        val geminiEmbedding = result.embeddingModels.find { it.modelId == "gemini-embedding-001" }
        assertNotNull(geminiEmbedding, "Gemini Embedding 001 should be loaded")
        assertEquals("gemini_embedding_001", geminiEmbedding?.name)
        assertEquals(3072, geminiEmbedding?.dimensions)
        assertNotNull(geminiEmbedding?.pricingModel)
        assertEquals(0.15, geminiEmbedding?.pricingModel?.usdPer1mTokens)
    }

    @Test
    fun `should validate embedding model with invalid dimensions`() {
        // Arrange
        val tempFile = createTempYamlFile(
            """
            embedding_models:
              - name: test-embedding
                model_id: gemini-embedding-test
                dimensions: -100
        """.trimIndent()
        )

        val loader = GoogleGenAiModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act & Assert
        val result = loader.loadAutoConfigMetadata()
        assertTrue(result.embeddingModels.isEmpty(), "Should fail validation for negative dimensions")
    }

    @Test
    fun `should validate embedding model with blank name`() {
        // Arrange
        val tempFile = createTempYamlFile(
            """
            embedding_models:
              - name: ""
                model_id: gemini-embedding-test
                dimensions: 3072
        """.trimIndent()
        )

        val loader = GoogleGenAiModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act & Assert
        val result = loader.loadAutoConfigMetadata()
        assertTrue(result.embeddingModels.isEmpty(), "Should fail validation for blank name")
    }

    @Test
    fun `should validate embedding model with blank model_id`() {
        // Arrange
        val tempFile = createTempYamlFile(
            """
            embedding_models:
              - name: test-embedding
                model_id: ""
                dimensions: 3072
        """.trimIndent()
        )

        val loader = GoogleGenAiModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act & Assert
        val result = loader.loadAutoConfigMetadata()
        assertTrue(result.embeddingModels.isEmpty(), "Should fail validation for blank model_id")
    }

    @Test
    fun `should validate embedding model with invalid pricing`() {
        // Arrange
        val tempFile = createTempYamlFile(
            """
            embedding_models:
              - name: test-embedding
                model_id: gemini-embedding-test
                dimensions: 3072
                pricing_model:
                  usd_per1m_tokens: -0.5
        """.trimIndent()
        )

        val loader = GoogleGenAiModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act & Assert
        val result = loader.loadAutoConfigMetadata()
        assertTrue(result.embeddingModels.isEmpty(), "Should fail validation for negative pricing")
    }

    @Test
    fun `should load valid embedding model with all fields`() {
        // Arrange
        val tempFile = createTempYamlFile(
            """
            embedding_models:
              - name: test-embedding
                model_id: gemini-embedding-test
                display_name: Test Embedding
                dimensions: 3072
                pricing_model:
                  usd_per1m_tokens: 0.15
        """.trimIndent()
        )

        val loader = GoogleGenAiModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertEquals(1, result.embeddingModels.size)
        val embedding = result.embeddingModels.first()
        assertEquals("test-embedding", embedding.name)
        assertEquals("gemini-embedding-test", embedding.modelId)
        assertEquals("Test Embedding", embedding.displayName)
        assertEquals(3072, embedding.dimensions)
        assertNotNull(embedding.pricingModel)
        assertEquals(0.15, embedding.pricingModel?.usdPer1mTokens)
    }

    @Test
    fun `should load embedding model with minimal fields`() {
        // Arrange
        val tempFile = createTempYamlFile(
            """
            embedding_models:
              - name: minimal-embedding
                model_id: gemini-embedding-minimal
        """.trimIndent()
        )

        val loader = GoogleGenAiModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertEquals(1, result.embeddingModels.size)
        val embedding = result.embeddingModels.first()
        assertEquals("minimal-embedding", embedding.name)
        assertEquals("gemini-embedding-minimal", embedding.modelId)
        assertNull(embedding.displayName)
        assertNull(embedding.dimensions)
        assertNull(embedding.pricingModel)
    }

    @Test
    fun `should load multiple embedding models correctly`() {
        // Arrange
        val tempFile = createTempYamlFile(
            """
            embedding_models:
              - name: embedding-1
                model_id: gemini-embedding-1
                dimensions: 768
              - name: embedding-2
                model_id: gemini-embedding-2
                dimensions: 3072
        """.trimIndent()
        )

        val loader = GoogleGenAiModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertEquals(2, result.embeddingModels.size)
        assertEquals("embedding-1", result.embeddingModels[0].name)
        assertEquals("embedding-2", result.embeddingModels[1].name)
        assertEquals(768, result.embeddingModels[0].dimensions)
        assertEquals(3072, result.embeddingModels[1].dimensions)
    }

    @Test
    fun `should load both LLM and embedding models from same file`() {
        // Arrange
        val tempFile = createTempYamlFile(
            """
            models:
              - name: llm-1
                model_id: gemini-test-1
                max_output_tokens: 4096
              - name: llm-2
                model_id: gemini-test-2
                max_output_tokens: 8192
            embedding_models:
              - name: embedding-1
                model_id: gemini-embedding-1
                dimensions: 3072
        """.trimIndent()
        )

        val loader = GoogleGenAiModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertEquals(2, result.models.size, "Should load 2 LLM models")
        assertEquals(1, result.embeddingModels.size, "Should load 1 embedding model")
    }

    @Test
    fun `should handle YAML with only LLM models`() {
        // Arrange
        val tempFile = createTempYamlFile(
            """
            models:
              - name: llm-only
                model_id: gemini-test
        """.trimIndent()
        )

        val loader = GoogleGenAiModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertEquals(1, result.models.size)
        assertEquals(0, result.embeddingModels.size)
    }

    @Test
    fun `should handle YAML with only embedding models`() {
        // Arrange
        val tempFile = createTempYamlFile(
            """
            embedding_models:
              - name: embedding-only
                model_id: gemini-embedding-test
                dimensions: 3072
        """.trimIndent()
        )

        val loader = GoogleGenAiModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertEquals(0, result.models.size)
        assertEquals(1, result.embeddingModels.size)
    }

    private fun createTempYamlFile(content: String): File {
        val tempFile = Files.createTempFile("test-googlegenai", ".yml").toFile()
        tempFile.writeText(content)
        tempFile.deleteOnExit()
        return tempFile
    }
}
