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

import com.embabel.agent.tools.DirectoryBased
import com.embabel.common.util.loggerFor
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Adds low level pattern search methods to the [com.embabel.agent.tools.DirectoryBased] interface
 */
interface PatternSearch : DirectoryBased {

    @Tool(description = "search for a regex in the project")
    fun findPatternInProject(
        @ToolParam(description = "regex pattern") pattern: String,
        @ToolParam(description = "glob pattern for files to search in") globPattern: String,
    ): String {
        return findPatternInProject(
            pattern = Regex(pattern),
            globPattern = globPattern,
        ).joinToString("\n") { "${it.file.path}:${it.matchedLine} ${it.contextLines.joinToString(" ")}" }
    }

    /**
     * Finds files containing the specified pattern using glob patterns
     * @param pattern The regex pattern to search for
     * @param globPattern Glob pattern to match files
     * @param useParallelSearch Whether to use parallel processing for faster searching
     * @return List of matching files with their relevant content snippets
     */
    fun findPatternInProject(
        pattern: Regex,
        globPattern: String,
        useParallelSearch: Boolean = true,
    ): List<PatternMatch> {
        val root = File(root)
        if (!root.exists() || !root.isDirectory) {
            throw IllegalArgumentException("Invalid root directory: $root")
        }

        val results = mutableListOf<PatternMatch>()
        val cancelled = AtomicBoolean(false)

        // Get all files recursively matching the glob pattern
        val allFiles = root.walkTopDown()
            .filter { it.isFile && matchesGlob(it.path, globPattern) }
            .toList()

        loggerFor<PatternSearch>().info(
            "Scanning {} files found using glob '{}' for regex pattern '{}'...",
            allFiles.size,
            globPattern,
            pattern.pattern
        )

        if (useParallelSearch && allFiles.size > 100) {
            // For larger projects, process files in parallel
            val numThreads = Runtime.getRuntime().availableProcessors()
            val chunks = allFiles.chunked(allFiles.size / numThreads + 1)
            val threads = chunks.map { chunk ->
                thread {
                    val threadResults = mutableListOf<PatternMatch>()
                    for (file in chunk) {
                        if (cancelled.get()) break
                        scanFile(file, pattern)?.let { threadResults.add(it) }
                    }
                    synchronized(results) {
                        results.addAll(threadResults)
                    }
                }
            }
            threads.forEach { it.join() }
        } else {
            // For smaller projects, process sequentially
            for (file in allFiles) {
                scanFile(file, pattern)?.let { results.add(it) }
            }
        }

        return results
    }

    /**
     * Represents a matching file with context
     */
    data class PatternMatch(
        val file: File,
        val relativePath: String,
        val matchedLine: Int,
        val contextLines: List<String>,
    )

    /**
     * Scans a single file for the pattern
     */
    private fun scanFile(
        file: File,
        pattern: Regex,
    ): PatternMatch? {
        try {
            val lines = file.readLines()

            // Quick check before detailed parsing
            val fileContent = file.readText()
            if (!pattern.containsMatchIn(fileContent)) return null

            // Look for class, interface, object, or enum declarations
            for (i in lines.indices) {
                val line = lines[i]

                // Check for class/interface/object declaration
                if (pattern.containsMatchIn(line)
                ) {

                    // Gather context lines
                    val startLine = maxOf(0, i - 2)
                    val endLine = minOf(lines.size - 1, i + 5)
                    val contextLines = lines.subList(startLine, endLine + 1)

                    return PatternMatch(
                        file = file,
                        relativePath = file.path,
                        matchedLine = i + 1,
                        contextLines = contextLines
                    )
                }
            }

            return null
        } catch (e: Exception) {
            loggerFor<PatternSearch>().warn("Error scanning file ${file.path}: ${e.message}")
            return null
        }
    }

    /**
     * Checks if a file path matches a glob pattern
     * @param path The file path to check
     * @param globPattern The glob pattern to match against
     * @return true if the path matches the pattern
     */
    fun matchesGlob(
        path: String,
        globPattern: String,
    ): Boolean {
        val pathParts = path.replace("\\", "/").split("/")
        val patternParts = globPattern.replace("\\", "/").split("/")

        // Convert glob pattern to regex
        val regexPattern = patternParts.joinToString("/") { part ->
            when {
                part == "**" -> ".*"
                part.contains("*") || part.contains("?") || part.contains("{") -> {
                    // Handle extensions like *.{kt,java}
                    if (part.contains("{") && part.contains("}")) {
                        val prefix = part.substringBefore("{")
                        val options = part.substringAfter("{").substringBefore("}").split(",")
                        val suffix = part.substringAfter("}")

                        "$prefix(${options.joinToString("|")})$suffix"
                            .replace(".", "\\.")
                            .replace("*", ".*")
                            .replace("?", ".")
                    } else {
                        part.replace(".", "\\.")
                            .replace("*", ".*")
                            .replace("?", ".")
                    }
                }

                else -> part
            }
        }

        return path.replace("\\", "/").matches(Regex(regexPattern))
    }
}
