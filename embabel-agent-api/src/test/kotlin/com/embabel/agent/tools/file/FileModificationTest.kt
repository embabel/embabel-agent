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

class FileModificationTest {

    @Test
    fun `should create FileModification with path and type`() {
        // Arrange & Act
        val modification = FileModification(
            path = "/path/to/file.txt",
            type = FileModificationType.CREATE
        )

        // Assert
        assertEquals("/path/to/file.txt", modification.path)
        assertEquals(FileModificationType.CREATE, modification.type)
    }

    @Test
    fun `should support all FileModificationType values`() {
        // Assert
        assertNotNull(FileModificationType.CREATE)
        assertNotNull(FileModificationType.EDIT)
        assertNotNull(FileModificationType.DELETE)
        assertNotNull(FileModificationType.APPEND)
        assertNotNull(FileModificationType.CREATE_DIRECTORY)
    }

    @Test
    fun `should create FileModification with CREATE type`() {
        // Arrange & Act
        val modification = FileModification("file.txt", FileModificationType.CREATE)

        // Assert
        assertEquals(FileModificationType.CREATE, modification.type)
    }

    @Test
    fun `should create FileModification with EDIT type`() {
        // Arrange & Act
        val modification = FileModification("file.txt", FileModificationType.EDIT)

        // Assert
        assertEquals(FileModificationType.EDIT, modification.type)
    }

    @Test
    fun `should create FileModification with DELETE type`() {
        // Arrange & Act
        val modification = FileModification("file.txt", FileModificationType.DELETE)

        // Assert
        assertEquals(FileModificationType.DELETE, modification.type)
    }

    @Test
    fun `should create FileModification with APPEND type`() {
        // Arrange & Act
        val modification = FileModification("file.txt", FileModificationType.APPEND)

        // Assert
        assertEquals(FileModificationType.APPEND, modification.type)
    }

    @Test
    fun `should create FileModification with CREATE_DIRECTORY type`() {
        // Arrange & Act
        val modification = FileModification("dir/", FileModificationType.CREATE_DIRECTORY)

        // Assert
        assertEquals(FileModificationType.CREATE_DIRECTORY, modification.type)
    }

    @Test
    fun `should support copy with modified path`() {
        // Arrange
        val original = FileModification("original.txt", FileModificationType.CREATE)

        // Act
        val modified = original.copy(path = "modified.txt")

        // Assert
        assertEquals("modified.txt", modified.path)
        assertEquals("original.txt", original.path)
    }

    @Test
    fun `should support copy with modified type`() {
        // Arrange
        val original = FileModification("file.txt", FileModificationType.CREATE)

        // Act
        val modified = original.copy(type = FileModificationType.EDIT)

        // Assert
        assertEquals(FileModificationType.EDIT, modified.type)
        assertEquals(FileModificationType.CREATE, original.type)
    }

    @Test
    fun `should support equality`() {
        // Arrange
        val mod1 = FileModification("file.txt", FileModificationType.CREATE)
        val mod2 = FileModification("file.txt", FileModificationType.CREATE)
        val mod3 = FileModification("other.txt", FileModificationType.CREATE)

        // Assert
        assertEquals(mod1, mod2)
        assertNotEquals(mod1, mod3)
    }

    @Test
    fun `should handle empty path`() {
        // Arrange & Act
        val modification = FileModification("", FileModificationType.CREATE)

        // Assert
        assertEquals("", modification.path)
    }

    @Test
    fun `should handle absolute paths`() {
        // Arrange & Act
        val modification = FileModification(
            "/absolute/path/to/file.txt",
            FileModificationType.EDIT
        )

        // Assert
        assertEquals("/absolute/path/to/file.txt", modification.path)
    }

    @Test
    fun `should handle relative paths`() {
        // Arrange & Act
        val modification = FileModification(
            "relative/path/file.txt",
            FileModificationType.APPEND
        )

        // Assert
        assertEquals("relative/path/file.txt", modification.path)
    }

    @Test
    fun `FileModificationType should have exactly 5 values`() {
        // Act
        val values = FileModificationType.values()

        // Assert
        assertEquals(5, values.size)
    }
}
