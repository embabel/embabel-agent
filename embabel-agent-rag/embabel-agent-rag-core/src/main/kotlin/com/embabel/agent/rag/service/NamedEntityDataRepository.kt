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
import com.embabel.agent.rag.filter.PropertyFilter
import com.embabel.agent.rag.model.NamedEntity
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.Relationship
import com.embabel.agent.rag.model.RelationshipDirection
import com.embabel.agent.rag.model.RelationshipNavigator
import com.embabel.agent.rag.model.Retrievable
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
 *
 * Extends [FilteringVectorSearch] and [FilteringTextSearch] to support native metadata filtering
 * when available, otherwise falls back to post-filtering.
 */
interface NamedEntityDataRepository : CoreSearchOperations, FinderOperations, FilteringVectorSearch, FilteringTextSearch, RelationshipNavigator {

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
        metadataFilter: PropertyFilter? = null,
        propertyFilter: PropertyFilter? = null,
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
     * @param metadataFilter optional metadata filter to apply
     * @param propertyFilter optional property filter to apply
     * @return matching results ranked by BM25 relevance score
     */
    fun textSearch(
        request: TextSimilaritySearchRequest,
        metadataFilter: PropertyFilter? = null,
        propertyFilter: PropertyFilter? = null,
    ): List<SimilarityResult<NamedEntityData>>

    /**
     * Notes on how much Lucene syntax is supported by this implementation
     * to help LLMs and users craft effective queries.
     */
    override val luceneSyntaxNotes: String

    // === SearchOperations interface implementations ===

    /**
     * Check if this repository supports a given type name.
     * Uses the [dataDictionary] to determine supported types.
     *
     * @param type the type name (typically the simple class name)
     * @return true if the type is in the data dictionary
     */
    override fun supportsType(type: String): Boolean =
        dataDictionary.jvmTypes.any { it.ownLabel == type }

    /**
     * Perform typed vector search, returning results hydrated to the specified type.
     * Type must implement [NamedEntity] for hydration to succeed.
     *
     * For types with native store mappings (e.g., @NodeFragment classes), uses
     * [findNativeById] instead of Jackson hydration to preserve proper field mappings.
     *
     * @param request the search request
     * @param clazz the target type for hydration
     * @return list of similarity results with hydrated instances
     */
    @Suppress("UNCHECKED_CAST")
    override fun <T : Retrievable> vectorSearch(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>,
    ): List<SimilarityResult<T>> {
        if (!NamedEntity::class.java.isAssignableFrom(clazz)) {
            return emptyList()
        }
        val namedEntityClass = clazz as Class<out NamedEntity>
        val useNative = isNativeType(namedEntityClass)
        return vectorSearch(request, metadataFilter = null, propertyFilter = null).mapNotNull { similarityResult ->
            val typed: NamedEntity? = if (useNative) {
                findNativeById(similarityResult.match.id, namedEntityClass)
            } else {
                similarityResult.match.toTypedInstance(objectMapper, namedEntityClass, this)
            }
            typed?.let { SimilarityResult(it as T, similarityResult.score) }
        }
    }

    /**
     * Perform typed text search, returning results hydrated to the specified type.
     * Type must implement [NamedEntity] for hydration to succeed.
     *
     * For types with native store mappings (e.g., @NodeFragment classes), uses
     * [findNativeById] instead of Jackson hydration to preserve proper field mappings.
     *
     * @param request the search request
     * @param clazz the target type for hydration
     * @return list of similarity results with hydrated instances
     */
    @Suppress("UNCHECKED_CAST")
    override fun <T : Retrievable> textSearch(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>,
    ): List<SimilarityResult<T>> {
        if (!NamedEntity::class.java.isAssignableFrom(clazz)) {
            return emptyList()
        }
        val namedEntityClass = clazz as Class<out NamedEntity>
        val useNative = isNativeType(namedEntityClass)
        return textSearch(request, metadataFilter = null, propertyFilter = null).mapNotNull { similarityResult ->
            val typed: NamedEntity? = if (useNative) {
                // Use native store loading for @NodeFragment and similar classes
                findNativeById(similarityResult.match.id, namedEntityClass)
            } else {
                similarityResult.match.toTypedInstance(objectMapper, namedEntityClass, this)
            }
            typed?.let { SimilarityResult(it as T, similarityResult.score) }
        }
    }

