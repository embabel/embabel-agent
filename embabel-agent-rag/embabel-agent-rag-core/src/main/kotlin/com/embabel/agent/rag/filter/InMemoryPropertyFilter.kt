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

import com.embabel.agent.filter.PropertyFilter
import com.embabel.agent.rag.model.Datum
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.Retrievable
import com.embabel.common.core.types.SimilarityResult
import com.embabel.agent.filter.InMemoryPropertyFilter as GenericPropertyFilter
import kotlin.reflect.full.memberProperties

/**
 * RAG-specific property filter evaluation, extending the generic [GenericPropertyFilter]
 * with entity-aware matching and result filtering capabilities.
 *
 * For generic map-based matching, delegates to [GenericPropertyFilter].
 * Adds support for:
 * - [NamedEntityData] entity matching with label filtering via [EntityFilter]
 * - Object matching via reflection
 * - Bulk result filtering for [SimilarityResult] lists
 */
object InMemoryPropertyFilter {

    /**
     * Test if a property map matches the filter.
     * Delegates to the generic [GenericPropertyFilter].
     */
    fun matches(filter: PropertyFilter, properties: Map<String, Any?>): Boolean =
        GenericPropertyFilter.matches(filter, properties)

    /**
     * Test if a Datum matches a metadata filter.
     */
    fun matchesMetadata(filter: PropertyFilter, metadata: Map<String, Any?>): Boolean =
        GenericPropertyFilter.matches(filter, metadata)

    /**
     * Test if properties match a property filter.
     */
    fun matchesProperties(filter: PropertyFilter, properties: Map<String, Any?>): Boolean =
        GenericPropertyFilter.matches(filter, properties)

    /**
     * Test if an object matches a [PropertyFilter] using reflection.
     * Supports both [PropertyFilter] leaf types and [EntityFilter].
     */
    fun matchesObject(filter: PropertyFilter, target: Any): Boolean {
        if (target is NamedEntityData) {
            return matchesEntity(filter, target)
        }

        // For other objects, use reflection to build a properties map
        return when (filter) {
            is EntityFilter -> false // Entity filters only apply to NamedEntityData
            else -> GenericPropertyFilter.matches(filter, extractProperties(target))
        }
    }

    /**
     * Test if a [NamedEntityData] matches a [PropertyFilter], including support for
     * [EntityFilter.HasAnyLabel] label-based filtering.
     */
    fun matchesEntity(filter: PropertyFilter, entity: NamedEntityData): Boolean = when (filter) {
        is EntityFilter.HasAnyLabel -> entity.labels().any { it in filter.labels }
        else -> GenericPropertyFilter.matches(filter, entity.properties)
    }

    /**
     * Filter results using a metadata filter on [Datum.metadata].
     */
    fun <T : Datum> filterByMetadata(
        results: List<SimilarityResult<T>>,
        filter: PropertyFilter?,
    ): List<SimilarityResult<T>> {
        if (filter == null) return results
        return results.filter { GenericPropertyFilter.matches(filter, it.match.metadata) }
    }

    /**
     * Filter results using an object filter.
     * For [NamedEntityData], filters on properties and labels.
     * For other types, uses reflection.
     */
    fun <T : Retrievable> filterByProperties(
        results: List<SimilarityResult<T>>,
        filter: PropertyFilter?,
    ): List<SimilarityResult<T>> {
        if (filter == null) return results
        return results.filter { matchesObject(filter, it.match) }
    }

    /**
     * Filter results using both metadata and entity filters.
     * Both filters must match for a result to be included.
     */
    fun <T> filterResults(
        results: List<SimilarityResult<T>>,
        metadataFilter: PropertyFilter?,
        entityFilter: PropertyFilter?,
    ): List<SimilarityResult<T>> where T : Datum, T : Retrievable {
        var filtered = results

        if (metadataFilter != null) {
            filtered = filtered.filter { GenericPropertyFilter.matches(metadataFilter, it.match.metadata) }
        }

        if (entityFilter != null) {
            filtered = filtered.filter { matchesObject(entityFilter, it.match) }
        }

        return filtered
    }

    /**
     * Extract properties from an object using reflection.
     */
    private fun extractProperties(target: Any): Map<String, Any?> {
        return try {
            target::class.memberProperties.associate { prop ->
                prop.name to try {
                    prop.getter.call(target)
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
