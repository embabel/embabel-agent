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

import com.embabel.agent.event.RagEventListener
import com.embabel.agent.rag.pipeline.event.EnhancementCompletedRagPipelineEvent
import com.embabel.agent.rag.pipeline.event.EnhancementStartingRagPipelineEvent
import com.embabel.agent.rag.service.*
import org.slf4j.LoggerFactory

/**
 * Pipeline of RagResponseEnhancers with adaptive execution based on research insights.
 * @param name Name of the pipeline
 * @param enhancers List of RagResponseEnhancers to apply in sequence
 * @param adaptiveExecution If true, adaptively skip enhancers based on quality and latency
 * @param qualityThreshold Quality score above which to skip expensive enhancers
 */
data class AdaptivePipelineRagResponseEnhancer @JvmOverloads constructor(
    val enhancers: List<RagResponseEnhancer>,
    val adaptiveExecution: Boolean = true,
    val qualityThreshold: Double = 0.7,
    val listener: RagEventListener,
) : RagResponseEnhancer {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val name
        get() =
            enhancers.joinToString("->") { it.name }

    override val enhancementType = EnhancementType.CUSTOM

    override fun enhance(response: RagResponse): RagResponse {
        var current = response
        val startTime = System.currentTimeMillis()

        val desiredMaxLatency = response.request.hintOfType<DesiredMaxLatency>() ?: DesiredMaxLatency.UNBOUNDED

        for (enhancer in enhancers) {
            if (adaptiveExecution) {
                val estimate = enhancer.estimateImpact(current)
                val elapsedMs = System.currentTimeMillis() - startTime

                when {
                    current.qualityMetrics?.let { it.overallScore > qualityThreshold } == true &&
                            (estimate?.estimatedLatencyMs ?: 0) > 1000 -> {
                        logger.info("Skipping expensive enhancer {} as quality is already high enough", enhancer.name)
                        continue
                    }

                    elapsedMs > desiredMaxLatency.duration.toMillis() -> {
                        logger.info(
                            "Skipping enhancer {} as elapsed time is {}ms with latency limit of {}ms",
                            estimate,
                            enhancer.name,
                            desiredMaxLatency,
                        )
                        break
                    }

                    estimate?.recommendation == EnhancementRecommendation.SKIP -> {
                        logger.info("Skipping enhancer {} it recommends skipping", enhancer.name)
                        continue
                    }
                }
            }

            logger.info(
                "Applying enhancer {} on response from service {} with {} results",
                enhancer.name, current.service, current.results.size,
            )
            val enhancementStart = System.currentTimeMillis()
            listener.onRagEvent(EnhancementStartingRagPipelineEvent(current, enhancer.name))
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
            listener.onRagEvent(EnhancementCompletedRagPipelineEvent(current, enhancer.name))
        }

        return current
    }
}