    /**
     * Perform typed vector search with property filtering.
     * Delegates to the entity-specific [vectorSearch] method with filters.
     *
     * For types with native store mappings (e.g., @NodeFragment classes), uses
     * [findNativeById] instead of Jackson hydration to preserve proper field mappings.
     *
     * @param request the search request
     * @param clazz the target type for hydration
     * @param metadataFilter metadata filter to apply
     * @param propertyFilter property filter to apply
     * @return list of similarity results with hydrated instances
     */
    @Suppress("UNCHECKED_CAST")
    override fun <T : Retrievable> vectorSearchWithFilter(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>,
        metadataFilter: PropertyFilter?,
        propertyFilter: PropertyFilter?,
    ): List<SimilarityResult<T>> {
        if (!NamedEntity::class.java.isAssignableFrom(clazz)) {
            return emptyList()
        }
        val namedEntityClass = clazz as Class<out NamedEntity>
        val useNative = isNativeType(namedEntityClass)
        return vectorSearch(request, metadataFilter, propertyFilter).mapNotNull { similarityResult ->
            val typed: NamedEntity? = if (useNative) {
                findNativeById(similarityResult.match.id, namedEntityClass)
            } else {
                similarityResult.match.toTypedInstance(objectMapper, namedEntityClass, this)
            }
            typed?.let { SimilarityResult(it as T, similarityResult.score) }
        }
    }

    /**
     * Perform typed text search with property filtering.
     * Delegates to the entity-specific [textSearch] method with filters.
     *
     * For types with native store mappings (e.g., @NodeFragment classes), uses
     * [findNativeById] instead of Jackson hydration to preserve proper field mappings.
     *
     * @param request the search request
     * @param clazz the target type for hydration
     * @param metadataFilter metadata filter to apply
     * @param propertyFilter property filter to apply
     * @return list of similarity results with hydrated instances
     */
    @Suppress("UNCHECKED_CAST")
    override fun <T : Retrievable> textSearchWithFilter(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>,
        metadataFilter: PropertyFilter?,
        propertyFilter: PropertyFilter?,
    ): List<SimilarityResult<T>> {
        if (!NamedEntity::class.java.isAssignableFrom(clazz)) {
            return emptyList()
        }
        val namedEntityClass = clazz as Class<out NamedEntity>
        val useNative = isNativeType(namedEntityClass)
        return textSearch(request, metadataFilter, propertyFilter).mapNotNull { similarityResult ->
            val typed: NamedEntity? = if (useNative) {
                findNativeById(similarityResult.match.id, namedEntityClass)
            } else {
                similarityResult.match.toTypedInstance(objectMapper, namedEntityClass, this)
            }
            typed?.let { SimilarityResult(it as T, similarityResult.score) }
        }
    }

    /**
     * Find an entity by ID and hydrate to the specified type.
     * Type must implement [NamedEntity] for lookup to succeed.
     *
     * @param id the entity ID
     * @param clazz the target type
     * @return the hydrated instance, or null if not found
     */
    @Suppress("UNCHECKED_CAST")
    override fun <T> findById(id: String, clazz: Class<T>): T? {
        if (!NamedEntity::class.java.isAssignableFrom(clazz)) {
            return null
        }
        return findTypedById(id, clazz as Class<out NamedEntity>) as T?
    }

