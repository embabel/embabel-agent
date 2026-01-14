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
import com.embabel.agent.rag.model.Retrievable
import com.embabel.common.core.types.SimilarityResult
import kotlin.reflect.full.memberProperties

/**
 * In-memory property filter for post-filtering when native query support is unavailable.
 *
 * Supports two filtering modes:
 * - **Metadata filtering**: Filters on [Datum.metadata] map
 * - **Property filtering**: Filters on [NamedEntityData.properties] map or typed object properties via reflection
 *
 * This is a fallback mechanism - prefer native filtering (Cypher WHERE, Lucene queries) when available.
 */
object InMemoryPropertyFilter {

    /**
     * Test if a property map matches the filter.
     * Works for both metadata maps and properties maps.
     *
     * Note: [EntityFilter.HasAnyLabel] always returns false when matching against
     * a plain property map since labels are entity-specific. Use [matchesEntity]
     * for entity filtering that includes label support.
     */
    fun matches(filter: PropertyFilter, properties: Map<String, Any?>): Boolean = when (filter) {
        is PropertyFilter.Eq -> properties[filter.key] == filter.value
        is PropertyFilter.Ne -> properties[filter.key] != filter.value
        is PropertyFilter.Gt -> compareNumbers(properties[filter.key], filter.value) { it > 0 }
        is PropertyFilter.Gte -> compareNumbers(properties[filter.key], filter.value) { it >= 0 }
        is PropertyFilter.Lt -> compareNumbers(properties[filter.key], filter.value) { it < 0 }
        is PropertyFilter.Lte -> compareNumbers(properties[filter.key], filter.value) { it <= 0 }
        is PropertyFilter.In -> properties[filter.key] in filter.values
        is PropertyFilter.Nin -> properties[filter.key] !in filter.values
        is PropertyFilter.Contains -> properties[filter.key]?.toString()?.contains(filter.value) == true
        is PropertyFilter.And -> filter.filters.all { matches(it, properties) }
        is PropertyFilter.Or -> filter.filters.any { matches(it, properties) }
        is PropertyFilter.Not -> !matches(filter.filter, properties)
        // EntityFilter types - labels don't exist on plain property maps
        is EntityFilter.HasAnyLabel -> false
    }

    /**
     * Test if a Datum matches a metadata filter.
     */
    fun matchesMetadata(filter: PropertyFilter, metadata: Map<String, Any?>): Boolean =
        matches(filter, metadata)

    /**
     * Test if properties match a property filter.
     */
    fun matchesProperties(filter: PropertyFilter, properties: Map<String, Any?>): Boolean =
        matches(filter, properties)

    /**
     * Test if an object matches a property filter using reflection.
     * Falls back to [NamedEntityData.properties] if available.
     *
     * Note: For [NamedEntityData] with [EntityFilter] containing [EntityFilter.HasAnyLabel],
     * use [matchesEntity] instead.
     */
    fun matchesObject(filter: PropertyFilter, target: Any): Boolean {
        // For NamedEntityData, use matchesEntity to support label filters
        if (target is NamedEntityData) {
            return matchesEntity(filter, target)
        }

        // For other objects, use reflection to build a properties map
        val propertiesMap = extractProperties(target)
        return matches(filter, propertiesMap)
    }

    /**
     * Test if a [NamedEntityData] matches a filter, including support for
     * [EntityFilter.HasAnyLabel] label-based filtering.
     */
    fun matchesEntity(filter: PropertyFilter, entity: NamedEntityData): Boolean = when (filter) {
        // EntityFilter-specific: check labels
        is EntityFilter.HasAnyLabel -> entity.labels().any { it in filter.labels }

        // PropertyFilter types: delegate to property matching
        is PropertyFilter.Eq -> entity.properties[filter.key] == filter.value
        is PropertyFilter.Ne -> entity.properties[filter.key] != filter.value
        is PropertyFilter.Gt -> compareNumbers(entity.properties[filter.key], filter.value) { it > 0 }
        is PropertyFilter.Gte -> compareNumbers(entity.properties[filter.key], filter.value) { it >= 0 }
        is PropertyFilter.Lt -> compareNumbers(entity.properties[filter.key], filter.value) { it < 0 }
        is PropertyFilter.Lte -> compareNumbers(entity.properties[filter.key], filter.value) { it <= 0 }
        is PropertyFilter.In -> entity.properties[filter.key] in filter.values
        is PropertyFilter.Nin -> entity.properties[filter.key] !in filter.values
        is PropertyFilter.Contains -> entity.properties[filter.key]?.toString()?.contains(filter.value) == true
        is PropertyFilter.And -> filter.filters.all { matchesEntity(it, entity) }
        is PropertyFilter.Or -> filter.filters.any { matchesEntity(it, entity) }
        is PropertyFilter.Not -> !matchesEntity(filter.filter, entity)
    }

    /**
     * Filter results using a metadata filter on [Datum.metadata].
     */
    fun <T : Datum> filterByMetadata(
        results: List<SimilarityResult<T>>,
        filter: PropertyFilter?,
    ): List<SimilarityResult<T>> {
        if (filter == null) return results
        return results.filter { matches(filter, it.match.metadata) }
    }

    /**
     * Filter results using a property filter.
     * For [NamedEntityData], filters on [NamedEntityData.properties].
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
            filtered = filtered.filter { matches(metadataFilter, it.match.metadata) }
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

    private fun compareNumbers(
        actual: Any?,
        expected: Number,
        comparison: (Int) -> Boolean,
    ): Boolean {
        if (actual == null) return false
        val actualNum = when (actual) {
            is Number -> actual.toDouble()
            is String -> actual.toDoubleOrNull() ?: return false
            else -> return false
        }
        return comparison(actualNum.compareTo(expected.toDouble()))
    }
}
