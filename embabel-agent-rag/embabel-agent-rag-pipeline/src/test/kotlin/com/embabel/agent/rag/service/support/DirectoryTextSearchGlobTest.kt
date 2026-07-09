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
package com.embabel.agent.rag.service.support

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.service.RagRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Glob-filtering spec for [DirectoryTextSearch.Config.fileGlob], which filters files through the shared
 * [com.embabel.agent.tools.file.globMatcher].
 *
 * Asserts ripgrep/gitignore semantics: a double-star followed by a slash matches ZERO or more
 * directories, so a leading or prefixed double-star also selects files at the root / directly under a
 * directory (Java NIO's raw `PathMatcher` required at least one directory there, silently dropping
 * root/direct-child files from indexing). These tests guard that the RAG path inherits the fix, and
 * that single-segment globs, brace alternation and no-match cases keep behaving.
 */
class DirectoryTextSearchGlobTest {

    @TempDir
    lateinit var tempDir: Path

    // Every file contains the search term, so results are driven purely by the glob filter.
    private val allFiles = setOf(
        "Root.kt",
        "src/Nested.kt",
        "src/sub/Deep.kt",
        "Root.md",
        "docs/guide.md",
        "notes.txt",
    )

    @BeforeEach
    fun setUp() {
        allFiles.forEach { rel ->
            val p = tempDir.resolve(rel)
            Files.createDirectories(p.parent)
            Files.writeString(p, "needle in $rel")
        }
    }

    /** Relative ids of files selected by the glob (one chunk per small file → id == relative path). */
    private fun matched(glob: String?): Set<String> {
        val config = if (glob == null) {
            DirectoryTextSearch.Config()
        } else {
            DirectoryTextSearch.Config().withFileGlob(glob)
        }
        val search = DirectoryTextSearch(tempDir.toString(), config)
        val request = RagRequest.query("needle").withSimilarityThreshold(0.0).withTopK(100)
        return search.textSearch(request, Chunk::class.java).map { it.match.id }.toSet()
    }

    @Test
    fun `leading double-star slash includes root-level kt files`() {
        assertEquals(setOf("Root.kt", "src/Nested.kt", "src/sub/Deep.kt"), matched("**/*.kt"))
    }

    @Test
    fun `leading double-star slash includes root-level md files`() {
        assertEquals(setOf("Root.md", "docs/guide.md"), matched("**/*.md"))
    }

    @Test
    fun `prefixed double-star slash includes files directly under the directory`() {
        // 'src/**/*.kt' must include src/Nested.kt (zero dirs) as well as the nested one
        assertEquals(setOf("src/Nested.kt", "src/sub/Deep.kt"), matched("src/**/*.kt"))
    }

    @Test
    fun `single star matches only root level`() {
        assertEquals(setOf("Root.kt"), matched("*.kt"))
    }

    @Test
    fun `single star under a directory matches only direct children`() {
        assertEquals(setOf("src/Nested.kt"), matched("src/*.kt"))
    }

    @Test
    fun `brace alternation selects every listed extension at any depth`() {
        assertEquals(
            setOf("Root.kt", "src/Nested.kt", "src/sub/Deep.kt", "Root.md", "docs/guide.md"),
            matched("**/*.{kt,md}"),
        )
    }

    @Test
    fun `glob matching nothing indexes no files`() {
        assertEquals(emptySet<String>(), matched("**/*.java"))
    }

    @Test
    fun `no glob selects every file`() {
        assertEquals(allFiles, matched(null))
    }
}
