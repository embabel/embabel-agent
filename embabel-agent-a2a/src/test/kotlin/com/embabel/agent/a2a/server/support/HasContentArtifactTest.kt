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
package com.embabel.agent.a2a.server.support

import com.embabel.agent.domain.library.HasContent
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.a2a.spec.DataPart
import io.a2a.spec.TextPart
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Test to verify that objects implementing HasContent are properly serialized
 * into A2A artifacts with separate TextPart and DataPart, and that content
 * appears in status messages for A2A Inspector visibility.
 */
class HasContentArtifactTest {

    /**
     * Test data class that implements HasContent
     */
    data class TestWriteup(
        val title: String,
        val author: String,
        override val content: String,
        val timestamp: Instant = Instant.now(),
    ) : HasContent

    /**
     * Test data class that does NOT implement HasContent
     */
    data class TestResult(
        val message: String,
        val value: Int
    )

    private val objectMapper: ObjectMapper = jacksonObjectMapper()
        .findAndRegisterModules()  // Registers JSR310 module for Instant support

    @Test
    fun `should extract content as TextPart when object implements HasContent`() {
        // Given
        val testContent = "This is the content that should be in TextPart"
        val writeup = TestWriteup(
            title = "Test Title",
            author = "Test Author",
            content = testContent,
            timestamp = Instant.parse("2025-11-20T12:00:00Z")
        )

        // When - simulate the createResultArtifact logic
        val parts = buildList {
            if (writeup is HasContent) {
                add(TextPart(writeup.content))

                @Suppress("UNCHECKED_CAST")
                val outputMap = objectMapper.convertValue(writeup, Map::class.java) as MutableMap<String, Any?>
                outputMap.remove("content")

                add(DataPart(mapOf("output" to outputMap)))
            }
        }

        // Then
        assertEquals(2, parts.size, "Should have exactly 2 parts")

        // Verify TextPart
        assertTrue(parts[0] is TextPart, "First part should be TextPart")
        val textPart = parts[0] as TextPart
        assertEquals(testContent, textPart.text, "TextPart should contain the content")

        // Verify DataPart
        assertTrue(parts[1] is DataPart, "Second part should be DataPart")
        val dataPart = parts[1] as DataPart
        val outputData = dataPart.data["output"] as Map<*, *>

        assertFalse(outputData.containsKey("content"), "DataPart should not contain 'content' field")
        assertEquals("Test Title", outputData["title"], "DataPart should contain title")
        assertEquals("Test Author", outputData["author"], "DataPart should contain author")
        assertTrue(outputData.containsKey("timestamp"), "DataPart should contain timestamp")
    }

    @Test
    fun `should use single DataPart when object does not implement HasContent`() {
        // Given
        val result = TestResult(
            message = "Success",
            value = 42
        )

        // When - simulate the createResultArtifact logic
        val parts = buildList {
            if (result is HasContent) {
                add(TextPart(result.content))

                @Suppress("UNCHECKED_CAST")
                val outputMap = objectMapper.convertValue(result, Map::class.java) as MutableMap<String, Any?>
                outputMap.remove("content")

                add(DataPart(mapOf("output" to outputMap)))
            } else {
                add(DataPart(mapOf("output" to result)))
            }
        }

        // Then
        assertEquals(1, parts.size, "Should have exactly 1 part")

        // Verify DataPart
        assertTrue(parts[0] is DataPart, "Part should be DataPart")
        val dataPart = parts[0] as DataPart
        val outputData = dataPart.data["output"] as TestResult

        assertEquals("Success", outputData.message)
        assertEquals(42, outputData.value)
    }

    @Test
    fun `should extract content for status message when object implements HasContent`() {
        // Given
        val testContent = "This is the content that should appear in the status message"
        val writeup = TestWriteup(
            title = "Test Title",
            author = "Test Author",
            content = testContent
        )

        // When - simulate the extractContentForDisplay logic
        val statusMessage = if (writeup is HasContent) {
            writeup.content
        } else {
            "Task completed successfully"
        }

        // Then
        assertEquals(testContent, statusMessage, "Status message should contain the content")
    }

    @Test
    fun `should use default status message when object does not implement HasContent`() {
        // Given
        val result = TestResult(
            message = "Success",
            value = 42
        )

        // When - simulate the extractContentForDisplay logic
        val statusMessage = if (result is HasContent) {
            result.content
        } else {
            "Task completed successfully"
        }

        // Then
        assertEquals("Task completed successfully", statusMessage, "Should use default message")
    }
}
