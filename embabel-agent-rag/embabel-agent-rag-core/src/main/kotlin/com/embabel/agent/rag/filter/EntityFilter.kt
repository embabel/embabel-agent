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

import com.embabel.agent.filter.ObjectFilter
import com.embabel.agent.rag.model.NamedEntityData

/**
 * Filter expression for entity-based filtering in RAG searches.
 *
 * Extends [ObjectFilter] to participate in the shared filter type hierarchy
 * while maintaining its own sealed hierarchy for exhaustive matching.
 *
 * Provides entity-specific filtering capabilities,
 * particularly label-based filtering via [HasAnyLabel].
 *
 * ```kotlin
 * // Filter entities that have any of the specified labels
 * val filter = EntityFilter.hasAnyLabel("Person", "Organization")
 * ```
 */
sealed interface EntityFilter : ObjectFilter {

    /**
     * Filter entities that have at least one of the specified labels.
     *
     * Labels are matched case-sensitively against [NamedEntityData.labels].
     */
    data class HasAnyLabel(val labels: Set<String>) : EntityFilter {
        constructor(vararg labels: String) : this(labels.toSet())

        init {
            require(labels.isNotEmpty()) { "At least one label must be specified" }
        }
    }

    companion object {
        /**
         * Create a filter that matches entities with any of the specified labels.
         */
        fun hasAnyLabel(vararg labels: String): HasAnyLabel = HasAnyLabel(labels.toSet())

        /**
         * Create a filter that matches entities with any of the specified labels.
         */
        fun hasAnyLabel(labels: Set<String>): HasAnyLabel = HasAnyLabel(labels)

        /**
         * Create a filter that matches entities with any of the specified labels.
         */
        fun hasAnyLabel(labels: Collection<String>): HasAnyLabel = HasAnyLabel(labels.toSet())
    }
}

/**
 * Extension function to check if a NamedEntityData matches a [PropertyFilter],
 * supporting both [PropertyFilter] leaf types and [EntityFilter].
 */
fun NamedEntityData.matchesEntityFilter(filter: com.embabel.agent.filter.PropertyFilter?): Boolean {
    if (filter == null) return true
    return InMemoryPropertyFilter.matchesEntity(filter, this)
}
