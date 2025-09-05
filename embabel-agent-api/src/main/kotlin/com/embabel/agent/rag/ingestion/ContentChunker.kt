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

import com.embabel.agent.rag.Chunk
import com.embabel.agent.rag.LeafSection
import com.embabel.agent.rag.MaterializedContainerSection
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Converts MaterializedContainerSection objects into Chunk objects with intelligent text splitting.
 *
 * For container sections with small total content (aggregated from leaves), creates a single chunk
 * containing all leaf content. For large leaf sections within containers, splits them individually
 * into multiple chunks.
 *
 * @param maxChunkSize Maximum characters per chunk (default: 1500)
 * @param overlapSize Characters of overlap between chunks (default: 200)
 * @param minChunkSize Minimum characters to warrant splitting (default: 2000)
 */
class ContentChunker(
    private val maxChunkSize: Int = 1500,
    private val overlapSize: Int = 200,
    private val minChunkSize: Int = 2000,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Split a MaterializedContainerSection into one or more Chunks
     */
    fun chunk(section: MaterializedContainerSection): List<Chunk> {
        val leaves = section.leaves()
        val totalContentLength = leaves.sumOf { it.content.length }

        // If total content is small enough, create a single chunk from all leaves
        if (totalContentLength <= minChunkSize) {
            logger.debug(
                "Creating single chunk for container section '{}' with {} leaves (total length: {})",
                section.title, leaves.size, totalContentLength
            )
            return listOf(createSingleChunkFromContainer(section, leaves))
        }

        // Container content is too long, process leaves individually
        logger.debug(
            "Processing container section '{}' leaves individually (total length: {})",
            section.title, totalContentLength
        )
        return splitLeavesIndividually(section, leaves)
    }

    /**
     * Split multiple MaterializedContainerSections into Chunks
     */
    fun splitSections(sections: List<MaterializedContainerSection>): List<Chunk> {
        return sections.flatMap { chunk(it) }
    }

    private fun createSingleChunkFromContainer(
        section: MaterializedContainerSection,
        leaves: List<LeafSection>,
    ): Chunk {
        val combinedContent = leaves.joinToString("\n\n") { leaf ->
            if (leaf.title.isNotBlank()) "${leaf.title}\n${leaf.content}" else leaf.content
        }.trim()

        val combinedMetadata = mutableMapOf<String, Any?>()
        combinedMetadata.putAll(section.metadata)
        combinedMetadata["container_section_id"] = section.id
        combinedMetadata["container_section_title"] = section.title
        combinedMetadata["container_section_url"] = section.url
        combinedMetadata["leaf_sections"] = leaves.map { mapOf("id" to it.id, "title" to it.title) }
        combinedMetadata["chunk_index"] = 0
        combinedMetadata["total_chunks"] = 1

        return Chunk(
            id = UUID.randomUUID().toString(),
            text = combinedContent,
            metadata = combinedMetadata,
            parentId = section.id
        )
    }

    private fun splitLeavesIndividually(
        containerSection: MaterializedContainerSection,
        leaves: List<LeafSection>,
    ): List<Chunk> {
        val allChunks = mutableListOf<Chunk>()

        for (leaf in leaves) {
            val leafChunks = if (leaf.content.length <= minChunkSize) {
                // Small leaf - create single chunk
                listOf(createSingleLeafChunk(containerSection, leaf))
            } else {
                // Large leaf - split into multiple chunks
                splitLeafIntoMultipleChunks(containerSection, leaf)
            }
            allChunks.addAll(leafChunks)
        }

        return allChunks
    }

    private fun createSingleLeafChunk(
        containerSection: MaterializedContainerSection,
        leaf: LeafSection,
    ): Chunk {
        val content = if (leaf.title.isNotBlank()) "${leaf.title}\n${leaf.content}" else leaf.content

        return Chunk(
            id = UUID.randomUUID().toString(),
            text = content.trim(),
            metadata = leaf.metadata + mapOf(
                "container_section_id" to containerSection.id,
                "container_section_title" to containerSection.title,
                "leaf_section_id" to leaf.id,
                "leaf_section_title" to leaf.title,
                "leaf_section_url" to leaf.url,
                "chunk_index" to 0,
                "total_chunks" to 1
            ),
            parentId = leaf.id
        )
    }

    private fun splitLeafIntoMultipleChunks(
        containerSection: MaterializedContainerSection,
        leaf: LeafSection,
    ): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        val fullContent = if (leaf.title.isNotBlank()) "${leaf.title}\n${leaf.content}" else leaf.content
        val textChunks = splitText(fullContent.trim()).filter { it.trim().isNotEmpty() }

        logger.debug("Split leaf section '{}' into {} text chunks", leaf.title, textChunks.size)

        textChunks.forEachIndexed { index, textChunk ->
            val chunk = Chunk(
                id = UUID.randomUUID().toString(),
                text = textChunk.trim(),
                metadata = leaf.metadata + mapOf(
                    "container_section_id" to containerSection.id,
                    "container_section_title" to containerSection.title,
                    "leaf_section_id" to leaf.id,
                    "leaf_section_title" to leaf.title,
                    "leaf_section_url" to leaf.url,
                    "chunk_index" to index,
                    "total_chunks" to textChunks.size
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
                currentChunk.length + paragraph.length + 2 > maxChunkSize
            ) {

                chunks.add(currentChunk.toString().trim())

                // Start new chunk with overlap from previous chunk if possible
                currentChunk = StringBuilder()
                if (chunks.isNotEmpty()) {
                    val overlap = getOverlapText(chunks.last())
                    if (overlap.isNotEmpty() && overlap.length + paragraph.length + 2 <= maxChunkSize) {
                        currentChunk.append(overlap).append("\n\n")
                    }
                }
            }

            // If single paragraph is too long, split it by sentences
            if (paragraph.length > maxChunkSize) {
                val sentenceChunks = splitBySentences(paragraph)
                for (sentenceChunk in sentenceChunks) {
                    if (currentChunk.isNotEmpty() &&
                        currentChunk.length + sentenceChunk.length + 2 > maxChunkSize
                    ) {

                        chunks.add(currentChunk.toString().trim())
                        currentChunk = StringBuilder()

                        // Add overlap
                        if (chunks.isNotEmpty()) {
                            val overlap = getOverlapText(chunks.last())
                            if (overlap.isNotEmpty() && overlap.length + sentenceChunk.length + 2 <= maxChunkSize) {
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
            if (chunk.length <= maxChunkSize) {
                listOf(chunk)
            } else {
                // Emergency fallback: split oversized chunk by character count
                chunk.chunked(maxChunkSize).filter { it.trim().isNotEmpty() }
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
                currentChunk.length + sentence.length + 1 > maxChunkSize
            ) {

                chunks.add(currentChunk.toString().trim())
                currentChunk = StringBuilder()

                // Add overlap from previous chunk
                if (chunks.isNotEmpty()) {
                    val overlap = getOverlapText(chunks.last())
                    if (overlap.isNotEmpty() && overlap.length + sentence.length + 1 <= maxChunkSize) {
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
            if (chunk.length <= maxChunkSize) {
                listOf(chunk)
            } else {
                // Emergency fallback: split oversized chunk by character count
                chunk.chunked(maxChunkSize).filter { it.trim().isNotEmpty() }
            }
        }.filter { it.trim().isNotEmpty() }

        return finalChunks.ifEmpty {
            if (text.trim().isNotEmpty()) listOf(text.trim()) else emptyList()
        }
    }

    private fun getOverlapText(previousChunk: String): String {
        if (previousChunk.length <= overlapSize) {
            return ""
        }

        // Try to get overlap at a sentence boundary
        val overlap = previousChunk.takeLast(overlapSize)
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

    /**
     * Configuration for the splitter
     */
    data class SplitterConfig(
        val maxChunkSize: Int = 1500,
        val overlapSize: Int = 200,
        val minChunkSize: Int = 2000,
    ) {
        init {
            require(maxChunkSize > 0) { "maxChunkSize must be positive" }
            require(overlapSize >= 0) { "overlapSize must be non-negative" }
            require(minChunkSize >= maxChunkSize) { "minChunkSize must be >= maxChunkSize" }
            require(overlapSize < maxChunkSize) { "overlapSize must be < maxChunkSize" }
        }
    }

    companion object {
        /**
         * Create a SectionSplitter with custom configuration
         */
        fun withConfig(config: SplitterConfig): ContentChunker {
            return ContentChunker(
                maxChunkSize = config.maxChunkSize,
                overlapSize = config.overlapSize,
                minChunkSize = config.minChunkSize
            )
        }
    }
}
