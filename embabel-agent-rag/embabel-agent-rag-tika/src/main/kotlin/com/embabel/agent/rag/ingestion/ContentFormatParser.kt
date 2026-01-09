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

import com.embabel.agent.rag.model.DefaultMaterializedContainerSection
import com.embabel.agent.rag.model.LeafSection
import com.embabel.agent.rag.model.MaterializedDocument
import com.embabel.agent.rag.model.NavigableSection
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import java.util.*

/**
 * Sealed interface for content format parsers that convert raw content into
 * hierarchical [MaterializedDocument] structures.
 *
 * Implementations handle specific content formats (Markdown, HTML, plain text)
 * and extract structured sections based on headings and content boundaries.
 */
internal sealed interface ContentFormatParser {

    /**
     * Parse the given content string into a hierarchical document structure.
     *
     * @param content The raw content string to parse
     * @param metadata Tika metadata containing content type hints and other info
     * @param uri The source URI of the content
     * @return A [MaterializedDocument] with extracted sections
     */
    fun parse(content: String, metadata: Metadata, uri: String): MaterializedDocument
}

/**
 * Shared utilities for content format parsers.
 */
internal object ContentFormatParserUtils {

    /**
     * Create a [LeafSection] with proper metadata for hierarchy tracking.
     */
    fun createLeafSection(
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

    /**
     * Build hierarchical structure from flat list of sections with parent IDs.
     * Sections with children become ContainerSections, sections without children remain LeafSections.
     * If a section has both content and children, the content is preserved as a preamble leaf section.
     */
    fun buildHierarchy(
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

    /**
     * Extract a title from content lines or metadata.
     *
     * @param lines Content split into lines
     * @param metadata Tika metadata
     * @return Extracted title or null if none found
     */
    fun extractTitle(
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

    /**
     * Convert Tika [Metadata] to a simple Map.
     */
    fun extractMetadataMap(metadata: Metadata): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()

        for (name in metadata.names()) {
            val value = metadata.get(name)
            if (value != null) {
                map[name] = value
            }
        }

        return map
    }

    /**
     * Create an empty [MaterializedDocument] when content is blank.
     */
    fun createEmptyContentRoot(
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
}
