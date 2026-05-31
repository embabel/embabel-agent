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
package com.embabel.agent.onnx.embeddings

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

@DisabledOnOs(OS.WINDOWS, disabledReason = "ONNX Runtime native DLL requires Visual C++ Redistributable")
class OnnxEmbeddingServicePropertiesTest {

    @Test
    fun `pricingModel returns null for local inference`() {
        // Arrange
        val environment = OrtEnvironment.getEnvironment()
        val session = mockk<OrtSession>()
        val tokenizer = mockk<HuggingFaceTokenizer>()
        val service = OnnxEmbeddingService(
            environment = environment,
            session = session,
            tokenizer = tokenizer,
            dimensions = 384,
            name = "test-model"
        )

        // Act
        val pricingModel = service.pricingModel

        // Assert
        assertNull(pricingModel)
    }

    @Test
    fun `service has correct default provider`() {
        // Arrange
        val environment = OrtEnvironment.getEnvironment()
        val session = mockk<OrtSession>()
        val tokenizer = mockk<HuggingFaceTokenizer>()
        val service = OnnxEmbeddingService(
            environment = environment,
            session = session,
            tokenizer = tokenizer
        )

        // Act & Assert
        assertEquals("onnx", service.provider)
    }

    @Test
    fun `service has correct default dimensions`() {
        // Arrange
        val environment = OrtEnvironment.getEnvironment()
        val session = mockk<OrtSession>()
        val tokenizer = mockk<HuggingFaceTokenizer>()
        val service = OnnxEmbeddingService(
            environment = environment,
            session = session,
            tokenizer = tokenizer
        )

        // Act & Assert
        assertEquals(384, service.dimensions)
    }

    @Test
    fun `service has correct default name`() {
        // Arrange
        val environment = OrtEnvironment.getEnvironment()
        val session = mockk<OrtSession>()
        val tokenizer = mockk<HuggingFaceTokenizer>()
        val service = OnnxEmbeddingService(
            environment = environment,
            session = session,
            tokenizer = tokenizer
        )

        // Act & Assert
        assertEquals("all-MiniLM-L6-v2", service.name)
    }

    @Test
    fun `service allows custom dimensions and name`() {
        // Arrange
        val environment = OrtEnvironment.getEnvironment()
        val session = mockk<OrtSession>()
        val tokenizer = mockk<HuggingFaceTokenizer>()
        val service = OnnxEmbeddingService(
            environment = environment,
            session = session,
            tokenizer = tokenizer,
            dimensions = 768,
            name = "custom-model"
        )

        // Act & Assert
        assertEquals(768, service.dimensions)
        assertEquals("custom-model", service.name)
    }

    @Test
    fun `service implements AutoCloseable`() {
        // Arrange
        val environment = OrtEnvironment.getEnvironment()
        val session = mockk<OrtSession>()
        val tokenizer = mockk<HuggingFaceTokenizer>()
        every { session.close() } returns Unit
        every { tokenizer.close() } returns Unit

        val service = OnnxEmbeddingService(
            environment = environment,
            session = session,
            tokenizer = tokenizer
        )

        // Assert
        assertTrue(service is AutoCloseable)
    }
}
