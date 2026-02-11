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
package com.embabel.agent.filter

/**
 * In-memory property filter evaluation for [PropertyFilter] expressions against key-value maps.
 *
 * Evaluates filter expressions against `Map<String, Any?>` properties.
 * This is useful as a fallback when native query support is unavailable,
 * or for evaluating guard conditions against context state.
 */
object InMemoryPropertyFilter {

    /**
     * Test if a property map matches the filter.
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
        is PropertyFilter.ContainsIgnoreCase ->
            properties[filter.key]?.toString()?.lowercase()?.contains(filter.value.lowercase()) == true
        is PropertyFilter.EqIgnoreCase ->
            properties[filter.key]?.toString()?.lowercase() == filter.value.lowercase()
        is PropertyFilter.StartsWith -> properties[filter.key]?.toString()?.startsWith(filter.value) == true
        is PropertyFilter.EndsWith -> properties[filter.key]?.toString()?.endsWith(filter.value) == true
        is PropertyFilter.Like -> matchesRegex(properties[filter.key], filter.pattern)
        is PropertyFilter.And -> filter.filters.all { matches(it, properties) }
        is PropertyFilter.Or -> filter.filters.any { matches(it, properties) }
        is PropertyFilter.Not -> !matches(filter.filter, properties)
        is ObjectFilter -> false // Extension point subtypes require their own evaluator
    }

    /**
     * Test if a metadata map matches the filter.
     * Alias for [matches] for readability in metadata filtering contexts.
     */
    fun matchesMetadata(filter: PropertyFilter, metadata: Map<String, Any?>): Boolean =
        matches(filter, metadata)

    /**
     * Test if a properties map matches the filter.
     * Alias for [matches] for readability in property filtering contexts.
     */
    fun matchesProperties(filter: PropertyFilter, properties: Map<String, Any?>): Boolean =
        matches(filter, properties)

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

    private fun matchesRegex(actual: Any?, pattern: String): Boolean {
        if (actual == null) return false
        return try {
            val regex = Regex(pattern)
            regex.containsMatchIn(actual.toString())
        } catch (e: Exception) {
            // Invalid regex pattern
            false
        }
    }
}
