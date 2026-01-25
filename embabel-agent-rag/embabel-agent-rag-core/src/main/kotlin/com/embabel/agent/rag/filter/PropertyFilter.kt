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
package com.embabel.agent.rag.filter

import com.embabel.agent.rag.model.Datum
import com.embabel.agent.rag.model.NamedEntityData

/**
 * Filter expression for property-based filtering in RAG searches.
 *
 * This filter type can be used in two contexts:
 * - **Metadata filtering**: Applied to [Datum.metadata] map
 * - **Property filtering**: Applied to object properties (e.g., [NamedEntityData.properties] or typed entity fields)
 *
 * Backends translate this to native query syntax (Lucene field queries, Cypher WHERE clauses, etc.)
 * and fall back to [InMemoryPropertyFilter] for post-filtering when native filtering isn't available.
 *
 * **Limitation: Nested Properties Not Supported**
 *
 * Filters operate on top-level properties only. Nested property paths like `"address.city"` or
 * `"metadata.source"` are **not** supported. The filter key must match a direct key in the
 * metadata map or a top-level property on the entity object.
 *
 * Kotlin users can use operator syntax for combining filters:
 * ```kotlin
 * val filter = (eq("owner", "alice") and gte("score", 0.8)) or eq("role", "admin")
 * val excluded = !eq("status", "deleted")
 * ```
 */
sealed interface PropertyFilter {

    /**
     * Logical NOT operator: `!filter`
     */
    operator fun not(): PropertyFilter = Not(this)

    /**
     * Logical AND infix: `filter1 and filter2`
     */
    infix fun and(other: PropertyFilter): PropertyFilter = And(listOf(this, other))

    /**
     * Logical OR infix: `filter1 or filter2`
     */
    infix fun or(other: PropertyFilter): PropertyFilter = Or(listOf(this, other))

    /**
     * Equals: properties[key] == value
     */
    data class Eq(val key: String, val value: Any) : PropertyFilter

    /**
     * Not equals: properties[key] != value
     */
    data class Ne(val key: String, val value: Any) : PropertyFilter

    /**
     * Greater than: properties[key] > value
     */
    data class Gt(val key: String, val value: Number) : PropertyFilter

    /**
     * Greater than or equal: properties[key] >= value
     */
    data class Gte(val key: String, val value: Number) : PropertyFilter

    /**
     * Less than: properties[key] < value
     */
    data class Lt(val key: String, val value: Number) : PropertyFilter

    /**
     * Less than or equal: properties[key] <= value
     */
    data class Lte(val key: String, val value: Number) : PropertyFilter

    /**
     * In list: properties[key] in values
     */
    data class In(val key: String, val values: List<Any>) : PropertyFilter

    /**
     * Not in list: properties[key] not in values
     */
    data class Nin(val key: String, val values: List<Any>) : PropertyFilter

    /**
     * Contains substring: properties[key].toString().contains(value)
     */
    data class Contains(val key: String, val value: String) : PropertyFilter

    /**
     * Contains substring (case-insensitive): properties[key].toString().lowercase().contains(value.lowercase())
     */
    data class ContainsIgnoreCase(val key: String, val value: String) : PropertyFilter

    /**
     * Equals (case-insensitive): properties[key].toString().lowercase() == value.lowercase()
     */
    data class EqIgnoreCase(val key: String, val value: String) : PropertyFilter

    /**
     * Starts with prefix: properties[key].toString().startsWith(value)
     */
    data class StartsWith(val key: String, val value: String) : PropertyFilter

    /**
     * Ends with suffix: properties[key].toString().endsWith(value)
     */
    data class EndsWith(val key: String, val value: String) : PropertyFilter

    /**
     * Regex pattern match: properties[key].toString().matches(Regex(pattern))
     *
     * Uses Java/Kotlin regex syntax. For case-insensitive matching,
     * use the (?i) flag at the start of the pattern.
     */
    data class Like(val key: String, val pattern: String) : PropertyFilter

    /**
     * Logical AND: all filters must match
     */
    data class And(val filters: List<PropertyFilter>) : PropertyFilter {
        constructor(vararg filters: PropertyFilter) : this(filters.toList())
    }

    /**
     * Logical OR: at least one filter must match
     */
    data class Or(val filters: List<PropertyFilter>) : PropertyFilter {
        constructor(vararg filters: PropertyFilter) : this(filters.toList())
    }

    /**
     * Logical NOT: filter must not match
     */
    data class Not(val filter: PropertyFilter) : PropertyFilter

    companion object {
        /**
         * DSL builder for creating filters
         */
        fun eq(key: String, value: Any) = Eq(key, value)
        fun ne(key: String, value: Any) = Ne(key, value)
        fun gt(key: String, value: Number) = Gt(key, value)
        fun gte(key: String, value: Number) = Gte(key, value)
        fun lt(key: String, value: Number) = Lt(key, value)
        fun lte(key: String, value: Number) = Lte(key, value)
        fun `in`(key: String, values: List<Any>) = In(key, values)
        fun `in`(key: String, vararg values: Any) = In(key, values.toList())
        fun nin(key: String, values: List<Any>) = Nin(key, values)
        fun nin(key: String, vararg values: Any) = Nin(key, values.toList())
        fun contains(key: String, value: String) = Contains(key, value)
        fun containsIgnoreCase(key: String, value: String) = ContainsIgnoreCase(key, value)
        fun eqIgnoreCase(key: String, value: String) = EqIgnoreCase(key, value)
        fun startsWith(key: String, value: String) = StartsWith(key, value)
        fun endsWith(key: String, value: String) = EndsWith(key, value)
        fun like(key: String, pattern: String) = Like(key, pattern)
        fun and(vararg filters: PropertyFilter) = And(filters.toList())
        fun or(vararg filters: PropertyFilter) = Or(filters.toList())
        fun not(filter: PropertyFilter) = Not(filter)
    }
}

/**
 * Extension function to check if a Datum matches a metadata filter.
 */
fun Datum.matchesMetadataFilter(filter: PropertyFilter?): Boolean {
    if (filter == null) return true
    return InMemoryPropertyFilter.matchesMetadata(filter, metadata)
}

/**
 * Extension function to check if a NamedEntityData matches a property filter.
 */
fun NamedEntityData.matchesPropertyFilter(filter: PropertyFilter?): Boolean {
    if (filter == null) return true
    return InMemoryPropertyFilter.matchesProperties(filter, properties)
}
