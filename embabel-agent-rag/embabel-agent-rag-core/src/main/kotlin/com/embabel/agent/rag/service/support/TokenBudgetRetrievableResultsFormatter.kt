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

import com.embabel.agent.rag.model.Retrievable
import com.embabel.agent.rag.service.RetrievableResultsFormatter
import com.embabel.agent.rag.service.SimilarityResults
import com.embabel.common.ai.model.TokenCountEstimator
import org.jetbrains.annotations.ApiStatus

/**
 * A [RetrievableResultsFormatter] that accumulates results from the top
 * (highest similarity first) until the given [tokenBudget] is exhausted.
 *
 * Each result is formatted using the shared [formatRetrievableResult] function.
 * Token cost is estimated via [tokenCountEstimator].
 */
@ApiStatus.Experimental
class TokenBudgetRetrievableResultsFormatter constructor(
    private val tokenCountEstimator: TokenCountEstimator<String>,
    private val tokenBudget: Int,
) : RetrievableResultsFormatter {

    override fun formatResults(similarityResults: SimilarityResults<out Retrievable>): String {
        val separator = "\n---\n"
        val headerSuffix = "\n\n"
        // Exploratory: reserve budget for the header wrapper ("N results:\n\n").
        // Uses total result count for the digit estimate, which may over-reserve slightly
        // when fewer results are selected (e.g., total=100 but selected=9). This is
        // intentionally conservative — better to under-fill than to exceed the budget.
        val headerOverhead = tokenCountEstimator.estimate("${similarityResults.results.size} results:$headerSuffix")
        var remaining = maxOf(0, tokenBudget - headerOverhead)
        val selected = mutableListOf<String>()
        for (result in similarityResults.results) {
            val formatted = formatRetrievableResult(result)
            val separatorCost = if (selected.isNotEmpty()) tokenCountEstimator.estimate(separator) else 0
            val cost = tokenCountEstimator.estimate(formatted) + separatorCost
            if (cost > remaining) break
            selected.add(formatted)
            remaining -= cost
        }
        val header = "${selected.size} results:"
        return if (selected.isEmpty()) header else "$header$headerSuffix${selected.joinToString(separator)}"
    }
}
