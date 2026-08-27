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
package com.embabel.agent.rag.model

/**
 * Load-bearing structural fields for a [Chunk], promoted out of free-form [Chunk.metadata].
 *
 * Written by the content chunker and used for navigation, ordering, and filtering.
 * During the deprecation window, [toMetadata] is merged into [Chunk.metadata] so existing
 * string-key readers keep working.
 */
data class ChunkStructure(
    val rootDocumentId: String? = null,
    val rootDocumentTitle: String? = null,
    val containerSectionId: String? = null,
    val containerSectionTitle: String? = null,
    val containerSectionUrl: String? = null,
    val leafSectionId: String? = null,
    val leafSectionTitle: String? = null,
    val leafSectionUrl: String? = null,
    val chunkIndex: Int? = null,
    val totalChunks: Int? = null,
    val sequenceNumber: Int? = null,
) {

    fun isEmpty(): Boolean = this == EMPTY

    /**
     * Non-null fields as the historical metadata key/value map.
     */
    fun toMetadata(): Map<String, Any?> {
        if (isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, Any?>(KEYS.size)
        rootDocumentId?.let { result[ROOT_DOCUMENT_ID] = it }
        rootDocumentTitle?.let { result[ROOT_DOCUMENT_TITLE] = it }
        containerSectionId?.let { result[CONTAINER_SECTION_ID] = it }
        containerSectionTitle?.let { result[CONTAINER_SECTION_TITLE] = it }
        containerSectionUrl?.let { result[CONTAINER_SECTION_URL] = it }
        leafSectionId?.let { result[LEAF_SECTION_ID] = it }
        leafSectionTitle?.let { result[LEAF_SECTION_TITLE] = it }
        leafSectionUrl?.let { result[LEAF_SECTION_URL] = it }
        chunkIndex?.let { result[CHUNK_INDEX] = it }
        totalChunks?.let { result[TOTAL_CHUNKS] = it }
        sequenceNumber?.let { result[SEQUENCE_NUMBER] = it }
        return result
    }

    /**
     * Prefer non-null values from [other].
     */
    fun merge(other: ChunkStructure): ChunkStructure = ChunkStructure(
        rootDocumentId = other.rootDocumentId ?: rootDocumentId,
        rootDocumentTitle = other.rootDocumentTitle ?: rootDocumentTitle,
        containerSectionId = other.containerSectionId ?: containerSectionId,
        containerSectionTitle = other.containerSectionTitle ?: containerSectionTitle,
        containerSectionUrl = other.containerSectionUrl ?: containerSectionUrl,
        leafSectionId = other.leafSectionId ?: leafSectionId,
        leafSectionTitle = other.leafSectionTitle ?: leafSectionTitle,
        leafSectionUrl = other.leafSectionUrl ?: leafSectionUrl,
        chunkIndex = other.chunkIndex ?: chunkIndex,
        totalChunks = other.totalChunks ?: totalChunks,
        sequenceNumber = other.sequenceNumber ?: sequenceNumber,
    )

    companion object {

        const val CHUNK_INDEX = "chunk_index"
        const val TOTAL_CHUNKS = "total_chunks"
        const val SEQUENCE_NUMBER = "sequence_number"
        const val ROOT_DOCUMENT_ID = "root_document_id"
        const val ROOT_DOCUMENT_TITLE = "root_document_title"
        const val CONTAINER_SECTION_ID = "container_section_id"
        const val CONTAINER_SECTION_TITLE = "container_section_title"
        const val CONTAINER_SECTION_URL = "container_section_url"
        const val LEAF_SECTION_ID = "leaf_section_id"
        const val LEAF_SECTION_TITLE = "leaf_section_title"
        const val LEAF_SECTION_URL = "leaf_section_url"

        val KEYS: Set<String> = setOf(
            CHUNK_INDEX,
            TOTAL_CHUNKS,
            SEQUENCE_NUMBER,
            ROOT_DOCUMENT_ID,
            ROOT_DOCUMENT_TITLE,
            CONTAINER_SECTION_ID,
            CONTAINER_SECTION_TITLE,
            CONTAINER_SECTION_URL,
            LEAF_SECTION_ID,
            LEAF_SECTION_TITLE,
            LEAF_SECTION_URL,
        )

        val EMPTY = ChunkStructure()

        @JvmStatic
        fun fromMetadata(metadata: Map<String, Any?>): ChunkStructure {
            if (metadata.isEmpty() || metadata.keys.none { it in KEYS }) {
                return EMPTY
            }
            return ChunkStructure(
                rootDocumentId = stringOrNull(metadata[ROOT_DOCUMENT_ID]),
                rootDocumentTitle = stringOrNull(metadata[ROOT_DOCUMENT_TITLE]),
                containerSectionId = stringOrNull(metadata[CONTAINER_SECTION_ID]),
                containerSectionTitle = stringOrNull(metadata[CONTAINER_SECTION_TITLE]),
                containerSectionUrl = stringOrNull(metadata[CONTAINER_SECTION_URL]),
                leafSectionId = stringOrNull(metadata[LEAF_SECTION_ID]),
                leafSectionTitle = stringOrNull(metadata[LEAF_SECTION_TITLE]),
                leafSectionUrl = stringOrNull(metadata[LEAF_SECTION_URL]),
                chunkIndex = intOrNull(metadata[CHUNK_INDEX]),
                totalChunks = intOrNull(metadata[TOTAL_CHUNKS]),
                sequenceNumber = intOrNull(metadata[SEQUENCE_NUMBER]),
            )
        }

        @JvmStatic
        fun withoutStructuralKeys(metadata: Map<String, Any?>): Map<String, Any?> {
            if (metadata.isEmpty() || metadata.keys.none { it in KEYS }) {
                return metadata
            }
            return metadata.filterKeys { it !in KEYS }
        }

        private fun stringOrNull(value: Any?): String? = when (value) {
            null -> null
            is String -> value
            else -> value.toString()
        }

        private fun intOrNull(value: Any?): Int? = when (value) {
            null -> null
            is Int -> value
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }
}
