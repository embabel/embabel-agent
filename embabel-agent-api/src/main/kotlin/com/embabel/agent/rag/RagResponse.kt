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

import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.ZeroToOne
import java.time.Instant

/**
 * Rag response
 * RagResponses can contain results from multiple RAG services.
 * Results are not necessarily chunks, but can be entities.
 * @param request the original request
 * @param service the name of the RAG service that produced this response
 * @param results the list of similarity results
 */
data class RagResponse(
    val request: RagRequest,
    val service: String,
    val results: List<SimilarityResult<out Retrievable>>,
    val enhancement: RagResponseEnhancement? = null,
    val qualityMetrics: QualityMetrics? = null,

    val timestamp: Instant = Instant.now(),
)

/**
 * RAGAS quality metrics
 * @param faithfulness Content grounded in retrieved docs
 * @param answerRelevancy Response relevance to query
 * @param contextPrecision Relevant chunks ranked higher
 * @param contextRecall All relevant info retrieved
 * @param contextRelevancy Retrieved chunks are relevant
 */
data class QualityMetrics(
    val faithfulness: ZeroToOne,
    val answerRelevancy: ZeroToOne,
    val contextPrecision: ZeroToOne,
    val contextRecall: ZeroToOne,
    val contextRelevancy: ZeroToOne,
    val overallScore: ZeroToOne = computeRAGASScore(
        faithfulness,
        answerRelevancy,
        contextPrecision,
        contextRecall,
        contextRelevancy,
    ),
)

fun computeRAGASScore(
    faithfulness: Double,
    answerRelevancy: Double,
    contextPrecision: Double,
    contextRecall: Double,
    contextRelevancy: Double,
): Double {
    // RAGAS uses harmonic mean to penalize any single poor metric
    val values = listOf(faithfulness, answerRelevancy, contextPrecision, contextRecall, contextRelevancy)
    return harmonicMean(values)
}

private fun harmonicMean(values: List<Double>): Double {
    val validValues = values.filter { it > 0.0 }
    if (validValues.isEmpty()) return 0.0
    return validValues.size / validValues.sumOf { 1.0 / it }
}
