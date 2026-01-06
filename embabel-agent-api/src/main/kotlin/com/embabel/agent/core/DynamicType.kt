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

import com.embabel.common.util.indent
import com.embabel.common.util.indentLines

/**
 * Simple data type. Enables interop with non-JVM types.
 * @param name name of the type. Should be unique within a given context
 * @param description description of the type
 * @param properties properties of the type
 * @param parents parent types of this type. Can be JVM types or dynamic types
 */
data class DynamicType(
    override val name: String,
    override val description: String = name,
    override val ownProperties: List<PropertyDefinition> = emptyList(),
    override val parents: List<DomainType> = emptyList(),
    override val creationPermitted: Boolean = true,
) : DomainType {

    override fun isAssignableFrom(other: Class<*>): Boolean = false

    override fun isAssignableFrom(other: DomainType): Boolean = other.name == name

    override fun isAssignableTo(other: Class<*>): Boolean = false

    override fun isAssignableTo(other: DomainType): Boolean = other.name == name

    override fun children(additionalBasePackages: Collection<String>): Collection<DomainType> {
        // Dynamic types don't have classpath descendants
        return emptySet()
    }

    fun withProperty(
        property: PropertyDefinition,
    ): DynamicType {
        return copy(ownProperties = properties + property)
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
