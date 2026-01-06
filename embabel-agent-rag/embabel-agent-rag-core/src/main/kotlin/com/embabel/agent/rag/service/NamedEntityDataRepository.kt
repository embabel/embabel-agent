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
package com.embabel.agent.rag.service

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.core.DomainType
import com.embabel.agent.core.JvmType
import com.embabel.agent.rag.model.NamedEntity
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.fasterxml.jackson.databind.ObjectMapper

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
 *
 * Supports typed hydration via [findTypedById] and [findByDomainType] when entities
 * have a [NamedEntityData.linkedDomainType] set.
 */
interface NamedEntityDataRepository {

    /**
     * ObjectMapper for hydrating entities to typed JVM instances.
     */
    val objectMapper: ObjectMapper

    /**
     * Data dictionary for [findEntityById].
     * Enables finding entities by ID and automatically creating
     * instances implementing all matching interfaces based on entity labels.
     */
    val dataDictionary: DataDictionary

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

    // === Typed hydration methods ===

    // === Native store hooks ===
    // Implementations can override these to use native store mappings (e.g., JPA, Spring Data)

    /**
     * Native store lookup by ID for a specific type.
     *
     * Override this method in implementations that have native mappings for certain types
     * (e.g., JPA entities, Spring Data repositories).
     *
     * @param id the entity ID
     * @param type the target class
     * @return the entity if found via native store, null to fall back to generic lookup
     */
    fun <T : NamedEntity> findNativeById(id: String, type: Class<T>): T? = null

    /**
     * Native store lookup for all entities of a specific type.
     *
     * Override this method in implementations that have native mappings for certain types
     * (e.g., JPA entities, Spring Data repositories).
     *
     * @param type the target class
     * @return list of entities if type has native mapping, null to fall back to generic lookup
     */
    fun <T : NamedEntity> findNativeAll(type: Class<T>): List<T>? = null

    // === Typed hydration methods ===

    /**
     * Find an entity by ID and type, then hydrate to a typed JVM instance.
     *
     * First tries [findNativeById] for native store mappings, then falls back to
     * generic label-based lookup with hydration.
     *
     * IDs are scoped by type, so the same ID can exist for different types.
     * Uses the type's simple name as a label filter.
     *
     * Note: This works even if [NamedEntityData.linkedDomainType] was not set when storing,
     * as long as the labels match and properties are compatible with the target type.
     *
     * @param id the entity ID
     * @param type the target class (must implement [NamedEntity])
     * @return the hydrated instance, or null if not found or hydration fails
     */
    fun <T : NamedEntity> findTypedById(id: String, type: Class<T>): T? {
        // Try native store first
        findNativeById(id, type)?.let { return it }

        // Fall back to generic lookup
        val jvmType = JvmType(type)
        return findByLabel(jvmType.ownLabel)
            .find { it.id == id }
            ?.toTypedInstance(objectMapper, type)
    }

    /**
     * Find all entities matching a [DomainType] (by label) and hydrate them.
     *
     * Uses [DomainType.ownLabel] for label matching.
     * Requires [NamedEntityData.linkedDomainType] to be set for hydration.
     *
     * @return list of hydrated instances (entities that fail hydration are filtered out)
     * @see findAll for type-based hydration without requiring linkedDomainType
     */
    fun <T : NamedEntity> findByDomainType(type: DomainType): List<T> =
        findByLabel(type.ownLabel).mapNotNull { it.toTypedInstance(objectMapper) }

    /**
     * Find all entities of a given class and hydrate them.
     *
     * First tries [findNativeAll] for native store mappings, then falls back to
     * generic label-based lookup with hydration.
     *
     * Note: This works even if [NamedEntityData.linkedDomainType] was not set when storing,
     * as long as the labels match and properties are compatible with the target type.
     *
     * @param type the target class (must implement [NamedEntity])
     * @return list of hydrated instances
     */
    fun <T : NamedEntity> findAll(type: Class<T>): List<T> {
        // Try native store first
        findNativeAll(type)?.let { return it }

        // Fall back to generic lookup
        val jvmType = JvmType(type)
        return findByLabel(jvmType.ownLabel).mapNotNull { it.toTypedInstance(objectMapper, type) }
    }

    /**
     * Find all entities matching a [DomainType] without hydration.
     *
     * Useful when you need the raw entity data or when working with [DynamicType][com.embabel.agent.core.DynamicType].
     */
    fun findEntityDataByDomainType(type: DomainType): List<NamedEntityData> =
        findByLabel(type.ownLabel)

    /**
     * Find an entity by ID and create an instance implementing all matching interfaces.
     *
     * This method:
     * 1. Finds the entity by ID
     * 2. If not found, returns null
     * 3. Matches the entity's labels against the provided candidate interfaces
     * 4. Creates an instance implementing all matching interfaces
     *
     * Example:
     * ```java
     * // Entity has labels ["Person", "Manager", "Entity"]
     * NamedEntity result = repository.findById(
     *     "emp-1",
     *     Person.class, Manager.class, Employee.class
     * );
     * // result implements Person and Manager (matching labels)
     * // Employee is not included (no matching label)
     * Person person = (Person) result;
     * Manager manager = (Manager) result;
     * ```
     *
     * @param id the entity ID
     * @param candidateInterfaces interfaces to check against entity labels
     * @return an instance implementing matching interfaces, or null if not found or no interfaces match
     */
    fun findById(id: String, vararg candidateInterfaces: Class<out NamedEntity>): NamedEntity? {
        val entity = findById(id) ?: return null
        val entityLabels = entity.labels()

        // Filter to interfaces whose simple name matches an entity label
        val matchingInterfaces = candidateInterfaces.filter { iface ->
            entityLabels.contains(iface.simpleName)
        }

        if (matchingInterfaces.isEmpty()) {
            return null
        }

        return entity.toInstance(*matchingInterfaces.toTypedArray())
    }

    /**
     * Find an entity by ID and create an instance implementing all matching interfaces
     * from the [dataDictionary].
     *
     * This method uses the configured [dataDictionary] to determine which interfaces
     * the returned instance should implement, based on matching entity labels to
     * JVM type labels.
     *
     * Example:
     * ```kotlin
     * // Configure repository with data dictionary
     * val dictionary = DataDictionary.fromClasses(Person::class.java, Manager::class.java)
     * repository.dataDictionary = dictionary
     *
     * // Entity has labels ["Person", "Manager", "Entity"]
     * val result = repository.findEntityById("emp-1")
     * // result implements both Person and Manager
     * val person = result as Person
     * val manager = result as Manager
     * ```
     *
     * @param id the entity ID
     * @return an instance implementing all matching interfaces from dataDictionary,
     *         or null if: entity not found, no dataDictionary configured, or no interfaces match
     */
    @Suppress("UNCHECKED_CAST")
    fun findEntityById(id: String): NamedEntity? {
        val entity = findById(id) ?: return null
        val entityLabels = entity.labels()

        // Find all JVM types whose labels intersect with entity labels
        val matchingTypes = dataDictionary.jvmTypes.filter { jvmType ->
            jvmType.labels.any { label -> entityLabels.contains(label) }
        }

        if (matchingTypes.isEmpty()) {
            return null
        }

        // Extract the classes and create the proxy
        val interfaces = matchingTypes.map { it.clazz as Class<out NamedEntity> }.toTypedArray()
        return entity.toInstance(*interfaces)
    }
}
