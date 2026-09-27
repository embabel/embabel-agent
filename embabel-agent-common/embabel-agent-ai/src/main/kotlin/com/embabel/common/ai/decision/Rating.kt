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
package com.embabel.common.ai.decision

import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import org.jetbrains.annotations.ApiStatus
import kotlin.math.abs

/**
 * One level on an ordinal rating scale, such as one point on a five-point mood scale.
 * The id identifies the level on the wire and in provider responses. The description
 * defaults to the id, so a caller can name a level once and use that name as both its
 * identity and its label.
 */
@ApiStatus.Experimental
data class RatingLevel @JvmOverloads constructor(val id: String, val description: String = id) {
    init {
        require(id.isNotBlank()) { "Rating level id must not be blank" }
    }
}

/**
 * The statistic that a [RatingScore] reports.
 */
@ApiStatus.Experimental
enum class RatingStatistic {
    /**
     * The probability-weighted mean of the zero-based positions of the scale's levels.
     * It is one continuous number over the level range. The probability of each level is
     * reported separately, in the distribution.
     */
    EXPECTED_LEVEL_INDEX,
}

/**
 * A single numeric score reported for a rating, together with the statistic it represents.
 * The value is finite and at least zero.
 */
@ApiStatus.Experimental
data class RatingScore(val value: Double, val statistic: RatingStatistic) {
    init {
        require(value.isFinite() && value >= 0.0) { "Rating score value must be finite and at least 0" }
    }
}

/**
 * The probability a provider reported for one level of a rating scale. The id is nonblank
 * and the probability is finite and between 0 and 1 inclusive.
 */
@ApiStatus.Experimental
data class LevelProbability(val levelId: String, val probability: Double) {
    init {
        require(levelId.isNotBlank()) { "Level id must not be blank" }
        require(probability.isFinite() && probability in 0.0..1.0) {
            "Probability must be finite and between 0 and 1"
        }
    }
}

/**
 * The evidence a provider returned for a rating question: a selected level, a per-level
 * distribution, a score, or any combination of these. Each part comes from the provider's
 * response as reported. A selected level is present only when the provider selected one,
 * and a distribution only when the provider reported one. Validation against a
 * `RatingQuestionSpec` checks that a selected level and a distribution belong to the
 * question's scale.
 */
@ApiStatus.Experimental
sealed interface RatingResult {

    /**
     * A rating the provider answered with at least one piece of evidence: a selected level id,
     * a distribution over levels, a score, or a combination of these. Confidence is the
     * provider's own measure of how concentrated its reported distribution is. It is absent
     * unless the provider actually reports it.
     *
     * @property provenance the model that answered
     * @property selectedLevelId the level the provider selected, when it selects one
     * @property score the provider's score and the statistic it represents, when reported
     * @property confidence the provider's concentration measure, in 0..1, when reported
     */
    class Answered @JvmOverloads constructor(
        val provenance: ModelProvenance,
        val selectedLevelId: String? = null,
        distribution: List<LevelProbability> = emptyList(),
        val score: RatingScore? = null,
        val confidence: Double? = null,
    ) : RatingResult {

        /**
         * The per-level probabilities the provider reported, in the order given. The list is
         * an unmodifiable copy, so changes to the list the caller passed in do not appear here.
         */
        val distribution: List<LevelProbability> = java.util.List.copyOf(distribution)

        init {
            require(selectedLevelId != null || this.distribution.isNotEmpty() || score != null) {
                "Answered rating must report a selected level, a distribution or a score"
            }
            require(selectedLevelId == null || selectedLevelId.isNotBlank()) {
                "Selected level id must not be blank"
            }
            val levelIds = this.distribution.map { it.levelId }
            require(levelIds.size == levelIds.toSet().size) {
                "Distribution level ids must be unique"
            }
            if (this.distribution.isNotEmpty()) {
                val sum = this.distribution.sumOf { it.probability }
                require(abs(sum - 1.0) <= 1e-6) {
                    "Distribution probabilities must sum to 1 within 1e-6, but summed to $sum"
                }
            }
            require(confidence == null || confidence.isFinite() && confidence in 0.0..1.0) {
                "Confidence must be finite and between 0 and 1"
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Answered) return false
            return provenance == other.provenance &&
                selectedLevelId == other.selectedLevelId &&
                distribution == other.distribution &&
                score == other.score &&
                confidence == other.confidence
        }

        override fun hashCode(): Int =
            java.util.Objects.hash(provenance, selectedLevelId, distribution, score, confidence)

        override fun toString(): String =
            "RatingResult.Answered(provenance=$provenance, selectedLevelId=$selectedLevelId, " +
                "distribution=$distribution, score=$score, confidence=$confidence)"
    }

    /**
     * Insufficient evidence to answer the rating.
     */
    data class Inconclusive(val provenance: ModelProvenance) : RatingResult

    /**
     * An operational failure with no raw provider error or throwable retained.
     */
    data class Failure(val reason: FailureReason) : RatingResult
}
