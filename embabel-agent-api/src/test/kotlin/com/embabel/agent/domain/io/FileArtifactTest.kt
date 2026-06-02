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
package com.embabel.agent.domain.io

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant

class FileArtifactTest {

    @Test
    fun `should create FileArtifact with file and timestamp`() {
        // Arrange
        val file = File("test.txt")
        val timestamp = Instant.now()

        // Act
        val artifact = FileArtifact(file, timestamp)

        // Assert
        assertEquals(file, artifact.file)
        assertEquals(timestamp, artifact.timestamp)
    }

    @Test
    fun `should create FileArtifact with file and default timestamp`() {
        // Arrange
        val file = File("output.txt")
        val before = Instant.now()

        // Act
        val artifact = FileArtifact(file)
        val after = Instant.now()

        // Assert
        assertEquals(file, artifact.file)
        assertTrue(artifact.timestamp.isAfter(before) || artifact.timestamp.equals(before))
        assertTrue(artifact.timestamp.isBefore(after) || artifact.timestamp.equals(after))
    }

    @Test
    fun `should create FileArtifact with directory and output file using secondary constructor`() {
        // Arrange
        val directory = "/tmp"
        val outputFile = "result.txt"

        // Act
        val artifact = FileArtifact(directory, outputFile)

        // Assert
        assertEquals(File("/tmp", "result.txt"), artifact.file)
        assertNotNull(artifact.timestamp)
    }

    @Test
    fun `should support copy with modified file`() {
        // Arrange
        val original = FileArtifact(File("original.txt"))

        // Act
        val modified = original.copy(file = File("modified.txt"))

        // Assert
        assertEquals(File("modified.txt"), modified.file)
        assertEquals(File("original.txt"), original.file)
    }

    @Test
    fun `should support copy with modified timestamp`() {
        // Arrange
        val originalTime = Instant.now().minusSeconds(100)
        val original = FileArtifact(File("test.txt"), originalTime)
        val newTime = Instant.now()

        // Act
        val modified = original.copy(timestamp = newTime)

        // Assert
        assertEquals(newTime, modified.timestamp)
        assertEquals(originalTime, original.timestamp)
    }

    @Test
    fun `should support equality`() {
        // Arrange
        val timestamp = Instant.now()
        val artifact1 = FileArtifact(File("test.txt"), timestamp)
        val artifact2 = FileArtifact(File("test.txt"), timestamp)
        val artifact3 = FileArtifact(File("other.txt"), timestamp)

        // Assert
        assertEquals(artifact1, artifact2)
        assertNotEquals(artifact1, artifact3)
    }

    @Test
    fun `should support component functions`() {
        // Arrange
        val file = File("data.json")
        val timestamp = Instant.now()
        val artifact = FileArtifact(file, timestamp)

        // Act
        val (extractedFile, extractedTimestamp) = artifact

        // Assert
        assertEquals(file, extractedFile)
        assertEquals(timestamp, extractedTimestamp)
    }

    @Test
    fun `should be a SystemOutput`() {
        // Arrange
        val artifact = FileArtifact(File("output.txt"))

        // Assert
        assertTrue(artifact is SystemOutput)
    }

    @Test
    fun `should handle absolute file paths`() {
        // Arrange
        val file = File("/home/user/documents/file.pdf")

        // Act
        val artifact = FileArtifact(file)

        // Assert
        assertEquals(file, artifact.file)
    }

    @Test
    fun `should handle relative file paths`() {
        // Arrange
        val file = File("relative/path/file.txt")

        // Act
        val artifact = FileArtifact(file)

        // Assert
        assertEquals(file, artifact.file)
    }

    @Test
    fun `should handle file with extension`() {
        // Arrange
        val file = File("document.docx")

        // Act
        val artifact = FileArtifact(file)

        // Assert
        assertEquals("document.docx", artifact.file.name)
    }

    @Test
    fun `should handle file without extension`() {
        // Arrange
        val file = File("README")

        // Act
        val artifact = FileArtifact(file)

        // Assert
        assertEquals("README", artifact.file.name)
    }

    @Test
    fun `hashCode should be consistent for equal objects`() {
        // Arrange
        val timestamp = Instant.now()
        val artifact1 = FileArtifact(File("test.txt"), timestamp)
        val artifact2 = FileArtifact(File("test.txt"), timestamp)

        // Act & Assert
        assertEquals(artifact1.hashCode(), artifact2.hashCode())
    }

    @Test
    fun `toString should contain field values`() {
        // Arrange
        val file = File("output.csv")
        val artifact = FileArtifact(file)

        // Act
        val string = artifact.toString()

        // Assert
        assertTrue(string.contains("output.csv") || string.contains("file"))
    }

    @Test
    fun `secondary constructor should handle nested directories`() {
        // Arrange
        val directory = "/var/log/app"
        val outputFile = "error.log"

        // Act
        val artifact = FileArtifact(directory, outputFile)

        // Assert
        assertEquals(File("/var/log/app/error.log"), artifact.file)
    }
}
