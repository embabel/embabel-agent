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
package com.embabel.agent.rag.service.support

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.Retrievable
import com.embabel.agent.rag.service.TextSearch
import com.embabel.agent.tools.file.FileTools
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.SimpleSimilaritySearchResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.pathString

/**
 * Perform text search on files in a directory.
 * Creates Chunk instances from file contents that match search criteria.
 * Files are split into chunks of configurable size for better RAG retrieval.
 *
 * **Performance Note:** This is a convenience implementation for small directories.
 * Every search re-reads all matching files from disk with no indexing or caching.
 *
 * Approximate performance:
 * - ~10 small files (< 10KB): Fast (~10-50ms)
 * - ~100 files (~50KB each): Noticeable (~500ms-2s)
 * - 1000+ files: Slow (several seconds)
 *
 * **Tip:** Use a restrictive [Config.fileGlob] pattern (e.g., `**&#47;*.kt`) to reduce the number of
 * files scanned and improve performance.
 *
 * For larger directories or frequent searches, use [com.embabel.agent.rag.lucene.LuceneRagFacetProvider]
 * which provides proper Lucene indexing and is orders of magnitude faster after initial indexing.
 *
 * @param directory Root directory to search in
 * @param config Configuration for file filtering and chunking
 */
class DirectoryTextSearch @JvmOverloads constructor(
    private val directory: String,
    private val config: Config = Config(),
) : TextSearch {

    /**
     * Configuration for [DirectoryTextSearch].
     * Use [withFileGlob], [withExcludeDirectories], [withChunkSize], and [withChunkOverlap]
     * for a fluent builder pattern.
     *
     * @param fileGlob Glob pattern to filter files (default: all files). Use `**&#47;*.kt` for Kotlin files, etc.
     * @param excludeDirectories Directory names to exclude (default: .git, node_modules, build, target)
     * @param chunkSize Maximum size of each chunk in characters. Use 0 to return whole files without chunking.
     * @param chunkOverlap Number of characters to overlap between chunks for context continuity.
     */
    data class Config @JvmOverloads constructor(
        val fileGlob: String? = null,
        val excludeDirectories: Set<String> = DEFAULT_EXCLUDE_DIRECTORIES,
        val chunkSize: Int = DEFAULT_CHUNK_SIZE,
        val chunkOverlap: Int = DEFAULT_CHUNK_OVERLAP,
    ) {
        fun withFileGlob(fileGlob: String): Config = copy(fileGlob = fileGlob)
        fun withExcludeDirectories(excludeDirectories: Set<String>): Config =
            copy(excludeDirectories = excludeDirectories)

        fun withChunkSize(chunkSize: Int): Config = copy(chunkSize = chunkSize)
        fun withChunkOverlap(chunkOverlap: Int): Config = copy(chunkOverlap = chunkOverlap)

        companion object {

            @JvmField
            val DEFAULT_EXCLUDE_DIRECTORIES: Set<String> = setOf(".git", "node_modules", "build", "target")

            const val DEFAULT_CHUNK_SIZE = 1000

            const val DEFAULT_CHUNK_OVERLAP = 200
        }
    }

    private val logger = LoggerFactory.getLogger(DirectoryTextSearch::class.java)
    private val fileReadTools = FileTools.readOnly(directory)
    private val rootPath = Path.of(directory).toAbsolutePath().normalize()
    private val globMatcher = config.fileGlob?.let {
        java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$it")
    }

    override fun <T : Retrievable> textSearch(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>,
    ): List<SimilarityResult<T>> {
        if (!Chunk::class.java.isAssignableFrom(clazz)) {
            logger.warn("DirectoryTextSearch only supports Chunk type, got {}", clazz.simpleName)
            return emptyList()
        }

        val query = request.query.lowercase()
        val queryTerms = query.split(Regex("\\s+")).filter { it.isNotBlank() }

        if (queryTerms.isEmpty()) {
            return emptyList()
        }

        val results = mutableListOf<SimilarityResult<T>>()

        findMatchingFiles().forEach { filePath ->
            try {
                val content = fileReadTools.safeReadFile(relativePath(filePath)) ?: return@forEach
                val contentLower = content.lowercase()

                // First check if file matches at all
                val matchCount = queryTerms.count { term -> contentLower.contains(term) }
                if (matchCount == 0) {
                    return@forEach
                }

                val score = matchCount.toDouble() / queryTerms.size
                if (score < request.similarityThreshold) {
                    return@forEach
                }

                // Find match positions and create chunks around them
                val chunks = extractChunksAroundMatches(filePath, content, queryTerms, score)
                @Suppress("UNCHECKED_CAST")
                chunks.forEach { chunk ->
                    results.add(SimpleSimilaritySearchResult(match = chunk as T, score = score))
                }
            } catch (e: Exception) {
                logger.debug("Failed to read file {}: {}", filePath, e.message)
            }
        }

        return results
            .sortedByDescending { it.score }
            .take(request.topK)
            .also {
                logger.info(
                    "Text search for '{}' in {} found {} results",
                    request.query,
                    directory,
                    it.size
                )
            }
    }

    override fun <T : Retrievable> regexSearch(
        regex: Regex,
        topK: Int,
        clazz: Class<T>,
    ): List<SimilarityResult<T>> {
        if (!Chunk::class.java.isAssignableFrom(clazz)) {
            logger.warn("DirectoryTextSearch only supports Chunk type, got {}", clazz.simpleName)
            return emptyList()
        }

        val results = mutableListOf<SimilarityResult<T>>()

        findMatchingFiles().forEach { filePath ->
            try {
                val content = fileReadTools.safeReadFile(relativePath(filePath)) ?: return@forEach

                // Find all match positions
                val matchPositions = regex.findAll(content).map { it.range.first }.toList()
                if (matchPositions.isEmpty()) {
                    return@forEach
                }

                // Create chunks around match positions
                val chunks = extractChunksAroundPositions(filePath, content, matchPositions)
                @Suppress("UNCHECKED_CAST")
                chunks.forEach { chunk ->
                    results.add(SimpleSimilaritySearchResult(match = chunk as T, score = 1.0))
                }
            } catch (e: Exception) {
                logger.debug("Failed to read file {}: {}", filePath, e.message)
            }
        }

        return results
            .take(topK)
            .also {
                logger.info(
                    "Regex search for '{}' in {} found {} results",
                    regex.pattern,
                    directory,
                    it.size
                )
            }
    }

    private fun findMatchingFiles(): Sequence<Path> {
        if (!Files.exists(rootPath)) {
            logger.warn("Directory does not exist: {}", directory)
            return emptySequence()
        }

        return Files.walk(rootPath)
            .filter { it.isRegularFile() }
            .filter { path ->
                // Check if any parent directory is excluded
                var current = path.parent
                while (current != null && current.startsWith(rootPath)) {
                    if (current.fileName?.toString() in config.excludeDirectories) {
                        return@filter false
                    }
                    current = current.parent
                }
                true
            }
            .filter { path ->
                // Apply glob filter if specified
                if (globMatcher != null) {
                    val relativePath = rootPath.relativize(path)
                    globMatcher.matches(relativePath)
                } else {
                    true
                }
            }
            .iterator()
            .asSequence()
    }

    private fun relativePath(path: Path): String {
        return rootPath.relativize(path).invariantSeparatorsPathString
    }

    /**
     * Extract chunks around text matches. Searches whole file first, then creates
     * chunks centered on match positions.
     */
    private fun extractChunksAroundMatches(
        filePath: Path,
        content: String,
        queryTerms: List<String>,
        score: Double,
    ): List<Chunk> {
        // Find all match positions (case-insensitive)
        val contentLower = content.lowercase()
        val matchPositions = queryTerms.flatMap { term ->
            val termLower = term.lowercase()
            var index = 0
            val positions = mutableListOf<Int>()
            while (true) {
                val pos = contentLower.indexOf(termLower, index)
                if (pos < 0) break
                positions.add(pos)
                index = pos + 1
            }
            positions
        }.distinct().sorted()

        return extractChunksAroundPositions(filePath, content, matchPositions)
    }

    /**
     * Create chunks centered around the given positions, merging overlapping chunks.
     */
    private fun extractChunksAroundPositions(
        filePath: Path,
        content: String,
        positions: List<Int>,
    ): List<Chunk> {
        val relativePath = relativePath(filePath)
        val baseMetadata = mapOf(
            "file_path" to filePath.invariantSeparatorsPathString,
            "relative_path" to relativePath,
            "file_name" to filePath.name,
            "source" to "directory:$directory",
        )

        // Return whole file if chunking disabled or file is small
        if (config.chunkSize <= 0 || content.length <= config.chunkSize) {
            return listOf(
                Chunk(
                    id = relativePath,
                    text = content,
                    parentId = directory,
                    metadata = baseMetadata + ("chunk_index" to 0) + ("total_chunks" to 1),
                )
            )
        }

        // Calculate chunk ranges centered on match positions
        val halfChunk = config.chunkSize / 2
        val ranges = positions.map { pos ->
            val start = maxOf(0, pos - halfChunk)
            val end = minOf(content.length, pos + halfChunk)
            start to end
        }

        // Merge overlapping ranges
        val mergedRanges = mergeOverlappingRanges(ranges)

        // Create chunks from merged ranges
        return mergedRanges.mapIndexed { index, (start, end) ->
            Chunk(
                id = if (mergedRanges.size == 1) relativePath else "$relativePath#$index",
                text = content.substring(start, end),
                parentId = if (mergedRanges.size == 1) directory else relativePath,
                metadata = baseMetadata + mapOf(
                    "chunk_index" to index,
                    "chunk_start" to start,
                    "chunk_end" to end,
                    "total_chunks" to mergedRanges.size,
                ),
            )
        }
    }

    /**
     * Merge overlapping or adjacent ranges.
     */
    private fun mergeOverlappingRanges(ranges: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        if (ranges.isEmpty()) return emptyList()

        val sorted = ranges.sortedBy { it.first }
        val merged = mutableListOf<Pair<Int, Int>>()
        var current = sorted.first()

        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.first <= current.second + config.chunkOverlap) {
                // Merge: extend current range
                current = current.first to maxOf(current.second, next.second)
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)

        return merged
    }
}
