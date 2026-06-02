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
package com.embabel.agent.rag.store

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DocumentDeletionResultTest {

    @Test
    fun `should create DocumentDeletionResult with rootUri and deletedCount`() {
        // Arrange & Act
        val result = DocumentDeletionResult(
            rootUri = "https://example.com/doc",
            deletedCount = 5
        )

        // Assert
        assertEquals("https://example.com/doc", result.rootUri)
        assertEquals(5, result.deletedCount)
    }

    @Test
    fun `should create DocumentDeletionResult with zero deletedCount`() {
        // Arrange & Act
        val result = DocumentDeletionResult(
            rootUri = "https://example.com/empty",
            deletedCount = 0
        )

        // Assert
        assertEquals("https://example.com/empty", result.rootUri)
        assertEquals(0, result.deletedCount)
    }

    @Test
    fun `should support copy with modified rootUri`() {
        // Arrange
        val original = DocumentDeletionResult("original-uri", 10)

        // Act
        val modified = original.copy(rootUri = "modified-uri")

        // Assert
        assertEquals("modified-uri", modified.rootUri)
        assertEquals(10, modified.deletedCount)
        assertEquals("original-uri", original.rootUri)
    }

    @Test
    fun `should support copy with modified deletedCount`() {
        // Arrange
        val original = DocumentDeletionResult("https://example.com", 5)

        // Act
        val modified = original.copy(deletedCount = 15)

        // Assert
        assertEquals("https://example.com", modified.rootUri)
        assertEquals(15, modified.deletedCount)
        assertEquals(5, original.deletedCount)
    }

    @Test
    fun `should support equality`() {
        // Arrange
        val result1 = DocumentDeletionResult("uri1", 3)
        val result2 = DocumentDeletionResult("uri1", 3)
        val result3 = DocumentDeletionResult("uri2", 3)

        // Assert
        assertEquals(result1, result2)
        assertNotEquals(result1, result3)
    }

    @Test
    fun `should support component functions`() {
        // Arrange
        val result = DocumentDeletionResult("test-uri", 7)

        // Act
        val (uri, count) = result

        // Assert
        assertEquals("test-uri", uri)
        assertEquals(7, count)
    }

    @Test
    fun `should handle large deletedCount`() {
        // Arrange & Act
        val result = DocumentDeletionResult("https://large.com", 10000)

        // Assert
        assertEquals(10000, result.deletedCount)
    }

    @Test
    fun `should handle empty rootUri`() {
        // Arrange & Act
        val result = DocumentDeletionResult("", 5)

        // Assert
        assertEquals("", result.rootUri)
        assertEquals(5, result.deletedCount)
    }

    @Test
    fun `should handle file URI scheme`() {
        // Arrange & Act
        val result = DocumentDeletionResult("file:///path/to/doc", 3)

        // Assert
        assertEquals("file:///path/to/doc", result.rootUri)
    }

    @Test
    fun `hashCode should be consistent`() {
        // Arrange
        val result1 = DocumentDeletionResult("uri", 5)
        val result2 = DocumentDeletionResult("uri", 5)

        // Act & Assert
        assertEquals(result1.hashCode(), result2.hashCode())
    }

    @Test
    fun `toString should contain field values`() {
        // Arrange
        val result = DocumentDeletionResult("test-uri", 42)

        // Act
        val string = result.toString()

        // Assert
        assertTrue(string.contains("test-uri"))
        assertTrue(string.contains("42"))
    }
}
