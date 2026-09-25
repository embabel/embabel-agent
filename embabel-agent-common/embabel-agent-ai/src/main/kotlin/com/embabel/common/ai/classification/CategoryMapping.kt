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
package com.embabel.common.ai.classification

import org.jetbrains.annotations.ApiStatus
import java.util.function.Function

/**
 * A closed category domain paired with caller-owned values. Structural collections are copied;
 * value references are retained and may themselves be mutable. Values, including Class tokens,
 * never enter provider requests and are never instantiated or populated from model output.
 */
@ApiStatus.Experimental
class CategoryMapping<T : Any>(values: Map<Category, T>) {
    private val definition = ClassificationRequest("", values.keys.toList())
    private val valuesById: Map<String, T> = java.util.Map.copyOf(values.mapKeys { it.key.id })

    /** The single source of category IDs and descriptions for requests and result mapping. */
    val categories: List<Category> get() = definition.categories

    /** Create a provider-neutral request from this mapping's category domain. */
    fun request(input: String): ClassificationRequest = ClassificationRequest(input, categories)

    /** Resolve a valid selection, retaining non-selection variants and their evidence unchanged. */
    fun map(result: ClassificationResult): MappedClassificationResult<T> = when (result) {
        is ClassificationResult.Selected -> {
            definition.validate(result)
            MappedClassificationResult.Selected(valuesById.getValue(result.categoryId), result)
        }
        is ClassificationResult.NoMatch -> result
        is ClassificationResult.Inconclusive -> result
        is ClassificationResult.Failure -> result
    }

    companion object {
        /**
         * Define categories from enum names in declaration order, never ordinal or toString.
         * Use an explicit mapping instead when IDs must survive enum constant renames.
         */
        @JvmStatic
        fun <E : Enum<E>> fromEnum(enumType: Class<E>, description: Function<E, String>): CategoryMapping<E> =
            CategoryMapping(enumType.enumConstants.associateBy { Category(it.name, description.apply(it)) })
    }
}
