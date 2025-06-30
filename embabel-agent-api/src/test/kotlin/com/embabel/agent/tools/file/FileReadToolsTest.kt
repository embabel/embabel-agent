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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class FileReadToolsTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var fileReadTools: FileReadTools
    private lateinit var rootPath: String

    @BeforeEach
    fun setUp() {
        rootPath = tempDir.toString()
        fileReadTools = object : FileReadTools {
            override val root: String = rootPath
            override val fileContentTransformers: List<FileContentTransformer> = emptyList()
        }
    }

    @Nested
    inner class FindFiles {

        @BeforeEach
        fun setupFiles() {
            // Create directory structure
            Files.createDirectories(tempDir.resolve("dir1"))
            Files.createDirectories(tempDir.resolve("dir2/subdir"))

            // Create files
            Files.writeString(tempDir.resolve("file1.txt"), "content1")
            Files.writeString(tempDir.resolve("file2.md"), "content2")
            Files.writeString(tempDir.resolve("dir1/file3.txt"), "content3")
            Files.writeString(tempDir.resolve("dir2/file4.txt"), "content4")
            Files.writeString(tempDir.resolve("dir2/subdir/file5.txt"), "content5")
        }

        @Test
        fun `should find files by extension excluding root`() {
            val result = fileReadTools.findFiles("**/*.txt")

            assertEquals(3, result.size)

            val paths = result.toPaths()

            assertTrue(paths.any { it.endsWith(Paths.get("dir1", "file3.txt")) })
            assertTrue(paths.any { it.endsWith(Paths.get("dir2", "file4.txt")) })
            assertTrue(paths.any { it.endsWith(Paths.get("dir2", "subdir", "file5.txt")) })
            assertTrue(
                paths.none { it.endsWith(Paths.get("dir1")) }, "" +
                        "Expected file1.txt NOT to be found:\n${result.joinToString("\n")}"
            )
        }

        @Test
        fun `should find files by extension in root`() {
            val result = fileReadTools.findFiles("*.txt")
            assertEquals(1, result.size)

            val paths = result.toPaths()
            assertTrue(
                paths.any { it.endsWith(Paths.get("file1.txt")) }, "" +
                        "Expected file1.txt to be found:\n${result.joinToString("\n")}"
            )
        }

        @Test
        fun `should find files in specific directory`() {
            val result = fileReadTools.findFiles("dir1/*")
            assertEquals(1, result.size)

            val paths = result.toPaths()
            assertTrue(paths[0].endsWith(Paths.get("dir1", "file3.txt")))
        }

        @Test
        fun `should return empty list when no matches`() {
            val result = fileReadTools.findFiles("**/*.java")

            assertTrue(result.isEmpty())
        }
    }

    @Nested
    inner class `readFile` {

        private val testContent = "test content"
        private lateinit var testFile: Path

        @BeforeEach
        fun setupFile() {
            testFile = tempDir.resolve("test.txt")
            Files.writeString(testFile, testContent)
        }

        @Test
        fun `should read file content`() {
            val result = fileReadTools.readFile("test.txt")

            assertEquals(testContent, result)
        }

        @Test
        fun `should throw exception when file does not exist`() {
            assertThrows<IllegalArgumentException> {
                fileReadTools.readFile("nonexistent.txt")
            }
        }

        @Test
        fun `should throw exception when path is a directory`() {
            Files.createDirectory(tempDir.resolve("testdir"))

            assertThrows<IllegalArgumentException> {
                fileReadTools.readFile("testdir")
            }
        }

        @Test
        fun `should prevent path traversal attacks`() {
            assertThrows<SecurityException> {
                fileReadTools.readFile("../../../etc/passwd")
            }
        }
    }

    @Nested
    inner class `listFiles` {

        @BeforeEach
        fun setupDirectoryStructure() {
            // Create directory structure
            Files.createDirectories(tempDir.resolve("emptydir"))
            Files.createDirectories(tempDir.resolve("nonemptydir"))

            // Create files
            Files.writeString(tempDir.resolve("file1.txt"), "content1")
            Files.writeString(tempDir.resolve("nonemptydir/file2.txt"), "content2")
        }

        @Test
        fun `should list files and directories in root`() {
            val result = fileReadTools.listFiles(".")

            assertEquals(3, result.size)
            assertTrue(result.contains("f:file1.txt"))
            assertTrue(result.contains("d:emptydir"))
            assertTrue(result.contains("d:nonemptydir"))
        }

        @Test
        fun `should list files in subdirectory`() {
            val result = fileReadTools.listFiles("nonemptydir")

            assertEquals(1, result.size)
            assertTrue(result.contains("f:file2.txt"))
        }

        @Test
        fun `should return empty list for empty directory`() {
            val result = fileReadTools.listFiles("emptydir")

            assertTrue(result.isEmpty())
        }

        @Test
        fun `should throw exception when directory does not exist`() {
            assertThrows<IllegalArgumentException> {
                fileReadTools.listFiles("nonexistentdir")
            }
        }

        @Test
        fun `should throw exception when path is a file`() {
            assertThrows<IllegalArgumentException> {
                fileReadTools.listFiles("file1.txt")
            }
        }
    }

    @Nested
    inner class ResolvePath {

        @Test
        fun `should resolve relative path`() {
            val result = fileReadTools.resolvePath("subdir/file.txt")

            assertEquals(tempDir.resolve("subdir/file.txt"), result)
        }

        @Test
        fun `should prevent path traversal`() {
            assertThrows<SecurityException> {
                fileReadTools.resolvePath("../../../etc/passwd")
            }
        }
    }

    @Nested
    inner class `resolveAndValidateFile` {

        @BeforeEach
        fun setupFile() {
            Files.createDirectories(tempDir.resolve("subdir"))
            Files.writeString(tempDir.resolve("file.txt"), "content")
        }

        @Test
        fun `should throw exception when file does not exist`() {
            assertThrows<IllegalArgumentException> {
                fileReadTools.resolveAndValidateFile("nonexistent.txt")
            }
        }

        @Test
        fun `should throw exception when path is a directory`() {
            assertThrows<IllegalArgumentException> {
                fileReadTools.resolveAndValidateFile("subdir")
            }
        }

        @Test
        fun `should return path when file exists`() {
            val result = fileReadTools.resolveAndValidateFile("file.txt")

            assertEquals(tempDir.resolve("file.txt"), result)
        }
    }
}

private fun List<String>.toPaths(): List<Path> = map { Paths.get(it) }
