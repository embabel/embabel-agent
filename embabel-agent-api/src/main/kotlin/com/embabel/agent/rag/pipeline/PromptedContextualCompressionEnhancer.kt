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

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.rag.*
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.core.types.SimpleSimilaritySearchResult

// TODO could we not compress all chunks together?

// What about the concept of adding notes to the context
// Keep them fixed size

// Also memory of user - Note that memory is not something we normally care about
// Maintain several named notes. They can be persisted

/**
 *  Question-aware compression using an LLM call.
 *  Instantiated per operation
 */
class PromptedContextualCompressionEnhancer(
    val operationContext: OperationContext,
    val llm: LlmOptions,
    val maxConcurrency: Int = 15,
    override val name: String = "contextual_compression",
    private val targetRatio: Double = 0.3,
    private val preserveEntities: Boolean = true,
) : RagResponseEnhancer {

    override val enhancementType = EnhancementType.COMPRESSION

    override fun enhance(response: RagResponse): RagResponse {
        val query = response.request.query

        val compressedResults = operationContext.parallelMap(
            items = response.results,
            maxConcurrency = maxConcurrency,
        ) { result ->
            val chunk = result.match as? Chunk
            if (chunk != null && chunk.text.length > 1500) {
                val compressed = compressWithQuestionAwareness(
                    content = chunk.text,
                    query = query,
                    targetRatio = targetRatio,
                    preserveEntities = preserveEntities
                )

                val c2 = chunk.transform(
                    compressed
                    // Add compression metadata
//                    contextualRelevance = ZeroToOne(assessCompressionQuality(compressed, query))
                )
                SimpleSimilaritySearchResult(c2, result.score)
            } else {
                result
            }
        }

        return response.copy(results = compressedResults)
    }

    override fun estimateImpact(response: RagResponse): EnhancementEstimate {
        val chunks = response.results.map { it.match }.filterIsInstance<Chunk>()
        val totalTokens =
            chunks.sumOf { it.text.length / 4 }
        val compressionCandidates = chunks.count { it.text.length > 1500 }

        return EnhancementEstimate(
            expectedQualityGain = if (totalTokens > 8000) 0.15 else 0.05,
            estimatedLatencyMs = compressionCandidates * 200L,
            estimatedTokenCost = compressionCandidates * 50,
            recommendation = if (totalTokens > 4000) EnhancementRecommendation.APPLY
            else EnhancementRecommendation.CONDITIONAL
        )
    }

    private fun compressWithQuestionAwareness(
        content: String,
        query: String,
        targetRatio: Double = 0.3,
        preserveEntities: Boolean = true,
        dynamicRatio: Boolean = true,
    ): String {
        return operationContext.ai()
            .withLlm(llm)
            .generateText(
                """
                Given the query, compress the content to include only what
                is relevant.

                # QUERY
                $query

                # CONTENT
                $content
            """.trimIndent()
            )
    }
}
