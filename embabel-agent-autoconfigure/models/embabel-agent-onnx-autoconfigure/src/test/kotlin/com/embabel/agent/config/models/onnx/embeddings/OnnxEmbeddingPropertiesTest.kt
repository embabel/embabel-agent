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
package com.embabel.agent.config.models.onnx.embeddings

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OnnxEmbeddingPropertiesTest {

    @Test
    fun `should create with default values`() {
        // Arrange & Act
        val properties = OnnxEmbeddingProperties()

        // Assert
        assertTrue(properties.enabled)
        assertEquals(OnnxEmbeddingProperties.DEFAULT_MODEL_URI, properties.modelUri)
        assertEquals(OnnxEmbeddingProperties.DEFAULT_TOKENIZER_URI, properties.tokenizerUri)
        assertTrue(properties.cacheDir.endsWith("/.embabel/models"))
        assertTrue(properties.dimensions > 0)
        assertNotNull(properties.modelName)
    }

    @Test
    fun `should set enabled to false`() {
        // Arrange
        val properties = OnnxEmbeddingProperties()

        // Act
        properties.enabled = false

        // Assert
        assertFalse(properties.enabled)
    }

    @Test
    fun `should set custom modelUri`() {
        // Arrange
        val properties = OnnxEmbeddingProperties()
        val customUri = "file:///path/to/model.onnx"

        // Act
        properties.modelUri = customUri

        // Assert
        assertEquals(customUri, properties.modelUri)
    }

    @Test
    fun `should set custom tokenizerUri`() {
        // Arrange
        val properties = OnnxEmbeddingProperties()
        val customUri = "file:///path/to/tokenizer.json"

        // Act
        properties.tokenizerUri = customUri

        // Assert
        assertEquals(customUri, properties.tokenizerUri)
    }

    @Test
    fun `should set custom cacheDir`() {
        // Arrange
        val properties = OnnxEmbeddingProperties()
        val customDir = "/custom/cache/directory"

        // Act
        properties.cacheDir = customDir

        // Assert
        assertEquals(customDir, properties.cacheDir)
    }

    @Test
    fun `should set custom dimensions`() {
        // Arrange
        val properties = OnnxEmbeddingProperties()

        // Act
        properties.dimensions = 512

        // Assert
        assertEquals(512, properties.dimensions)
    }

    @Test
    fun `should set custom modelName`() {
        // Arrange
        val properties = OnnxEmbeddingProperties()

        // Act
        properties.modelName = "custom-model"

        // Assert
        assertEquals("custom-model", properties.modelName)
    }

    @Test
    fun `should have valid default URIs from HuggingFace`() {
        // Arrange & Act
        val properties = OnnxEmbeddingProperties()

        // Assert
        assertTrue(properties.modelUri.startsWith("https://huggingface.co/"))
        assertTrue(properties.tokenizerUri.startsWith("https://huggingface.co/"))
        assertTrue(properties.modelUri.contains("model.onnx"))
        assertTrue(properties.tokenizerUri.contains("tokenizer.json"))
    }

    @Test
    fun `should set all properties together`() {
        // Arrange
        val properties = OnnxEmbeddingProperties()

        // Act
        properties.enabled = false
        properties.modelUri = "file:///model.onnx"
        properties.tokenizerUri = "file:///tokenizer.json"
        properties.cacheDir = "/tmp/cache"
        properties.dimensions = 256
        properties.modelName = "test-model"

        // Assert
        assertFalse(properties.enabled)
        assertEquals("file:///model.onnx", properties.modelUri)
        assertEquals("file:///tokenizer.json", properties.tokenizerUri)
        assertEquals("/tmp/cache", properties.cacheDir)
        assertEquals(256, properties.dimensions)
        assertEquals("test-model", properties.modelName)
    }

    @Test
    fun `companion object should have correct default URIs`() {
        // Assert
        assertTrue(OnnxEmbeddingProperties.DEFAULT_MODEL_URI.contains("sentence-transformers"))
        assertTrue(OnnxEmbeddingProperties.DEFAULT_TOKENIZER_URI.contains("sentence-transformers"))
    }
}
