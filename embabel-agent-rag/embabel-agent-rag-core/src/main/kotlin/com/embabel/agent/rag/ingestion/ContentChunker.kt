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

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.NavigableContainerSection

/**
 * Converts container objects into Chunks with intelligent text splitting.
 * Created chunks contain metadata linking them back to their source sections.
 *
 * For container sections with small total content (aggregated from leaves), creates a single chunk
 * containing all leaf content. For large leaf sections within containers, splits them individually
 * into multiple chunks.
 */
interface ContentChunker {

    interface Config {
        val maxChunkSize: Int
        val overlapSize: Int
        val includeSectionTitleInChunk: Boolean
    }

    /**
     * Configuration for the splitter
     */
    data class DefaultConfig @JvmOverloads constructor(
        override val maxChunkSize: Int = 1500,
        override val overlapSize: Int = 200,
        override val includeSectionTitleInChunk: Boolean = true,
    ) : Config {
        init {
            require(maxChunkSize > 0) { "maxChunkSize must be positive" }
            require(overlapSize >= 0) { "overlapSize must be non-negative" }
            require(overlapSize < maxChunkSize) { "overlapSize must be < maxChunkSize" }
        }
    }

    /**
     * Chunk the given section
     */
    fun chunk(section: NavigableContainerSection): Iterable<Chunk>

    companion object {

        /** Metadata key for the zero-based index of this chunk within its parent section */
        const val CHUNK_INDEX = "chunk_index"

        /** Metadata key for the total number of chunks created from the parent section */
        const val TOTAL_CHUNKS = "total_chunks"

        /**
         * Metadata key for a stable sequence number used for sorting chunks within a container section hierarchy.
         * This is a zero-based sequential number assigned to each chunk as it is created from a container section,
         * preserving the original order of leaves and their chunks. Use this for stable, predictable sorting.
         */
        const val SEQUENCE_NUMBER = "sequence_number"

        /** Metadata key for the unique identifier of the root document */
        const val ROOT_DOCUMENT_ID = "root_document_id"

        /** Metadata key for the unique identifier of the container section */
        const val CONTAINER_SECTION_ID = "container_section_id"

        /** Metadata key for the title of the container section */
        const val CONTAINER_SECTION_TITLE = "container_section_title"

        /** Metadata key for the URI/URL of the container section */
        const val CONTAINER_SECTION_URL = "container_section_url"

        /** Metadata key for the unique identifier of the leaf section */
        const val LEAF_SECTION_ID = "leaf_section_id"

        /** Metadata key for the title of the leaf section */
        const val LEAF_SECTION_TITLE = "leaf_section_title"

        /** Metadata key for the URI/URL of the leaf section */
        const val LEAF_SECTION_URL = "leaf_section_url"

        operator fun invoke(config: Config = DefaultConfig()) =
            InMemoryContentChunker(config)
    }
}
