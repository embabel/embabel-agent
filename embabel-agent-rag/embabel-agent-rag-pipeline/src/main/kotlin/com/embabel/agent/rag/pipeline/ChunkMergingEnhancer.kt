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

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.ChunkStructure
import com.embabel.agent.rag.model.Retrievable
import com.embabel.agent.rag.service.*
import com.embabel.common.core.types.SimilarityResult
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Merge adjacent chunks from the same root document
 */
object ChunkMergingEnhancer : RagResponseEnhancer {

    var logger: Logger = LoggerFactory.getLogger(javaClass)

    override val name: String = "chunk-merge"

    override val enhancementType: EnhancementType
        get() = EnhancementType.CONTENT_SYNTHESIS

    override fun enhance(response: RagResponse): RagResponse {
        val mergedResults = mutableListOf<SimilarityResult<out Retrievable>>()
        val chunksToMerge = mutableListOf<SimilarityResult<out Retrievable>>()

        for (result in response.results) {
            if (chunksToMerge.isEmpty()) {
                chunksToMerge.add(result)
            } else {
                val lastResult = chunksToMerge.last()
                if (canMerge(lastResult, result)) {
                    chunksToMerge.add(result)
                } else {
                    mergedResults.add(mergeChunks(chunksToMerge))
                    chunksToMerge.clear()
                    chunksToMerge.add(result)
                }
            }
        }
        if (chunksToMerge.isNotEmpty()) {
            mergedResults.add(mergeChunks(chunksToMerge))
        }

        return if (mergedResults.size == response.results.size) {
            response
        } else {
            response.copy(results = mergedResults)
        }
    }

    private fun canMerge(
        first: SimilarityResult<out Retrievable>,
        second: SimilarityResult<out Retrievable>,
    ): Boolean {
        val firstRootId = rootDocumentId(first.match) ?: return false
        val secondRootId = rootDocumentId(second.match) ?: return false
        val firstSeq = sequenceNumber(first.match) ?: return false
        val secondSeq = sequenceNumber(second.match) ?: return false

        return firstRootId == secondRootId && secondSeq == firstSeq + 1
    }

    private fun rootDocumentId(match: Retrievable): String? =
        (match as? Chunk)?.structure?.rootDocumentId
            ?: match.metadata[ChunkStructure.ROOT_DOCUMENT_ID] as? String

    private fun sequenceNumber(match: Retrievable): Int? {
        (match as? Chunk)?.structure?.sequenceNumber?.let { return it }
        return when (val value = match.metadata[ChunkStructure.SEQUENCE_NUMBER]) {
            is Int -> value
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }

    private fun mergeChunks(
        chunks: List<SimilarityResult<out Retrievable>>,
    ): SimilarityResult<out Retrievable> {
        if (chunks.size == 1) {
            return chunks[0]
        }

        logger.info("Merging {} chunks from document {}", chunks.size, rootDocumentId(chunks[0].match))

        val firstChunk = chunks[0].match as Chunk
        val mergedText = chunks.joinToString(" ") { (it.match as Chunk).text }
        val highestScore = chunks.maxOf { it.score }

        val mergedChunk = Chunk(
            id = "${firstChunk.id}-merged",
            text = mergedText,
            metadata = firstChunk.metadata,
            parentId = firstChunk.parentId
        )

        return object : SimilarityResult<Chunk> {
            override val match: Chunk = mergedChunk
            override val score: Double = highestScore
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