    /**
     * Find an entity by ID and type name.
     * Uses the [dataDictionary] to resolve the type name to a class.
     *
     * @param id the entity ID
     * @param type the type name (typically the simple class name)
     * @return the hydrated instance, or null if not found or type not supported
     */
    @Suppress("UNCHECKED_CAST")
    override fun <T : Retrievable> findById(id: String, type: String): T? {
        val jvmType = dataDictionary.jvmTypes.find { it.ownLabel == type }
            ?: return null
        return findById(id, jvmType.clazz) as T?
    }

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
     * Create a relationship between two retrievables (entities, chunks, etc.).
     * For example, linking a Chunk to the NamedEntity it mentions.
     */
    fun createRelationship(a: RetrievableIdentifier, b: RetrievableIdentifier, relationship: RelationshipData)

    /**
     * Find entities related to the given entity via a named relationship.
     *
     * This method is used by [NamedEntityInvocationHandler][com.embabel.agent.rag.model.NamedEntityInvocationHandler]
     * to support lazy loading of relationships marked with [@Relationship][Relationship].
     *
     * Example:
     * ```kotlin
     * // Find all pets owned by person "person-1"
     * val pets = repository.findRelated(RetrievableIdentifier("person-1", "Person"), "OWNS", RelationshipDirection.OUTGOING)
     * ```
     *
     * @param source identifier for the source entity (id + type)
     * @param relationshipName the relationship type/name (e.g., "EMPLOYED_BY", "OWNS")
     * @param direction the direction of traversal
     * @return list of related entity data, or empty list if none found
     */
    override fun findRelated(
        source: RetrievableIdentifier,
        relationshipName: String,
        direction: RelationshipDirection,
    ): List<NamedEntityData>

    /**
     * Find a single entity related to the given entity via a named relationship.
     *
     * This is a convenience method for relationships expected to have at most one target.
     * If multiple targets exist, returns the first one found.
     *
     * @param source identifier for the source entity (id + type)
     * @param relationshipName the relationship type/name
     * @param direction the direction of traversal
     * @return the related entity data, or null if none found
     */
    fun findRelatedSingle(
        source: RetrievableIdentifier,
        relationshipName: String,
        direction: RelationshipDirection = RelationshipDirection.OUTGOING,
    ): NamedEntityData? = findRelated(source, relationshipName, direction).firstOrNull()

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

    /**
     * Check if a type can be loaded natively by this repository.
     *
     * Override this method to indicate which types have native mappings.
     * This is used by [findEntityById] to avoid calling [findNativeById] for types
     * that would throw exceptions or fail.
     *
     * For example, a Drivine-based implementation might check for @NodeFragment annotation:
     * ```kotlin
     * override fun isNativeType(type: Class<*>): Boolean =
     *     type.isAnnotationPresent(NodeFragment::class.java)
     * ```
     *
     * @param type the class to check
     * @return true if this repository can natively load instances of this type
     */
    fun isNativeType(type: Class<*>): Boolean = false

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
        // Try native store first, but only if this type is natively mapped
        if (isNativeType(type)) {
            findNativeById(id, type)?.let { return it }
        }

        // Fall back to generic lookup
        val jvmType = JvmType(type)
        return findByLabel(jvmType.ownLabel)
            .find { it.id == id }
            ?.toTypedInstance(objectMapper, type, this)
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
        return findByLabel(jvmType.ownLabel).mapNotNull { it.toTypedInstance(objectMapper, type, this) }
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
     * // Entity has labels ["Person", "Manager", "__Entity__"]
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
     * // Entity has labels ["Person", "Manager", "__Entity__"]
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

        // Try native store first for each matching type that supports native loading
        for (jvmType in matchingTypes) {
            val clazz = jvmType.clazz as Class<out NamedEntity>
            if (isNativeType(clazz)) {
                findNativeById(id, clazz)?.let { return it }
            }
        }

        // Fall back to creating a proxy implementing all matching interfaces
        // Filter to only interfaces - Java proxies cannot implement classes
        val interfaces = matchingTypes
            .filter { it.clazz.isInterface }
            .map { it.clazz as Class<out NamedEntity> }
            .toTypedArray()

        if (interfaces.isEmpty()) {
            return null
        }

        return entity.toInstance(*interfaces)
    }
}
