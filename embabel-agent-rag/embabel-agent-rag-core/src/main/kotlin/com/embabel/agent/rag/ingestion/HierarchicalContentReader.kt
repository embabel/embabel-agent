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
package com.embabel.agent.rag.ingestion

import com.embabel.agent.rag.model.NavigableDocument
import com.embabel.agent.tools.file.FileReadTools
import java.io.File
import java.io.InputStream
import java.time.Duration

data class DirectoryParsingConfig @JvmOverloads constructor(
    val includedExtensions: Set<String> = setOf(
        "txt", "md", "rst", "adoc", "asciidoc",
        "html", "htm", "xml", "json", "yaml", "yml",
        "java", "kt", "scala", "py", "js", "ts",
        "go", "rs", "c", "cpp", "h", "hpp",
        "pdf", "docx", "doc", "odt", "rtf"
    ),
    val excludedDirectories: Set<String> = setOf(
        ".git", ".svn", ".hg",
        "node_modules", ".npm",
        "target", "build", "dist", "out",
        ".gradle", ".m2",
        "__pycache__", ".pytest_cache",
        "venv", "env", ".venv",
        ".idea", ".vscode", ".vs",
        "bin", "obj",
        ".next", ".nuxt"
    ),
    val relativePath: String = "",
    val maxFileSize: Long = 10 * 1024 * 1024, // 10MB default
    val followSymlinks: Boolean = false,
    val maxDepth: Int = Int.MAX_VALUE,
) {

    fun withRelativePath(newRelativePath: String): DirectoryParsingConfig =
        this.copy(relativePath = newRelativePath)

    fun withMaxFileSize(newMaxFileSize: Long): DirectoryParsingConfig =
        this.copy(maxFileSize = newMaxFileSize)

    fun withFollowSymlinks(newFollowSymlinks: Boolean): DirectoryParsingConfig =
        this.copy(followSymlinks = newFollowSymlinks)

    fun withMaxDepth(newMaxDepth: Int): DirectoryParsingConfig =
        this.copy(maxDepth = newMaxDepth)
}

/**
 * Result of directory parsing operation
 */
data class DirectoryParsingResult(
    val totalFilesFound: Int,
    val filesProcessed: Int,
    val filesSkipped: Int,
    val filesErrored: Int,
    val contentRoots: List<NavigableDocument>,
    val processingTime: Duration,
    val errors: List<String>,
) {
    val success: Boolean
        get() = filesErrored == 0 && errors.isEmpty()

    val totalSectionsExtracted: Int
        get() = contentRoots.sumOf { it.leaves().count() }
}

/**
 * Read resources and parse them into hierarchical content structures
 */
interface HierarchicalContentReader {

    fun parseUrl(
        resourcePath: String,
    ): NavigableDocument = parseResource(resourcePath)

    /**
     * Parse content from a Spring Resource and return materialized content root
     */
    fun parseResource(
        resourcePath: String,
    ): NavigableDocument

    /**
     * Parse content from a file and return materialized content root
     */
    fun parseFile(
        file: File,
        url: String? = null,
    ): NavigableDocument

    /**
     * Parse content from an InputStream with optional metadata
     */
    fun parseContent(
        inputStream: InputStream,
        uri: String,
    ): NavigableDocument

    /**
     * Parse all files from a directory structure using FileTools for safe access.
     *
     * @param fileTools The FileTools instance to use for file system operations
     * @param config Configuration for the parsing process
     * @return Result of the parsing operation
     */
    fun parseFromDirectory(
        fileTools: FileReadTools,
        config: DirectoryParsingConfig = DirectoryParsingConfig(),
    ): DirectoryParsingResult
}
