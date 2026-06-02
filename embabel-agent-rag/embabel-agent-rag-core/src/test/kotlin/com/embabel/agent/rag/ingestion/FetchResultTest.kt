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
package com.embabel.agent.rag.ingestion

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.util.MimeType

class FetchResultTest {

    @Test
    fun `should create FetchResult with content and contentType`() {
        // Arrange
        val content = "test content".toByteArray()
        val mimeType = MimeType("text", "plain")

        // Act
        val result = FetchResult(content, mimeType)

        // Assert
        assertArrayEquals(content, result.content)
        assertEquals(mimeType, result.contentType)
    }

    @Test
    fun `should create FetchResult with content only`() {
        // Arrange
        val content = "test content".toByteArray()

        // Act
        val result = FetchResult(content)

        // Assert
        assertArrayEquals(content, result.content)
        assertNull(result.contentType)
    }

    @Test
    fun `equals should return true for same instance`() {
        // Arrange
        val result = FetchResult("test".toByteArray(), MimeType("text", "plain"))

        // Act & Assert
        assertTrue(result.equals(result))
    }

    @Test
    fun `equals should return true for identical content and contentType`() {
        // Arrange
        val content = "test content".toByteArray()
        val mimeType = MimeType("text", "html")
        val result1 = FetchResult(content, mimeType)
        val result2 = FetchResult(content, mimeType)

        // Act & Assert
        assertEquals(result1, result2)
    }

    @Test
    fun `equals should return false for different content`() {
        // Arrange
        val mimeType = MimeType("text", "plain")
        val result1 = FetchResult("content1".toByteArray(), mimeType)
        val result2 = FetchResult("content2".toByteArray(), mimeType)

        // Act & Assert
        assertNotEquals(result1, result2)
    }

    @Test
    fun `equals should return false for different contentType`() {
        // Arrange
        val content = "test".toByteArray()
        val result1 = FetchResult(content, MimeType("text", "plain"))
        val result2 = FetchResult(content, MimeType("text", "html"))

        // Act & Assert
        assertNotEquals(result1, result2)
    }

    @Test
    fun `equals should return false for null contentType vs non-null`() {
        // Arrange
        val content = "test".toByteArray()
        val result1 = FetchResult(content, null)
        val result2 = FetchResult(content, MimeType("text", "plain"))

        // Act & Assert
        assertNotEquals(result1, result2)
    }

    @Test
    fun `equals should return true for both null contentType`() {
        // Arrange
        val content = "test".toByteArray()
        val result1 = FetchResult(content, null)
        val result2 = FetchResult(content, null)

        // Act & Assert
        assertEquals(result1, result2)
    }

    @Test
    fun `equals should return false for non-FetchResult type`() {
        // Arrange
        val result = FetchResult("test".toByteArray())

        // Act & Assert
        assertNotEquals(result, "not a FetchResult")
        assertNotEquals(result, null)
    }

    @Test
    fun `hashCode should be consistent for same content and contentType`() {
        // Arrange
        val content = "test content".toByteArray()
        val mimeType = MimeType("application", "json")
        val result1 = FetchResult(content, mimeType)
        val result2 = FetchResult(content, mimeType)

        // Act & Assert
        assertEquals(result1.hashCode(), result2.hashCode())
    }

    @Test
    fun `hashCode should differ for different content`() {
        // Arrange
        val mimeType = MimeType("text", "plain")
        val result1 = FetchResult("content1".toByteArray(), mimeType)
        val result2 = FetchResult("content2".toByteArray(), mimeType)

        // Act & Assert
        assertNotEquals(result1.hashCode(), result2.hashCode())
    }

    @Test
    fun `hashCode should handle null contentType`() {
        // Arrange
        val content = "test".toByteArray()
        val result1 = FetchResult(content, null)
        val result2 = FetchResult(content, null)

        // Act & Assert
        assertEquals(result1.hashCode(), result2.hashCode())
    }

    @Test
    fun `should handle empty content`() {
        // Arrange
        val emptyContent = ByteArray(0)
        val result = FetchResult(emptyContent, MimeType("text", "plain"))

        // Assert
        assertEquals(0, result.content.size)
        assertNotNull(result.contentType)
    }

    @Test
    fun `should handle large content`() {
        // Arrange
        val largeContent = ByteArray(10000) { it.toByte() }
        val result = FetchResult(largeContent, MimeType("application", "octet-stream"))

        // Assert
        assertEquals(10000, result.content.size)
        assertArrayEquals(largeContent, result.content)
    }

    @Test
    fun `equals should properly compare byte arrays with same values`() {
        // Arrange
        val result1 = FetchResult(byteArrayOf(1, 2, 3), MimeType("text", "plain"))
        val result2 = FetchResult(byteArrayOf(1, 2, 3), MimeType("text", "plain"))

        // Act & Assert
        assertEquals(result1, result2)
    }

    @Test
    fun `hashCode should be same for byte arrays with same values`() {
        // Arrange
        val result1 = FetchResult(byteArrayOf(1, 2, 3, 4, 5), MimeType("text", "xml"))
        val result2 = FetchResult(byteArrayOf(1, 2, 3, 4, 5), MimeType("text", "xml"))

        // Act & Assert
        assertEquals(result1.hashCode(), result2.hashCode())
    }
}
