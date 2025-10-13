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
package com.embabel.agent.core

import com.embabel.common.core.types.HasInfoString
import com.embabel.common.core.types.NamedAndDescribed
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * Type known to the Embabel agent platform.
 * May be backed by a domain object or ba dynamic type.
 * Supports inheritance.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.DEDUCTION,
)
@JsonSubTypes(
    JsonSubTypes.Type(value = DynamicType::class, name = "dynamic"),
    JsonSubTypes.Type(value = JvmType::class, name = "jvm"),
)
sealed interface DomainType : HasInfoString, NamedAndDescribed {

    /**
     * Get all properties, including inherited ones.
     * Exposed even for JvmTypes, for consistency.
     * Properties are deduplicated by name, with own properties taking precedence over inherited ones.
     */
    @get:JsonIgnore
    val properties: List<PropertyDefinition>
        get() {
            val propertiesByName = mutableMapOf<String, PropertyDefinition>()
            // First add inherited properties (so they can be overridden)
            parents.forEach { parent ->
                parent.properties.forEach { property ->
                    propertiesByName.putIfAbsent(property.name, property)
                }
            }
            // Then add own properties (these take precedence)
            ownProperties.forEach { property ->
                propertiesByName[property.name] = property
            }
            return propertiesByName.values.toList()
        }

    /**
     * Properties defined on this type only (not inherited)
     */
    val ownProperties: List<PropertyDefinition>

    /**
     * Supports inheritance
     */
    val parents: List<DomainType>

    fun isAssignableFrom(other: Class<*>): Boolean

    fun isAssignableFrom(other: DomainType): Boolean

    fun isAssignableTo(other: Class<*>): Boolean

    fun isAssignableTo(other: DomainType): Boolean

}

/**
 * Semantics of holding the value for the property
 */
enum class Cardinality {
    OPTIONAL,
    ONE,
    LIST,
    SET,
}

@JsonTypeInfo(
    use = JsonTypeInfo.Id.SIMPLE_NAME,
)
@JsonSubTypes(
    JsonSubTypes.Type(value = SimplePropertyDefinition::class, name = "simple"),
    JsonSubTypes.Type(value = DomainTypePropertyDefinition::class, name = "domain"),
)
sealed interface PropertyDefinition {
    val name: String
    val description: String
    val cardinality: Cardinality
}

data class SimplePropertyDefinition(
    override val name: String,
    val type: String = "string",
    override val cardinality: Cardinality = Cardinality.ONE,
    override val description: String = name,
) : PropertyDefinition

data class DomainTypePropertyDefinition(
    override val name: String,
    val type: DomainType,
    override val cardinality: Cardinality = Cardinality.ONE,
    override val description: String = name,
) : PropertyDefinition
