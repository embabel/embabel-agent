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

import com.embabel.agent.onnx.embeddings.OnnxEmbeddingService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OnnxEmbeddingPropertiesTest {

    @Test
    fun `default properties have expected values`() {
        val props = OnnxEmbeddingProperties()

        assertTrue(props.enabled)
        assertEquals(OnnxEmbeddingService.DEFAULT_DIMENSIONS, props.dimensions)
        assertEquals(OnnxEmbeddingService.DEFAULT_MODEL_NAME, props.modelName)
        assertTrue(props.cacheDir.endsWith("/.embabel/models"))
    }

    @Test
    fun `default URIs point to HuggingFace`() {
        val props = OnnxEmbeddingProperties()

        assertTrue(props.modelUri.startsWith("https://huggingface.co/"))
        assertTrue(props.modelUri.endsWith("model.onnx"))
        assertTrue(props.tokenizerUri.startsWith("https://huggingface.co/"))
        assertTrue(props.tokenizerUri.endsWith("tokenizer.json"))
    }

    @Test
    fun `properties can be overridden`() {
        val props = OnnxEmbeddingProperties()
        props.enabled = false
        props.dimensions = 768
        props.modelName = "custom-model"
        props.modelUri = "file:///local/model.onnx"
        props.tokenizerUri = "file:///local/tokenizer.json"
        props.cacheDir = "/tmp/models"

        assertFalse(props.enabled)
        assertEquals(768, props.dimensions)
        assertEquals("custom-model", props.modelName)
        assertEquals("file:///local/model.onnx", props.modelUri)
        assertEquals("file:///local/tokenizer.json", props.tokenizerUri)
        assertEquals("/tmp/models", props.cacheDir)
    }
}
