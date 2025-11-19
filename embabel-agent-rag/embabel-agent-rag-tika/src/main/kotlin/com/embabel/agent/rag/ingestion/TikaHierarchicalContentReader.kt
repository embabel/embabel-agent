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

import com.embabel.agent.rag.model.DefaultMaterializedContainerSection
import com.embabel.agent.rag.model.LeafSection
import com.embabel.agent.rag.model.MaterializedDocument
import com.embabel.agent.rag.model.NavigableSection
import com.embabel.agent.tools.file.FileReadTools
import org.apache.tika.detect.DefaultDetector
import org.apache.tika.detect.Detector
import org.apache.tika.exception.ZeroByteFileException
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.sax.BodyContentHandler
import org.slf4j.LoggerFactory
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.Resource
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.*

/**
 * Reads various content types using Apache Tika and extracts LeafSection objects containing the actual content.
 * Can read local files or URLs via Spring Resource loading.
 *
 * This reader can handle Markdown, HTML, PDF, Word documents, and many other formats
 * supported by Apache Tika and returns a list of LeafSection objects that can be processed for RAG.
 */
class TikaHierarchicalContentReader : HierarchicalContentReader {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val parser = AutoDetectParser()
    private val detector: Detector = DefaultDetector()

    override fun parseResource(
        resourcePath: String,
    ): MaterializedDocument {
        val resource: Resource = DefaultResourceLoader().getResource(resourcePath)
        return resource.inputStream.use { inputStream ->
            parseContent(inputStream, resourcePath)
        }
    }

    override fun parseFile(
        file: File,
        url: String?,
    ): MaterializedDocument {
        logger.debug("Parsing file: {}", file.absolutePath)

        val metadata = Metadata()
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.name)

