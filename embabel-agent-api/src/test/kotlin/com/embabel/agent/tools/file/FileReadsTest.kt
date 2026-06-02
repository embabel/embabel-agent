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
package com.embabel.agent.tools.file

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class FileReadsTest {

    @Test
    fun `should create FileReads with path and empty reads`() {
        // Arrange & Act
        val fileReads = FileReads(path = "/path/to/file.txt")

        // Assert
        assertEquals("/path/to/file.txt", fileReads.path)
        assertTrue(fileReads.reads.isEmpty())
        assertEquals(0, fileReads.count())
    }

    @Test
    fun `should create FileReads with path and reads list`() {
        // Arrange
        val instant1 = Instant.parse("2024-05-31T10:00:00Z")
        val instant2 = Instant.parse("2024-05-31T11:00:00Z")
        val reads = listOf(instant1, instant2)

        // Act
        val fileReads = FileReads(
            path = "/path/to/file.txt",
            reads = reads
        )

        // Assert
        assertEquals("/path/to/file.txt", fileReads.path)
        assertEquals(2, fileReads.reads.size)
        assertEquals(2, fileReads.count())
        assertEquals(instant1, fileReads.reads[0])
        assertEquals(instant2, fileReads.reads[1])
    }

    @Test
    fun `should return correct count for empty reads`() {
        // Arrange
        val fileReads = FileReads(path = "/file.txt")

        // Act
        val count = fileReads.count()

        // Assert
        assertEquals(0, count)
    }

    @Test
    fun `should return correct count for single read`() {
        // Arrange
        val fileReads = FileReads(
            path = "/file.txt",
            reads = listOf(Instant.now())
        )

        // Act
        val count = fileReads.count()

        // Assert
        assertEquals(1, count)
    }

    @Test
    fun `should return correct count for multiple reads`() {
        // Arrange
        val reads = (1..5).map { Instant.now() }
        val fileReads = FileReads(
            path = "/file.txt",
            reads = reads
        )

        // Act
        val count = fileReads.count()

        // Assert
        assertEquals(5, count)
    }

    @Test
    fun `should support copy with modified path`() {
        // Arrange
        val original = FileReads(path = "/original.txt")

        // Act
        val modified = original.copy(path = "/modified.txt")

        // Assert
        assertEquals("/modified.txt", modified.path)
        assertEquals("/original.txt", original.path)
    }

    @Test
    fun `should support copy with modified reads`() {
        // Arrange
        val instant = Instant.now()
        val original = FileReads(
            path = "/file.txt",
            reads = emptyList()
        )

        // Act
        val modified = original.copy(reads = listOf(instant))

        // Assert
        assertEquals(1, modified.count())
        assertEquals(0, original.count())
    }

    @Test
    fun `should support copy with added read`() {
        // Arrange
        val instant1 = Instant.parse("2024-05-31T10:00:00Z")
        val instant2 = Instant.parse("2024-05-31T11:00:00Z")
        val original = FileReads(
            path = "/file.txt",
            reads = listOf(instant1)
        )

        // Act
        val modified = original.copy(reads = original.reads + instant2)

        // Assert
        assertEquals(2, modified.count())
        assertEquals(1, original.count())
    }

    @Test
    fun `should support equality`() {
        // Arrange
        val instant = Instant.parse("2024-05-31T10:00:00Z")
        val fileReads1 = FileReads(
            path = "/file.txt",
            reads = listOf(instant)
        )
        val fileReads2 = FileReads(
            path = "/file.txt",
            reads = listOf(instant)
        )
        val fileReads3 = FileReads(
            path = "/different.txt",
            reads = listOf(instant)
        )

        // Assert
        assertEquals(fileReads1, fileReads2)
        assertNotEquals(fileReads1, fileReads3)
    }

    @Test
    fun `should handle absolute file paths`() {
        // Arrange & Act
        val fileReads = FileReads(path = "/home/user/project/src/Main.java")

        // Assert
        assertEquals("/home/user/project/src/Main.java", fileReads.path)
    }

    @Test
    fun `should handle relative file paths`() {
        // Arrange & Act
        val fileReads = FileReads(path = "src/main/kotlin/App.kt")

        // Assert
        assertEquals("src/main/kotlin/App.kt", fileReads.path)
    }

    @Test
    fun `should handle Windows-style paths`() {
        // Arrange & Act
        val fileReads = FileReads(path = "C:\\Users\\user\\file.txt")

        // Assert
        assertEquals("C:\\Users\\user\\file.txt", fileReads.path)
    }

    @Test
    fun `should preserve read timestamps order`() {
        // Arrange
        val instant1 = Instant.parse("2024-05-31T10:00:00Z")
        val instant2 = Instant.parse("2024-05-31T11:00:00Z")
        val instant3 = Instant.parse("2024-05-31T12:00:00Z")
        val reads = listOf(instant1, instant2, instant3)

        // Act
        val fileReads = FileReads(
            path = "/file.txt",
            reads = reads
        )

        // Assert
        assertEquals(instant1, fileReads.reads[0])
        assertEquals(instant2, fileReads.reads[1])
        assertEquals(instant3, fileReads.reads[2])
    }

    @Test
    fun `should handle large number of reads`() {
        // Arrange
        val reads = (1..100).map { Instant.now() }

        // Act
        val fileReads = FileReads(
            path = "/file.txt",
            reads = reads
        )

        // Assert
        assertEquals(100, fileReads.count())
    }
}
