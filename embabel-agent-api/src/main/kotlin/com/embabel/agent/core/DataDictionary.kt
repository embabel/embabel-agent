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
package com.embabel.agent.core

import com.embabel.common.core.types.Named

/**
 * Represents a relationship between two domain types.
 * @param from The source domain type
 * @param to The target domain type
 * @param name The name of the relationship (inferred from property name)
 * @param cardinality The cardinality of the relationship
 * @param metadata Semantic metadata from [@Semantics] annotation, including natural language predicates
 */
data class AllowedRelationship(
    val from: DomainType,
    val to: DomainType,
    val name: String,
    val description: String = name,
    val cardinality: Cardinality,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Exposes access to a set of known data types
 */
interface DataDictionary : Named {

    /**
     * All known types referenced by this component.
     * These may or may not be backed by JVM objects.
     */
    val domainTypes: Collection<DomainType>

    /**
     * Returns a new DataDictionary containing only domain types that match the predicate.
     * @param predicate Filter function applied to each DomainType
     * @return A new DataDictionary with filtered types
     */
    fun filter(predicate: (DomainType) -> Boolean): DataDictionary =
        fromDomainTypes(name, domainTypes.filter(predicate))

    /**
     * Returns a new DataDictionary excluding the specified classes.
     * Only affects JvmType entries; DynamicTypes are preserved.
     * @param classes Classes to exclude
     * @return A new DataDictionary without the specified classes
     */
    fun excluding(vararg classes: Class<*>): DataDictionary {
        val classSet = classes.toSet()
        return filter { domainType ->
            when (domainType) {
                is JvmType -> domainType.clazz !in classSet
                else -> true
            }
        }
    }

    /**
     * Returns a new DataDictionary excluding the specified classes.
     * Only affects JvmType entries; DynamicTypes are preserved.
     * @param classes Collection of classes to exclude
     * @return A new DataDictionary without the specified classes
     */
    fun excluding(classes: Collection<Class<*>>): DataDictionary {
        val classSet = classes.toSet()
        return filter { domainType ->
            when (domainType) {
                is JvmType -> domainType.clazz !in classSet
                else -> true
            }
        }
    }

    /**
     * Kotlin operator for excluding a single class.
     * Usage: `dictionary - Foo::class.java`
     * @param clazz Class to exclude
     * @return A new DataDictionary without the specified class
     */
    operator fun minus(clazz: Class<*>): DataDictionary = excluding(clazz)

    /**
     * Kotlin operator for excluding multiple classes.
     * Usage: `dictionary - setOf(Foo::class.java, Bar::class.java)`
     * @param classes Classes to exclude
     * @return A new DataDictionary without the specified classes
     */
    operator fun minus(classes: Collection<Class<*>>): DataDictionary = excluding(classes)

    /**
     * Combine two DataDictionaries, merging their domain types.
     * Usage: `dictionary1 + dictionary2`
     * @param other The DataDictionary to merge with
     * @return A new DataDictionary containing types from both
     */
    operator fun plus(other: DataDictionary): DataDictionary =
        fromDomainTypes(name, (domainTypes + other.domainTypes).toSet())

    val dynamicTypes: Collection<DynamicType>
        get() =
            domainTypes.filterIsInstance<DynamicType>().toSet()

    val jvmTypes: Collection<JvmType>
        get() =
            domainTypes.filterIsInstance<JvmType>().toSet()

    /**
     * Get all relationships between domain types in this dictionary.
     * A relationship is a property that references another DomainType (not a simple property).
     * @return List of all possible relationships
     */
    fun allowedRelationships(): List<AllowedRelationship> {
        val relationships = mutableListOf<AllowedRelationship>()
        domainTypes.forEach { domainType ->
            domainType.properties.forEach { property ->
                if (property is DomainTypePropertyDefinition) {
                    relationships.add(
                        AllowedRelationship(
                            from = domainType,
                            to = property.type,
                            name = property.name,
                            cardinality = property.cardinality,
                            metadata = property.metadata,
                        )
                    )
                }
            }
        }
        return relationships
    }

    /**
     * The domain type matching these labels, if we have one
     */
    fun domainTypeForLabels(labels: Set<String>): DomainType? {
        return domainTypes.maxByOrNull { entity ->
            entity.labels.intersect(labels).size
        }
    }

    companion object {

        @JvmStatic
        fun fromDomainTypes(
            name: String,
            domainTypes: Collection<DomainType>,
        ): DataDictionary {
            return DataDictionaryImpl(name, domainTypes)
        }

        @JvmStatic
        fun fromClasses(
            name: String,
            vararg embabelTypes: Class<*>,
        ): DataDictionary {
            return fromDomainTypes(name, embabelTypes.map { JvmType(it) })
        }
    }

}

private class DataDictionaryImpl(
    override val name: String,
    override val domainTypes: Collection<DomainType>,
) : DataDictionary
