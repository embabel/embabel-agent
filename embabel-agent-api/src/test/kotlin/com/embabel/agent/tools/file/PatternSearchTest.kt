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

import com.embabel.agent.spi.support.ExecutorAsyncer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

        @Test
        fun `should find pattern without asyncer - sequential`() {
            val matches = patternSearch.findPatternInProject(
                pattern = Regex("content"),
                globPattern = "**/*.txt",
                asyncer = null,
            )

            assertEquals(4, matches.size)
        }
    }

    @Nested
    inner class ParallelSearch {

        private val executor = Executors.newFixedThreadPool(2)
        private val asyncer = ExecutorAsyncer(executor)

        @AfterEach
        fun cleanup() {
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }

        @BeforeEach
        fun setupManyFiles() {
            // Create >100 files to trigger parallel path
            repeat(150) { i ->
                Files.writeString(tempDir.resolve("file$i.txt"), "searchable_content_$i")
            }
        }

        @Test
        fun `should find patterns in parallel with asyncer`() {
            val matches = patternSearch.findPatternInProject(
                pattern = Regex("searchable_content"),
                globPattern = "**/*.txt",
                asyncer = asyncer,
            )

            assertEquals(150, matches.size)
        }

        @Test
        fun `should find specific pattern in parallel`() {
            val matches = patternSearch.findPatternInProject(
                pattern = Regex("searchable_content_42"),
                globPattern = "**/*.txt",
                asyncer = asyncer,
            )

            assertEquals(1, matches.size)
            assertTrue(matches.first().file.name.contains("42"))
        }
    }
}
