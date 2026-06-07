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
package com.embabel.agent.config.models.ocigenai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader
import java.io.File
import java.nio.file.Files

class OciGenAiModelLoaderTest {

    @Test
    fun `should load valid model definitions from default YAML file`() {
        val result = OciGenAiModelLoader().loadAutoConfigMetadata()

        assertTrue(result.models.isNotEmpty(), "Should load at least one LLM model")
        assertTrue(result.embeddingModels.isNotEmpty(), "Should load at least one embedding model")
        assertTrue(result.models.any { it.apiFormat == OciGenAiApiFormat.GENERIC }, "Should include generic chat models")
        assertTrue(result.models.any { it.apiFormat == OciGenAiApiFormat.COHERE_V2 }, "Should include Cohere chat models")

        val firstModel = result.models.first()
        assertNotNull(firstModel.name)
        assertTrue(firstModel.modelId.isNotBlank())

        val firstEmbedding = result.embeddingModels.first()
        assertTrue(firstEmbedding.name.isNotBlank())
        assertTrue(firstEmbedding.modelId.isNotBlank())
    }

    @Test
    fun `should validate all loaded model defaults`() {
        val result = OciGenAiModelLoader().loadAutoConfigMetadata()

        result.models.forEach { model ->
            model.maxTokens?.let { assertTrue(it > 0, "Max tokens should be positive for ${model.name}") }
            model.temperature?.let { assertTrue(it in 0.0..2.0, "Temperature should be valid for ${model.name}") }
            model.topP?.let { assertTrue(it in 0.0..1.0, "Top P should be valid for ${model.name}") }
            model.topK?.let { assertTrue(it > 0, "Top K should be positive for ${model.name}") }
        }

        result.embeddingModels.forEach { model ->
            model.dimensions?.let { assertTrue(it > 0, "Dimensions should be positive for ${model.name}") }
        }
    }

    @Test
    fun `should return empty definitions when file does not exist`() {
        val result = OciGenAiModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "classpath:does-not-exist.yml",
        ).loadAutoConfigMetadata()

        assertTrue(result.models.isEmpty())
        assertTrue(result.embeddingModels.isEmpty())
    }

    @Test
    fun `should reject invalid temperature`() {
        val tempFile = createTempYamlFile(
            """
            models:
              - name: invalid-temperature
                model_id: meta.test
                temperature: 3.0
            """.trimIndent()
        )

        val result = OciGenAiModelLoader(
            resourceLoader = DefaultResourceLoader(),
            configPath = "file:${tempFile.absolutePath}",
        ).loadAutoConfigMetadata()

        assertTrue(result.models.isEmpty())
    }

    @Test
    fun `should parse embedding enum values`() {
        val result = OciGenAiModelLoader().loadAutoConfigMetadata()
        val embedV4 = result.embeddingModels.first { it.name == "cohere_embed_v4" }

        assertEquals(OciGenAiEmbeddingTruncate.END, embedV4.truncate)
    }

    private fun createTempYamlFile(content: String): File {
        val tempFile = Files.createTempFile("oci-genai-models", ".yml").toFile()
        tempFile.writeText(content)
        tempFile.deleteOnExit()
        return tempFile
    }
}
