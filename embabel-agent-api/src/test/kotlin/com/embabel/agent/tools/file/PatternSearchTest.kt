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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PatternSearchTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var patternSearch: PatternSearch
    private lateinit var rootPath: String

    @BeforeEach
    fun setUp() {
        rootPath = tempDir.toString()
        patternSearch = object : PatternSearch {
            override val root: String = rootPath
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
        fun `should find literal pattern in single file`() {
            val matches = patternSearch.findPatternInProject(Regex("content1"), "**/*.txt")

            assertEquals(1, matches.size)

            val m1 = matches.first()
            assertEquals(m1.file.name, "file1.txt")
            assertEquals(1, m1.matchedLine)
        }
    }
}
