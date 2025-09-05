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
import java.util.*

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
        return parseContent(resource.inputStream, metadata, resource.uri.toString())
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
}
