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
package com.embabel.agent.rag.store

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.ContentElement
import com.embabel.agent.rag.model.HierarchicalContentElement
import com.embabel.common.core.types.Named

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

    fun findAllChunksById(chunkIds: List<String>): Iterable<Chunk>

    fun findById(id: String): ContentElement?

    fun save(element: ContentElement): ContentElement

    /**
     * Return the total number of content elements in the repository
     */
    fun count(): Int

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
