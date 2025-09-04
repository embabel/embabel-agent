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
package com.embabel.agent.rag

import com.embabel.common.core.types.Named
import com.embabel.common.core.types.ZeroToOne

data class RagResponseEnhancement(
    val enhancer: RagResponseEnhancer,
    val basis: RagResponse,

    val processingTimeMs: Long = 0,
    val tokensProcessed: Int = 0,
    val enhancementType: EnhancementType,
    // Before/after quality score delta
    val qualityImpact: ZeroToOne? = null,
)

enum class EnhancementType {
    COMPRESSION, RERANKING, DEDUPLICATION,
    ENTITY_EXTRACTION, FACT_CHECKING, QUALITY_ASSESSMENT,
    CONTENT_SYNTHESIS, MULTIMODAL_PROCESSING, CUSTOM
}

enum class EnhancementRecommendation {
    APPLY, SKIP, CONDITIONAL
}

data class EnhancementEstimate(
    val expectedQualityGain: Double,
    val estimatedLatencyMs: Long,
    val estimatedTokenCost: Int,
    val recommendation: EnhancementRecommendation,
)

interface RagResponseEnhancer : Named {

    val enhancementType: EnhancementType

    fun enhance(response: RagResponse): RagResponse

    fun estimateImpact(response: RagResponse): EnhancementEstimate? = null

}
