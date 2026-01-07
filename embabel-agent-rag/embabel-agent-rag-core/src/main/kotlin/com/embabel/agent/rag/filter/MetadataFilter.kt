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

/**
 * Filter expression for metadata-based filtering in RAG searches.
 * Backends can translate this to native query syntax (Lucene, Cypher, SQL, etc.)
 * or use [InMemoryMetadataFilter] for post-filtering on [Datum.metadata].
 */
sealed interface MetadataFilter {

    /**
     * Equals: metadata[key] == value
     */
    data class Eq(val key: String, val value: Any) : MetadataFilter

    /**
     * Not equals: metadata[key] != value
     */
    data class Ne(val key: String, val value: Any) : MetadataFilter

    /**
     * Greater than: metadata[key] > value
     */
    data class Gt(val key: String, val value: Number) : MetadataFilter

    /**
     * Greater than or equal: metadata[key] >= value
     */
    data class Gte(val key: String, val value: Number) : MetadataFilter

    /**
     * Less than: metadata[key] < value
     */
    data class Lt(val key: String, val value: Number) : MetadataFilter

    /**
     * Less than or equal: metadata[key] <= value
     */
    data class Lte(val key: String, val value: Number) : MetadataFilter

    /**
     * In list: metadata[key] in values
     */
    data class In(val key: String, val values: List<Any>) : MetadataFilter

    /**
     * Not in list: metadata[key] not in values
     */
    data class Nin(val key: String, val values: List<Any>) : MetadataFilter

    /**
     * Contains substring: metadata[key].toString().contains(value)
     */
    data class Contains(val key: String, val value: String) : MetadataFilter

    /**
     * Logical AND: all filters must match
     */
    data class And(val filters: List<MetadataFilter>) : MetadataFilter {
        constructor(vararg filters: MetadataFilter) : this(filters.toList())
    }

    /**
     * Logical OR: at least one filter must match
     */
    data class Or(val filters: List<MetadataFilter>) : MetadataFilter {
        constructor(vararg filters: MetadataFilter) : this(filters.toList())
    }

    /**
     * Logical NOT: filter must not match
     */
    data class Not(val filter: MetadataFilter) : MetadataFilter

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
        fun and(vararg filters: MetadataFilter) = And(filters.toList())
        fun or(vararg filters: MetadataFilter) = Or(filters.toList())
        fun not(filter: MetadataFilter) = Not(filter)
    }
}

/**
 * Extension function to check if a Datum matches a filter.
 */
fun Datum.matchesFilter(filter: MetadataFilter?): Boolean {
    if (filter == null) return true
    return InMemoryMetadataFilter.matches(filter, metadata)
}