        return file.inputStream().use { inputStream ->
            parseContent(inputStream, metadata = metadata, uri = url ?: file.toURI().toString())
        }
    }

    override fun parseContent(
        inputStream: InputStream,
        uri: String,
    ) = parseContent(
        inputStream,
        uri,
        Metadata()
    )

    fun parseContent(
        inputStream: InputStream,
        uri: String,
        metadata: Metadata,
    ): MaterializedDocument {
        try {
            // Wrap in BufferedInputStream to support mark/reset for detection
            val bufferedStream = inputStream as? BufferedInputStream ?: BufferedInputStream(inputStream)

            // Autodetect content type if not explicitly provided
            val detectedType = if (metadata.get(TikaCoreProperties.CONTENT_TYPE_HINT) != null) {
                metadata.get(TikaCoreProperties.CONTENT_TYPE_HINT)
            } else {
                val mediaType = detector.detect(bufferedStream, metadata)
                mediaType.toString()
            }

            logger.debug("Detected content type: {}", detectedType)

            // For HTML content, read raw bytes to preserve HTML structure
            if (detectedType.contains("html")) {
                val rawContent = bufferedStream.readBytes().toString(Charsets.UTF_8)
                return parseHtml(rawContent, metadata, uri)
            }

            val handler = BodyContentHandler(-1) // No limit on content size
            val parseContext = ParseContext()

            parser.parse(bufferedStream, handler, metadata, parseContext)
            val content = handler.toString()

            logger.debug("Parsed content of type: {}, length: {}", detectedType, content.length)

            // Detect markdown by content patterns if MIME type detection fails
            val hasMarkdownHeaders = content.lines().any { line ->
                line.trim().matches(Regex("^#{1,6}\\s+.+"))
            }

            return when {
                detectedType.contains("markdown") || metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY)
                    ?.endsWith(".md") == true || hasMarkdownHeaders -> {
                    parseMarkdown(content, metadata, uri)
                }

                else -> {
                    parsePlainText(content, metadata, uri)
                }
            }

        } catch (e: ZeroByteFileException) {
            // Handle empty files gracefully
            logger.debug("Empty content detected, returning empty content root")
            return createEmptyContentRoot(metadata, uri)
        } catch (e: Exception) {
            logger.error("Error parsing content", e)
            return createErrorContentRoot(e.message ?: "Unknown parsing error", metadata, uri)
        }
    }

    /**
     * Parse markdown content and build hierarchical structure
     */
    private fun parseMarkdown(
        content: String,
        metadata: Metadata,
        uri: String,
    ): MaterializedDocument {
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
                                uri,
                                metadata,
                                rootId
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
                    uri,
                    metadata,
                    rootId
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
                    uri,
                    metadata,
                    rootId
                )
            )
        }

        logger.debug("Created {} leaf sections from markdown content", leafSections.size)

        // Build the hierarchical structure
        val documentTitle =
            extractTitle(lines, metadata) ?: metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY) ?: "Document"

        val hierarchicalSections = buildHierarchy(leafSections, rootId)

        return MaterializedDocument(
            id = rootId,
            uri = uri,
            title = documentTitle,
            ingestionTimestamp = java.time.Instant.now(),
            children = hierarchicalSections,
            metadata = extractMetadataMap(metadata)
        )
    }

    /**
     * Parse HTML content - simplified approach focusing on headings
     */
    private fun parseHtml(
        content: String,
        metadata: Metadata,
        uri: String,
    ): MaterializedDocument {
        // Parse HTML headings and create sections similar to markdown
        val headingPattern = Regex("<h([1-6])[^>]*>(.*?)</h\\1>", RegexOption.IGNORE_CASE)
        val headingMatches = headingPattern.findAll(content).toList()

        if (headingMatches.isEmpty()) {
            // No headings found, treat as plain text
            val cleanContent = content
                .replace(Regex("<[^>]+>"), " ") // Remove HTML tags
                .replace(Regex("\\s+"), " ") // Normalize whitespace
                .trim()
            return parsePlainText(cleanContent, metadata, uri)
        }

        // Build sections from HTML headings
        val leafSections = mutableListOf<LeafSection>()
        val rootId = UUID.randomUUID().toString()
        val sectionStack = mutableMapOf<Int, String>() // level -> sectionId

        for (i in headingMatches.indices) {
            val match = headingMatches[i]
            val level = match.groupValues[1].toInt()
            val title = match.groupValues[2]
                .replace(Regex("<[^>]+>"), "") // Remove any HTML tags in title
                .replace(Regex("\\s+"), " ")
                .trim()

            // Extract content between this heading and the next
            val startIdx = match.range.last + 1
            val endIdx = if (i + 1 < headingMatches.size) {
                headingMatches[i + 1].range.first
            } else {
                content.length
            }

            val rawContent = if (startIdx < endIdx) {
                content.substring(startIdx, endIdx)
            } else {
                ""
            }

            // Clean HTML tags from content
            val cleanContent = rawContent
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            // Determine parent based on hierarchy
            val sectionId = UUID.randomUUID().toString()
            val parentId = when {
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

            leafSections.add(
                createLeafSection(
                    sectionId,
                    title,
                    cleanContent,
                    parentId,
                    uri,
                    metadata,
                    rootId
                )
            )
        }

        logger.debug("Created {} leaf sections from HTML content", leafSections.size)

        // Build the hierarchical structure
        val documentTitle = metadata.get(TikaCoreProperties.TITLE)
            ?: metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY)
            ?: (if (leafSections.isNotEmpty()) leafSections.first().title else "Document")

        val hierarchicalSections = buildHierarchy(leafSections, rootId)

        return MaterializedDocument(
            id = rootId,
            uri = uri,
            title = documentTitle,
            ingestionTimestamp = java.time.Instant.now(),
            children = hierarchicalSections,
            metadata = extractMetadataMap(metadata)
        )
    }

    /**
     * Parse plain text content into a content root with single section
     */
    private fun parsePlainText(
        content: String,
        metadata: Metadata,
        uri: String,
    ): MaterializedDocument {
        if (content.isBlank()) {
            return createEmptyContentRoot(metadata, uri)
        }

        val rootId = UUID.randomUUID().toString()
        val title = extractTitle(content.lines(), metadata) ?: "Document"
        val leafSection = createLeafSection(
            id = UUID.randomUUID().toString(),
            title = title,
            content = content.trim(),
            parentId = rootId,
            url = uri,
            metadata = metadata,
            rootId = rootId
        )

        return MaterializedDocument(
            id = rootId,
            uri = uri,
            title = title,
            ingestionTimestamp = java.time.Instant.now(),
            children = listOf(leafSection),
            metadata = extractMetadataMap(metadata)
        )
    }

    /**
     * Build hierarchical structure from flat list of sections with parent IDs.
     * Sections with children become ContainerSections, sections without children remain LeafSections.
     * If a section has both content and children, the content is preserved as a preamble leaf section.
     */
    private fun buildHierarchy(
        sections: List<LeafSection>,
        rootId: String,
    ): List<NavigableSection> {
        if (sections.isEmpty()) return emptyList()

        // Group sections by their parent ID
        val sectionsByParent = sections.groupBy { it.parentId }

        // Recursive function to build a section with its children
        fun buildSection(section: LeafSection): NavigableSection {
            val children = sectionsByParent[section.id] ?: emptyList()

            return if (children.isEmpty()) {
                // No children - keep as LeafSection
                section
            } else {
                // Has children - convert to ContainerSection
                // If section has content, preserve it as a preamble/introduction leaf
                val childSections = mutableListOf<NavigableSection>()

                if (section.content.isNotBlank()) {
                    // Create a preamble leaf section to preserve the content
                    val preambleId = "${section.id}_preamble"
                    val preambleMetadata = section.metadata.toMutableMap().apply {
                        // Update leaf_section_id to match the preamble's id
                        put("leaf_section_id", preambleId)
                    }
                    val preambleSection = LeafSection(
                        id = preambleId,
                        uri = section.uri,
                        title = section.title,
                        text = section.content,
                        parentId = section.id,
                        metadata = preambleMetadata
                    )
                    childSections.add(preambleSection)
                }

                // Add the actual child sections
                childSections.addAll(children.map { buildSection(it) })

                DefaultMaterializedContainerSection(
                    id = section.id,
                    uri = section.uri,
                    title = section.title,
                    children = childSections,
                    parentId = section.parentId,
                    metadata = section.metadata
                )
            }
        }

        // Build the tree starting from top-level sections (those with rootId as parent)
        val topLevelSections = sectionsByParent[rootId] ?: emptyList()
        return topLevelSections.map { buildSection(it) }
    }

    private fun createLeafSection(
        id: String,
        title: String,
        content: String,
        parentId: String?,
        url: String?,
        metadata: Metadata,
        rootId: String,
    ): LeafSection {
        val metadataMap = extractMetadataMap(metadata).toMutableMap()

        // Add required metadata for pathFromRoot computation
        metadataMap["root_document_id"] = rootId
        metadataMap["container_section_id"] = parentId ?: rootId
        metadataMap["leaf_section_id"] = id

        return LeafSection(
            id = id,
            uri = url,
            title = title,
            text = content,
            parentId = parentId,
            metadata = metadataMap
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
        uri: String,
    ): MaterializedDocument {
        return MaterializedDocument(
            id = UUID.randomUUID().toString(),
            uri = uri,
            title = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY) ?: "Empty Document",
            ingestionTimestamp = java.time.Instant.now(),
            children = emptyList(),
            metadata = extractMetadataMap(metadata)
        )
    }

    private fun createErrorContentRoot(
        errorMessage: String,
        metadata: Metadata,
        uri: String,
    ): MaterializedDocument {
        val rootId = UUID.randomUUID().toString()
        val leafId = UUID.randomUUID().toString()
        val errorSection = LeafSection(
            id = leafId,
            uri = uri,
            title = "Parse Error",
            text = "Error parsing content: $errorMessage",
            parentId = rootId,
            metadata = extractMetadataMap(metadata) + mapOf(
                "error" to errorMessage,
                "root_document_id" to rootId,
                "container_section_id" to rootId,
                "leaf_section_id" to leafId
            )
        )

        return MaterializedDocument(
            id = rootId,
            uri = uri,
            title = "Parse Error",
            ingestionTimestamp = java.time.Instant.now(),
            children = listOf(errorSection),
            metadata = extractMetadataMap(metadata) + mapOf("error" to errorMessage)
        )
    }


    override fun parseFromDirectory(
        fileTools: FileReadTools,
        config: DirectoryParsingConfig,
    ): DirectoryParsingResult {
        val startTime = Instant.now()

        logger.info("Starting directory parsing with config: {}", config)

        return try {
            val files = discoverFiles(fileTools, config)
            logger.info("Discovered {} files for parsing", files.size)

            processFiles(fileTools, files, config, startTime)

        } catch (e: Exception) {
            logger.error("Failed to parse directory '{}': {}", config.relativePath, e.message, e)
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
    ): MaterializedDocument? {
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
                filePath, result.leaves().count()
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
        config: DirectoryParsingConfig,
    ): List<String> {
        val files = mutableListOf<String>()
        val startPath = config.relativePath.ifEmpty { "" }

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
        val contentRoots = mutableListOf<MaterializedDocument>()
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
                        filePath, index + 1, files.size, result.leaves().count()
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
        logger.info("Total sections extracted: {}", contentRoots.sumOf { it.leaves().count() })

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
