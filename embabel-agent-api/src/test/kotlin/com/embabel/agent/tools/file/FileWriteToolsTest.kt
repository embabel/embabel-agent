/*
 * Copyright 2024-2025 Embabel Software, Inc.
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

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileWriteToolsTest {

    private lateinit var tempDir: File
    private lateinit var fileWriteTools: FileWriteTools
    private lateinit var rootPath: String

    @BeforeEach
    fun setUp() {
        tempDir = FileWriteTools.createTempDir("file-write-tools-test")
        rootPath = tempDir.absolutePath
        fileWriteTools = FileTools.readWrite(rootPath)
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Nested
    inner class CreateFile {

        @Test
        fun `should create file with content`() {
            val path = "test.txt"
            val content = "test content"

            val result = fileWriteTools.createFile(path, content)

            assertEquals("file created", result)
            val file = File(tempDir, path)
            assertTrue(file.exists())
            assertEquals(content, Files.readString(file.toPath()))
        }

        @Test
        fun `should create file and track changes`() {
            val path = "tracked-test.txt"
            val content = "test content"

            // Clear any existing changes
            fileWriteTools.flushChanges()

            val result = fileWriteTools.createFile(path, content)

            assertEquals("file created", result)
            assertEquals(1, fileWriteTools.getChanges().size, "Should track one change")
            val change = fileWriteTools.getChanges().first()
            assertEquals(
                FileModificationType.CREATE, change.type,
                "Change type should be CREATE"
            )
            assertEquals(
                path,
                change.path,
                "Change path should match created file path"
            )
        }

        @Test
        fun `should create file in subdirectory`() {
            val path = "subdir/test.txt"
            val content = "test content"

            val result = fileWriteTools.createFile(path, content)

            assertEquals("file created", result)
            val file = File(tempDir, path)
            assertTrue(file.exists())
            assertEquals(content, Files.readString(file.toPath()))
        }

        @Test
        fun `should not overwrite file in subdirectory unless asked`() {
            val path = "subdir/test.txt"
            val content = "test content"

            fileWriteTools.createFile(path, content)
            assertThrows<Exception> { fileWriteTools.createFile(path, content) }
        }

        @Test
        fun `should overwrite file in subdirectory if asked`() {
            val path = "subdir/test.txt"
            val content = "test content"

            val result1 = fileWriteTools.createFile(path, content)
            val result2 = fileWriteTools.createFile(path, content, overwrite = true)
            val file = File(tempDir, path)
            assertTrue(file.exists())
            assertEquals(content, Files.readString(file.toPath()))
        }

//        @Test
//        fun `should throw exception when file already exists`() {
//            val path = "existing.txt"
//            Files.writeString(tempDir.resolve(path), "existing content")
//
//            assertThrows<IllegalArgumentException> {
//                fileWriteTools.createFile(path, "new content")
//            }
//        }
    }

    @Nested
    inner class EditFile {

        private val originalContent = "This is the original content."
        private val path = "edit-test.txt"
        private lateinit var file: File

        @BeforeEach
        fun setupFile() {
            file = File(tempDir, path)
            Files.writeString(file.toPath(), originalContent)
        }

        @Test
        fun `should replace content in file`() {
            val oldContent = "original content"
            val newContent = "modified content"
            val fullContent = "This is the $oldContent"
            Files.writeString(file.toPath(), fullContent)

            val result = fileWriteTools.editFile(path, oldContent, newContent)

            assertEquals("file edited", result)
            val loadedContent = Files.readString(file.toPath())
            assertEquals(
                "This is the $newContent",
                loadedContent,
                "File content is [$loadedContent]"
            )
        }

        @Test
        fun `should edit file and track changes`() {
            val oldContent = "original content"
            val newContent = "modified content"
            val fullContent = "This is the $oldContent"
            Files.writeString(file.toPath(), fullContent)

            // Clear any existing changes
            fileWriteTools.flushChanges()

            val result = fileWriteTools.editFile(path, oldContent, newContent)

            assertEquals("file edited", result)
            assertEquals(1, fileWriteTools.getChanges().size, "Should track one change")
            val change = fileWriteTools.getChanges().first()
            assertEquals(
                FileModificationType.EDIT, change.type,
                "Change type should be EDIT"
            )
            assertEquals(
                path,
                change.path,
                "Change path should match edited file path"
            )
        }

        @Test
        fun `should throw exception when file does not exist`() {
            assertThrows<IllegalArgumentException> {
                fileWriteTools.editFile("nonexistent.txt", "old", "new")
            }
        }
    }

    @Nested
    inner class CreateDirectory {

        @Test
        fun `should create directory`() {
            val path = "newdir"

            val result = fileWriteTools.createDirectory(path)

            assertEquals("directory created", result)
            val dir = File(tempDir, path)
            assertTrue(dir.exists())
            assertTrue(dir.isDirectory)
        }

        @Test
        fun `should create directory and track changes`() {
            val path = "tracked-newdir"

            // Clear any existing changes
            fileWriteTools.flushChanges()

            val result = fileWriteTools.createDirectory(path)

            assertEquals("directory created", result)
            assertEquals(1, fileWriteTools.getChanges().size, "Should track one change")
            val change = fileWriteTools.getChanges().first()
            assertEquals(
                FileModificationType.CREATE_DIRECTORY, change.type,
                "Change type should be CREATE_DIRECTORY"
            )
            assertEquals(
                path,
                change.path,
                "Change path should match created directory path"
            )
        }

        @Test
        fun `should create nested directories`() {
            val path = "parent/child/grandchild"

            val result = fileWriteTools.createDirectory(path)

            assertEquals("directory created", result)
            val dir = File(tempDir, path)
            assertTrue(dir.exists())
            assertTrue(dir.isDirectory)
        }

        @Test
        fun `should return message when directory already exists`() {
            val path = "existingdir"
            File(tempDir, path).mkdir()

            val result = fileWriteTools.createDirectory(path)

            assertEquals("directory already exists", result)
        }

//        @Test
//        fun `should throw exception when path exists as file`() {
//            val path = "existing.txt"
//            Files.writeString(tempDir.resolve(path), "content")
//
//            assertThrows<IllegalArgumentException> {
//                fileWriteTools.createDirectory(path)
//            }
//        }
    }

    @Nested
    inner class AppendFile {

        private val originalContent = "Original content\n"
        private val path = "append-test.txt"
        private lateinit var file: File

        @BeforeEach
        fun setupFile() {
            file = File(tempDir, path)
            Files.writeString(file.toPath(), originalContent)
        }

        @Test
        fun `should append content to file`() {
            val appendedContent = "Appended content"
            val result = fileWriteTools.appendFile(path, appendedContent)
            assertEquals("content appended to file", result)
            assertEquals(originalContent + appendedContent, Files.readString(file.toPath()))
        }

        @Test
        fun `should append content to file and track changes`() {
            val appendedContent = "Appended content"

            // Clear any existing changes
            fileWriteTools.flushChanges()

            val result = fileWriteTools.appendFile(path, appendedContent)
            assertEquals("content appended to file", result)
            assertEquals(originalContent + appendedContent, Files.readString(file.toPath()))
            assertEquals(1, fileWriteTools.getChanges().size, "Should track one change")
            val change = fileWriteTools.getChanges().first()
            assertEquals(
                FileModificationType.APPEND, change.type,
                "Change type should be APPEND"
            )
            assertEquals(
                path,
                change.path,
                "Change path should match appended file path"
            )
        }

        @Test
        fun `should throw exception when file does not exist`() {
            assertThrows<IllegalArgumentException> {
                fileWriteTools.appendFile("nonexistent.txt", "content")
            }
        }

        @Test
        fun `should create if required when file does not exist`() {
            val path = "new-file.txt"
            val appendedContent = "Appended content"
            fileWriteTools.appendToFile(path, appendedContent, true)
            assertEquals(
                appendedContent,
                Files.readString(
                    File(tempDir, path).toPath()
                ),
                "Content should be appended to new file",
            )
        }

        @Test
        fun `appendToFile should never throw exception if file exists`() {
            val path = "new-file.txt"
            fileWriteTools.createFile(path, "")
            val appendedContent = "Appended content"
            fileWriteTools.appendToFile(path, appendedContent, true)
            assertEquals(
                appendedContent,
                Files.readString(
                    File(tempDir, path).toPath()
                ),
                "Content should be appended to new file",
            )
        }
    }

    @Nested
    inner class Delete {

        private val path = "to-delete.txt"
        private lateinit var file: File

        @BeforeEach
        fun setupFile() {
            file = File(tempDir, path)
            Files.writeString(file.toPath(), "content to delete")
        }

        @Test
        fun `should delete file`() {
            val result = fileWriteTools.delete(path)

            assertEquals("file deleted", result)
            assertFalse(file.exists())
        }

        @Test
        fun `should delete file and track changes`() {
            // Clear any existing changes
            fileWriteTools.flushChanges()

            val result = fileWriteTools.delete(path)

            assertEquals("file deleted", result)
            assertEquals(1, fileWriteTools.getChanges().size, "Should track one change")
            val change = fileWriteTools.getChanges().first()
            assertEquals(
                FileModificationType.DELETE, change.type,
                "Change type should be DELETE"
            )
            assertEquals(
                path,
                change.path,
                "Change path should match deleted file path"
            )
        }

        @Test
        fun `should throw exception when file does not exist`() {
            assertThrows<IllegalArgumentException> {
                fileWriteTools.delete("nonexistent.txt")
            }
        }
    }

    @Nested
    inner class CreateTempDir {

        private lateinit var createdTempDir: File

        @AfterEach
        fun cleanupTempDir() {
            if (::createdTempDir.isInitialized) {
                createdTempDir.deleteRecursively()
            }
        }

        @Test
        fun `should create temporary directory with seed name`() {
            val seed = "test-seed"

            createdTempDir = FileWriteTools.createTempDir(seed)

            assertTrue(createdTempDir.exists())
            assertTrue(createdTempDir.isDirectory)
            assertTrue(createdTempDir.absolutePath.contains(seed))
        }
    }

    @Nested
    inner class ExtractZipFile {

        private lateinit var zipFile: File
        private lateinit var extractDir: File

        @BeforeEach
        fun createZipFile() {
            // Create a zip file with test content
            zipFile = File(tempDir, "test.zip")
            ZipOutputStream(zipFile.outputStream()).use { zipOut ->
                // Add a file
                val entry1 = ZipEntry("file1.txt")
                zipOut.putNextEntry(entry1)
                zipOut.write("content1".toByteArray())
                zipOut.closeEntry()

                // Add a file in a subdirectory
                val entry2 = ZipEntry("subdir/file2.txt")
                zipOut.putNextEntry(entry2)
                zipOut.write("content2".toByteArray())
                zipOut.closeEntry()
            }

            extractDir = FileWriteTools.createTempDir("extract-test")
        }

        @AfterEach
        fun cleanup() {
            extractDir.deleteRecursively()
        }

        @Test
        @Disabled("not yet working")
        fun `should extract zip file contents`() {
            val result = FileWriteTools.extractZipFile(
                zipFile,
                extractDir,
                delete = false
            )

            assertTrue(
                result.exists(),
                "Zip file content should exist at ${result.absolutePath}",
            )
            assertEquals("test", result.name)

            val file1 = File(extractDir, "file1.txt")
            val file2 = File(extractDir, "subdir/file2.txt")

            assertTrue(file1.exists())
            assertTrue(file2.exists())
            assertEquals("content1", Files.readString(file1.toPath()))
            assertEquals("content2", Files.readString(file2.toPath()))
        }

        @Test
        fun `should delete zip file when delete is true`() {
            FileWriteTools.extractZipFile(zipFile, extractDir, true)
            assertFalse(zipFile.exists())
        }
    }

    @Nested
    inner class FlushChanges {

        @Test
        fun `should clear tracked changes`() {
            // Create a file to generate a change
            val path = "flush-test.txt"
            val content = "test content"
            fileWriteTools.createFile(path, content)

            // Verify change was tracked
            assertEquals(1, fileWriteTools.getChanges().size, "Should have one change before flush")

            // Flush changes
            fileWriteTools.flushChanges()

            // Verify changes were cleared
            assertEquals(0, fileWriteTools.getChanges().size, "Should have no changes after flush")
        }

        @Test
        fun `should track new changes after flush`() {
            // Create a file to generate a change
            val path1 = "flush-test1.txt"
            val content = "test content"
            fileWriteTools.createFile(path1, content)

            // Flush changes
            fileWriteTools.flushChanges()

            // Create another file
            val path2 = "flush-test2.txt"
            fileWriteTools.createFile(path2, content)

            // Verify only the new change is tracked
            assertEquals(1, fileWriteTools.getChanges().size, "Should have one change after flush")
            val change = fileWriteTools.getChanges().first()
            assertEquals(path2, change.path, "Change should be for the second file")
        }
    }

    @Nested
    inner class ChangLogDoesNotDuplicate {

        @Test
        fun `should not duplicate add`() {
            val ft = FileTools.readWrite(rootPath)
            ft.recordChange(
                FileModification(
                    type = FileModificationType.CREATE,
                    path = "test.txt",
                )
            )
            assertEquals(1, ft.getChanges().size, "Should have one change after first add")
            ft.recordChange(
                FileModification(
                    type = FileModificationType.CREATE,
                    path = "test.txt",
                )
            )
            assertEquals(1, ft.getChanges().size, "Should still have one change after duplicate add")
        }

        @Test
        fun `should not duplicate add and modify`() {
            val ft = FileTools.readWrite(rootPath)
            ft.recordChange(
                FileModification(
                    type = FileModificationType.CREATE,
                    path = "test.txt",
                )
            )
            assertEquals(1, ft.getChanges().size, "Should have one change after first add")
            ft.recordChange(
                FileModification(
                    type = FileModificationType.EDIT,
                    path = "test.txt",
                )
            )
            assertEquals(1, ft.getChanges().size, "Should still have one change after add and modify")
        }

    }

    @Nested
    inner class AccessLog {

        @Test
        fun `log write`() {
            val ft = FileTools.readWrite(rootPath)
            assertEquals(0, ft.getPathsAccessed().size, "Should have 0 access before doing anything")
            ft.createFile("thing", "content")
            assertEquals(1, ft.getPathsAccessed().size, "Should have 1 access")
            ft.createFile("thing2", "content")
            assertEquals(2, ft.getPathsAccessed().size, "Should have 2 accesses")
        }

        @Test
        fun `log duplicates only once`() {
            val ft = FileTools.readWrite(rootPath)
            ft.createFile("thing", "content")
            ft.readFile("thing")
            ft.readFile("thing")
            ft.readFile("thing")
            assertEquals(1, ft.getPathsAccessed().size, "Should have 1 access after read")
        }

        @Test
        fun `log duplicate read and write only once`() {
            val ft = FileTools.readWrite(rootPath)
            ft.createFile("thing", "content")
            ft.readFile("thing")
            assertEquals(1, ft.getPathsAccessed().size, "Should have 1 access after duplicate read")
        }

    }
}
