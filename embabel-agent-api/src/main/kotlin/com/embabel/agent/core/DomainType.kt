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

import com.embabel.agent.api.common.SomeOf
import com.embabel.common.core.types.HasInfoString
import com.embabel.common.core.types.NamedAndDescribed
import com.embabel.common.util.indent
import com.embabel.common.util.indentLines
import com.fasterxml.jackson.annotation.*

/**
 * Type known to the Embabel agent platform.
 * May be backed by a domain object or by a map.
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
     * Exposed even for JvmTypes, for consistency
     */
    val properties: List<PropertyDefinition>

    fun isAssignableFrom(other: Class<*>): Boolean

    fun isAssignableFrom(other: DomainType): Boolean

    fun isAssignableTo(other: Class<*>): Boolean

    fun isAssignableTo(other: DomainType): Boolean

}

/**
 * Simple data type
 * @param name name of the type. Should be unique within a given context
 * @param description description of the type
 * @param properties properties of the type
 */
data class DynamicType(
    override val name: String,
    override val description: String = name,
    override val properties: List<PropertyDefinition> = emptyList(),
) : DomainType {

    override fun isAssignableFrom(other: Class<*>): Boolean = false

    override fun isAssignableFrom(other: DomainType): Boolean = other.name == name

    override fun isAssignableTo(other: Class<*>): Boolean = false

    override fun isAssignableTo(other: DomainType): Boolean = other.name == name

    fun withProperty(
        property: PropertyDefinition,
    ): DynamicType {
        return copy(properties = properties + property)
    }

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        return """
                |name: $name
                |properties:
                |${properties.map { it }.joinToString("\n") { it.toString().indent(1) }}
                |"""
            .trimMargin()
            .indentLines(indent)
    }

}

data class PropertyDefinition(
    val name: String,
    val type: String = "string",
    val description: String? = name,
)

/**
 * Typed backed by a JVM object
 */
data class JvmType @JsonCreator constructor(
    @param:JsonProperty("className")
    val className: String,
) : DomainType {


    @get:JsonIgnore
    val clazz: Class<*> by lazy {
        Class.forName(className)
    }

    constructor(clazz: Class<*>) : this(clazz.name)

    @get:JsonIgnore
    override val name: String
        get() = className

    @get:JsonIgnore
    override val description: String
        get() {
            val ann = clazz.getAnnotation(JsonClassDescription::class.java)
            return if (ann != null) {
                "${clazz.simpleName}: ${ann.value}"
            } else {
                clazz.name
            }
        }

    override fun isAssignableFrom(other: Class<*>): Boolean =
        clazz.isAssignableFrom(other)

    override fun isAssignableFrom(other: DomainType): Boolean =
        when (other) {
            is JvmType -> clazz.isAssignableFrom(other.clazz)
            is DynamicType -> false
        }

    override fun isAssignableTo(other: Class<*>): Boolean =
        other.isAssignableFrom(clazz)

    override fun isAssignableTo(other: DomainType): Boolean =
        when (other) {
            is JvmType -> other.clazz.isAssignableFrom(clazz)
            is DynamicType -> false
        }

    @get:JsonIgnore
    override val properties: List<PropertyDefinition>
        get() {
            return clazz.declaredFields.map {
                PropertyDefinition(
                    name = it.name,
                    type = it.type.simpleName,
                    description = null,
                )
            }
        }

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        return """
                |class: ${clazz.name}
                |"""
            .trimMargin()
            .indentLines(indent)
    }

    companion object {

        /**
         * May need to break up with SomeOf
         */
        fun fromClasses(
            classes: Collection<Class<*>>,
        ): List<JvmType> {
            return classes.flatMap {
                if (SomeOf::class.java.isAssignableFrom(it)) {
                    SomeOf.eligibleFields(it)
                        .map { field ->
                            JvmType(field.type)
                        }
                } else {
                    listOf(JvmType(it))
                }
            }
        }
    }

}
