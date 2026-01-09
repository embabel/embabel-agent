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

import com.embabel.agent.rag.ingestion.ContentChunker.Companion.CHUNK_INDEX
import com.embabel.agent.rag.ingestion.ContentChunker.Companion.CONTAINER_SECTION_ID
import com.embabel.agent.rag.ingestion.ContentChunker.Companion.CONTAINER_SECTION_TITLE
import com.embabel.agent.rag.ingestion.ContentChunker.Companion.CONTAINER_SECTION_URL
import com.embabel.agent.rag.ingestion.ContentChunker.Companion.LEAF_SECTION_ID
import com.embabel.agent.rag.ingestion.ContentChunker.Companion.LEAF_SECTION_TITLE
import com.embabel.agent.rag.ingestion.ContentChunker.Companion.LEAF_SECTION_URL
import com.embabel.agent.rag.ingestion.ContentChunker.Companion.ROOT_DOCUMENT_ID
import com.embabel.agent.rag.ingestion.ContentChunker.Companion.SEQUENCE_NUMBER
import com.embabel.agent.rag.ingestion.ContentChunker.Companion.TOTAL_CHUNKS
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.ContentRoot
import com.embabel.agent.rag.model.LeafSection
import com.embabel.agent.rag.model.NavigableContainerSection
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Simple implementation of ContentChunker that operates in memory.
 * Will whole entire document in memory.
 */
