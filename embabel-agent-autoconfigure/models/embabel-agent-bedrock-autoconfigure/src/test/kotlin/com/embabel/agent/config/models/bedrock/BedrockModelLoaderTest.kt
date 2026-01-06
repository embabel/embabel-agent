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
package com.embabel.agent.config.models.bedrock

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader
import java.io.File
import java.nio.file.Files

class BedrockModelLoaderTest {

    @Test
    fun `should load valid model definitions from default YAML file`() {
        // Arrange
        val loader = BedrockModelLoader()

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertNotNull(result)
        assertTrue(result.models.isNotEmpty(), "Should load at least one LLM model")
        assertTrue(result.embeddingModels.isNotEmpty(), "Should load at least one embedding model")

        // Verify first LLM model has required fields
        val firstModel = result.models.first()
        assertNotNull(firstModel.name)
        assertNotNull(firstModel.modelId)
        assertNotNull(firstModel.region)
        assertTrue(firstModel.name.isNotBlank(), "Model name should not be blank")
        assertTrue(firstModel.modelId.isNotBlank(), "Model ID should not be blank")
        assertTrue(firstModel.region.isNotBlank(), "Region should not be blank")

        // Verify first embedding model has required fields
        val firstEmbedding = result.embeddingModels.first()
        assertNotNull(firstEmbedding.name)
        assertNotNull(firstEmbedding.modelId)
        assertNotNull(firstEmbedding.modelType)
        assertTrue(firstEmbedding.name.isNotBlank(), "Embedding name should not be blank")
        assertTrue(firstEmbedding.modelId.isNotBlank(), "Embedding model ID should not be blank")
        assertTrue(firstEmbedding.modelType.isNotBlank(), "Model type should not be blank")
    }

    @Test
    fun `should validate all loaded models have correct region prefixes`() {
        // Arrange
        val loader = BedrockModelLoader()

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        result.models.forEach { model ->
            // Verify region is valid
            assertTrue(
                model.region in listOf("us", "eu", "apac"),
                "Region should be one of us, eu, apac for ${model.name}, got: ${model.region}"
            )

            // Verify model ID starts with region prefix
            assertTrue(
                model.modelId.startsWith("${model.region}."),
                "Model ID should start with '${model.region}.' for ${model.name}, got: ${model.modelId}"
            )
        }
    }

    @Test
    fun `should validate all embedding models have correct types`() {
        // Arrange
        val loader = BedrockModelLoader()

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        result.embeddingModels.forEach { model ->
            assertTrue(
                model.modelType in listOf("titan", "cohere"),
                "Model type should be titan or cohere for ${model.name}, got: ${model.modelType}"
            )
        }
    }

    @Test
    fun `should verify specific known models are loaded`() {
        // Arrange
        val loader = BedrockModelLoader()

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert - verify some known Bedrock models are present
        val modelNames = result.models.map { it.name }
        assertTrue(modelNames.isNotEmpty(), "Should have loaded LLM model names")

        val embeddingNames = result.embeddingModels.map { it.name }
        assertTrue(embeddingNames.isNotEmpty(), "Should have loaded embedding model names")

        // Verify at least one model has pricing info
        assertTrue(
            result.models.any { it.pricingModel != null },
            "At least one model should have pricing information"
        )

        // Verify we have models from all regions
        val regions = result.models.map { it.region }.toSet()
        assertTrue(regions.contains("us"), "Should have US region models")
        assertTrue(regions.contains("eu"), "Should have EU region models")
        assertTrue(regions.contains("apac"), "Should have APAC region models")
    }

