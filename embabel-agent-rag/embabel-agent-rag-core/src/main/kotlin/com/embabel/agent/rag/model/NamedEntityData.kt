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

import com.embabel.agent.core.DomainType
import com.embabel.agent.core.JvmType
import com.embabel.agent.rag.model.NamedEntityData.Companion.ENTITY_LABEL
import com.embabel.common.util.indent
import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.media.Schema
import org.slf4j.LoggerFactory
import java.lang.reflect.Proxy

/**
 * Storage format for named entities.
 *
 * Extends [NamedEntity] to share the common contract with domain classes,
 * enabling hydration via [toTypedInstance].
 *
 * The [linkedDomainType] field enables:
 * - [JvmType]: hydration to a typed JVM instance
 * - [DynamicType][com.embabel.agent.core.DynamicType]: schema/structure metadata
 */
interface NamedEntityData : NamedEntity {

    /**
     * Properties of this entity. Arbitrary key-value pairs.
     */
    @get:Schema(
        description = "Properties of this object. Arbitrary key-value pairs, although likely specified in schema. Must filter out embedding",
        example = "{\"birthYear\": 1854, \"deathYear\": 1930}",
        required = true,
    )
    val properties: Map<String, Any>

    /**
     * Optional linkage to a [DomainType] ([JvmType] or [DynamicType][com.embabel.agent.core.DynamicType]).
     *
     * When set to a [JvmType], enables hydration to a typed JVM instance via [toTypedInstance].
     * When set to a [DynamicType][com.embabel.agent.core.DynamicType], provides schema/structure metadata.
     */
    val linkedDomainType: DomainType?

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        val labelsString = labels().joinToString(":")
        return "(${labelsString} id='$id', name=$name, description=$description)".indent(indent)
    }

    override fun embeddableValue(): String {
        val props = properties.entries
            .filterNot { DEFAULT_EXCLUDED_PROPERTIES.contains(it.key) }
            .joinToString { (k, v) -> "$k=$v" }
        return "Entity {${labels()}}: properties=[$props]"
    }

    // Don't call super.labels() - NamedEntityData is a data container, not a domain class.
    // The class name should come from stored labels, not from this::class.simpleName.
    override fun labels(): Set<String> = setOf(ENTITY_LABEL)

    companion object {
        val DEFAULT_EXCLUDED_PROPERTIES = setOf("embedding", "id")

        /**
         * Base label for all entities.
         * Aligns with Neo4j LLM Graph Builder convention.
         * @see <a href="https://github.com/neo4j-labs/llm-graph-builder">LLM Graph Builder</a>
         */
        const val ENTITY_LABEL = "__Entity__"

        /**
         * Relationship type from Chunk to Entity.
         * Aligns with Neo4j LLM Graph Builder convention.
         * ```
         * (Chunk)-[:HAS_ENTITY]->(__Entity__)
         * ```
         */
        const val HAS_ENTITY = "HAS_ENTITY"
    }

    /**
     * Hydrate this entity to a typed JVM instance using [linkedDomainType].
     *
     * Requires [linkedDomainType] to be a [JvmType].
     * The target class must implement [NamedEntity].
     *
     * @param objectMapper the ObjectMapper to use for deserialization
     * @return the hydrated instance, or null if linkedDomainType is not a JvmType or hydration fails
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : NamedEntity> toTypedInstance(objectMapper: ObjectMapper): T? {
        val jvmType = linkedDomainType as? JvmType ?: return null
        return toTypedInstance(objectMapper, jvmType.clazz as Class<T>)
    }

    /**
     * Hydrate this entity to a typed JVM instance using an explicit target class.
     *
     * This allows hydration even when [linkedDomainType] is not set,
     * as long as the entity's labels match the target type and the properties are compatible.
     *
     * For interface types, a dynamic proxy is created.
     * For concrete classes, Jackson deserialization is used.
     *
     * @param objectMapper the ObjectMapper to use for deserialization (only for concrete classes)
     * @param type the target class to hydrate to
     * @return the hydrated instance, or null if hydration fails
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : NamedEntity> toTypedInstance(objectMapper: ObjectMapper, type: Class<T>): T? =
        toTypedInstance(objectMapper, type, null)

    /**
     * Hydrate this entity to a typed JVM instance with relationship navigation support.
     *
     * When a [RelationshipNavigator] is provided, methods annotated with [@Relationship]
     * will lazily load related entities via the navigator.
     *
     * @param objectMapper the ObjectMapper to use for deserialization (only for concrete classes)
     * @param type the target class to hydrate to
     * @param navigator optional navigator for relationship traversal
     * @return the hydrated instance, or null if hydration fails
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : NamedEntity> toTypedInstance(
        objectMapper: ObjectMapper,
        type: Class<T>,
        navigator: RelationshipNavigator?,
    ): T? {
        return try {
            if (type.isInterface) {
                // Use dynamic proxy for interfaces with navigator support
                toInstance(navigator, type) as T
            } else {
                // Use Jackson for concrete classes
                val allProperties = buildMap {
                    put("id", id)
                    put("name", name)
                    put("description", description)
                    putAll(properties)
                }
                objectMapper.convertValue(allProperties, type)
            }
        } catch (e: Exception) {
            LoggerFactory.getLogger(type).warn(
                "Failed to hydrate NamedEntityData (id=$id) to type ${type.name}: ${e.message}",
                e
            )
            null
        }
    }

    /**
     * Create an instance implementing the specified interfaces, backed by this entity's properties.
     *
     * This allows a single [NamedEntityData] with multiple labels (e.g., "Employee", "Manager")
     * to be cast to multiple interfaces without requiring a concrete class for each combination.
     *
     * Example:
     * ```java
     * // Java usage
     * NamedEntity result = entityData.toInstance(Employee.class, Manager.class);
     * Employee emp = (Employee) result;
     * Manager mgr = (Manager) result;
     * ```
     *
     * @param interfaces the interfaces the instance should implement (must all extend [NamedEntity])
     * @return an instance implementing all specified interfaces
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : NamedEntity> toInstance(vararg interfaces: Class<out NamedEntity>): T =
        toInstance(navigator = null, interfaces = interfaces)

    /**
     * Create an instance implementing the specified interfaces with relationship navigation support.
     *
     * When a [RelationshipNavigator] is provided, methods annotated with
     * `@Semantics(relationship = "...")` will lazily load related entities.
     *
     * Example:
     * ```kotlin
     * // With relationship navigation
     * val person = entityData.toInstance<Person>(repository, Person::class.java)
     * val employer = person.getEmployer() // Lazy loads via repository
     * ```
     *
     * @param navigator optional navigator for relationship traversal (typically the repository)
     * @param interfaces the interfaces the instance should implement
     * @return an instance implementing all specified interfaces
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : NamedEntity> toInstance(
        navigator: RelationshipNavigator?,
        vararg interfaces: Class<out NamedEntity>,
    ): T {
        require(interfaces.isNotEmpty()) { "At least one interface must be specified" }

        val allProperties = buildMap {
            put("id", id)
            put("name", name)
            put("description", description)
            uri?.let { put("uri", it) }
            putAll(properties)
        }

        val handler = NamedEntityInvocationHandler(
            properties = allProperties,
            metadata = metadata,
            labels = labels(),
            entityData = this,
            navigator = navigator,
            interfaces = interfaces,
        )

        return Proxy.newProxyInstance(
            interfaces.first().classLoader,
            interfaces,
            handler
        ) as T
    }
}

data class SimpleNamedEntityData(
    override val id: String,
    override val uri: String? = null,
    override val name: String,
    override val description: String,
    val labels: Set<String>,
    override val properties: Map<String, Any>,
    override val metadata: Map<String, Any?> = emptyMap(),
    override val linkedDomainType: DomainType? = null,
) : NamedEntityData {

    override fun labels() = labels + super.labels()

    override fun embeddableValue(): String {
        var sup = super.embeddableValue()
        if (!sup.contains("name")) {
            sup += ", name=$name"
        }
        if (!sup.contains("description")) {
            sup += ", description=$description"
        }
        return sup
    }
}
