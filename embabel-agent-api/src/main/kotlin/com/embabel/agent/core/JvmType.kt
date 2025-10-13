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
import com.embabel.common.util.indentLines
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty

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
                SimplePropertyDefinition(
                    name = it.name,
                    type = it.type.simpleName,
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
                    SomeOf.Companion.eligibleFields(it)
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
