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
        fileReadTools = FileTools.readOnly(
            rootPath,
            emptyList()
        )
    }

    @Nested
    inner class FindFiles {

        @BeforeEach
        fun setupFiles() {
            // Create directory structure
            Files.createDirectories(tempDir.resolve("dir1"))
            Files.createDirectories(tempDir.resolve(Paths.get("dir2", "subdir")))

            // Create files
            Files.writeString(tempDir.resolve("file1.txt"), "content1")
            Files.writeString(tempDir.resolve("file2.md"), "content2")
            Files.writeString(tempDir.resolve(Paths.get("dir1", "file3.txt")), "content3")
            Files.writeString(tempDir.resolve(Paths.get("dir2", "file4.txt")), "content4")
            Files.writeString(tempDir.resolve(Paths.get("dir2", "subdir", "file5.txt")), "content5")
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

        @Test
        fun `should not exclude under`() {
            Files.createDirectories(tempDir.resolve("thing"))
            Files.createDirectories(tempDir.resolve(Paths.get("thing", "foo")))
            Files.writeString(tempDir.resolve(Paths.get("thing", "pom.xml")), "maven stuff")
            Files.writeString(tempDir.resolve(Paths.get("thing", "foo", "pom.xml")), "maven stuff")
            val result = fileReadTools.findFiles("**/pom.xml")
            assertEquals(2, result.size, "Should not exclude under directories by default")
        }

        @Test
        fun `should exclude under when requested`() {
            Files.createDirectories(tempDir.resolve("thing"))
            Files.createDirectories(tempDir.resolve(Paths.get("thing", "foo")))
            Files.writeString(tempDir.resolve(Paths.get("thing", "pom.xml")), "maven stuff")
            Files.writeString(tempDir.resolve(Paths.get("thing", "foo", "pom.xml")), "maven stuff")

            val result = fileReadTools.findFiles("**/pom.xml", findHighest = true)

            assertEquals(1, result.size, "Should only find highest level pom.xml")
            val resultPath = Paths.get(result[0])
            val expectedPath = Paths.get("thing", "pom.xml")
            assertTrue(resultPath.endsWith(expectedPath), "Should exclude under directories when requested")
        }

        @Test
        fun `should not exclude under when parallel`() {
            Files.createDirectories(tempDir.resolve("thing"))
            Files.createDirectories(tempDir.resolve(Paths.get("thing", "foo")))
            Files.createDirectories(tempDir.resolve("that"))
            Files.createDirectories(tempDir.resolve(Paths.get("that", "foo")))
            Files.writeString(tempDir.resolve(Paths.get("thing", "pom.xml")), "maven stuff")
            // Not parallel
            Files.writeString(tempDir.resolve(Paths.get("that", "foo", "pom.xml")), "maven stuff")
            val result = fileReadTools.findFiles("**/pom.xml")
            assertEquals(2, result.size, "Should not exclude when not nested")
        }
    }

    @Nested
    inner class ReadFile {

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
    inner class ListFiles {

        @BeforeEach
        fun setupDirectoryStructure() {
            // Create directory structure
            Files.createDirectories(tempDir.resolve("emptydir"))
            Files.createDirectories(tempDir.resolve("nonemptydir"))

            // Create files
            Files.writeString(tempDir.resolve("file1.txt"), "content1")
            Files.writeString(tempDir.resolve(Paths.get("nonemptydir", "file2.txt")), "content2")
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
            val relativePath = Paths.get("subdir", "file.txt").toString()
            val result = fileReadTools.resolvePath(relativePath)

            assertEquals(tempDir.resolve(relativePath), result)
        }

        @Test
        fun `should prevent path traversal`() {
            assertThrows<SecurityException> {
                fileReadTools.resolvePath("../../../etc/passwd")
            }
        }
    }

    @Nested
    inner class ResolveAndValidateFile {

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

    @Nested
    inner class FileReadLog {

        private val testContent = "test content"
        private lateinit var testFile: Path

        @BeforeEach
        fun setupFile() {
            fileReadTools.flushReads()
            testFile = tempDir.resolve("test.txt")
            Files.writeString(testFile, testContent)
        }

        @Test
        fun `should read file content`() {
            assertTrue(fileReadTools.getReads().isEmpty(), "Reads should be empty")
            val result = fileReadTools.readFile("test.txt")
            assertEquals(1, fileReadTools.getReads().size, "Should have recorded read")
            assertEquals("test.txt", fileReadTools.getReads()[0].path)
            assertEquals("test.txt", fileReadTools.getPathsRead().single())
            assertEquals(testContent, result)
        }

        @Test
        fun `should count reads`() {
            assertTrue(fileReadTools.getReads().isEmpty(), "Reads should be empty")
            val result = fileReadTools.readFile("test.txt")
            assertEquals(1, fileReadTools.getReads().size, "Should have recorded read")
            fileReadTools.readFile("test.txt")
            assertEquals(1, fileReadTools.getReads().size, "Should still have one read")

            val read = fileReadTools.getReads().single()
            assertEquals("test.txt", read.path)
            assertEquals(2, read.count())
        }

        @Test
        fun `should throw exception when file does not exist`() {
            assertThrows<IllegalArgumentException> {
                fileReadTools.readFile("nonexistent.txt")
            }
            assertTrue(fileReadTools.getReads().isEmpty(), "Reading a non-existent file should not count")

        }

        @Test
        fun `should throw exception when path is a directory`() {
            Files.createDirectory(tempDir.resolve("testdir"))
            assertThrows<IllegalArgumentException> {
                fileReadTools.readFile("testdir")
            }
            assertTrue(fileReadTools.getReads().isEmpty(), "Writing a directory should not count")
        }
    }

    @Nested
    inner class AccessLog {

        private val testContent = "test content"
        private lateinit var testFile: Path

        @BeforeEach
        fun setupFile() {
            fileReadTools.flushReads()
            testFile = tempDir.resolve("test.txt")
            Files.writeString(testFile, testContent)
        }

        @Test
        fun `should read file content`() {
            assertTrue(fileReadTools.getPathsAccessed().isEmpty(), "Reads should be empty")
            val result = fileReadTools.readFile("test.txt")
            assertEquals(1, fileReadTools.getPathsAccessed().size, "Should have recorded read")
        }

        @Test
        fun `should throw exception when file does not exist`() {
            assertThrows<IllegalArgumentException> {
                fileReadTools.readFile("nonexistent.txt")
            }
            assertTrue(fileReadTools.getPathsAccessed().isEmpty(), "Reading a non-existent file should not count")

        }

        @Test
        fun `should throw exception when path is a directory`() {
            Files.createDirectory(tempDir.resolve("testdir"))
            assertThrows<IllegalArgumentException> {
                fileReadTools.readFile("testdir")
            }
            assertTrue(fileReadTools.getPathsAccessed().isEmpty(), "Writing a directory should not count")
        }
    }

    @Nested
    inner class FileCount {

        @BeforeEach
        fun setupFiles() {
            // Create directory structure
            Files.createDirectories(tempDir.resolve("dir1"))
            Files.createDirectories(tempDir.resolve(Paths.get("dir2", "subdir")))
            Files.createDirectories(tempDir.resolve(".git"))
            Files.createDirectories(tempDir.resolve(Paths.get(".git", "objects")))

            // Create regular files
            Files.writeString(tempDir.resolve("file1.txt"), "content1")
            Files.writeString(tempDir.resolve("file2.md"), "content2")
            Files.writeString(tempDir.resolve(Paths.get("dir1", "file3.txt")), "content3")
            Files.writeString(tempDir.resolve(Paths.get("dir2", "file4.txt")), "content4")
            Files.writeString(tempDir.resolve(Paths.get("dir2", "subdir", "file5.txt")), "content5")

            // Create files in .git directory that should be excluded
            Files.writeString(tempDir.resolve(Paths.get(".git", "config")), "git config")
            Files.writeString(tempDir.resolve(Paths.get(".git", "objects", "abc123")), "git object")
        }

        @Test
        fun `should count all files excluding git directory`() {
            val count = fileReadTools.fileCount()

            // Should count: file1.txt, file2.md, dir1/file3.txt, dir2/file4.txt, dir2/subdir/file5.txt
            // Should exclude: .git/config, .git/objects/abc123
            assertEquals(5, count)
        }

        @Test
        fun `should return 0 for empty directory`(@TempDir emptyDir: Path) {
            val emptyFileReadTools = FileTools.readOnly(emptyDir.toString())

            val count = emptyFileReadTools.fileCount()

            assertEquals(0, count)
        }

        @Test
        fun `should handle directory with only git files`(@TempDir gitOnlyDir: Path) {
            // Create only .git directory and files
            Files.createDirectories(gitOnlyDir.resolve(".git"))
            Files.writeString(gitOnlyDir.resolve(Paths.get(".git", "config")), "git config")

            val gitOnlyFileReadTools = FileTools.readOnly(gitOnlyDir.toString())

            val count = gitOnlyFileReadTools.fileCount()

            assertEquals(0, count)
        }

        @Test
        fun `should handle deeply nested directories`() {
            // Create deeply nested structure
            val deepPath = Paths.get("level1", "level2", "level3", "level4")
            Files.createDirectories(tempDir.resolve(deepPath))
            Files.writeString(tempDir.resolve(deepPath.resolve("deep-file.txt")), "deep content")

            val count = fileReadTools.fileCount()

            // Original 5 files plus the new deep file
            assertEquals(6, count)
        }

        @Test
        fun `should exclude git directory regardless of location`() {
            // Create .git directory in subdirectory
            Files.createDirectories(tempDir.resolve(Paths.get("subproject", ".git")))
            Files.writeString(tempDir.resolve(Paths.get("subproject", ".git", "config")), "subproject git config")
            Files.writeString(tempDir.resolve(Paths.get("subproject", "README.md")), "subproject readme")

            val count = fileReadTools.fileCount()

            // Original 5 files plus subproject/README.md (but not the .git/config)
            assertEquals(6, count)
        }
    }
}

private fun List<String>.toPaths(): List<Path> = map { Paths.get(it) }
