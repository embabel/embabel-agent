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
package com.embabel.agent.config.models.anthropic

import com.embabel.agent.api.models.AnthropicModels
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.ResourceLoader

/**
 * Unit tests for [AnthropicModelLoader].
 *
 * Covers loading and validating model definitions from `anthropic-models.yml`, default-value
 * handling, and rejection of malformed entries. Includes a grounding check that the
 * `claude_opus_48` model is present with the expected id ([AnthropicModels.CLAUDE_OPUS_4_8])
 * and pricing.
 */
class AnthropicModelLoaderTest {

    @Nested
    inner class NativeSupportTests {

        @Test
        fun `default YAML loads native structured-output metadata for anthropic models`() {
            val loader = AnthropicModelLoader()

            val result = loader.loadAutoConfigMetadata()
            val defaults = result.nativeSupportDefaults
            assertNotNull(defaults)
            val structuredOutput = defaults?.structuredOutput
            assertNotNull(structuredOutput)
            assertTrue(structuredOutput?.supported == false)
            assertEquals("tool", structuredOutput?.strategy)
            assertEquals(true, structuredOutput?.strict)
            assertEquals("include", structuredOutput?.promptInstructions)
            assertEquals("tools", structuredOutput?.options?.get("tools_field"))
            assertEquals("input_schema", structuredOutput?.options?.get("input_schema_field"))

            val effectiveModel = result.effectiveModels().first { it.name == "claude_opus_46" }
            assertNotNull(effectiveModel.nativeSupport)
            assertEquals("tool", effectiveModel.nativeSupport?.structuredOutput?.strategy)
        }

        @Test
        fun `model native support overrides defaults in YAML`() {
            val tempYaml = createTempYamlFile("""
                native_support_defaults:
                  structured_output:
                    supported: true
                    strategy: tool
                    strict: true
                    prompt_instructions: include
                    options:
                      tools_field: tools
                      input_schema_field: input_schema
                models:
                  - name: alias-model
                    model_id: claude-alias
                    native_support:
                      structured_output:
                        supported: true
                        strategy: tool
                        strict: false
                        prompt_instructions: suppress
                        options:
                          tools_field: tools
                          input_schema_field: custom_schema
            """.trimIndent())

            val loader = AnthropicModelLoader(
                resourceLoader = tempYaml.resourceLoader,
                configPath = tempYaml.configPath
            )

            val result = loader.loadAutoConfigMetadata()
            val model = result.effectiveModels().single()
            val structuredOutput = model.nativeSupport?.structuredOutput

            assertNotNull(structuredOutput)
            assertEquals(true, structuredOutput?.supported)
            assertEquals("tool", structuredOutput?.strategy)
            assertEquals(false, structuredOutput?.strict)
            assertEquals("suppress", structuredOutput?.promptInstructions)
            assertEquals("tools", structuredOutput?.options?.get("tools_field"))
            assertEquals("custom_schema", structuredOutput?.options?.get("input_schema_field"))
        }

        @Test
        fun `native support defaults and overrides merge in YAML`() {
            val tempYaml = createTempYamlFile("""
                native_support_defaults:
                  structured_output:
                    supported: true
                    strategy: tool
                    strict: true
                    prompt_instructions: include
                    options:
                      tools_field: tools
                      input_schema_field: input_schema
                models:
                  - name: alias-model
                    model_id: claude-alias
                    native_support:
                      structured_output:
                        supported: true
                        strategy: tool
                        strict: true
                        prompt_instructions: include
                        options:
                          tools_field: tools
                          input_schema_field: input_schema
            """.trimIndent())

            val loader = AnthropicModelLoader(
                resourceLoader = tempYaml.resourceLoader,
                configPath = tempYaml.configPath
            )

            val result = loader.loadAutoConfigMetadata()
            val model = result.effectiveModels().single()
            val structuredOutput = model.nativeSupport?.structuredOutput

            assertNotNull(structuredOutput)
            assertEquals("tool", structuredOutput?.strategy)
            assertEquals("include", structuredOutput?.promptInstructions)
            assertEquals("tools", structuredOutput?.options?.get("tools_field"))
            assertEquals("input_schema", structuredOutput?.options?.get("input_schema_field"))
        }
    }

