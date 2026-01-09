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
 * Parser for Markdown content that extracts sections based on heading hierarchy.
 */
internal class MarkdownContentParser(
    private val logger: Logger,
) : ContentFormatParser {

    override fun parse(content: String, metadata: Metadata, uri: String): MaterializedDocument {
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
                            ContentFormatParserUtils.createLeafSection(
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
                ContentFormatParserUtils.createLeafSection(
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
            val title = ContentFormatParserUtils.extractTitle(lines, metadata) ?: "Document"
            leafSections.add(
                ContentFormatParserUtils.createLeafSection(
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
            ContentFormatParserUtils.extractTitle(lines, metadata)
                ?: metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY)
                ?: "Document"

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
