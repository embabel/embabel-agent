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
package com.embabel.agent.rag.pipeline

import com.embabel.agent.rag.service.*

/**
 * Filter out sub-par results. Runs at the end
 * after reranking.
 */
object FilterEnhancer : RagResponseEnhancer {

    override val name: String = "filter"

    override val enhancementType: EnhancementType
        get() = EnhancementType.CUSTOM

    override fun enhance(response: RagResponse): RagResponse {
        val filteredResults = response.results.filter {
            it.score >= response.request.similarityThreshold
        }.sortedByDescending { it.score }
            .take(response.request.topK)
        return if (filteredResults == response.results) {
            response
        } else {
            response.copy(results = filteredResults)
        }
    }

    override fun estimateImpact(response: RagResponse): EnhancementEstimate {
        return EnhancementEstimate(
            expectedQualityGain = 1.0,
            estimatedLatencyMs = 0L,
            estimatedTokenCost = 0,
            recommendation = EnhancementRecommendation.APPLY,
        )
    }
}