    @Test
    fun `should load valid model definitions from default YAML file`() {
        // Arrange
        val loader = AnthropicModelLoader()

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
        val loader = AnthropicModelLoader()

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
            model.topK?.let {
                assertTrue(it > 0, "Top K should be positive for ${model.name}")
            }
            model.thinking?.tokenBudget?.let {
                assertTrue(it > 0, "Thinking token budget should be positive for ${model.name}")
            }
        }
    }

    @Test
    fun `should verify specific known models are loaded`() {
        // Arrange
        val loader = AnthropicModelLoader()

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert - verify some known Anthropic models are present
        val modelNames = result.models.map { it.name }
        assertTrue(modelNames.isNotEmpty(), "Should have loaded model names")
        assertTrue(modelNames.contains("claude_opus_48"), "Should include Claude Opus 4.8")

        // Verify the Opus 4.8 model id and pricing are grounded
        val opus48 = result.models.first { it.name == "claude_opus_48" }
        assertEquals(AnthropicModels.CLAUDE_OPUS_4_8, opus48.modelId, "Opus 4.8 model id should match constant")
        assertNotNull(opus48.pricingModel, "Opus 4.8 should have pricing information")
        assertEquals(5.0, opus48.pricingModel?.usdPer1mInputTokens)
        assertEquals(25.0, opus48.pricingModel?.usdPer1mOutputTokens)

        // Verify at least one model has pricing info
        assertTrue(result.models.any { it.pricingModel != null }, "At least one model should have pricing information")
    }

    @Test
    fun `should return empty definitions when file does not exist`() {
        // Arrange
        val loader = AnthropicModelLoader(
            resourceLoader = org.springframework.core.io.DefaultResourceLoader(),
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
        val tempYaml = createTempYamlFile("invalid: yaml: content: ][")

        val loader = AnthropicModelLoader(
            resourceLoader = tempYaml.resourceLoader,
            configPath = tempYaml.configPath
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
        val tempYaml = createTempYamlFile("""
            models:
              - name: test-model
                model_id: test-id
                max_tokens: -100
        """.trimIndent())

        val loader = AnthropicModelLoader(
            resourceLoader = tempYaml.resourceLoader,
            configPath = tempYaml.configPath
        )

        // Act & Assert
        val result = loader.loadAutoConfigMetadata()
        assertTrue(result.models.isEmpty(), "Should fail validation for negative maxTokens")
    }

    @Test
    fun `should validate model with invalid temperature`() {
        // Arrange
        val tempYaml = createTempYamlFile("""
            models:
              - name: test-model
                model_id: test-id
                temperature: 3.0
        """.trimIndent())

        val loader = AnthropicModelLoader(
            resourceLoader = tempYaml.resourceLoader,
            configPath = tempYaml.configPath
        )

        // Act & Assert
        val result = loader.loadAutoConfigMetadata()
        assertTrue(result.models.isEmpty(), "Should fail validation for temperature out of range")
    }

    @Test
    fun `should validate model with invalid topP`() {
        // Arrange
        val tempYaml = createTempYamlFile("""
            models:
              - name: test-model
                model_id: test-id
                top_p: 1.5
        """.trimIndent())

        val loader = AnthropicModelLoader(
            resourceLoader = tempYaml.resourceLoader,
            configPath = tempYaml.configPath
        )

        // Act & Assert
        val result = loader.loadAutoConfigMetadata()
        assertTrue(result.models.isEmpty(), "Should fail validation for topP out of range")
    }

    @Test
    fun `should validate model with blank name`() {
        // Arrange
        val tempYaml = createTempYamlFile("""
            models:
              - name: ""
                model_id: test-id
        """.trimIndent())

        val loader = AnthropicModelLoader(
            resourceLoader = tempYaml.resourceLoader,
            configPath = tempYaml.configPath
        )

        // Act & Assert
        val result = loader.loadAutoConfigMetadata()
        assertTrue(result.models.isEmpty(), "Should fail validation for blank name")
    }

    @Test
    fun `should load valid model with all optional fields`() {
        // Arrange
        val tempYaml = createTempYamlFile("""
            models:
              - name: test-model
                model_id: claude-test
                display_name: Test Model
                max_tokens: 4096
                temperature: 0.7
                top_p: 0.9
                top_k: 50
                thinking:
                  token_budget: 1000
                pricing_model:
                  usd_per1m_input_tokens: 10.0
                  usd_per1m_output_tokens: 20.0
        """.trimIndent())

        val loader = AnthropicModelLoader(
            resourceLoader = tempYaml.resourceLoader,
            configPath = tempYaml.configPath
        )

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertEquals(1, result.models.size)
        val model = result.models.first()
        assertEquals("test-model", model.name)
        assertEquals("claude-test", model.modelId)
        assertEquals("Test Model", model.displayName)
        assertEquals(4096, model.maxTokens)
        assertEquals(0.7, model.temperature)
        assertEquals(0.9, model.topP)
        assertEquals(50, model.topK)
        assertNotNull(model.thinking)
        assertEquals(1000, model.thinking?.tokenBudget)
        assertNotNull(model.pricingModel)
        assertEquals(10.0, model.pricingModel?.usdPer1mInputTokens)
        assertEquals(20.0, model.pricingModel?.usdPer1mOutputTokens)
    }

    @Test
    fun `should load multiple models correctly`() {
        // Arrange
        val tempYaml = createTempYamlFile("""
            models:
              - name: model-1
                model_id: claude-1
                max_tokens: 2000
              - name: model-2
                model_id: claude-2
                max_tokens: 4000
        """.trimIndent())

        val loader = AnthropicModelLoader(
            resourceLoader = tempYaml.resourceLoader,
            configPath = tempYaml.configPath
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
    fun `should validate model with invalid topK`() {
        // Arrange
        val tempYaml = createTempYamlFile("""
            models:
              - name: test-model
                model_id: test-id
                top_k: -5
        """.trimIndent())

        val loader = AnthropicModelLoader(
            resourceLoader = tempYaml.resourceLoader,
            configPath = tempYaml.configPath
        )

        // Act & Assert
        val result = loader.loadAutoConfigMetadata()
        assertTrue(result.models.isEmpty(), "Should fail validation for negative topK")
    }

    @Test
    fun `should validate model with invalid thinking token budget`() {
        // Arrange
        val tempYaml = createTempYamlFile("""
            models:
              - name: test-model
                model_id: test-id
                thinking:
                  token_budget: -1000
        """.trimIndent())

        val loader = AnthropicModelLoader(
            resourceLoader = tempYaml.resourceLoader,
            configPath = tempYaml.configPath
        )

        // Act & Assert
        val result = loader.loadAutoConfigMetadata()
        assertTrue(result.models.isEmpty(), "Should fail validation for negative thinking token budget")
    }

    @Test
    fun `should load model with minimal fields`() {
        // Arrange
        val tempYaml = createTempYamlFile("""
            models:
              - name: minimal-model
                model_id: claude-minimal
        """.trimIndent())

        val loader = AnthropicModelLoader(
            resourceLoader = tempYaml.resourceLoader,
            configPath = tempYaml.configPath
        )

        // Act
        val result = loader.loadAutoConfigMetadata()

        // Assert
        assertEquals(1, result.models.size)
        val model = result.models.first()
        assertEquals("minimal-model", model.name)
        assertEquals("claude-minimal", model.modelId)
        assertNull(model.displayName)
        assertEquals(8192, model.maxTokens) // Default value
        assertEquals(1.0, model.temperature) // Default value
        assertNull(model.topP)
        assertNull(model.topK)
        assertNull(model.thinking)
        assertNull(model.pricingModel)
    }

    private data class TempYamlResource(
        val resourceLoader: ResourceLoader,
        val configPath: String,
    )

    private fun createTempYamlFile(content: String): TempYamlResource {
        val resource = ByteArrayResource(content.toByteArray())
        val resourceLoader = object : ResourceLoader {
            override fun getResource(location: String) = resource
            override fun getClassLoader() = javaClass.classLoader
        }
        return TempYamlResource(
            resourceLoader = resourceLoader,
            configPath = "memory:test-anthropic.yml"
        )
    }
}
