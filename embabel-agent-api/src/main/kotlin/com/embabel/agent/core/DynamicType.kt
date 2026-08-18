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
 * @param ownProperties properties directly on the type, versus inherited properties
 * @param parents parent types of this type. Can be JVM types or dynamic types
 */
data class DynamicType(
    override val name: String,
    override val description: String = name,
    override val ownProperties: List<PropertyDefinition> = emptyList(),
    override val parents: List<DomainType> = emptyList(),
    override val creationPermitted: Boolean = true,
) : DomainType {

    /**
     * A dynamic type has no JVM class, so no class can be assignable to it.
     * (Unchanged: whether a dynamic type declaring a JVM parent should be
     * assignable TO that `Class` is a separate question — see [isAssignableTo].)
     */
    override fun isAssignableFrom(other: Class<*>): Boolean = false

    /**
     * True when [other] IS this type or DECLARES it as an ancestor, transitively.
     *
     * [parents] existed but was never consulted here, so a declared hierarchy
     * conferred no subtyping: with `Employee -> Person`, `Person.isAssignableFrom(Employee)`
     * was false. [JvmType] has always walked the real class hierarchy, so the two
     * halves of `DomainType` disagreed about what inheritance means.
     */
    override fun isAssignableFrom(other: DomainType): Boolean = other.isAssignableTo(this)

    /** A dynamic type has no JVM class, so it is assignable to none. */
    override fun isAssignableTo(other: Class<*>): Boolean = false

    /**
     * True when this type IS [other] or DECLARES it as an ancestor, transitively.
     *
     * An ancestor may be a [JvmType] — a realm declaring `parents: [Signal]` in its
     * type YAML — in which case that ancestor's own (class-hierarchy) assignability
     * decides, so a type declaring `Signal` is also assignable to `Signal`'s
     * supertypes.
     */
    override fun isAssignableTo(other: DomainType): Boolean =
        selfAndAncestors().any { ancestor ->
            // A dynamic ancestor matches by name (the identity dynamic types have);
            // a JVM ancestor delegates to its own class-hierarchy walk.
            if (ancestor is DynamicType) ancestor.name == other.name else ancestor.isAssignableTo(other)
        }

    /**
     * This type and every ancestor reachable through [parents], nearest first.
     *
     * Iterative and de-duplicated by name, so a hand-built cyclic chain terminates
     * instead of overflowing the stack — nothing stops a caller constructing one.
     * Only DYNAMIC parents are expanded: a [JvmType] ancestor answers for its own
     * hierarchy above, and expanding it here would reflectively load every
     * superclass and interface for a question they can answer themselves.
     */
    private fun selfAndAncestors(): Collection<DomainType> {
        val seen = LinkedHashMap<String, DomainType>()
        val queue = ArrayDeque<DomainType>()
        queue.add(this)
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (seen.containsKey(next.name)) continue
            seen[next.name] = next
            if (next is DynamicType) queue.addAll(next.parents)
        }
        return seen.values
    }

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
