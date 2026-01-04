/*
 * Copyright 2024-2026 Embabel Software, Inc.
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
package com.embabel.agent.rag.service

import com.embabel.agent.rag.model.NamedEntityData

/**
 * Named relationship that may have properties.
 */
data class RelationshipData(
    val name: String,
    val properties: Map<String, Any> = emptyMap(),
)

/**
 * Simple storage interface for named entities.
 *
 * Implementations may use different backends (in-memory, database, vector store, graph database).
 */
interface NamedEntityDataRepository {

    /**
     * Save an entity. If an entity with the same ID exists, it will be replaced.
     * @return The saved entity (may have updated fields like timestamps)
     */
    fun save(entity: NamedEntityData): NamedEntityData

    /**
     * Save multiple entities.
     * @return The saved entities
     */
    fun saveAll(entities: Collection<NamedEntityData>): List<NamedEntityData> =
        entities.map { save(it) }

    /**
     * Update an existing entity. Fails if entity doesn't exist.
     * Use this when you want to ensure you're updating, not creating.
     * @return The updated entity
     * @throws NoSuchElementException if entity with given ID doesn't exist
     */
    fun update(entity: NamedEntityData): NamedEntityData {
        findById(entity.id) ?: throw NoSuchElementException("Entity not found: ${entity.id}")
        return save(entity)
    }

    /**
     * Create a relationship between two entities.
     */
    fun createRelationship(a: EntityIdentifier, b: EntityIdentifier, relationship: RelationshipData)

    /**
     * Delete an entity by ID.
     * @return true if the entity was deleted, false if it didn't exist
     */
    fun delete(id: String): Boolean

    /**
     * Find an entity by its ID.
     */
    fun findById(id: String): NamedEntityData?

    /**
     * Find all entities with a specific label.
     * @param label The label to search for (e.g., "Person", "Organization")
     */
    fun findByLabel(label: String): List<NamedEntityData>

}
