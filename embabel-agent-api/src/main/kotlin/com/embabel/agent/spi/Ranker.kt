/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.spi

import com.embabel.common.core.types.*
import com.embabel.common.util.indent

/**
 * Rank available choices based on user input and agent metadata.
 * It's possible that no ranking will be high enough to progress with,
 * but that's a matter for the AgentPlatform using this service.
 */
interface Ranker {

    /**
     * Rank a set of items based on user input and agent metadata.
     * @param description Description of the item being ranked
     * @param userInput User input to rank against
     * @param rankables Set of items to rank
     */
    fun <T> rank(
        description: String,
        userInput: String,
        rankables: Collection<T>,
    ): Rankings<T> where T : Named, T : Described
}

/**
 * Rankings, sorted by score descending
 */
data class Rankings<T>(
    private val rankings: List<Ranking<T>>,
) : HasInfoString where T : Named, T : Described {

    fun rankings(): List<Ranking<T>> = rankings.sortedByDescending { it.score }

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String =
        rankings().joinToString("\n") { it.infoString(verbose, indent) }
}

/**
 * Ranking choice returned by the ranker
 * @param match The ranked item
 * @param score The confidence score of the ranker in this choice,
 * between 0 and 1
 */
data class Ranking<T>(
    override val match: T,
    override val score: ZeroToOne,
) : HasInfoString, SimilarityResult<T> where T : Named, T : Described {

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        var s = "${match.name}: ${"%.2f".format(score)}"
        if (verbose == true) {
            s += " - ${match.description}"
        }
        return if (verbose == true) s.indent(indent) else s
    }
}
