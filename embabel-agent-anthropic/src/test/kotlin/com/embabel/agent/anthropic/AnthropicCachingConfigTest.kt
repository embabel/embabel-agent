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
package com.embabel.agent.anthropic

import com.embabel.chat.MessageRole
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.ai.anthropic.api.AnthropicCacheTtl

class AnthropicCachingConfigTest {

    @Test
    fun `should create with default values`() {
        // Arrange & Act
        val config = AnthropicCachingConfig()

        // Assert
        assertFalse(config.systemPrompt)
        assertFalse(config.tools)
        assertFalse(config.conversationHistory)
        assertTrue(config.messageTypeMinContentLengths.isEmpty())
        assertTrue(config.messageTypeTtls.isEmpty())
    }

    @Test
    fun `should create with custom values`() {
        // Arrange & Act
        val config = AnthropicCachingConfig(
            systemPrompt = true,
            tools = true,
            conversationHistory = true
        )

        // Assert
        assertTrue(config.systemPrompt)
        assertTrue(config.tools)
        assertTrue(config.conversationHistory)
    }

    @Test
    fun `should set systemPrompt`() {
        // Arrange
        val config = AnthropicCachingConfig()

        // Act
        config.systemPrompt = true

        // Assert
        assertTrue(config.systemPrompt)
    }

    @Test
    fun `should set tools`() {
        // Arrange
        val config = AnthropicCachingConfig()

        // Act
        config.tools = true

        // Assert
        assertTrue(config.tools)
    }

    @Test
    fun `should set conversationHistory`() {
        // Arrange
        val config = AnthropicCachingConfig()

        // Act
        config.conversationHistory = true

        // Assert
        assertTrue(config.conversationHistory)
    }

    @Test
    fun `should add messageTypeMinContentLength and return this`() {
        // Arrange
        val config = AnthropicCachingConfig()

        // Act
        val result = config.messageTypeMinContentLength(MessageRole.USER, 1024)

        // Assert
        assertSame(config, result)
        assertEquals(1024, config.messageTypeMinContentLengths[MessageRole.USER])
    }

    @Test
    fun `should add multiple messageTypeMinContentLengths`() {
        // Arrange
        val config = AnthropicCachingConfig()

        // Act
        config.messageTypeMinContentLength(MessageRole.USER, 1024)
        config.messageTypeMinContentLength(MessageRole.ASSISTANT, 2048)

        // Assert
        assertEquals(2, config.messageTypeMinContentLengths.size)
        assertEquals(1024, config.messageTypeMinContentLengths[MessageRole.USER])
        assertEquals(2048, config.messageTypeMinContentLengths[MessageRole.ASSISTANT])
    }

    @Test
    fun `should add messageTypeTtl and return this`() {
        // Arrange
        val config = AnthropicCachingConfig()

        // Act
        val result = config.messageTypeTtl(MessageRole.USER, AnthropicCacheTtl.FIVE_MINUTES)

        // Assert
        assertSame(config, result)
        assertEquals(AnthropicCacheTtl.FIVE_MINUTES, config.messageTypeTtls[MessageRole.USER])
    }

    @Test
    fun `should add multiple messageTypeTtls`() {
        // Arrange
        val config = AnthropicCachingConfig()

        // Act
        config.messageTypeTtl(MessageRole.USER, AnthropicCacheTtl.FIVE_MINUTES)
        config.messageTypeTtl(MessageRole.ASSISTANT, AnthropicCacheTtl.ONE_HOUR)

        // Assert
        assertEquals(2, config.messageTypeTtls.size)
        assertEquals(AnthropicCacheTtl.FIVE_MINUTES, config.messageTypeTtls[MessageRole.USER])
        assertEquals(AnthropicCacheTtl.ONE_HOUR, config.messageTypeTtls[MessageRole.ASSISTANT])
    }

    @Test
    fun `should support copy with modified systemPrompt`() {
        // Arrange
        val original = AnthropicCachingConfig(systemPrompt = false)

        // Act
        val modified = original.copy(systemPrompt = true)

        // Assert
        assertTrue(modified.systemPrompt)
        assertFalse(original.systemPrompt)
    }

    @Test
    fun `should support chaining messageTypeMinContentLength calls`() {
        // Arrange
        val config = AnthropicCachingConfig()

        // Act
        val result = config
            .messageTypeMinContentLength(MessageRole.USER, 1024)
            .messageTypeMinContentLength(MessageRole.ASSISTANT, 2048)

        // Assert
        assertSame(config, result)
        assertEquals(2, config.messageTypeMinContentLengths.size)
    }

    @Test
    fun `should support chaining messageTypeTtl calls`() {
        // Arrange
        val config = AnthropicCachingConfig()

        // Act
        val result = config
            .messageTypeTtl(MessageRole.USER, AnthropicCacheTtl.FIVE_MINUTES)
            .messageTypeTtl(MessageRole.ASSISTANT, AnthropicCacheTtl.ONE_HOUR)

        // Assert
        assertSame(config, result)
        assertEquals(2, config.messageTypeTtls.size)
    }

    @Test
    fun `should support chaining both methods`() {
        // Arrange
        val config = AnthropicCachingConfig()

        // Act
        val result = config
            .messageTypeMinContentLength(MessageRole.USER, 1024)
            .messageTypeTtl(MessageRole.USER, AnthropicCacheTtl.FIVE_MINUTES)

        // Assert
        assertSame(config, result)
        assertEquals(1, config.messageTypeMinContentLengths.size)
        assertEquals(1, config.messageTypeTtls.size)
    }

    @Test
    fun `should allow overwriting messageTypeMinContentLength`() {
        // Arrange
        val config = AnthropicCachingConfig()

        // Act
        config.messageTypeMinContentLength(MessageRole.USER, 1024)
        config.messageTypeMinContentLength(MessageRole.USER, 2048)

        // Assert
        assertEquals(2048, config.messageTypeMinContentLengths[MessageRole.USER])
    }

    @Test
    fun `should allow overwriting messageTypeTtl`() {
        // Arrange
        val config = AnthropicCachingConfig()

        // Act
        config.messageTypeTtl(MessageRole.USER, AnthropicCacheTtl.FIVE_MINUTES)
        config.messageTypeTtl(MessageRole.USER, AnthropicCacheTtl.ONE_HOUR)

        // Assert
        assertEquals(AnthropicCacheTtl.ONE_HOUR, config.messageTypeTtls[MessageRole.USER])
    }
}
