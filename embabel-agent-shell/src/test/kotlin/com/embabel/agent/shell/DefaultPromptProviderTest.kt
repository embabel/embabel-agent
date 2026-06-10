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
package com.embabel.agent.shell

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStyle
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.shell.jline.PromptProvider

class DefaultPromptProviderTest {

    @Test
    fun `should implement PromptProvider interface`() {
        // Arrange & Act
        val provider = DefaultPromptProvider()

        // Assert
        assertTrue(provider is PromptProvider)
    }

    @Test
    fun `should return non-null prompt`() {
        // Arrange
        val provider = DefaultPromptProvider()

        // Act
        val prompt = provider.getPrompt()

        // Assert
        assertNotNull(prompt)
    }

    @Test
    fun `should return AttributedString`() {
        // Arrange
        val provider = DefaultPromptProvider()

        // Act
        val prompt = provider.getPrompt()

        // Assert
        assertTrue(prompt is AttributedString)
    }

    @Test
    fun `should return prompt with correct text`() {
        // Arrange
        val provider = DefaultPromptProvider()

        // Act
        val prompt = provider.getPrompt()

        // Assert
        assertEquals("Type help for available commands.\nembabel> ", prompt.toString())
    }

    @Test
    fun `should return prompt with styling applied`() {
        // Arrange
        val provider = DefaultPromptProvider()

        // Act
        val prompt = provider.getPrompt()

        // Assert
        val style = prompt.styleAt(0)
        assertNotNull(style)
        assertNotEquals(AttributedStyle.DEFAULT, style)
    }

    @Test
    fun `should return same prompt text on multiple calls`() {
        // Arrange
        val provider = DefaultPromptProvider()

        // Act
        val prompt1 = provider.getPrompt()
        val prompt2 = provider.getPrompt()

        // Assert
        assertEquals(prompt1.toString(), prompt2.toString())
    }
}
