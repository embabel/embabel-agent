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

class IngestionResultTest {

    @Test
    fun `should create IngestionResult with stores and chunks`() {
        // Arrange
        val stores = setOf("store1", "store2")
        val chunks = listOf("chunk1", "chunk2", "chunk3")

        // Act
        val result = IngestionResult(
            storesWrittenTo = stores,
            chunkIds = chunks
        )

        // Assert
        assertEquals(2, result.storesWrittenTo.size)
        assertEquals(3, result.chunkIds.size)
        assertEquals(3, result.documentsWritten)
    }

    @Test
    fun `should create IngestionResult with empty collections`() {
        // Arrange & Act
        val result = IngestionResult(
            storesWrittenTo = emptySet(),
            chunkIds = emptyList()
        )

        // Assert
        assertTrue(result.storesWrittenTo.isEmpty())
        assertTrue(result.chunkIds.isEmpty())
        assertEquals(0, result.documentsWritten)
    }

    @Test
    fun `documentsWritten should equal chunkIds size`() {
        // Arrange
        val chunks = listOf("chunk1", "chunk2", "chunk3", "chunk4", "chunk5")

        // Act
        val result = IngestionResult(
            storesWrittenTo = setOf("store1"),
            chunkIds = chunks
        )

        // Assert
        assertEquals(5, result.documentsWritten)
        assertEquals(chunks.size, result.documentsWritten)
    }

    @Test
    fun `success should return true when stores are not empty`() {
        // Arrange
        val result = IngestionResult(
            storesWrittenTo = setOf("store1"),
            chunkIds = listOf("chunk1")
        )

        // Act & Assert
        assertTrue(result.success())
    }

    @Test
    fun `success should return false when stores are empty`() {
        // Arrange
        val result = IngestionResult(
            storesWrittenTo = emptySet(),
            chunkIds = listOf("chunk1")
        )

        // Act & Assert
        assertFalse(result.success())
    }

    @Test
    fun `success should return false when no stores written even with chunks`() {
        // Arrange
        val result = IngestionResult(
            storesWrittenTo = emptySet(),
            chunkIds = listOf("chunk1", "chunk2", "chunk3")
        )

        // Act & Assert
        assertFalse(result.success())
        assertEquals(3, result.documentsWritten)
    }

    @Test
    fun `should support copy with modified storesWrittenTo`() {
        // Arrange
        val original = IngestionResult(
            storesWrittenTo = setOf("store1"),
            chunkIds = listOf("chunk1")
        )

        // Act
        val modified = original.copy(storesWrittenTo = setOf("store1", "store2"))

        // Assert
        assertEquals(2, modified.storesWrittenTo.size)
        assertEquals(1, original.storesWrittenTo.size)
    }

    @Test
    fun `should support copy with modified chunkIds`() {
        // Arrange
        val original = IngestionResult(
            storesWrittenTo = setOf("store1"),
            chunkIds = listOf("chunk1")
        )

        // Act
        val modified = original.copy(chunkIds = listOf("chunk1", "chunk2", "chunk3"))

        // Assert
        assertEquals(3, modified.documentsWritten)
        assertEquals(1, original.documentsWritten)
    }

    @Test
    fun `should support equality`() {
        // Arrange
        val result1 = IngestionResult(
            storesWrittenTo = setOf("store1"),
            chunkIds = listOf("chunk1")
        )
        val result2 = IngestionResult(
            storesWrittenTo = setOf("store1"),
            chunkIds = listOf("chunk1")
        )
        val result3 = IngestionResult(
            storesWrittenTo = setOf("store2"),
            chunkIds = listOf("chunk1")
        )

        // Assert
        assertEquals(result1, result2)
        assertNotEquals(result1, result3)
    }

    @Test
    fun `should handle single store and chunk`() {
        // Arrange & Act
        val result = IngestionResult(
            storesWrittenTo = setOf("single-store"),
            chunkIds = listOf("single-chunk")
        )

        // Assert
        assertEquals(1, result.storesWrittenTo.size)
        assertEquals(1, result.documentsWritten)
        assertTrue(result.success())
    }

    @Test
    fun `should handle multiple stores`() {
        // Arrange
        val stores = setOf("store1", "store2", "store3", "store4", "store5")

        // Act
        val result = IngestionResult(
            storesWrittenTo = stores,
            chunkIds = listOf("chunk1")
        )

        // Assert
        assertEquals(5, result.storesWrittenTo.size)
        assertTrue(result.success())
    }

    @Test
    fun `should handle large number of chunks`() {
        // Arrange
        val chunks = (1..1000).map { "chunk$it" }

        // Act
        val result = IngestionResult(
            storesWrittenTo = setOf("store1"),
            chunkIds = chunks
        )

        // Assert
        assertEquals(1000, result.documentsWritten)
        assertEquals(1000, result.chunkIds.size)
        assertTrue(result.success())
    }

    @Test
    fun `documentsWritten should be zero for empty chunkIds`() {
        // Arrange & Act
        val result = IngestionResult(
            storesWrittenTo = setOf("store1"),
            chunkIds = emptyList()
        )

        // Assert
        assertEquals(0, result.documentsWritten)
    }
}
