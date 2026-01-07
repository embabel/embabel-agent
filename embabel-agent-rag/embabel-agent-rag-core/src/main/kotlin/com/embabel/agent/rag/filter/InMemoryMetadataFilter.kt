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
import com.embabel.common.core.types.SimilarityResult

/**
 * Convenience class for in-memory filtering on [com.embabel.agent.rag.model.Datum.metadata].
 * Implementations can use this for post-filtering when native query support is unavailable.
 * This is inefficient so is a last resort.
 */
object InMemoryMetadataFilter {

    /**
     * Test if a metadata map matches the filter.
     */
    fun matches(filter: MetadataFilter, metadata: Map<String, Any?>): Boolean = when (filter) {
        is MetadataFilter.Eq -> metadata[filter.key] == filter.value
        is MetadataFilter.Ne -> metadata[filter.key] != filter.value
        is MetadataFilter.Gt -> compareNumbers(metadata[filter.key], filter.value) { it > 0 }
        is MetadataFilter.Gte -> compareNumbers(metadata[filter.key], filter.value) { it >= 0 }
        is MetadataFilter.Lt -> compareNumbers(metadata[filter.key], filter.value) { it < 0 }
        is MetadataFilter.Lte -> compareNumbers(metadata[filter.key], filter.value) { it <= 0 }
        is MetadataFilter.In -> metadata[filter.key] in filter.values
        is MetadataFilter.Nin -> metadata[filter.key] !in filter.values
        is MetadataFilter.Contains -> metadata[filter.key]?.toString()?.contains(filter.value) == true
        is MetadataFilter.And -> filter.filters.all { matches(it, metadata) }
        is MetadataFilter.Or -> filter.filters.any { matches(it, metadata) }
        is MetadataFilter.Not -> !matches(filter.filter, metadata)
    }

    /**
     * Filter a list of results using the metadata filter.
     */
    fun <T : Datum> filterResults(
        results: List<SimilarityResult<T>>,
        filter: MetadataFilter?,
    ): List<SimilarityResult<T>> {
        if (filter == null) return results
        return results.filter { matches(filter, it.match.metadata) }
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
