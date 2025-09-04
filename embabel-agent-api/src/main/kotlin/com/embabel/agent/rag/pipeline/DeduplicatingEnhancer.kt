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
package com.embabel.agent.rag.pipeline

import com.embabel.agent.rag.*

object DeduplicatingEnhancer : RagResponseEnhancer {

    override val name: String = "dedupe"

    override val enhancementType: EnhancementType
        get() = EnhancementType.DEDUPLICATION

    override fun enhance(response: RagResponse): RagResponse {
        val dedupedResults = response.results.distinctBy { it.match.id }
        // TODO add count metadata so we know which were duplicated
        return if (dedupedResults.size == response.results.size) {
            response
        } else {
            response.copy(results = dedupedResults)
        }
    }

    override fun estimateImpact(response: RagResponse): EnhancementEstimate? {
        return EnhancementEstimate(
            expectedQualityGain = 1.0,
            estimatedLatencyMs = 0L,
            estimatedTokenCost = 0,
            recommendation = EnhancementRecommendation.APPLY,
        )
    }
}
