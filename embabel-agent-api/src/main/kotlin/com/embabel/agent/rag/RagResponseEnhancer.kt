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