class InMemoryContentChunker(
    val config: ContentChunker.Config = ContentChunker.DefaultConfig(),
) : ContentChunker {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun chunk(section: NavigableContainerSection): List<Chunk> {
        val leaves = section.leaves().toList()
        val totalContentLength = leaves.sumOf { it.content.length + it.title.length + 1 } // +1 for newline after title

        // Determine root document ID: if section is a ContentRoot, use its ID, otherwise try to get from metadata
        val rootId = if (section is ContentRoot) {
            section.id
        } else {
            section.metadata[ROOT_DOCUMENT_ID] as? String ?: section.id
        }

        // Strategy 1: If total content fits in a single chunk, combine everything
        if (totalContentLength <= config.maxChunkSize) {
            logger.debug(
                "Creating single chunk for container section '{}' with {} leaves (total length: {} <= max: {})",
                section.title, leaves.size, totalContentLength, config.maxChunkSize
            )
            return listOf(createSingleChunkFromContainer(section, leaves, rootId))
        }

        // Strategy 2: Try to group leaves intelligently before splitting
        logger.debug(
            "Total content ({} chars) exceeds maxChunkSize ({}), attempting intelligent grouping",
            totalContentLength, config.maxChunkSize
        )
        return chunkLeavesIntelligently(section, leaves, rootId)
    }

    /**
     * Split multiple MaterializedContainerSections into Chunks
     */
    fun splitSections(sections: List<NavigableContainerSection>): List<Chunk> {
        return sections.flatMap { chunk(it) }
    }

    private fun createSingleChunkFromContainer(
        section: NavigableContainerSection,
        leaves: List<LeafSection>,
        rootId: String,
    ): Chunk {
        val combinedContent = leaves.joinToString("\n\n") { leaf ->
            if (leaf.title.isNotBlank()) "${leaf.title}\n${leaf.content}" else leaf.content
        }.trim()

        val contentWithSectionTitle = prependSectionTitle(combinedContent, section.title)

        val combinedMetadata = mutableMapOf<String, Any?>()
        combinedMetadata.putAll(section.metadata)
        combinedMetadata[ROOT_DOCUMENT_ID] = rootId
        combinedMetadata[CONTAINER_SECTION_ID] = section.id
        combinedMetadata[CONTAINER_SECTION_TITLE] = section.title
        combinedMetadata[CONTAINER_SECTION_URL] = section.uri
        combinedMetadata[CHUNK_INDEX] = 0
        combinedMetadata[TOTAL_CHUNKS] = 1
        combinedMetadata[SEQUENCE_NUMBER] = 0

        return Chunk.Companion(
            id = UUID.randomUUID().toString(),
            text = contentWithSectionTitle,
            metadata = combinedMetadata,
            parentId = section.id
        )
    }

    private fun chunkLeavesIntelligently(
        containerSection: NavigableContainerSection,
        leaves: List<LeafSection>,
        rootId: String,
    ): List<Chunk> {
        val allChunks = mutableListOf<Chunk>()
        val leafGroups = groupLeavesForOptimalChunking(leaves)
        var sequenceNumber = 0

        logger.debug("Grouped {} leaves into {} groups for chunking", leaves.size, leafGroups.size)

        for (group in leafGroups) {
            when {
                group.size == 1 -> {
                    // Single leaf group
                    val leaf = group.first()
                    val leafContentSize = leaf.content.length + leaf.title.length + 1

                    if (leafContentSize <= config.maxChunkSize) {
                        // Small enough for single chunk
                        allChunks.add(createSingleLeafChunk(containerSection, leaf, rootId, sequenceNumber++))
                    } else {
                        // Too large, split it
                        val chunks = splitLeafIntoMultipleChunks(containerSection, leaf, rootId, sequenceNumber)
                        sequenceNumber += chunks.size
                        allChunks.addAll(chunks)
                    }
                }

                else -> {
                    // Multi-leaf group - create combined chunk
                    allChunks.add(createCombinedLeafChunk(containerSection, group, rootId, sequenceNumber++))
                }
            }
        }

        return allChunks
    }

    private fun groupLeavesForOptimalChunking(leaves: List<LeafSection>): List<List<LeafSection>> {
        val groups = mutableListOf<List<LeafSection>>()
        val currentGroup = mutableListOf<LeafSection>()
        var currentGroupSize = 0

        for (leaf in leaves) {
            val leafSize = leaf.content.length + leaf.title.length + 1 // +1 for newline

            // If adding this leaf would exceed maxChunkSize, finalize current group
            if (currentGroup.isNotEmpty() && currentGroupSize + leafSize + 2 > config.maxChunkSize) { // +2 for separator
                groups.add(currentGroup.toList())
                currentGroup.clear()
                currentGroupSize = 0
            }

            // If single leaf is too large, it goes in its own group
            if (leafSize > config.maxChunkSize) {
                if (currentGroup.isNotEmpty()) {
                    groups.add(currentGroup.toList())
                    currentGroup.clear()
                    currentGroupSize = 0
                }
                groups.add(listOf(leaf))
            } else {
                // Add leaf to current group
                currentGroup.add(leaf)
                currentGroupSize += leafSize + 2 // +2 for separator between leaves
            }
        }

        // Add final group if it has content
        if (currentGroup.isNotEmpty()) {
            groups.add(currentGroup.toList())
        }

        return groups
    }

    private fun createCombinedLeafChunk(
        containerSection: NavigableContainerSection,
        leaves: List<LeafSection>,
        rootId: String,
        sequenceNumber: Int,
    ): Chunk {
        val combinedContent = leaves.joinToString("\n\n") { leaf ->
            if (leaf.title.isNotBlank()) "${leaf.title}\n${leaf.content}" else leaf.content
        }.trim()

        val contentWithSectionTitle = prependSectionTitle(combinedContent, containerSection.title)

        val combinedMetadata = mutableMapOf<String, Any?>()
        combinedMetadata.putAll(containerSection.metadata)
        combinedMetadata[ROOT_DOCUMENT_ID] = rootId
        combinedMetadata[CONTAINER_SECTION_ID] = containerSection.id
        combinedMetadata[CONTAINER_SECTION_TITLE] = containerSection.title
        combinedMetadata[CONTAINER_SECTION_URL] = containerSection.uri
        combinedMetadata[CHUNK_INDEX] = 0
        combinedMetadata[TOTAL_CHUNKS] = 1
        combinedMetadata[SEQUENCE_NUMBER] = sequenceNumber

        return Chunk.Companion(
            id = UUID.randomUUID().toString(),
            text = contentWithSectionTitle,
            metadata = combinedMetadata,
            parentId = containerSection.id
        )
    }

    private fun createSingleLeafChunk(
        containerSection: NavigableContainerSection,
        leaf: LeafSection,
        rootId: String,
        sequenceNumber: Int,
    ): Chunk {
        val content = if (leaf.title.isNotBlank()) "${leaf.title}\n${leaf.content}" else leaf.content
        val contentWithSectionTitle = prependSectionTitle(content.trim(), containerSection.title)

        return Chunk.Companion(
            id = UUID.randomUUID().toString(),
            text = contentWithSectionTitle,
            metadata = leaf.metadata + mapOf(
                ROOT_DOCUMENT_ID to rootId,
                CONTAINER_SECTION_ID to containerSection.id,
                CONTAINER_SECTION_TITLE to containerSection.title,
                LEAF_SECTION_ID to leaf.id,
                LEAF_SECTION_TITLE to leaf.title,
                LEAF_SECTION_URL to leaf.uri,
                CHUNK_INDEX to 0,
                TOTAL_CHUNKS to 1,
                SEQUENCE_NUMBER to sequenceNumber
            ),
            parentId = leaf.id
        )
    }

    private fun splitLeafIntoMultipleChunks(
        containerSection: NavigableContainerSection,
        leaf: LeafSection,
        rootId: String,
        startingSequenceNumber: Int,
    ): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        val fullContent = if (leaf.title.isNotBlank()) "${leaf.title}\n${leaf.content}" else leaf.content
        // Prepend section title before splitting, so it's only in the first chunk and accounted for in size
        val contentWithSectionTitle = prependSectionTitle(fullContent.trim(), containerSection.title)
        val textChunks = splitText(contentWithSectionTitle).filter { it.trim().isNotEmpty() }

        logger.debug("Split leaf section '{}' into {} text chunks", leaf.title, textChunks.size)

        textChunks.forEachIndexed { index, textChunk ->
            val chunk = Chunk.Companion(
                id = UUID.randomUUID().toString(),
                text = textChunk.trim(),
                metadata = leaf.metadata + mapOf(
                    ROOT_DOCUMENT_ID to rootId,
                    CONTAINER_SECTION_ID to containerSection.id,
                    CONTAINER_SECTION_TITLE to containerSection.title,
                    LEAF_SECTION_ID to leaf.id,
                    LEAF_SECTION_TITLE to leaf.title,
                    LEAF_SECTION_URL to leaf.uri,
                    CHUNK_INDEX to index,
                    TOTAL_CHUNKS to textChunks.size,
                    SEQUENCE_NUMBER to (startingSequenceNumber + index)
                ),
                parentId = leaf.id
            )
            chunks.add(chunk)
        }

        return chunks
    }

    private fun splitText(text: String): List<String> {
        // First, try to split by paragraphs
        val paragraphs = text.split("\n\n").filter { it.trim().isNotEmpty() }

        val chunks = mutableListOf<String>()
        var currentChunk = StringBuilder()

        for (paragraph in paragraphs) {
            // If adding this paragraph would exceed the limit, finalize current chunk
            if (currentChunk.isNotEmpty() &&
                currentChunk.length + paragraph.length + 2 > config.maxChunkSize
            ) {

                chunks.add(currentChunk.toString().trim())

                // Start new chunk with overlap from previous chunk if possible
                currentChunk = StringBuilder()
                if (chunks.isNotEmpty()) {
                    val overlap = getOverlapText(chunks.last())
                    if (overlap.isNotEmpty() && overlap.length + paragraph.length + 2 <= config.maxChunkSize) {
                        currentChunk.append(overlap).append("\n\n")
                    }
                }
            }

            // If single paragraph is too long, split it by sentences
            if (paragraph.length > config.maxChunkSize) {
                val sentenceChunks = splitBySentences(paragraph)
                for (sentenceChunk in sentenceChunks) {
                    if (currentChunk.isNotEmpty() &&
                        currentChunk.length + sentenceChunk.length + 2 > config.maxChunkSize
                    ) {

                        chunks.add(currentChunk.toString().trim())
                        currentChunk = StringBuilder()

                        // Add overlap
                        if (chunks.isNotEmpty()) {
                            val overlap = getOverlapText(chunks.last())
                            if (overlap.isNotEmpty() && overlap.length + sentenceChunk.length + 2 <= config.maxChunkSize) {
                                currentChunk.append(overlap).append("\n\n")
                            }
                        }
                    }

                    if (currentChunk.isNotEmpty()) {
                        currentChunk.append("\n\n")
                    }
                    currentChunk.append(sentenceChunk)
                }
            } else {
                // Add paragraph as is
                if (currentChunk.isNotEmpty()) {
                    currentChunk.append("\n\n")
                }
                currentChunk.append(paragraph)
            }
        }

        // Add final chunk if it has content
        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString().trim())
        }

        // Safety check: ensure no chunk exceeds max size and filter out empty chunks
        val finalChunks = chunks.flatMap { chunk ->
            if (chunk.length <= config.maxChunkSize) {
                listOf(chunk)
            } else {
                // Emergency fallback: split oversized chunk by character count
                chunk.chunked(config.maxChunkSize).filter { it.trim().isNotEmpty() }
            }
        }.filter { it.trim().isNotEmpty() }

        return finalChunks.ifEmpty {
            if (text.trim().isNotEmpty()) listOf(text.trim()) else emptyList()
        }
    }

    private fun splitBySentences(text: String): List<String> {
        // Split by sentence endings, but be careful with abbreviations
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
            .filter { it.trim().isNotEmpty() }

        val chunks = mutableListOf<String>()
        var currentChunk = StringBuilder()

        for (sentence in sentences) {
            if (currentChunk.isNotEmpty() &&
                currentChunk.length + sentence.length + 1 > config.maxChunkSize
            ) {

                chunks.add(currentChunk.toString().trim())
                currentChunk = StringBuilder()

                // Add overlap from previous chunk
                if (chunks.isNotEmpty()) {
                    val overlap = getOverlapText(chunks.last())
                    if (overlap.isNotEmpty() && overlap.length + sentence.length + 1 <= config.maxChunkSize) {
                        currentChunk.append(overlap).append(" ")
                    }
                }
            }

            if (currentChunk.isNotEmpty()) {
                currentChunk.append(" ")
            }
            currentChunk.append(sentence)
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString().trim())
        }

        // Safety check: ensure no chunk exceeds max size and filter out empty chunks
        val finalChunks = chunks.flatMap { chunk ->
            if (chunk.length <= config.maxChunkSize) {
                listOf(chunk)
            } else {
                // Emergency fallback: split oversized chunk by character count
                chunk.chunked(config.maxChunkSize).filter { it.trim().isNotEmpty() }
            }
        }.filter { it.trim().isNotEmpty() }

        return finalChunks.ifEmpty {
            if (text.trim().isNotEmpty()) listOf(text.trim()) else emptyList()
        }
    }

    private fun getOverlapText(previousChunk: String): String {
        if (previousChunk.length <= config.overlapSize) {
            return ""
        }

        // Try to get overlap at a sentence boundary
        val overlap = previousChunk.takeLast(config.overlapSize)
        val sentenceStart = overlap.indexOf(". ") + 2

        return if (sentenceStart > 1 && sentenceStart < overlap.length) {
            overlap.substring(sentenceStart)
        } else {
            // Fallback to word boundary
            val words = overlap.split(" ")
            if (words.size > 1) {
                words.drop(1).joinToString(" ")
            } else {
                ""
            }
        }
    }

    private fun prependSectionTitle(
        content: String,
        sectionTitle: String,
    ): String {
        // Don't prepend if config is false, section title is blank, or content is empty
        if (!config.includeSectionTitleInChunk || sectionTitle.isBlank() || content.isBlank()) {
            return content
        }

        val titleWithSeparator = "FROM: $sectionTitle\n\n"
        val resultLength = titleWithSeparator.length + content.length

        // Don't prepend if it would cause the chunk to exceed maxChunkSize
        if (resultLength > config.maxChunkSize) {
            return content
        }

        return titleWithSeparator + content
    }

}
