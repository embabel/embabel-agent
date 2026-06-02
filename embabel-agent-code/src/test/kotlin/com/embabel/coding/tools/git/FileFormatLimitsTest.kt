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
package com.embabel.coding.tools.git

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FileFormatLimitsTest {

    @Test
    fun `should create FileFormatLimits with default values`() {
        // Arrange & Act
        val limits = FileFormatLimits()

        // Assert
        assertEquals(100, limits.fileCountLimit)
        assertEquals(200_000L, limits.fileSizeLimit)
    }

    @Test
    fun `should create FileFormatLimits with custom fileCountLimit`() {
        // Arrange & Act
        val limits = FileFormatLimits(fileCountLimit = 50)

        // Assert
        assertEquals(50, limits.fileCountLimit)
        assertEquals(200_000L, limits.fileSizeLimit)
    }

    @Test
    fun `should create FileFormatLimits with custom fileSizeLimit`() {
        // Arrange & Act
        val limits = FileFormatLimits(fileSizeLimit = 500_000L)

        // Assert
        assertEquals(100, limits.fileCountLimit)
        assertEquals(500_000L, limits.fileSizeLimit)
    }

    @Test
    fun `should create FileFormatLimits with both custom values`() {
        // Arrange & Act
        val limits = FileFormatLimits(
            fileCountLimit = 200,
            fileSizeLimit = 1_000_000L
        )

        // Assert
        assertEquals(200, limits.fileCountLimit)
        assertEquals(1_000_000L, limits.fileSizeLimit)
    }

    @Test
    fun `should support copy with modified fileCountLimit`() {
        // Arrange
        val original = FileFormatLimits()

        // Act
        val modified = original.copy(fileCountLimit = 150)

        // Assert
        assertEquals(150, modified.fileCountLimit)
        assertEquals(200_000L, modified.fileSizeLimit)
        assertEquals(100, original.fileCountLimit)
    }

    @Test
    fun `should support copy with modified fileSizeLimit`() {
        // Arrange
        val original = FileFormatLimits()

        // Act
        val modified = original.copy(fileSizeLimit = 300_000L)

        // Assert
        assertEquals(100, modified.fileCountLimit)
        assertEquals(300_000L, modified.fileSizeLimit)
        assertEquals(200_000L, original.fileSizeLimit)
    }

    @Test
    fun `should support copy with both values modified`() {
        // Arrange
        val original = FileFormatLimits(fileCountLimit = 50, fileSizeLimit = 100_000L)

        // Act
        val modified = original.copy(
            fileCountLimit = 75,
            fileSizeLimit = 250_000L
        )

        // Assert
        assertEquals(75, modified.fileCountLimit)
        assertEquals(250_000L, modified.fileSizeLimit)
    }

    @Test
    fun `should support equality comparison`() {
        // Arrange
        val limits1 = FileFormatLimits(fileCountLimit = 100, fileSizeLimit = 200_000L)
        val limits2 = FileFormatLimits(fileCountLimit = 100, fileSizeLimit = 200_000L)
        val limits3 = FileFormatLimits(fileCountLimit = 50, fileSizeLimit = 200_000L)

        // Assert
        assertEquals(limits1, limits2)
        assertNotEquals(limits1, limits3)
    }

    @Test
    fun `should have consistent hashCode`() {
        // Arrange
        val limits1 = FileFormatLimits(fileCountLimit = 100, fileSizeLimit = 200_000L)
        val limits2 = FileFormatLimits(fileCountLimit = 100, fileSizeLimit = 200_000L)

        // Assert
        assertEquals(limits1.hashCode(), limits2.hashCode())
    }

    @Test
    fun `should have meaningful toString`() {
        // Arrange
        val limits = FileFormatLimits(fileCountLimit = 75, fileSizeLimit = 150_000L)

        // Act
        val string = limits.toString()

        // Assert
        assertTrue(string.contains("FileFormatLimits"))
        assertTrue(string.contains("75"))
        assertTrue(string.contains("150000"))
    }
}
