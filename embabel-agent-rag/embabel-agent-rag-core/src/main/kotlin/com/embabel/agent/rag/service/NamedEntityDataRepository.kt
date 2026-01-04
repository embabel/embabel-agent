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
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest

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
     * Find an entity by its ID.
     */
    fun findById(id: String): NamedEntityData?

    fun vectorSearch(
        request: TextSimilaritySearchRequest,
    ): List<SimilarityResult<NamedEntityData>>

    /**
     * Performs full-text search using Lucene query syntax.
     * Not all implementations will support all capabilities (such as fuzzy matching).
     * However, the use of quotes for phrases and + / - for required / excluded terms should be widely supported.
     *
     * The "query" field of request supports the following syntax:
     *
     * ## Basic queries
     * - `machine learning` - matches documents containing either term (implicit OR)
     * - `+machine +learning` - both terms required (AND)
     * - `"machine learning"` - exact phrase match
     *
     * ## Modifiers
     * - `+term` - term must appear
     * - `-term` - term must not appear
     * - `term*` - prefix wildcard
     * - `term~` - fuzzy match (edit distance)
     * - `term~0.8` - fuzzy match with similarity threshold
     *
     * ## Query Field Examples
     * ```
     * // Find chunks mentioning either kotlin or java
     * "kotlin java"
     *
     * // Find chunks with both "error" and "handling"
     * "+error +handling"
     *
     * // Find exact phrase
     * "\"null pointer exception\""
     *
     * // Find "test" but exclude "unit"
     * "+test -unit"
     * ```
     *
     * @param request the text similarity search request
     * @return matching results ranked by BM25 relevance score
     */
    fun textSearch(
        request: TextSimilaritySearchRequest,
    ): List<SimilarityResult<NamedEntityData>>

    /**
     * Notes on how much Lucene syntax is supported by this implementation
     * to help LLMs and users craft effective queries.
     */
    val luceneSyntaxNotes: String

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
     * Find all entities with a specific label.
     * @param label The label to search for (e.g., "Person", "Organization")
     */
    fun findByLabel(label: String): List<NamedEntityData>

}
