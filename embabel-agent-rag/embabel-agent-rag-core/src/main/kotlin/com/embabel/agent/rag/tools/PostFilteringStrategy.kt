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
package com.embabel.agent.rag.tools

import com.embabel.agent.filter.PropertyFilter
import com.embabel.agent.rag.filter.InMemoryPropertyFilter
import com.embabel.agent.rag.model.Datum
import com.embabel.agent.rag.model.Retrievable
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest

/**
 * Strategy for inflating topK when post-filtering is required.
 *
 * When a search backend doesn't support native property filtering,
 * we need to request more results than needed, then filter in memory.
 * This strategy determines how many extra results to request.
 */
fun interface TopKInflationStrategy {

    /**
     * Calculate the inflated topK to request when post-filtering will reduce results.
     * @param requestedTopK the original topK requested by the caller
     * @return the inflated topK to use for the underlying search
     */
    fun inflate(requestedTopK: Int): Int

    companion object {

        /**
         * Default strategy: multiply by 3, cap at 1000.
         */
        @JvmField
        val DEFAULT: TopKInflationStrategy = multiplier(3, 1000)

        /**
         * Create a multiplier-based inflation strategy.
         * @param multiplier factor to multiply requestedTopK by
         * @param maxTopK maximum topK to return (prevents excessive memory usage)
         */
        @JvmStatic
        fun multiplier(multiplier: Int, maxTopK: Int = 1000): TopKInflationStrategy =
            TopKInflationStrategy { requestedTopK ->
                (requestedTopK * multiplier).coerceAtMost(maxTopK)
            }

        /**
         * Create a fixed-offset inflation strategy.
         * Useful when you expect a relatively constant number of filtered results.
         * @param offset fixed number to add to requestedTopK
         * @param maxTopK maximum topK to return
         */
        @JvmStatic
        fun offset(offset: Int, maxTopK: Int = 1000): TopKInflationStrategy =
            TopKInflationStrategy { requestedTopK ->
                (requestedTopK + offset).coerceAtMost(maxTopK)
            }

        /**
         * Create a percentage-based inflation strategy.
         * @param percentage expected percentage of results that will pass the filter (0.0 to 1.0)
         * @param maxTopK maximum topK to return
         */
        @JvmStatic
        fun expectedPassRate(percentage: Double, maxTopK: Int = 1000): TopKInflationStrategy {
            require(percentage in 0.01..1.0) { "percentage must be between 0.01 and 1.0" }
            return TopKInflationStrategy { requestedTopK ->
                (requestedTopK / percentage).toInt().coerceAtMost(maxTopK)
            }
        }
    }
}

/**
 * Helper for performing searches with post-filtering when native filtering isn't available.
 */
internal object PostFilteringSearch {

    /**
     * Perform a search with post-filtering using both metadata and entity filters.
     *
     * @param request the original search request
     * @param metadataFilter filter on metadata properties
     * @param entityFilter filter on object properties and labels
     * @param inflationStrategy strategy for inflating topK
     * @param search function that performs the actual search with the inflated request
     * @return filtered results, limited to original topK
     */
    fun <T> search(
        request: TextSimilaritySearchRequest,
        metadataFilter: PropertyFilter?,
        entityFilter: PropertyFilter?,
        inflationStrategy: TopKInflationStrategy,
        search: (TextSimilaritySearchRequest) -> List<SimilarityResult<T>>,
    ): List<SimilarityResult<T>> where T : Datum, T : Retrievable {
        val inflatedTopK = inflationStrategy.inflate(request.topK)
        val inflatedRequest = TextSimilaritySearchRequest(
            request.query,
            request.similarityThreshold,
            inflatedTopK
        )
        return InMemoryPropertyFilter.filterResults(search(inflatedRequest), metadataFilter, entityFilter)
            .take(request.topK)
    }

    /**
     * Perform a regex search with post-filtering using both metadata and entity filters.
     *
     * @param topK the original topK requested
     * @param metadataFilter filter on metadata properties
     * @param entityFilter filter on object properties and labels
     * @param inflationStrategy strategy for inflating topK
     * @param search function that performs the actual search with the inflated topK
     * @return filtered results, limited to original topK
     */
    fun <T> regexSearch(
        topK: Int,
        metadataFilter: PropertyFilter?,
        entityFilter: PropertyFilter?,
        inflationStrategy: TopKInflationStrategy,
        search: (Int) -> List<SimilarityResult<T>>,
    ): List<SimilarityResult<T>> where T : Datum, T : Retrievable {
        val inflatedTopK = inflationStrategy.inflate(topK)
        return InMemoryPropertyFilter.filterResults(search(inflatedTopK), metadataFilter, entityFilter)
            .take(topK)
    }
}