    @Test
    fun `should return empty definitions when file does not exist`() {
        // Arrange
        val loader = BedrockModelLoader(
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

        val loader = BedrockModelLoader(
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
    fun `should validate model with invalid region`() {
        // Arrange
        val tempFile = createTempYamlFile("""
            models:
              - name: test-model
                model_id: invalid.anthropic.claude-test
                region: invalid
        """.trimIndent())

        val loader = BedrockModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act & Assert
        val result = loader.loadAutoConfigMetadata()
        assertTrue(result.models.isEmpty(), "Should fail validation for invalid region")
    }

    @Test
    fun `should validate model with mismatched region prefix`() {
        // Arrange
        val tempFile = createTempYamlFile("""
            models:
              - name: test-model
                model_id: eu.anthropic.claude-test
                region: us
        """.trimIndent())

        val loader = BedrockModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act & Assert
        val result = loader.loadAutoConfigMetadata()
        assertTrue(result.models.isEmpty(), "Should fail validation when model_id doesn't start with region prefix")
    }

    @Test
    fun `should validate embedding model with invalid type`() {
        // Arrange
        val tempFile = createTempYamlFile("""
            embedding_models:
              - name: test-embedding
                model_id: test.embed-v1
                model_type: invalid
        """.trimIndent())

        val loader = BedrockModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act & Assert
        val result = loader.loadAutoConfigMetadata()
        assertTrue(result.embeddingModels.isEmpty(), "Should fail validation for invalid embedding type")
    }

    @Test
    fun `should validate model with blank name`() {
        // Arrange
        val tempFile = createTempYamlFile("""
            models:
              - name: ""
                model_id: us.anthropic.claude-test
                region: us
        """.trimIndent())

        val loader = BedrockModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act & Assert
        val result = loader.loadAutoConfigMetadata()
        assertTrue(result.models.isEmpty(), "Should fail validation for blank name")
    }

    @Test
    fun `should validate embedding model with blank name`() {
        // Arrange
        val tempFile = createTempYamlFile("""
            embedding_models:
              - name: ""
                model_id: amazon.titan-embed-v1
                model_type: titan
        """.trimIndent())

        val loader = BedrockModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act & Assert
        val result = loader.loadAutoConfigMetadata()
        assertTrue(result.embeddingModels.isEmpty(), "Should fail validation for blank embedding name")
    }

    @Test
    fun `should load valid LLM model with all optional fields`() {
        // Arrange
        val tempFile = createTempYamlFile("""
            models:
              - name: test-model
                model_id: us.anthropic.claude-test
                display_name: Test Claude Model
                region: us
                knowledge_cutoff_date: "2025-01-01"
                pricing_model:
                  usd_per1m_input_tokens: 3.0
                  usd_per1m_output_tokens: 15.0
        """.trimIndent())

        val loader = BedrockModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertEquals(1, result.models.size)
        val model = result.models.first()
        assertEquals("test-model", model.name)
        assertEquals("us.anthropic.claude-test", model.modelId)
        assertEquals("Test Claude Model", model.displayName)
        assertEquals("us", model.region)
        assertNotNull(model.knowledgeCutoffDate)
        assertNotNull(model.pricingModel)
        assertEquals(3.0, model.pricingModel?.usdPer1mInputTokens)
        assertEquals(15.0, model.pricingModel?.usdPer1mOutputTokens)
    }

    @Test
    fun `should load valid embedding model with all fields`() {
        // Arrange
        val tempFile = createTempYamlFile("""
            embedding_models:
              - name: test-embedding
                model_id: amazon.titan-embed-test
                display_name: Test Titan Embedding
                model_type: titan
        """.trimIndent())

        val loader = BedrockModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertEquals(1, result.embeddingModels.size)
        val embedding = result.embeddingModels.first()
        assertEquals("test-embedding", embedding.name)
        assertEquals("amazon.titan-embed-test", embedding.modelId)
        assertEquals("Test Titan Embedding", embedding.displayName)
        assertEquals("titan", embedding.modelType)
    }

    @Test
    fun `should load multiple LLM models correctly`() {
        // Arrange
        val tempFile = createTempYamlFile("""
            models:
              - name: us-model
                model_id: us.anthropic.claude-1
                region: us
              - name: eu-model
                model_id: eu.anthropic.claude-2
                region: eu
              - name: apac-model
                model_id: apac.anthropic.claude-3
                region: apac
        """.trimIndent())

        val loader = BedrockModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertEquals(3, result.models.size)
        assertEquals("us-model", result.models[0].name)
        assertEquals("eu-model", result.models[1].name)
        assertEquals("apac-model", result.models[2].name)
        assertEquals("us", result.models[0].region)
        assertEquals("eu", result.models[1].region)
        assertEquals("apac", result.models[2].region)
    }

    @Test
    fun `should load multiple embedding models correctly`() {
        // Arrange
        val tempFile = createTempYamlFile("""
            embedding_models:
              - name: titan-model
                model_id: amazon.titan-embed-v1
                model_type: titan
              - name: cohere-model
                model_id: cohere.embed-v1
                model_type: cohere
        """.trimIndent())

        val loader = BedrockModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertEquals(2, result.embeddingModels.size)
        assertEquals("titan-model", result.embeddingModels[0].name)
        assertEquals("cohere-model", result.embeddingModels[1].name)
        assertEquals("titan", result.embeddingModels[0].modelType)
        assertEquals("cohere", result.embeddingModels[1].modelType)
    }

    @Test
    fun `should load both LLM and embedding models together`() {
        // Arrange
        val tempFile = createTempYamlFile("""
            models:
              - name: us-claude
                model_id: us.anthropic.claude-test
                region: us
            embedding_models:
              - name: titan-embed
                model_id: amazon.titan-embed-v1
                model_type: titan
        """.trimIndent())

        val loader = BedrockModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertEquals(1, result.models.size)
        assertEquals(1, result.embeddingModels.size)
        assertEquals("us-claude", result.models[0].name)
        assertEquals("titan-embed", result.embeddingModels[0].name)
    }

    @Test
    fun `should load LLM model with minimal fields`() {
        // Arrange
        val tempFile = createTempYamlFile("""
            models:
              - name: minimal-model
                model_id: us.anthropic.claude-minimal
                region: us
        """.trimIndent())

        val loader = BedrockModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertEquals(1, result.models.size)
        val model = result.models.first()
        assertEquals("minimal-model", model.name)
        assertEquals("us.anthropic.claude-minimal", model.modelId)
        assertEquals("us", model.region)
        assertNull(model.displayName)
        assertNull(model.knowledgeCutoffDate)
        assertNull(model.pricingModel)
    }

    @Test
    fun `should load embedding model with minimal fields`() {
        // Arrange
        val tempFile = createTempYamlFile("""
            embedding_models:
              - name: minimal-embedding
                model_id: amazon.titan-minimal
                model_type: titan
        """.trimIndent())

        val loader = BedrockModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertEquals(1, result.embeddingModels.size)
        val embedding = result.embeddingModels.first()
        assertEquals("minimal-embedding", embedding.name)
        assertEquals("amazon.titan-minimal", embedding.modelId)
        assertEquals("titan", embedding.modelType)
        assertNull(embedding.displayName)
    }

    @Test
    fun `should validate all three valid regions`() {
        // Arrange
        val tempFile = createTempYamlFile("""
            models:
              - name: us-model
                model_id: us.provider.model
                region: us
              - name: eu-model
                model_id: eu.provider.model
                region: eu
              - name: apac-model
                model_id: apac.provider.model
                region: apac
        """.trimIndent())

        val loader = BedrockModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertEquals(3, result.models.size)
        assertTrue(result.models.all { it.modelId.startsWith("${it.region}.") })
    }

    @Test
    fun `should validate both valid embedding types`() {
        // Arrange
        val tempFile = createTempYamlFile("""
            embedding_models:
              - name: titan-embed
                model_id: amazon.titan-embed-v1
                model_type: titan
              - name: cohere-embed
                model_id: cohere.embed-v1
                model_type: cohere
        """.trimIndent())

        val loader = BedrockModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertEquals(2, result.embeddingModels.size)
        assertTrue(result.embeddingModels.any { it.modelType == "titan" })
        assertTrue(result.embeddingModels.any { it.modelType == "cohere" })
    }

    @Test
    fun `should handle YAML with only LLM models`() {
        // Arrange
        val tempFile = createTempYamlFile("""
            models:
              - name: us-model
                model_id: us.anthropic.claude-test
                region: us
        """.trimIndent())

        val loader = BedrockModelLoader(
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
        val tempFile = createTempYamlFile("""
            embedding_models:
              - name: titan-embed
                model_id: amazon.titan-embed-v1
                model_type: titan
        """.trimIndent())

        val loader = BedrockModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertEquals(0, result.models.size)
        assertEquals(1, result.embeddingModels.size)
    }

    @Test
    fun `should validate embedding model with blank model_id`() {
        // Arrange
        val tempFile = createTempYamlFile("""
            embedding_models:
              - name: test-embedding
                model_id: ""
                model_type: titan
        """.trimIndent())

        val loader = BedrockModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act & Assert
        val result = loader.loadAutoConfigMetadata()
        assertTrue(result.embeddingModels.isEmpty(), "Should fail validation for blank model_id")
    }

    @Test
    fun `should validate model with blank model_id`() {
        // Arrange
        val tempFile = createTempYamlFile("""
            models:
              - name: test-model
                model_id: ""
                region: us
        """.trimIndent())

        val loader = BedrockModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}"
        )

        // Act & Assert
        val result = loader.loadAutoConfigMetadata()
        assertTrue(result.models.isEmpty(), "Should fail validation for blank model_id")
    }

    private fun createTempYamlFile(content: String): File {
        val tempFile = Files.createTempFile("test-bedrock", ".yml").toFile()
        tempFile.writeText(content)
        tempFile.deleteOnExit()
        return tempFile
    }
}
