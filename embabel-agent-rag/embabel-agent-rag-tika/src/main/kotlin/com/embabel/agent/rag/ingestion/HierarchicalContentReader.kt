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

import com.embabel.agent.rag.LeafSection
import com.embabel.agent.rag.MaterializedContentRoot
import com.embabel.agent.tools.file.FileReadTools
import org.apache.tika.exception.ZeroByteFileException
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.sax.BodyContentHandler
import org.slf4j.LoggerFactory
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.Resource
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.*

data class DirectoryParsingConfig(
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
    val maxFileSize: Long = 10 * 1024 * 1024, // 10MB default
    val followSymlinks: Boolean = false,
    val maxDepth: Int = Int.MAX_VALUE,
)

/**
 * Result of directory parsing operation
 */
data class DirectoryParsingResult(
    val totalFilesFound: Int,
    val filesProcessed: Int,
    val filesSkipped: Int,
    val filesErrored: Int,
    val contentRoots: List<MaterializedContentRoot>,
    val processingTime: Duration,
    val errors: List<String>,
) {
    val success: Boolean
        get() = filesErrored == 0 && errors.isEmpty()

    val totalSectionsExtracted: Int
        get() = contentRoots.sumOf { it.leaves().size }
}

/**
 * Reads various content types using Apache Tika and extracts LeafSection objects containing the actual content.
 *
 * This reader can handle markdown, HTML, PDF, Word documents, and many other formats
 * supported by Apache Tika and returns a list of LeafSection objects that can be processed for RAG.
 */
class HierarchicalContentReader {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val parser = AutoDetectParser()

    fun parseResource(
        resourcePath: String,
        metadata: Metadata = Metadata(),
    ): MaterializedContentRoot {
        val resource: Resource = DefaultResourceLoader().getResource(resourcePath)
        return resource.inputStream.use { inputStream ->
            parseContent(inputStream, metadata, resource.uri.toString())
        }
    }

    /**
     * Parse content from a file and return materialized content root
     */
    fun parseFile(
        file: File,
        url: String? = null,
    ): MaterializedContentRoot {
        logger.debug("Parsing file: {}", file.absolutePath)

        val metadata = org.apache.tika.metadata.Metadata()
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.name)

