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
package com.embabel.agent.openai

import com.embabel.common.ai.model.LlmOptions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StandardOpenAiOptionsConverterTest {

    @Test
    fun `should convert all LlmOptions to OpenAiChatOptions`() {
        // Arrange
        val llmOptions = LlmOptions()
            .withTemperature(0.7)
            .withTopP(0.9)
            .withMaxTokens(1000)
            .withPresencePenalty(0.5)
            .withFrequencyPenalty(0.3)

        // Act
        val result = StandardOpenAiOptionsConverter.convertOptions(llmOptions)

        // Assert
        assertEquals(0.7, result.temperature)
        assertEquals(0.9, result.topP)
        assertEquals(1000, result.maxTokens)
        assertEquals(0.5, result.presencePenalty)
        assertEquals(0.3, result.frequencyPenalty)
    }

    @Test
    fun `should handle null temperature`() {
        // Arrange
        val llmOptions = LlmOptions()

        // Act
        val result = StandardOpenAiOptionsConverter.convertOptions(llmOptions)

        // Assert
        assertNull(result.temperature)
    }

    @Test
    fun `should handle null topP`() {
        // Arrange
        val llmOptions = LlmOptions()

        // Act
        val result = StandardOpenAiOptionsConverter.convertOptions(llmOptions)

        // Assert
        assertNull(result.topP)
    }

    @Test
    fun `should handle null maxTokens`() {
        // Arrange
        val llmOptions = LlmOptions()

        // Act
        val result = StandardOpenAiOptionsConverter.convertOptions(llmOptions)

        // Assert
        assertNull(result.maxTokens)
    }

    @Test
    fun `should handle null penalties`() {
        // Arrange
        val llmOptions = LlmOptions()

        // Act
        val result = StandardOpenAiOptionsConverter.convertOptions(llmOptions)

        // Assert
        assertNull(result.presencePenalty)
        assertNull(result.frequencyPenalty)
    }

    @Test
    fun `should preserve temperature for standard models`() {
        // Arrange
        val llmOptions = LlmOptions().withTemperature(0.5)

        // Act
        val result = StandardOpenAiOptionsConverter.convertOptions(llmOptions)

        // Assert
        assertEquals(0.5, result.temperature)
    }

    @Test
    fun `should handle extreme temperature values`() {
        // Arrange
        val llmOptions = LlmOptions().withTemperature(2.0)

        // Act
        val result = StandardOpenAiOptionsConverter.convertOptions(llmOptions)

        // Assert
        assertEquals(2.0, result.temperature)
    }

    @Test
    fun `should handle zero temperature`() {
        // Arrange
        val llmOptions = LlmOptions().withTemperature(0.0)

        // Act
        val result = StandardOpenAiOptionsConverter.convertOptions(llmOptions)

        // Assert
        assertEquals(0.0, result.temperature)
    }
}
