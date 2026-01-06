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
package com.embabel.agent.rag.store

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.ContentElement
import com.embabel.agent.rag.model.HierarchicalContentElement
import com.embabel.common.core.types.Named

/**
 * Information about content and capabilities of a ContentElementRepository.
 */
interface ContentElementRepositoryInfo {

    /**
     * Number of chunks stored in this repository.
     */
    val chunkCount: Int

    /**
     * Number of documents stored in this repository.
     */
    val documentCount: Int

    /**
     * Number of content elements stored in this repository.
     */
    val contentElementCount: Int

    /**
     * Does this repository support embeddings?
     */
    val hasEmbeddings: Boolean

    /**
     * Is this repository persistent across application restarts?
     */
    val isPersistent: Boolean
}

/**
 * Repository for ContentElements.
 */
interface ContentElementRepository : Named {

    /**
     * Provision this rag service if necessary
     */
    fun provision() {
        // Default no-op
    }

    fun info(): ContentElementRepositoryInfo

    fun findAllChunksById(chunkIds: List<String>): Iterable<Chunk>

    fun findById(id: String): ContentElement?

    /**
     * Save or update the given content element.
     * Does not perform embedding or any other processing,
     * even if the ContentElementRepository supports that.
     */
    fun save(element: ContentElement): ContentElement

    /**
     * Find chunks associated with the given entity with the given ID.
     */
    fun findChunksForEntity(
        entityId: String,
    ): List<Chunk>

    /**
     * Compute the path from root to the given element by traversing parentId relationships.
     * Returns list of IDs from root to element, or null if path cannot be determined.
     */
    fun pathFromRoot(element: HierarchicalContentElement): List<String>? {
        val path = mutableListOf<String>()
        var current: HierarchicalContentElement? = element

        // Build path from element to root (reversed)
        while (current != null) {
            path.add(0, current.id) // Add to front

            val parentId = current.parentId
            if (parentId == null) {
                // Reached root
                return path
            }

            // Look up parent
            current = findById(parentId) as? HierarchicalContentElement
        }

        // If we exit loop without finding root, path is incomplete
        return null
    }
}