        return file.inputStream().use { inputStream ->
            parseContent(inputStream, metadata, url ?: file.toURI().toString())
        }
    }

    /**
     * Parse content from an InputStream with optional metadata
     */
    fun parseContent(
        inputStream: InputStream,
        metadata: Metadata = Metadata(),
        url: String? = null,
    ): MaterializedContentRoot {
        val handler = BodyContentHandler(-1) // No limit on content size
        val parseContext = ParseContext()

        try {
            parser.parse(inputStream, handler, metadata, parseContext)
            val content = handler.toString()
            val mimeType = metadata.get(TikaCoreProperties.CONTENT_TYPE_HINT) ?: "text/plain"

            logger.debug("Parsed content of type: {}, length: {}", mimeType, content.length)

            // Detect markdown by content patterns if MIME type detection fails
            val hasMarkdownHeaders = content.lines().any { line ->
                line.trim().matches(Regex("^#{1,6}\\s+.+"))
            }

            return when {
                mimeType.contains("markdown") || metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY)
                    ?.endsWith(".md") == true || hasMarkdownHeaders -> {
                    parseMarkdown(content, metadata, url)
                }

                mimeType.contains("html") -> {
                    parseHtml(content, metadata, url)
                }

                else -> {
                    parsePlainText(content, metadata, url)
                }
            }

        } catch (e: ZeroByteFileException) {
            // Handle empty files gracefully
            logger.debug("Empty content detected, returning empty content root")
            return createEmptyContentRoot(metadata, url)
        } catch (e: Exception) {
            logger.error("Error parsing content", e)
            return createErrorContentRoot(e.message ?: "Unknown parsing error", metadata, url)
        }
    }

    /**
     * Parse markdown content and build hierarchical structure
     */
    private fun parseMarkdown(
        content: String,
        metadata: Metadata,
        url: String?,
    ): MaterializedContentRoot {
        val lines = content.lines()
        val leafSections = mutableListOf<LeafSection>()
        val currentSection = StringBuilder()
        var currentTitle = ""
        var sectionId = ""
        val rootId = UUID.randomUUID().toString()
        var parentId: String? = rootId
        val sectionStack = mutableMapOf<Int, String>() // level -> sectionId

        for (line in lines) {
            when {
                line.startsWith("#") -> {
                    // Save previous section if it exists
                    if (currentTitle.isNotBlank()) {
                        leafSections.add(
                            createLeafSection(
                                sectionId,
                                currentTitle,
                                currentSection.toString().trim(),
                                parentId,
                                url,
                                metadata
                            )
                        )
                    }

                    // Parse new heading
                    val level = line.takeWhile { it == '#' }.length
                    currentTitle = line.substring(level).trim()
                    sectionId = UUID.randomUUID().toString()
                    currentSection.clear()

                    // Determine parent based on hierarchy
                    parentId = when {
                        level == 1 -> rootId
                        level > 1 -> {
                            // Find the most recent parent at level - 1
                            (level - 1 downTo 1).firstNotNullOfOrNull { sectionStack[it] } ?: rootId
                        }

                        else -> rootId
                    }

                    sectionStack[level] = sectionId
                    // Clear deeper levels
                    sectionStack.keys.filter { it > level }.forEach { sectionStack.remove(it) }
                }

                else -> {
                    if (line.isNotBlank() || currentSection.isNotEmpty()) {
                        currentSection.appendLine(line)
                    }
                }
            }
        }

        // Add final section if exists
        if (currentTitle.isNotBlank()) {
            leafSections.add(
                createLeafSection(
                    sectionId,
                    currentTitle,
                    currentSection.toString().trim(),
                    parentId,
                    url,
                    metadata
                )
            )
        }

        // If no sections were found, create a single section with the whole content
        if (leafSections.isEmpty() && content.isNotBlank()) {
            val title = extractTitle(lines, metadata) ?: "Document"
            leafSections.add(
                createLeafSection(
                    UUID.randomUUID().toString(),
                    title,
                    content.trim(),
                    rootId,
                    url,
                    metadata
                )
            )
        }

        logger.debug("Created {} leaf sections from markdown content", leafSections.size)

        // Build the hierarchical structure
        val documentTitle =
            extractTitle(lines, metadata) ?: metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY) ?: "Document"

        return MaterializedContentRoot(
            id = rootId,
            url = url,
            title = documentTitle,
            children = leafSections,
            metadata = extractMetadataMap(metadata)
        )
    }

    /**
     * Parse HTML content - simplified approach focusing on headings
     */
    private fun parseHtml(
        content: String,
        metadata: Metadata,
        url: String?,
    ): MaterializedContentRoot {
        // For HTML, we'll use a simplified approach similar to markdown
        // In a full implementation, you might want to use JSoup or similar
        val cleanContent = content
            .replace(Regex("<[^>]+>"), " ") // Remove HTML tags
            .replace(Regex("\\s+"), " ") // Normalize whitespace
            .trim()

        return parsePlainText(cleanContent, metadata, url)
    }

    /**
     * Parse plain text content into a content root with single section
     */
    private fun parsePlainText(
        content: String,
        metadata: Metadata,
        url: String?,
    ): MaterializedContentRoot {
        if (content.isBlank()) {
            return createEmptyContentRoot(metadata, url)
        }

        val rootId = UUID.randomUUID().toString()
        val title = extractTitle(content.lines(), metadata) ?: "Document"
        val leafSection = createLeafSection(
            id = UUID.randomUUID().toString(),
            title = title,
            content = content.trim(),
            parentId = rootId,
            url = url,
            metadata = metadata
        )

        return MaterializedContentRoot(
            id = rootId,
            url = url,
            title = title,
            children = listOf(leafSection),
            metadata = extractMetadataMap(metadata)
        )
    }

    private fun createLeafSection(
        id: String,
        title: String,
        content: String,
        parentId: String?,
        url: String?,
        metadata: Metadata,
    ): LeafSection {
        return LeafSection(
            id = id,
            url = url,
            title = title,
            content = content,
            parentId = parentId,
            metadata = extractMetadataMap(metadata)
        )
    }

    private fun extractTitle(
        lines: List<String>,
        metadata: Metadata,
    ): String? {
        // Try to get title from metadata first
        metadata.get(TikaCoreProperties.TITLE)?.let { return it }

        // Look for first heading in markdown
        for (line in lines) {
            if (line.startsWith("#")) {
                return line.substring(line.takeWhile { it == '#' }.length).trim()
            }
            if (line.isNotBlank()) {
                // Use first non-blank line as title if no heading found
                return line.take(50).trim()
            }
        }

        return null
    }

    private fun extractMetadataMap(metadata: Metadata): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()

        for (name in metadata.names()) {
            val value = metadata.get(name)
            if (value != null) {
                map[name] = value
            }
        }

        return map
    }

    private fun createEmptyContentRoot(
        metadata: Metadata,
        url: String?,
    ): MaterializedContentRoot {
        return MaterializedContentRoot(
            id = UUID.randomUUID().toString(),
            url = url,
            title = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY) ?: "Empty Document",
            children = emptyList(),
            metadata = extractMetadataMap(metadata)
        )
    }

    private fun createErrorContentRoot(
        errorMessage: String,
        metadata: Metadata,
        url: String?,
    ): MaterializedContentRoot {
        val rootId = UUID.randomUUID().toString()
        val errorSection = LeafSection(
            id = UUID.randomUUID().toString(),
            url = url,
            title = "Parse Error",
            content = "Error parsing content: $errorMessage",
            parentId = rootId,
            metadata = extractMetadataMap(metadata) + mapOf("error" to errorMessage)
        )

        return MaterializedContentRoot(
            id = rootId,
            url = url,
            title = "Parse Error",
            children = listOf(errorSection),
            metadata = extractMetadataMap(metadata) + mapOf("error" to errorMessage)
        )
    }

    /**
     * Parse all files from a directory structure using FileTools for safe access.
     *
     * @param fileTools The FileTools instance to use for file system operations
     * @param directoryPath The relative path to the directory to parse (relative to FileTools root)
     * @param config Configuration for the parsing process
     * @return Result of the parsing operation
     */
    @JvmOverloads
    fun parseFromDirectory(
        fileTools: FileReadTools,
        directoryPath: String = "",
        config: DirectoryParsingConfig = DirectoryParsingConfig(),
    ): DirectoryParsingResult {
        val startTime = Instant.now()

        logger.info("Starting directory parsing from '{}' with config: {}", directoryPath, config)

        return try {
            val files = discoverFiles(fileTools, directoryPath, config)
            logger.info("Discovered {} files for parsing", files.size)

            processFiles(fileTools, files, config, startTime)

        } catch (e: Exception) {
            logger.error("Failed to parse directory '{}': {}", directoryPath, e.message, e)
            DirectoryParsingResult(
                totalFilesFound = 0,
                filesProcessed = 0,
                filesSkipped = 0,
                filesErrored = 1,
                contentRoots = emptyList(),
                processingTime = Duration.between(startTime, Instant.now()),
                errors = listOf("Directory parsing failed: ${e.message}")
            )
        }
    }

    /**
     * Parse a single file using the configured reader.
     *
     * @param fileTools The FileTools instance to use for file access
     * @param filePath The relative path to the file to parse
     * @return Result of the parsing operation, or null if the file couldn't be processed
     */
    fun parseFile(
        fileTools: FileReadTools,
        filePath: String,
    ): MaterializedContentRoot? {
        return try {
            logger.debug("Parsing single file: {}", filePath)

            // Validate file exists and is readable through FileTools
            val content = fileTools.safeReadFile(filePath)
            if (content == null) {
                logger.warn("Could not read file: {}", filePath)
                return null
            }

            // Use file URI for local files - convert to proper URI format
            val fileUri = fileTools.resolvePath(filePath).toUri().toString()
            val result = parseResource(fileUri)

            logger.info(
                "Successfully parsed file '{}' - {} sections extracted",
                filePath, result.leaves().size
            )

            result

        } catch (e: Exception) {
            logger.error("Failed to parse file '{}': {}", filePath, e.message, e)
            null
        }
    }

    /**
     * Discover all files in the directory structure that match the parsing criteria.
     */
    private fun discoverFiles(
        fileTools: FileReadTools,
        directoryPath: String,
        config: DirectoryParsingConfig,
    ): List<String> {
        val files = mutableListOf<String>()
        val startPath = if (directoryPath.isEmpty()) "" else directoryPath

        logger.debug("Discovering files in directory: {}", startPath)

        try {
            discoverFilesRecursive(fileTools, startPath, files, config, 0)
        } catch (e: Exception) {
            logger.error("Error discovering files in '{}': {}", startPath, e.message, e)
        }

        logger.debug("Discovered {} files in directory '{}'", files.size, startPath)
        return files
    }

    /**
     * Recursively discover files in a directory structure.
     */
    private fun discoverFilesRecursive(
        fileTools: FileReadTools,
        currentPath: String,
        files: MutableList<String>,
        config: DirectoryParsingConfig,
        depth: Int,
    ) {
        if (depth > config.maxDepth) {
            logger.debug("Reached max depth {} at path '{}'", config.maxDepth, currentPath)
            return
        }

        try {
            val entries = fileTools.listFiles(currentPath)

            for (entry in entries) {
                val isDirectory = entry.startsWith("d:")
                val name = entry.substring(2) // Remove "d:" or "f:" prefix
                val fullPath = if (currentPath.isEmpty()) name else "$currentPath/$name"

                if (isDirectory) {
                    // Check if directory should be excluded
                    if (name in config.excludedDirectories) {
                        logger.debug("Skipping excluded directory: {}", fullPath)
                        continue
                    }

                    // Recurse into subdirectory
                    discoverFilesRecursive(fileTools, fullPath, files, config, depth + 1)
                } else {
                    // Check file extension
                    val extension = name.substringAfterLast('.', "").lowercase()
                    if (extension in config.includedExtensions) {
                        // Check file size
                        val resolvedPath = fileTools.resolvePath(fullPath)
                        if (Files.exists(resolvedPath)) {
                            val size = Files.size(resolvedPath)
                            if (size <= config.maxFileSize) {
                                files.add(fullPath)
                                logger.trace("Added file for parsing: {} (size: {} bytes)", fullPath, size)
                            } else {
                                logger.debug(
                                    "Skipping large file: {} (size: {} bytes, limit: {} bytes)",
                                    fullPath, size, config.maxFileSize
                                )
                            }
                        }
                    } else {
                        logger.trace("Skipping file with excluded extension: {} (extension: {})", fullPath, extension)
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Could not list files in directory '{}': {}", currentPath, e.message)
        }
    }

    /**
     * Process the discovered files for parsing.
     */
    private fun processFiles(
        fileTools: FileReadTools,
        files: List<String>,
        config: DirectoryParsingConfig,
        startTime: Instant,
    ): DirectoryParsingResult {
        var filesProcessed = 0
        var filesSkipped = 0
        var filesErrored = 0
        val contentRoots = mutableListOf<MaterializedContentRoot>()
        val errors = mutableListOf<String>()

        logger.info("Processing {} files for parsing", files.size)

        for ((index, filePath) in files.withIndex()) {
            if ((index + 1) % 100 == 0) {
                logger.info("Progress: {}/{} files processed", index + 1, files.size)
            }

            try {
                val result = parseFile(fileTools, filePath)
                if (result != null) {
                    contentRoots.add(result)
                    filesProcessed++
                    logger.debug(
                        "Successfully processed file {} ({}/{}): {} sections",
                        filePath, index + 1, files.size, result.leaves().size
                    )
                } else {
                    filesSkipped++
                    logger.debug("Skipped file {} ({}/{})", filePath, index + 1, files.size)
                }
            } catch (e: Exception) {
                filesErrored++
                val error = "Error processing file '$filePath': ${e.message}"
                errors.add(error)
                logger.error(error, e)
            }
        }

        val processingTime = Duration.between(startTime, Instant.now())

        logger.info("Directory parsing completed in {} ms", processingTime.toMillis())
        logger.info("Files processed: {}, skipped: {}, errors: {}", filesProcessed, filesSkipped, filesErrored)
        logger.info("Total sections extracted: {}", contentRoots.sumOf { it.leaves().size })

        return DirectoryParsingResult(
            totalFilesFound = files.size,
            filesProcessed = filesProcessed,
            filesSkipped = filesSkipped,
            filesErrored = filesErrored,
            contentRoots = contentRoots,
            processingTime = processingTime,
            errors = errors
        )
    }
}
