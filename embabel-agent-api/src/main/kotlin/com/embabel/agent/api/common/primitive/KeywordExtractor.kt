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
package com.embabel.agent.api.common.primitive

import com.embabel.common.core.types.ZeroToOne
import kotlin.math.pow

/**
 * Implementations can extract keywords from text
 */
interface KeywordExtractor {

    /**
     * All known keywords
     */
    val keywords: Set<String>

    /**
     * Extract keywords from the given text
     *
     * @param text the text to extract keywords from
     * @return the set of extracted keywords
     */
    fun extractKeywords(text: String): Set<String>

    /**
     * Converts a match count to a similarity score between 0 and 1.
     *
     * This default implementation uses an exponent of 0.4 to provide nonlinear scoring that is
     * generous to partial matches while still giving diminishing returns. For example, matching
     * 2 out of 15 keywords yields ~0.45 rather than 0.13, reflecting that even partial matches
     * can be quite valuable.
     *
     * The formula is: (matchCount / totalKeywords)^0.4
     *
     * This approach aligns with information retrieval principles where early matches are most
     * significant, but avoids being overly harsh on documents that match only a subset of keywords.
     *
     * @param matchCount the number of keywords that matched
     * @return a similarity score from 0.0 to 1.0
     */
    fun matchCountToScore(matchCount: Int): ZeroToOne {
        val fraction = matchCount.toDouble() / keywords.size
        return fraction.pow(0.4).coerceAtMost(1.0)
    }

}
