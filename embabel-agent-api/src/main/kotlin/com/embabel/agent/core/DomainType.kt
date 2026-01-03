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

    /**
     * Get all descendant types from the classpath.
     * For JvmType: scans the classpath for classes that extend or implement this type.
     * For DynamicType: returns empty list as dynamic types don't have classpath descendants.
     * @param additionalBasePackages additional base packages to scan for descendants
     * We always include the package of this type as a base package.
     * Don't add packages near the top of the classpath, such as "com", as this can increase scan time.
     */
    fun children(additionalBasePackages: Collection<String> = listOf()): Collection<DomainType>

    /**
     * Is instance creation permitted?
     * Or is this reference data?
     */
    val creationPermitted: Boolean

    /**
     * Get all labels for this type, including from parent types.
     * For JvmType: simple class names of this type and all parent types.
     * For DynamicType: capitalized value after last '.' in name, plus parent labels.
     */
    @get:JsonIgnore
    val labels: Set<String>
        get() {
            val allLabels = mutableSetOf<String>()
            allLabels.add(ownLabel)
            parents.forEach { parent ->
                allLabels.addAll(parent.labels)
            }
            return allLabels
        }

    /**
     * Get the label for this type only (not including parent labels)
     * This will avoid long FQNs which are not useful for labeling.
     */
    @get:JsonIgnore
    val ownLabel: String
        get() {
            val simpleName = name.substringAfterLast('.')
            return simpleName.replaceFirstChar { it.uppercase() }
        }

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
