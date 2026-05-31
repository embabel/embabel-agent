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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MarkdownTest {

    @Test
    fun `should convert bold text`() {
        // Arrange
        val markdown = "This is **bold** text"

        // Act
        val result = markdownToConsole(markdown)

        // Assert
        assertTrue(result.contains("\u001B[1mbold\u001B[0m"))
        assertTrue(result.contains("This is"))
        assertTrue(result.contains("text"))
    }

    @Test
    fun `should convert italic text`() {
        // Arrange
        val markdown = "This is *italic* text"

        // Act
        val result = markdownToConsole(markdown)

        // Assert
        assertTrue(result.contains("\u001B[3mitalic\u001B[0m"))
    }

    @Test
    fun `should convert underline text`() {
        // Arrange
        val markdown = "This is __underlined__ text"

        // Act
        val result = markdownToConsole(markdown)

        // Assert
        assertTrue(result.contains("\u001B[4munderlined\u001B[0m"))
    }

    @Test
    fun `should convert strikethrough text`() {
        // Arrange
        val markdown = "This is ~~strikethrough~~ text"

        // Act
        val result = markdownToConsole(markdown)

        // Assert
        assertTrue(result.contains("\u001B[9mstrikethrough\u001B[0m"))
    }

    @Test
    fun `should convert blockquote`() {
        // Arrange
        val markdown = "> This is a quote"

        // Act
        val result = markdownToConsole(markdown)

        // Assert
        assertTrue(result.contains("\u001B[3m\u001B[34m\u001B[1m> This is a quote\u001B[22m\u001B[0m"))
    }

    @Test
    fun `should convert numbered list`() {
        // Arrange
        val markdown = "1. First item"

        // Act
        val result = markdownToConsole(markdown)

        // Assert
        assertTrue(result.contains("\u001B[35m\u001B[1m1.\u001B[22m\u001B[0m First item"))
    }

    @Test
    fun `should convert bullet list with dash`() {
        // Arrange
        val markdown = "- List item"

        // Act
        val result = markdownToConsole(markdown)

        // Assert
        assertTrue(result.contains("\u001B[35m\u001B[1m-\u001B[22m\u001B[0m List item"))
    }

    @Test
    fun `should convert bullet list with asterisk`() {
        // Arrange
        val markdown = "* List item"

        // Act
        val result = markdownToConsole(markdown)

        // Assert
        assertTrue(result.contains("\u001B[35m\u001B[1m*\u001B[22m\u001B[0m List item"))
    }

    @Test
    fun `should convert inline code`() {
        // Arrange
        val markdown = "Use `console.log()` to print"

        // Act
        val result = markdownToConsole(markdown)

        // Assert
        assertTrue(result.contains("\u001B[57;107mconsole.log()\u001B[0m"))
    }

    @Test
    fun `should convert code block`() {
        // Arrange
        val markdown = """
            ```kotlin
            fun hello() = println("Hello")
            ```
        """.trimIndent()

        // Act
        val result = markdownToConsole(markdown)

        // Assert
        assertTrue(result.contains("\u001B[3m\u001B[1mkotlin\u001B[22m\u001B[0m"))
        assertTrue(result.contains("\u001B[57;107mfun hello() = println(\"Hello\")\u001B[0m"))
    }

    @Test
    fun `should convert code block without language`() {
        // Arrange
        val markdown = """
            ```
            code here
            ```
        """.trimIndent()

        // Act
        val result = markdownToConsole(markdown)

        // Assert
        assertTrue(result.contains("\u001B[57;107mcode here\u001B[0m"))
    }

    @Test
    fun `should convert header with hash`() {
        // Arrange
        val markdown = "# Heading 1\n"

        // Act
        val result = markdownToConsole(markdown)

        // Assert
        assertTrue(result.contains("\u001B[36m\u001B[1m# Heading 1\u001B[22m\u001B[0m"))
    }

    @Test
    fun `should convert header with multiple hashes`() {
        // Arrange
        val markdown = "### Heading 3\n"

        // Act
        val result = markdownToConsole(markdown)

        // Assert
        assertTrue(result.contains("\u001B[36m\u001B[1m### Heading 3\u001B[22m\u001B[0m"))
    }

    @Test
    fun `should convert header with equals underline`() {
        // Arrange
        val markdown = "Heading\n===\n"

        // Act
        val result = markdownToConsole(markdown)

        // Assert
        assertTrue(result.contains("\u001B[36m\u001B[1mHeading\n===\n\u001B[22m\u001B[0m"))
    }

    @Test
    fun `should convert header with dash underline`() {
        // Arrange
        val markdown = "Heading\n---\n"

        // Act
        val result = markdownToConsole(markdown)

        // Assert
        assertTrue(result.contains("\u001B[36m\u001B[1mHeading\n---\n\u001B[22m\u001B[0m"))
    }

    @Test
    fun `should convert links`() {
        // Arrange
        val markdown = "[Google](https://google.com)"

        // Act
        val result = markdownToConsole(markdown)

        // Assert
        assertTrue(result.contains("\u001B[34mGoogle\u001B[0m"))
        assertTrue(result.contains("\u001B[34m\u001B[4mhttps://google.com\u001B[0m"))
    }

    @Test
    fun `should convert images`() {
        // Arrange
        val markdown = "![Alt text](https://example.com/image.png)"

        // Act
        val result = markdownToConsole(markdown)

        // Assert
        assertTrue(result.contains("\u001B[34mAlt text\u001B[0m"))
        assertTrue(result.contains("\u001B[34m\u001B[4mhttps://example.com/image.png\u001B[0m"))
    }

    @Test
    fun `should handle plain text without markdown`() {
        // Arrange
        val markdown = "Just plain text"

        // Act
        val result = markdownToConsole(markdown)

        // Assert
        assertEquals("Just plain text", result)
    }

    @Test
    fun `should handle empty string`() {
        // Arrange
        val markdown = ""

        // Act
        val result = markdownToConsole(markdown)

        // Assert
        assertEquals("", result)
    }

    @Test
    fun `should handle multiple formatting in one line`() {
        // Arrange
        val markdown = "This is **bold** and *italic* and `code`"

        // Act
        val result = markdownToConsole(markdown)

        // Assert
        assertTrue(result.contains("\u001B[1mbold\u001B[0m"))
        assertTrue(result.contains("\u001B[3mitalic\u001B[0m"))
        assertTrue(result.contains("\u001B[57;107mcode\u001B[0m"))
    }

    @Test
    fun `should handle multiline markdown with various elements`() {
        // Arrange
        val markdown = """
            # Main Title

            This is **bold** and *italic*.

            - Item 1
            - Item 2

            > A quote

            `inline code`
        """.trimIndent()

        // Act
        val result = markdownToConsole(markdown)

        // Assert
        assertNotNull(result)
        assertTrue(result.contains("\u001B[1mbold\u001B[0m"))
        assertTrue(result.contains("\u001B[3mitalic\u001B[0m"))
        assertTrue(result.contains("\u001B[35m\u001B[1m-\u001B[22m\u001B[0m"))
        assertTrue(result.contains("\u001B[57;107minline code\u001B[0m"))
    }
}
