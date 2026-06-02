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
package com.embabel.agent.prompt

import com.embabel.common.ai.prompt.PromptContributor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ResponseFormatTest {

    @Test
    fun `should create ResponseFormat with custom format`() {
        // Arrange & Act
        val format = ResponseFormat("JSON")

        // Assert
        assertEquals("JSON", format.format)
    }

    @Test
    fun `should implement PromptContributor interface`() {
        // Arrange & Act
        val format = ResponseFormat("Markdown")

        // Assert
        assertTrue(format is PromptContributor)
    }

    @Test
    fun `should have role response_format`() {
        // Arrange & Act
        val format = ResponseFormat("HTML")

        // Assert
        assertEquals("response_format", format.role)
    }

    @Test
    fun `contribution should include format in response format section`() {
        // Arrange
        val format = ResponseFormat("XML")

        // Act
        val contribution = format.contribution()

        // Assert
        assertTrue(contribution.contains("# RESPONSE FORMAT #"))
        assertTrue(contribution.contains("XML"))
    }

    @Test
    fun `should have MARKDOWN constant`() {
        // Assert
        assertNotNull(ResponseFormat.MARKDOWN)
        assertEquals("Markdown", ResponseFormat.MARKDOWN.format)
    }

    @Test
    fun `should have HTML constant`() {
        // Assert
        assertNotNull(ResponseFormat.HTML)
        assertEquals("HTML", ResponseFormat.HTML.format)
    }

    @Test
    fun `MARKDOWN constant should have correct contribution`() {
        // Act
        val contribution = ResponseFormat.MARKDOWN.contribution()

        // Assert
        assertTrue(contribution.contains("# RESPONSE FORMAT #"))
        assertTrue(contribution.contains("Markdown"))
    }

    @Test
    fun `HTML constant should have correct contribution`() {
        // Act
        val contribution = ResponseFormat.HTML.contribution()

        // Assert
        assertTrue(contribution.contains("# RESPONSE FORMAT #"))
        assertTrue(contribution.contains("HTML"))
    }

    @Test
    fun `should support copy with modified format`() {
        // Arrange
        val original = ResponseFormat("JSON")

        // Act
        val modified = original.copy(format = "YAML")

        // Assert
        assertEquals("YAML", modified.format)
        assertEquals("JSON", original.format)
    }

    @Test
    fun `should support equality`() {
        // Arrange
        val format1 = ResponseFormat("JSON")
        val format2 = ResponseFormat("JSON")
        val format3 = ResponseFormat("XML")

        // Assert
        assertEquals(format1, format2)
        assertNotEquals(format1, format3)
    }

    @Test
    fun `should allow empty format string`() {
        // Arrange & Act
        val format = ResponseFormat("")

        // Assert
        assertEquals("", format.format)
    }

    @Test
    fun `contribution should not modify format string`() {
        // Arrange
        val customFormat = "Use bullet points:\n- Point 1\n- Point 2"
        val format = ResponseFormat(customFormat)

        // Act
        val contribution = format.contribution()

        // Assert
        assertTrue(contribution.contains(customFormat))
    }
}
