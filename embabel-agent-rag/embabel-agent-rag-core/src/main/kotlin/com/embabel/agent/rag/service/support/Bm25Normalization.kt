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
package com.embabel.agent.rag.service.support

import com.embabel.common.core.types.ZeroToOne
import org.slf4j.Logger

/**
 * Maps an unbounded BM25 relevance score onto `[0, 1)` **without reference to the
 * other results in the same response**.
 *
 * ## Why not divide by the best score in the result set
 *
 * The obvious normalization — `score / maxScore` — is broken in three ways, and all
 * three bit us:
 *
 * 1. **The top hit always scores 1.0**, however bad it is. A similarity threshold can
 *    therefore never exclude a garbage best-match, which is exactly when you want it to.
 * 2. **The threshold changes meaning per query.** The same document scores differently
 *    depending on what else happened to match, so `threshold = 0.5` is not a stable
 *    contract — it means "half as good as today's best hit".
 * 3. **Scores are not comparable across calls**, so results from two searches cannot be
 *    merged, ranked together, or deduplicated by score in any meaningful way.
 *
 * ## What this does instead
 *
 * A saturating transform `score / (score + k)`. It is strictly monotonic (so ranking is
 * preserved exactly), bounded in `[0, 1)` across any magnitude BM25 produces, and depends
 * only on the document's own score. (Only past ~1e16, where `score + k == score` in double
 * arithmetic, does the ratio round to 1.0 — far outside any real relevance score.)
 * `k` is the score at which a document maps to 0.5 — a soft "this is a decent match"
 * midpoint. Raise it if your corpus produces large raw scores and everything saturates
 * near 1.0; lower it if scores cluster near 0.
 *
 * Absolute BM25 magnitudes still depend on corpus statistics, so the output is a
 * *stable, comparable* score rather than a calibrated probability of relevance. Rank —
 * and topK — remain the meaningful selector; see
 * [com.embabel.agent.rag.tools.SearchDefaults].
 */
object Bm25Normalization {

    /**
     * Raw BM25 score that maps to 0.5. Chosen as a reasonable midpoint for typical
     * Lucene/Neo4j full-text scores; tune per store if scores cluster or saturate.
     */
    const val DEFAULT_K: Double = 3.0

    /**
     * @param rawScore the unbounded BM25 score. Negative values are treated as 0.
     * @param k the score that maps to 0.5. Must be positive.
     */
    @JvmStatic
    @JvmOverloads
    fun normalize(
        rawScore: Double,
        k: Double = DEFAULT_K,
    ): ZeroToOne {
        require(k > 0.0) { "k must be positive, was $k" }
        val score = rawScore.coerceAtLeast(0.0)
        return score / (score + k)
    }

    /**
     * A threshold above this requires a raw score above [DEFAULT_K] — already a strong
     * match. Empty results above it are far more likely a mis-set threshold than an
     * empty corpus.
     */
    const val SUPPRESSION_WARNING_THRESHOLD: ZeroToOne = 0.5

    /**
     * Explain an empty full-text result set that the caller's threshold most likely caused.
     *
     * Before scores were bounded, an out-of-range threshold was silently inert: raw BM25
     * scores are unbounded, so any `0..1` floor admitted everything. Callers carrying a
     * cosine-calibrated threshold (notably [com.embabel.agent.rag.service.RagRequest]'s
     * 0.8 default) now get zero rows instead, with nothing to indicate why. This says why.
     */
    @JvmStatic
    fun warnIfThresholdSuppressedResults(
        logger: Logger,
        query: String,
        threshold: ZeroToOne,
        resultCount: Int,
    ) {
        if (resultCount == 0 && threshold > SUPPRESSION_WARNING_THRESHOLD) {
            logger.warn(
                """
                Full-text search for '{}' returned no results with similarityThreshold={}.
                Full-text scores are normalized to [0, 1) as score/(score+{}), so a threshold this high
                demands a very strong raw match — thresholds calibrated for cosine similarity do not
                transfer. Lower or omit the threshold and let topK rank; see SearchDefaults.
                """.trimIndent(),
                query, threshold, DEFAULT_K,
            )
        }
    }
}
