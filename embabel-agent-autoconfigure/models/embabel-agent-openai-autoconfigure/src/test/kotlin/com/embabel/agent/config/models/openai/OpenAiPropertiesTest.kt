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
package com.embabel.agent.config.models.openai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OpenAiPropertiesTest {

    @Test
    fun `should create with default values`() {
        // Arrange & Act
        val properties = OpenAiProperties()

        // Assert
        assertNull(properties.baseUrl)
        assertNull(properties.apiKey)
        assertNull(properties.completions)
        assertNull(properties.embeddingsPath)
        assertEquals(10, properties.maxAttempts)
        assertEquals(5000L, properties.backoffMillis)
        assertEquals(5.0, properties.backoffMultiplier)
        assertEquals(180000L, properties.backoffMaxInterval)
    }

    @Test
    fun `should set baseUrl`() {
        // Arrange
        val properties = OpenAiProperties()

        // Act
        properties.baseUrl = "https://api.openai.com"

        // Assert
        assertEquals("https://api.openai.com", properties.baseUrl)
    }

    @Test
    fun `should set apiKey`() {
        // Arrange
        val properties = OpenAiProperties()

        // Act
        properties.apiKey = "sk-test-key-123"

        // Assert
        assertEquals("sk-test-key-123", properties.apiKey)
    }

    @Test
    fun `should set completions path`() {
        // Arrange
        val properties = OpenAiProperties()

        // Act
        properties.completions = "/v1/chat/completions"

        // Assert
        assertEquals("/v1/chat/completions", properties.completions)
    }

    @Test
    fun `should set embeddingsPath`() {
        // Arrange
        val properties = OpenAiProperties()

        // Act
        properties.embeddingsPath = "/v1/embeddings"

        // Assert
        assertEquals("/v1/embeddings", properties.embeddingsPath)
    }

    @Test
    fun `should set maxAttempts`() {
        // Arrange
        val properties = OpenAiProperties()

        // Act
        properties.maxAttempts = 5

        // Assert
        assertEquals(5, properties.maxAttempts)
    }

    @Test
    fun `should set backoffMillis`() {
        // Arrange
        val properties = OpenAiProperties()

        // Act
        properties.backoffMillis = 3000L

        // Assert
        assertEquals(3000L, properties.backoffMillis)
    }

    @Test
    fun `should set backoffMultiplier`() {
        // Arrange
        val properties = OpenAiProperties()

        // Act
        properties.backoffMultiplier = 2.5

        // Assert
        assertEquals(2.5, properties.backoffMultiplier)
    }

    @Test
    fun `should set backoffMaxInterval`() {
        // Arrange
        val properties = OpenAiProperties()

        // Act
        properties.backoffMaxInterval = 120000L

        // Assert
        assertEquals(120000L, properties.backoffMaxInterval)
    }

    @Test
    fun `should implement RetryProperties interface`() {
        // Arrange
        val properties = OpenAiProperties()

        // Assert
        assertTrue(properties is com.embabel.agent.spi.common.RetryProperties)
    }

    @Test
    fun `should set all properties together`() {
        // Arrange
        val properties = OpenAiProperties()

        // Act
        properties.baseUrl = "https://custom.api.com"
        properties.apiKey = "test-key"
        properties.completions = "/completions"
        properties.embeddingsPath = "/embeddings"
        properties.maxAttempts = 3
        properties.backoffMillis = 1000L
        properties.backoffMultiplier = 2.0
        properties.backoffMaxInterval = 60000L

        // Assert
        assertEquals("https://custom.api.com", properties.baseUrl)
        assertEquals("test-key", properties.apiKey)
        assertEquals("/completions", properties.completions)
        assertEquals("/embeddings", properties.embeddingsPath)
        assertEquals(3, properties.maxAttempts)
        assertEquals(1000L, properties.backoffMillis)
        assertEquals(2.0, properties.backoffMultiplier)
        assertEquals(60000L, properties.backoffMaxInterval)
    }
}
