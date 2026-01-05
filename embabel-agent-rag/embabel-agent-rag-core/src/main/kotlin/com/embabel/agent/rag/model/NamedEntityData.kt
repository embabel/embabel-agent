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
package com.embabel.agent.rag.model

import com.embabel.agent.core.DomainType
import com.embabel.agent.core.JvmType
import com.embabel.common.util.indent
import com.fasterxml.jackson.databind.ObjectMapper
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
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
interface NamedEntityData : EntityData, NamedEntity {

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

    // Use EntityData's embeddableValue which includes properties
    override fun embeddableValue(): String = super<EntityData>.embeddableValue()

    // Use RetrievableEntity's labels which adds "Entity"
    override fun labels(): Set<String> = super<EntityData>.labels()

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
     * @param objectMapper the ObjectMapper to use for deserialization
     * @param type the target class to hydrate to
     * @return the hydrated instance, or null if hydration fails
     */
    fun <T : NamedEntity> toTypedInstance(objectMapper: ObjectMapper, type: Class<T>): T? {
        return try {
            // Build the full property map including id, name, description
            val allProperties = buildMap {
                put("id", id)
                put("name", name)
                put("description", description)
                putAll(properties)
            }
            objectMapper.convertValue(allProperties, type)
        } catch (e: Exception) {
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
    fun <T : NamedEntity> toInstance(vararg interfaces: Class<out NamedEntity>): T {
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
            entityData = this
        )

        return Proxy.newProxyInstance(
            interfaces.first().classLoader,
            interfaces,
            handler
        ) as T
    }
}

/**
 * InvocationHandler that backs a [NamedEntity] instance with a property map.
 */
internal class NamedEntityInvocationHandler(
    private val properties: Map<String, Any?>,
    private val metadata: Map<String, Any?>,
    private val labels: Set<String>,
    private val entityData: NamedEntityData,
) : InvocationHandler {

    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
        val methodName = method.name

        return when (methodName) {
            "toString" -> "NamedEntityInstance(id=${properties["id"]}, name=${properties["name"]}, labels=$labels)"
            "hashCode" -> properties["id"].hashCode()
            "equals" -> {
                val other = args?.firstOrNull()
                when {
                    other === proxy -> true
                    other is NamedEntity -> other.id == properties["id"]
                    else -> false
                }
            }

            // NamedEntity / Retrievable interface methods
            "getId" -> properties["id"]
            "getName" -> properties["name"]
            "getDescription" -> properties["description"]
            "getUri" -> properties["uri"]
            "getMetadata" -> metadata
            "labels" -> labels
            "embeddableValue" -> entityData.embeddableValue()
            "infoString" -> {
                val verbose = args?.getOrNull(0) as? Boolean
                val indent = args?.getOrNull(1) as? Int ?: 0
                entityData.infoString(verbose, indent)
            }
            "propertiesToPersist" -> entityData.propertiesToPersist()

            // Kotlin property getter pattern: getXxx() -> property "xxx"
            else -> {
                if (methodName.startsWith("get") && methodName.length > 3 && args.isNullOrEmpty()) {
                    val propertyName = methodName.substring(3).replaceFirstChar { it.lowercase() }
                    properties[propertyName]
                } else if (methodName.startsWith("is") && methodName.length > 2 && args.isNullOrEmpty()) {
                    // Boolean property: isXxx() -> property "xxx" or "isXxx"
                    val propertyName = methodName.substring(2).replaceFirstChar { it.lowercase() }
                    properties[propertyName] ?: properties[methodName]
                } else {
                    // Try direct property lookup
                    properties[methodName]
                }
            }
        }
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
