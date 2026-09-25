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

/** A canonical category ID and its natural-language meaning, including any caller-defined aliases. */
@ApiStatus.Experimental
data class Category(val id: String, val description: String) {
    init {
        require(id.isNotBlank()) { "Category ID must not be blank" }
    }
}

/**
 * Text to classify against a closed, nonempty set of categories. The category list is copied
 * and immutable; it contains no application values or class tokens.
 */
@ApiStatus.Experimental
class ClassificationRequest(val input: String, categories: List<Category>) {
    /** Canonical categories in caller order. IDs are unique within this request. */
    val categories: List<Category> = java.util.List.copyOf(categories)

    init {
        require(this.categories.isNotEmpty()) { "At least one category is required" }
        require(this.categories.map { it.id }.distinct().size == this.categories.size) { "Category IDs must be unique" }
    }

    /** Create a selection only when the provider's category ID belongs to this request. */
    @JvmOverloads
    fun selected(categoryId: String, provenance: ModelProvenance, confidence: Double? = null): ClassificationResult.Selected {
        val result = ClassificationResult.Selected(categoryId, provenance, confidence)
        validate(result)
        return result
    }

    /** Reject unknown provider IDs without converting them into a successful no-match judgment. */
    fun validate(result: ClassificationResult): ClassificationResult {
        require(result !is ClassificationResult.Selected || categories.any { it.id == result.categoryId }) {
            "Selected category ID is outside the request"
        }
        return result
    }
}
