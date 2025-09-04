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
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.SimpleSimilaritySearchResult
import org.slf4j.LoggerFactory

/**
 * Response from LLM for reranking request
 */
private data class RerankingResponse(
    val scores: List<Double>
)

/**
 * Enhancer that reranks search results using LLM-based relevance scoring.
 * This improves result quality by considering semantic relevance beyond vector similarity.
 */
class RerankingEnhancer(
    private val operationContext: OperationContext,
    private val llm: LlmOptions,
    private val maxResults: Int = 10,
    private val rerankingThreshold: Int = 3,
) : RagResponseEnhancer {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val name: String = "rerank"
    override val enhancementType = EnhancementType.RERANKING

    override fun enhance(response: RagResponse): RagResponse {
        // Only rerank if we have enough results to make it worthwhile
        if (response.results.size <= rerankingThreshold) {
            logger.debug("Skipping reranking for {} results (threshold: {})", response.results.size, rerankingThreshold)
            return response
        }

        val query = response.request.query
        val resultsToRerank = response.results.take(maxResults)

        logger.debug("Reranking {} results for query: {}", resultsToRerank.size, query)

        try {
            val rerankedResults = performLlmReranking(query, resultsToRerank)
            return response.copy(results = rerankedResults)
        } catch (e: Exception) {
            logger.warn("Failed to rerank results, returning original response", e)
            return response
        }
    }

    private fun performLlmReranking(
        query: String,
        results: List<SimilarityResult<out Retrievable>>
    ): List<SimilarityResult<out Retrievable>> {

        // Build prompt for LLM-based reranking
        val prompt = buildRerankingPrompt(query, results)

        // Use the operation context to get structured LLM response
        val rerankingResponse = operationContext.ai()
            .withLlm(llm)
            .createObject(
                """
                You are a relevance scoring expert. You evaluate how well search results match a query.

                $prompt

                Return a JSON object with a "scores" array containing relevance scores from 0.0 to 1.0 for each result in order.
                """.trimIndent(),
                RerankingResponse::class.java
            )

        val relevanceScores = rerankingResponse.scores.take(results.size)

        // Combine original similarity scores with LLM relevance scores
        val rerankedResults = results.mapIndexed { index, result ->
            val llmScore = relevanceScores.getOrElse(index) { 0.5 }
            val combinedScore = (result.score * 0.3 + llmScore * 0.7).coerceIn(0.0, 1.0)

            SimpleSimilaritySearchResult(result.match, combinedScore)
        }.sortedByDescending { it.score }

        logger.debug("Reranked {} results with LLM relevance scoring", rerankedResults.size)
        return rerankedResults
    }

    private fun buildRerankingPrompt(
        query: String,
        results: List<SimilarityResult<out Retrievable>>
    ): String {
        val resultsText = results.mapIndexed { index, result ->
            "${index + 1}. ${result.match.embeddableValue().take(200)}..."
        }.joinToString("\n")

        return """
            Query: "$query"

            Search Results:
            $resultsText

            Please score each result from 0.0 to 1.0 based on how relevant it is to the query.
            Consider semantic meaning, context, and how well each result would help answer the query.
        """.trimIndent()
    }


    override fun estimateImpact(response: RagResponse): EnhancementEstimate? {
        val resultCount = response.results.size

        return if (resultCount <= rerankingThreshold) {
            EnhancementEstimate(
                expectedQualityGain = 0.0,
                estimatedLatencyMs = 0L,
                estimatedTokenCost = 0,
                recommendation = EnhancementRecommendation.SKIP
            )
        } else {
            // Estimate based on number of results to process
            val estimatedLatency = minOf(2000L + (resultCount * 100L), 5000L)
            val estimatedTokens = 50 + (resultCount * 30) // Rough estimate

            EnhancementEstimate(
                expectedQualityGain = 0.15, // 15% quality improvement expected
                estimatedLatencyMs = estimatedLatency,
                estimatedTokenCost = estimatedTokens,
                recommendation = if (resultCount >= 5) EnhancementRecommendation.APPLY else EnhancementRecommendation.CONDITIONAL
            )
        }
    }
}
