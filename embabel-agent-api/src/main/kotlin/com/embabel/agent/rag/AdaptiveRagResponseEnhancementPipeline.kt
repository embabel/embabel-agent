package com.embabel.agent.rag

data class AdaptiveRagResponseEnhancementPipeline(
    override val name: String,
    val enhancers: List<RagResponseEnhancer>,

    // Add adaptive execution based on research
    val adaptiveExecution: Boolean = true,
    val qualityThreshold: Double = 0.7, // Skip expensive enhancers if quality is high
    val maxLatencyMs: Long = 5000, // Skip remaining enhancers if approaching limit
) : RagResponseEnhancer {

    override val enhancementType = EnhancementType.CUSTOM

    override fun enhance(response: RagResponse): RagResponse {
        var current = response
        val startTime = System.currentTimeMillis()

        for (enhancer in enhancers) {

            // Adaptive execution based on research insights
            if (adaptiveExecution) {
                val estimate = enhancer.estimateImpact(current)
                val elapsed = System.currentTimeMillis() - startTime

                when {
                    // Skip if already high quality and expensive enhancer
                    current.qualityMetrics?.let { it.overallScore > qualityThreshold } == true &&
                            (estimate?.estimatedLatencyMs ?: 0) > 1000 -> continue

                    // Skip if approaching latency limit
                    elapsed > maxLatencyMs -> break

                    // Skip if enhancement estimate says to
                    estimate?.recommendation == EnhancementRecommendation.SKIP -> continue
                }
            }

            val enhancementStart = System.currentTimeMillis()
            current = enhancer.enhance(current).copy(
                enhancement = RagResponseEnhancement(
                    enhancer = enhancer,
                    basis = current,
                    processingTimeMs = System.currentTimeMillis() - enhancementStart,
                    enhancementType = enhancer.enhancementType,
                    tokensProcessed = 0,
                    // TODO fix this
                    //  current.results.sumOf { it.match.length / 4 } // Rough token estimate
                )
            )
        }

        return current
    }
}