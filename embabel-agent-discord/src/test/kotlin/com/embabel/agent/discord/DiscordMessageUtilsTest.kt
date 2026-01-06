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
package com.embabel.agent.discord

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class DiscordMessageUtilsTest {

    @Test
    fun `splitMessage should return single message when content is short`() {
        val content = "This is a short message"
        val result = DiscordMessageUtils.splitMessage(content)

        assertEquals(1, result.size)
        assertEquals(content, result[0])
    }

    @Test
    fun `splitMessage should preserve simple code blocks across splits`() {
        val longContent = "Here's some text " + "x".repeat(1950) + "\n```kotlin\nfun example() {\n    println(\"Hello\")\n}\n```\nMore text"
        val result = DiscordMessageUtils.splitMessage(longContent)

        assertTrue(result.size > 1)

        // Find the message containing the code block
        val codeBlockMessage = result.find { it.contains("```kotlin") }
        assertNotNull(codeBlockMessage)

        // Ensure the code block is properly closed
        assertTrue(codeBlockMessage!!.startsWith("```kotlin") || codeBlockMessage.contains("\n```kotlin"))
        assertTrue(codeBlockMessage.endsWith("```") || codeBlockMessage.contains("```\n"))
    }

    @Test
    fun `splitMessage should handle code blocks that span multiple splits`() {
        val largeCodeBlock = "```kotlin\n" + "// ".repeat(1000) + "Long comment\n" + "fun test() {\n" + "    // ".repeat(1000) + "Another long comment\n}\n```"
        val result = DiscordMessageUtils.splitMessage(largeCodeBlock)

        assertTrue(result.size > 1)

        // Each message should start and end with code block markers
        result.forEach { message ->
            if (message.contains("kotlin") || message.contains("Long comment") || message.contains("fun test")) {
                assertTrue(message.startsWith("```") || message.startsWith("```kotlin"))
                assertTrue(message.endsWith("```"))
            }
        }
    }

    @Test
    fun `splitMessage should handle nested code blocks correctly`() {
        val content = """
            Here's some documentation:

            ```markdown
            # Example

            Use this code:

            ```kotlin
            fun hello() = "world"
            ```

            That's it!
            ```

            End of documentation.
        """.trimIndent()

        val result = DiscordMessageUtils.splitMessage(content)

        // Should be split appropriately, but code blocks should be preserved
        result.forEach { message ->
            val openBlocks = message.count { it == '`' && message.indexOf(it) % 3 == 0 }
            val closeBlocks = message.count { it == '`' && message.lastIndexOf(it) % 3 == 2 }
            // Code blocks should be balanced within each message
            assertTrue(message.contains("```").not() || (openBlocks % 2 == 0))
        }
    }

    @Test
    fun `splitMessage should handle inline code with backticks`() {
        val content = "Here's some `inline code` and more text. " + "x".repeat(1950) + " Here's more `inline code`."
        val result = DiscordMessageUtils.splitMessage(content)

        assertTrue(result.size > 1)

        // Inline code should be preserved where possible
        result.forEach { message ->
            if (message.contains("`") && !message.contains("```")) {
                val backtickCount = message.count { it == '`' }
                // For inline code, backticks should be balanced (even number)
                assertTrue(backtickCount % 2 == 0)
            }
        }
    }

    @Test
    fun `splitMessage should handle code blocks with different languages`() {
        val content = """
            ```python
            def hello():
                return "world"
            ```

            And some JavaScript:

            ```javascript
            function hello() {
                return "world";
            }
            ```
        """.trimIndent() + "x".repeat(1800)

        val result = DiscordMessageUtils.splitMessage(content)

        // Verify language tags are preserved
        val pythonMessage = result.find { it.contains("```python") }
        val jsMessage = result.find { it.contains("```javascript") }

        if (pythonMessage != null) {
            assertTrue(pythonMessage.contains("def hello()"))
        }
        if (jsMessage != null) {
            assertTrue(jsMessage.contains("function hello()"))
        }
    }

    @Test
    fun `splitMessage should handle extremely long single lines`() {
        val longLine = "This is a very long line that exceeds Discord's limit: " + "x".repeat(2100)
        val result = DiscordMessageUtils.splitMessage(longLine)

        assertTrue(result.size > 1)
        result.forEach { message ->
            assertTrue(message.length <= 2000)
        }
    }

    @Test
    fun `splitMessage should handle extremely long single lines within code blocks`() {
        val longCodeLine = "```kotlin\nval veryLongString = \"" + "x".repeat(2100) + "\"\n```"
        val result = DiscordMessageUtils.splitMessage(longCodeLine)

        assertTrue(result.size > 1)
        result.forEach { message ->
            assertTrue(message.length <= 2000)
            // Each chunk should be wrapped in code block markers
            if (message.contains("veryLongString") || message.contains("x")) {
                assertTrue(message.startsWith("```") || message.startsWith("```kotlin"))
                assertTrue(message.endsWith("```"))
            }
        }
    }

    @Test
    fun `splitMessage should preserve empty lines in code blocks`() {
        val content = """
            ```kotlin
            fun test() {
                println("start")

                // Empty line above
                println("end")
            }
            ```
        """.trimIndent()

        val result = DiscordMessageUtils.splitMessage(content)

        val codeMessage = result.find { it.contains("```kotlin") }
        assertNotNull(codeMessage)
        assertTrue(codeMessage!!.contains("println(\"start\")\n\n    // Empty line above"))
    }

    @Test
    fun `splitMessage should handle multiple consecutive code blocks`() {
        val content = """
            First block:
            ```kotlin
            fun first() = 1
            ```

            Second block:
            ```kotlin
            fun second() = 2
            ```

            Third block:
            ```kotlin
            fun third() = 3
            ```
        """.trimIndent() + "x".repeat(1800)

        val result = DiscordMessageUtils.splitMessage(content)

        // Should properly handle transitions between code blocks
        assertTrue(result.isNotEmpty())

        result.forEach { message ->
            if (message.contains("```")) {
                // Count opening and closing markers
                val markers = message.split("```").size - 1
                // Should be even (balanced) unless it's a continuation
                assertTrue(markers % 2 == 0 || message.startsWith("```") || message.endsWith("```"))
            }
        }
    }
}
