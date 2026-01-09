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
package com.embabel.agent.rag.ingestion

import com.embabel.agent.rag.model.LeafSection
import com.embabel.agent.rag.model.MaterializedDocument
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.slf4j.Logger
import java.time.Instant
import java.util.*

/**
 * Parser for HTML content that extracts sections based on heading tags (h1-h6).
 */
internal class HtmlContentParser(
    private val logger: Logger,
    private val plainTextParser: PlainTextContentParser,
) : ContentFormatParser {

    override fun parse(content: String, metadata: Metadata, uri: String): MaterializedDocument {
        // Parse HTML headings and create sections similar to markdown
        val headingPattern = Regex("<h([1-6])[^>]*>(.*?)</h\\1>", RegexOption.IGNORE_CASE)
        val headingMatches = headingPattern.findAll(content).toList()

        if (headingMatches.isEmpty()) {
            // No headings found, treat as plain text
            val cleanContent = content
                .replace(Regex("<[^>]+>"), " ") // Remove HTML tags
                .replace(Regex("\\s+"), " ") // Normalize whitespace
                .trim()
            return plainTextParser.parse(cleanContent, metadata, uri)
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
                ContentFormatParserUtils.createLeafSection(
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

        val hierarchicalSections = ContentFormatParserUtils.buildHierarchy(leafSections, rootId)

        return MaterializedDocument(
            id = rootId,
            uri = uri,
            title = documentTitle,
            ingestionTimestamp = Instant.now(),
            children = hierarchicalSections,
            metadata = ContentFormatParserUtils.extractMetadataMap(metadata)
        )
    }
}
