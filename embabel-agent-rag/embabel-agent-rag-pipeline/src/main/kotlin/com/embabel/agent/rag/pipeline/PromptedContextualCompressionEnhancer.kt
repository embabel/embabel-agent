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
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import org.slf4j.LoggerFactory

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
    private val minLengthToCompress: Int = 1500,
    private val preserveEntities: Boolean = true,
) : RagResponseEnhancer {

    private val logger = LoggerFactory.getLogger(PromptedContextualCompressionEnhancer::class.java)

    override val enhancementType = EnhancementType.COMPRESSION

    override fun enhance(response: RagResponse): RagResponse {
        val query = response.request.query

        val compressedResults = operationContext.parallelMap(
            items = response.results,
            maxConcurrency = maxConcurrency,
        ) { result ->
            val chunk = result.match as? Chunk
            if (chunk != null && chunk.text.length > minLengthToCompress) {
                val compressionResult = compressWithQuestionAwareness(
                    content = chunk.text,
                    query = query,
                    targetRatio = targetRatio,
                    preserveEntities = preserveEntities
                )
                if (compressionResult.irrelevant || compressionResult.compressed.isNullOrBlank()) {
                    logger.debug("Discarding irrelevant content")
                    null
                } else {
                    val compressedChunk = chunk.transform(
                        compressionResult.compressed
                        // Add compression metadata
//                    contextualRelevance = ZeroToOne(assessCompressionQuality(compressed, query))
                    )
                    logger.debug("Compressed chunk:\n{}\n----->\n{}", chunk.text, compressedChunk.text)
                    SimpleSimilaritySearchResult(compressedChunk, result.score)
                }
            } else {
                result
            }
        }.filterNotNull()
        logger.info(
            "Eliminated {} irrelevant results from {}",
            response.results.size - compressedResults.size,
            response.results.size,
        )
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
    ): CompressionResult {
        val prompt = """
                Given the query, compress the content to include only what
                is relevant. If you cannot compress, set 'irrelevant' to true.

                <query>
                $query
                </query>

                <content>
                $content
                </content>
            """.trimIndent()
        return operationContext
            .ai()
            .withLlm(llm)
            .withId(name)
            .creating(CompressionResult::class.java)
            .withExample(
                "relevant content", CompressionResult(
                    compressed = "This is the compressed content that is relevant."
                )
            )
            .withExample(
                "irrelevant content", CompressionResult(
                    irrelevant = true,
                )
            )
            .fromPrompt(prompt = prompt)
            .also {
                if (it.irrelevant) {
                    logger.debug(
                        "{}\nContent deemed irrelevant: Query=[{}], Content:\n{}\nPrompt was\n{}\n{}",
                        "*".repeat(140),
                        query,
                        content,
                        prompt,
                        "*".repeat(140)
                    )
                }
            }
    }

}

private data class CompressionResult(
    @get:JsonPropertyDescription("Return only if the content is irrelevant")
    val irrelevant: Boolean = false,
    @get:JsonPropertyDescription("Return only if the content is valid")
    val compressed: String? = null,
)
