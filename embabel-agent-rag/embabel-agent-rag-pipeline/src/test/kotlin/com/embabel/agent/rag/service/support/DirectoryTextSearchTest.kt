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
import com.embabel.agent.rag.model.Retrievable
import com.embabel.agent.rag.service.RagRequest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DirectoryTextSearchTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var search: DirectoryTextSearch

    @BeforeEach
    fun setUp() {
        search = DirectoryTextSearch(tempDir.toString())
    }

    @Nested
    inner class TextSearchTests {

        @Test
        fun `should find files containing search terms`() {
            // Create test files
            createFile("doc1.txt", "Machine learning algorithms for data analysis")
            createFile("doc2.txt", "Cooking recipes and kitchen tips")
            createFile("doc3.txt", "Deep learning neural networks")

            val request = RagRequest.query("machine learning").withSimilarityThreshold(0.0)
            val results = search.textSearch(request, Chunk::class.java)

            assertTrue(results.isNotEmpty())
            assertTrue(results.any { it.match.text.contains("Machine learning") })
        }

        @Test
        fun `should calculate correct score based on term matches`() {
            createFile("doc1.txt", "machine learning machine learning")
            createFile("doc2.txt", "machine only")

            val request = RagRequest.query("machine learning").withSimilarityThreshold(0.0)
            val results = search.textSearch(request, Chunk::class.java)

            assertEquals(2, results.size)
            // doc1 has both terms (score = 1.0), doc2 has only one (score = 0.5)
            val doc1Result = results.find { it.match.id == "doc1.txt" }
            val doc2Result = results.find { it.match.id == "doc2.txt" }

            assertTrue(doc1Result != null, "doc1Result should not be null")
            assertTrue(doc2Result != null, "doc2Result should not be null")
            assertEquals(1.0, doc1Result!!.score)
            assertEquals(0.5, doc2Result!!.score)
        }

        @Test
        fun `should respect similarity threshold`() {
            createFile("doc1.txt", "machine learning algorithms")
            createFile("doc2.txt", "machine only here")

            // With threshold 0.8, only doc1 (score 1.0) should match
            val request = RagRequest.query("machine learning").withSimilarityThreshold(0.8)
            val results = search.textSearch(request, Chunk::class.java)

            assertEquals(1, results.size)
            assertEquals("doc1.txt", results[0].match.id)
        }

        @Test
        fun `should respect topK limit`() {
            (1..10).forEach { i ->
                createFile("doc$i.txt", "machine learning document number $i")
            }

            val request = RagRequest.query("machine learning").withTopK(3).withSimilarityThreshold(0.0)
            val results = search.textSearch(request, Chunk::class.java)

            assertTrue(results.size <= 3)
        }

        @Test
        fun `should sort results by score descending`() {
            createFile("doc1.txt", "machine")
            createFile("doc2.txt", "machine learning")
            createFile("doc3.txt", "machine learning algorithms")

            val request = RagRequest.query("machine learning algorithms").withSimilarityThreshold(0.0)
            val results = search.textSearch(request, Chunk::class.java)

            // Check descending order
            for (i in 0 until results.size - 1) {
                assertTrue(results[i].score >= results[i + 1].score)
            }
        }

        @Test
        fun `should return empty list when no matches`() {
            createFile("doc1.txt", "Cooking recipes")

            val request = RagRequest.query("quantum physics").withSimilarityThreshold(0.0)
            val results = search.textSearch(request, Chunk::class.java)

            assertTrue(results.isEmpty())
        }

        @Test
        fun `should return empty list for empty query`() {
            createFile("doc1.txt", "Some content")

            val request = RagRequest.query("   ").withSimilarityThreshold(0.0)
            val results = search.textSearch(request, Chunk::class.java)

            assertTrue(results.isEmpty())
        }

        @Test
        fun `should perform case-insensitive search`() {
            createFile("doc1.txt", "MACHINE LEARNING in uppercase")

            val request = RagRequest.query("machine learning").withSimilarityThreshold(0.0)
            val results = search.textSearch(request, Chunk::class.java)

            assertEquals(1, results.size)
        }

        @Test
        fun `should search in subdirectories`() {
            createFile("root.txt", "root file with machine learning")
            createFile("subdir/nested.txt", "nested file with machine learning")
            createFile("subdir/deep/deeper.txt", "deep nested machine learning file")

            val request = RagRequest.query("machine learning").withSimilarityThreshold(0.0)
            val results = search.textSearch(request, Chunk::class.java)

            assertEquals(3, results.size)
        }

        @Test
        fun `should include file metadata in chunk`() {
            createFile("docs/readme.txt", "Some machine learning content")

            val request = RagRequest.query("machine").withSimilarityThreshold(0.0)
            val results = search.textSearch(request, Chunk::class.java)

            assertEquals(1, results.size)
            val chunk = results[0].match

            assertEquals("docs/readme.txt", chunk.id)
            assertEquals("readme.txt", chunk.metadata["file_name"])
            assertEquals("docs/readme.txt", chunk.metadata["relative_path"])
            assertTrue((chunk.metadata["file_path"] as String).endsWith("docs/readme.txt"))
            assertEquals("directory:${tempDir}", chunk.metadata["source"])
        }
    }

    @Nested
    inner class RegexSearchTests {

        @Test
        fun `should find files matching regex pattern`() {
            createFile("doc1.txt", "Error code E001: Connection failed")
            createFile("doc2.txt", "Error code E002: Timeout occurred")
            createFile("doc3.txt", "Success: Operation completed")

            val results = search.regexSearch(
                regex = Regex("E\\d{3}"),
                topK = 10,
                clazz = Chunk::class.java
            )

            assertEquals(2, results.size)
            assertTrue(results.all { it.match.id in listOf("doc1.txt", "doc2.txt") })
        }

        @Test
        fun `should respect topK for regex search`() {
            (1..10).forEach { i ->
                createFile("doc$i.txt", "Error E00$i occurred")
            }

            val results = search.regexSearch(
                regex = Regex("E\\d+"),
                topK = 3,
                clazz = Chunk::class.java
            )

            assertEquals(3, results.size)
        }

        @Test
        fun `should return empty when no regex matches`() {
            createFile("doc1.txt", "Normal text without patterns")

            val results = search.regexSearch(
                regex = Regex("E\\d{3}"),
                topK = 10,
                clazz = Chunk::class.java
            )

            assertTrue(results.isEmpty())
        }

        @Test
        fun `should find complex regex patterns`() {
            createFile("doc1.txt", "Email: user@example.com is valid")
            createFile("doc2.txt", "Contact admin@test.org for help")
            createFile("doc3.txt", "No email here")

            val results = search.regexSearch(
                regex = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"),
                topK = 10,
                clazz = Chunk::class.java
            )

            assertEquals(2, results.size)
            assertTrue(results.none { it.match.id == "doc3.txt" })
        }

        @Test
        fun `regex search should return score of 1_0`() {
            createFile("doc1.txt", "Error E001")

            val results = search.regexSearch(
                regex = Regex("E\\d+"),
                topK = 10,
                clazz = Chunk::class.java
            )

            assertEquals(1, results.size)
            assertEquals(1.0, results[0].score)
        }

        @Test
        fun `should find multiline patterns`() {
            createFile("doc1.txt", "Line 1\nError: something\nLine 3")

            val results = search.regexSearch(
                regex = Regex("Error.*"),
                topK = 10,
                clazz = Chunk::class.java
            )

            assertEquals(1, results.size)
        }
    }

    @Nested
    inner class FileFilteringTests {

        @Test
        fun `should filter by file glob pattern`() {
            createFile("src/code.kt", "fun main() { println(\"machine\") }")
            createFile("src/code.java", "public class Main { /* machine */ }")
            createFile("src/readme.txt", "machine learning docs")

            val kotlinSearch = DirectoryTextSearch(
                directory = tempDir.toString(),
                config = DirectoryTextSearch.Config().withFileGlob("**/*.kt")
            )

            val request = RagRequest.query("machine").withSimilarityThreshold(0.0)
            val results = kotlinSearch.textSearch(request, Chunk::class.java)

            assertEquals(1, results.size)
            assertTrue(results[0].match.id.endsWith(".kt"))
        }

        @Test
        fun `should exclude directories by default`() {
            createFile("main.txt", "machine learning in main")
            createFile("node_modules/lib.txt", "machine learning in node_modules")
            createFile(".git/config", "machine learning in git")

            val request = RagRequest.query("machine learning").withSimilarityThreshold(0.0)
            val results = search.textSearch(request, Chunk::class.java)

            assertEquals(1, results.size)
            assertEquals("main.txt", results[0].match.id)
        }

        @Test
        fun `should support custom exclude directories`() {
            createFile("main.txt", "machine learning in main")
            createFile("test/test.txt", "machine learning in test")
            createFile("build/output.txt", "machine learning in build")

            val customSearch = DirectoryTextSearch(
                directory = tempDir.toString(),
                config = DirectoryTextSearch.Config().withExcludeDirectories(setOf("test", "build"))
            )

            val request = RagRequest.query("machine learning").withSimilarityThreshold(0.0)
            val results = customSearch.textSearch(request, Chunk::class.java)

            assertEquals(1, results.size)
            assertEquals("main.txt", results[0].match.id)
        }

        @Test
        fun `should combine glob and exclude directories`() {
            createFile("src/main.kt", "machine learning kotlin")
            createFile("src/test.kt", "machine learning test")
            createFile("src/main.java", "machine learning java")
            createFile("test/test.kt", "machine learning test dir")

            val customSearch = DirectoryTextSearch(
                directory = tempDir.toString(),
                config = DirectoryTextSearch.Config()
                    .withFileGlob("**/*.kt")
                    .withExcludeDirectories(setOf("test"))
            )

            val request = RagRequest.query("machine").withSimilarityThreshold(0.0)
            val results = customSearch.textSearch(request, Chunk::class.java)

            assertEquals(2, results.size)
            assertTrue(results.all { it.match.id.endsWith(".kt") })
            assertTrue(results.none { it.match.id.startsWith("test/") })
        }
    }

    @Nested
    inner class EdgeCaseTests {

        @Test
        fun `should handle non-existent directory`() {
            val nonExistentSearch = DirectoryTextSearch("/non/existent/path")

            val request = RagRequest.query("anything").withSimilarityThreshold(0.0)
            val results = nonExistentSearch.textSearch(request, Chunk::class.java)

            assertTrue(results.isEmpty())
        }

        @Test
        fun `should handle empty directory`() {
            val request = RagRequest.query("anything").withSimilarityThreshold(0.0)
            val results = search.textSearch(request, Chunk::class.java)

            assertTrue(results.isEmpty())
        }

        @Test
        fun `should handle files with special characters in name`() {
            createFile("file with spaces.txt", "machine learning content")
            createFile("file-with-dashes.txt", "machine learning content")
            createFile("file_with_underscores.txt", "machine learning content")

            val request = RagRequest.query("machine").withSimilarityThreshold(0.0)
            val results = search.textSearch(request, Chunk::class.java)

            assertEquals(3, results.size)
        }

        @Test
        fun `should handle large files`() {
            val largeContent = "machine learning ".repeat(10000)
            createFile("large.txt", largeContent)

            val request = RagRequest.query("machine").withSimilarityThreshold(0.0).withTopK(1000)
            val results = search.textSearch(request, Chunk::class.java)

            // Large files are chunked, so we expect multiple results
            assertTrue(results.isNotEmpty())
            assertTrue(results.all { it.match.text.contains("machine") })
        }

        @Test
        fun `should handle binary-like content gracefully`() {
            // File with some binary content mixed with text
            val mixedContent = "machine learning\u0000\u0001\u0002more text"
            createFile("mixed.txt", mixedContent)

            val request = RagRequest.query("machine").withSimilarityThreshold(0.0)
            val results = search.textSearch(request, Chunk::class.java)

            assertEquals(1, results.size)
        }

        @Test
        fun `should return empty for non-Chunk class type`() {
            createFile("doc.txt", "machine learning")

            // Using a custom Retrievable that's not Chunk
            val request = RagRequest.query("machine").withSimilarityThreshold(0.0)
            val results = search.textSearch(request, CustomRetrievable::class.java)

            assertTrue(results.isEmpty())
        }

        @Test
        fun `regex search should return empty for non-Chunk class type`() {
            createFile("doc.txt", "Error E001")

            val results = search.regexSearch(
                regex = Regex("E\\d+"),
                topK = 10,
                clazz = CustomRetrievable::class.java
            )

            assertTrue(results.isEmpty())
        }
    }

    @Nested
    inner class ChunkContentTests {

        @Test
        fun `small file should be returned as single chunk`() {
            val content = "This is small content with machine learning"
            createFile("doc.txt", content)

            val request = RagRequest.query("machine").withSimilarityThreshold(0.0)
            val results = search.textSearch(request, Chunk::class.java)

            assertEquals(1, results.size)
            assertEquals(content, results[0].match.text)
            assertEquals(0, results[0].match.metadata["chunk_index"])
            assertEquals(1, results[0].match.metadata["total_chunks"])
        }

        @Test
        fun `chunk id should be relative path for single chunk`() {
            createFile("subdir/nested/doc.txt", "machine learning")

            val request = RagRequest.query("machine").withSimilarityThreshold(0.0)
            val results = search.textSearch(request, Chunk::class.java)

            assertEquals(1, results.size)
            assertEquals("subdir/nested/doc.txt", results[0].match.id)
        }

        @Test
        fun `chunk parentId should be directory for single chunk`() {
            createFile("doc.txt", "machine learning")

            val request = RagRequest.query("machine").withSimilarityThreshold(0.0)
            val results = search.textSearch(request, Chunk::class.java)

            assertEquals(1, results.size)
            assertEquals(tempDir.toString(), results[0].match.parentId)
        }
    }

    @Nested
    inner class ChunkingTests {

        @Test
        fun `matches far apart should create multiple chunks`() {
            // Create content with matches at beginning and end, separated by more than chunk size
            val content = "machine learning " + "x".repeat(2000) + " machine learning"
            createFile("large.txt", content)

            val request = RagRequest.query("machine").withSimilarityThreshold(0.0).withTopK(100)
            val results = search.textSearch(request, Chunk::class.java)

            // With default 1000 char chunks, matches 2000+ chars apart should create separate chunks
            assertTrue(results.size > 1, "Expected multiple chunks for distant matches, got ${results.size}")
            assertTrue(results.all { it.match.metadata["total_chunks"] as Int > 1 })
        }

        @Test
        fun `chunk ids should include index for multi-chunk files`() {
            val content = "machine " + "x".repeat(2000) + " machine"
            createFile("large.txt", content)

            val request = RagRequest.query("machine").withSimilarityThreshold(0.0).withTopK(100)
            val results = search.textSearch(request, Chunk::class.java)

            assertTrue(results.any { it.match.id == "large.txt#0" })
            assertTrue(results.any { it.match.id == "large.txt#1" })
        }

        @Test
        fun `chunk parentId should be file path for multi-chunk files`() {
            val content = "machine " + "x".repeat(2000) + " machine"
            createFile("large.txt", content)

            val request = RagRequest.query("machine").withSimilarityThreshold(0.0).withTopK(100)
            val results = search.textSearch(request, Chunk::class.java)

            // For multi-chunk files, parentId is the file's relative path
            assertTrue(results.all { it.match.parentId == "large.txt" })
        }

        @Test
        fun `nearby matches should be merged into single chunk`() {
            // Multiple matches close together should merge into one chunk
            val content = "machine learning algorithms machine learning"
            createFile("merged.txt", content)

            val request = RagRequest.query("machine").withSimilarityThreshold(0.0).withTopK(100)
            val results = search.textSearch(request, Chunk::class.java)

            // All matches are within chunk size, so should be one chunk
            assertEquals(1, results.size)
            assertTrue(results[0].match.text.contains("machine"))
        }

        @Test
        fun `chunk should be centered around match`() {
            // Match in the middle of a large file
            val content = "x".repeat(1000) + "machine" + "y".repeat(1000)
            createFile("centered.txt", content)

            val smallChunkSearch = DirectoryTextSearch(
                directory = tempDir.toString(),
                config = DirectoryTextSearch.Config().withChunkSize(500)
            )

            val request = RagRequest.query("machine").withSimilarityThreshold(0.0).withTopK(100)
            val results = smallChunkSearch.textSearch(request, Chunk::class.java)

            assertEquals(1, results.size)
            assertTrue(results[0].match.text.contains("machine"))
            // Chunk should be ~500 chars, centered on "machine"
            assertTrue(results[0].match.text.length <= 500)
        }

        @Test
        fun `chunk metadata should include position info`() {
            val content = "x".repeat(500) + "machine" + "y".repeat(500)
            createFile("meta.txt", content)

            val request = RagRequest.query("machine").withSimilarityThreshold(0.0).withTopK(100)
            val results = search.textSearch(request, Chunk::class.java)

            val chunk = results[0]
            assertTrue(chunk.match.metadata.containsKey("chunk_start"))
            assertTrue(chunk.match.metadata.containsKey("chunk_end"))
            assertTrue((chunk.match.metadata["chunk_end"] as Int) > (chunk.match.metadata["chunk_start"] as Int))
        }

        @Test
        fun `disabling chunking should return whole file`() {
            val content = "machine learning ".repeat(100) // ~1700 chars
            createFile("whole.txt", content)

            val noChunkSearch = DirectoryTextSearch(
                directory = tempDir.toString(),
                config = DirectoryTextSearch.Config().withChunkSize(0) // Disable chunking
            )

            val request = RagRequest.query("machine").withSimilarityThreshold(0.0)
            val results = noChunkSearch.textSearch(request, Chunk::class.java)

            assertEquals(1, results.size)
            assertEquals(content, results[0].match.text)
            assertEquals("whole.txt", results[0].match.id)
        }

        @Test
        fun `custom chunk size should limit chunk length`() {
            val content = "x".repeat(500) + "machine" + "y".repeat(500)
            createFile("custom.txt", content)

            val smallChunkSearch = DirectoryTextSearch(
                directory = tempDir.toString(),
                config = DirectoryTextSearch.Config().withChunkSize(100)
            )

            val request = RagRequest.query("machine").withSimilarityThreshold(0.0).withTopK(100)
            val results = smallChunkSearch.textSearch(request, Chunk::class.java)

            assertEquals(1, results.size)
            assertTrue(results[0].match.text.length <= 100, "Chunk should be <= 100 chars")
            assertTrue(results[0].match.text.contains("machine"))
        }

        @Test
        fun `regex search should create chunks around matches`() {
            val content = "Error E001 " + "x".repeat(2000) + " Error E002"
            createFile("errors.txt", content)

            val results = search.regexSearch(
                regex = Regex("E\\d{3}"),
                topK = 100,
                clazz = Chunk::class.java
            )

            // Matches are far apart, should create separate chunks
            assertEquals(2, results.size)
            assertTrue(results.any { it.match.text.contains("E001") })
            assertTrue(results.any { it.match.text.contains("E002") })
        }
    }

    // Helper methods

    private fun createFile(relativePath: String, content: String) {
        val path = tempDir.resolve(relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    // Custom Retrievable for testing type filtering
    private class CustomRetrievable : Retrievable {
        override val id: String = "custom"
        override val uri: String? = null
        override val metadata: Map<String, Any?> = emptyMap()
        override fun embeddableValue(): String = "custom"
        override fun propertiesToPersist(): Map<String, Any?> = emptyMap()
        override fun labels(): Set<String> = setOf("Custom")
        override fun infoString(verbose: Boolean?, indent: Int): String = "custom"
    }
}
