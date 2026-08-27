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
package com.embabel.agent.rag.tools

import com.embabel.common.core.types.ZeroToOne

/**
 * Similarity thresholds applied when the LLM does not supply one.
 *
 * The `threshold` parameter on [VectorSearchTools] and [TextSearchTools] is optional:
 * an absolute similarity cutoff is a hyperparameter the model has no feedback signal
 * for, and cosine scores are not calibrated across embedding models — 0.7 against one
 * model is not 0.7 against another. A model guessing high silently turns retrieval off
 * and reports "the documents don't cover it".
 *
 * `topK` remains the real limiter. These thresholds are a noise floor, not a relevance
 * judgement, and are deliberately low. Deployments that HAVE calibrated a cutoff against
 * their own eval set should raise them here rather than relying on the LLM to pass one.
 *
 * @param vectorSimilarityThreshold floor for vector search when the LLM omits `threshold`
 * @param textSimilarityThreshold floor for text search when the LLM omits `threshold`.
 * Defaults to 0.0 — BM25 scores are corpus-relative, so rank (topK) is the meaningful
 * cutoff and any absolute floor is arbitrary.
 */
data class SearchDefaults @JvmOverloads constructor(
    val vectorSimilarityThreshold: ZeroToOne = DEFAULT_VECTOR_SIMILARITY_THRESHOLD,
    val textSimilarityThreshold: ZeroToOne = DEFAULT_TEXT_SIMILARITY_THRESHOLD,
    val expandNeighbours: Int = DEFAULT_EXPAND_NEIGHBOURS,
) {

    init {
        require(vectorSimilarityThreshold in 0.0..1.0) {
            "vectorSimilarityThreshold must be in 0.0..1.0, was $vectorSimilarityThreshold"
        }
        require(textSimilarityThreshold in 0.0..1.0) {
            "textSimilarityThreshold must be in 0.0..1.0, was $textSimilarityThreshold"
        }
        require(expandNeighbours >= 0) {
            "expandNeighbours must be >= 0, was $expandNeighbours"
        }
    }

    companion object {

        const val DEFAULT_VECTOR_SIMILARITY_THRESHOLD: ZeroToOne = 0.25

        const val DEFAULT_TEXT_SIMILARITY_THRESHOLD: ZeroToOne = 0.0

        /**
         * Neighbouring chunks fetched around every search hit, automatically.
         *
         * ZERO by default: today's behaviour, where context is only ever widened if the model
         * thinks to call `broadenChunk` itself. That is a lot to expect — measured on a
         * 69-page statute, recovering a severed provision needed `chunksToAdd` of about 12
         * against a tool default of 2, and the model has no way to know that.
         *
         * The failure this addresses is structural rather than a ranking problem: a chunker
         * splitting an oversized leaf can separate a provision from the sentence that governs
         * it, so the retrieved chunk carries "(i) investments ..." and not what the list is
         * FOR. Widening chunks at INGESTION was measured and is worse (30.67 against a 33
         * baseline) because it dilutes every embedding and makes siblings alike. Expanding
         * AFTER the match leaves embeddings untouched and pays only for what actually hit.
         *
         * Not free, and off by default for that reason: every hit costs a store round trip
         * and enlarges what the model reads, and irrelevant passages actively degrade
         * generation. Raise it where the corpus is continuous prose whose meaning runs across
         * chunk boundaries; leave it at 0 for corpora of self-contained records.
         */
        const val DEFAULT_EXPAND_NEIGHBOURS: Int = 0

        @JvmField
        val DEFAULT: SearchDefaults = SearchDefaults()
    }
}
