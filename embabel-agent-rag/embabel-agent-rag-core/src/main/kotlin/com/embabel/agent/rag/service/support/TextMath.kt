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
package com.embabel.agent.rag.service.support

/**
 * Helper functions for text similarity calculations used in search.
 */
object TextMath {

    /**
     * Calculate a simple text match score based on term matching.
     * Score is between 0.0 and 1.0 based on percentage of query terms found in the text.
     *
     * @param text The text to search in (should be lowercased)
     * @param queryTerms The query terms to search for (should be lowercased)
     * @return Score between 0.0 and 1.0
     */
    fun textMatchScore(text: String, queryTerms: List<String>): Double {
        if (queryTerms.isEmpty()) return 0.0
        val matchedTerms = queryTerms.count { term -> text.contains(term) }
        return matchedTerms.toDouble() / queryTerms.size
    }
}
