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
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.jetbrains.annotations.ApiStatus
import kotlin.math.abs

/**
 * One level on an ordinal rating scale, such as one point on a five-point mood scale.
 * The id identifies the level on the wire and in provider responses. The description
 * defaults to the id, so a caller can name a level once and use that name as both its
 * identity and its label.
 */
@ApiStatus.Experimental
@JsonPropertyOrder("id", "description")
data class RatingLevel @JvmOverloads constructor(val id: String, val description: String = id) {
    init {
        require(id.isNotBlank()) { "Rating level id must not be blank" }
    }

    /**
     * Rejects a JSON member this type doesn't define.
     *
     * @param name the unknown member's name
     * @param value the unknown member's value
     */
    @JsonAnySetter
    private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("RatingLevel", name, value)

    private companion object {
        /**
         * Builds a rating level from deserialized JSON fields. JSON always carries both members.
         *
         * @param id the level id
         * @param description what the level means
         * @return the level
         */
        @JvmStatic
        @JsonCreator
        private fun fromJson(
            @JsonProperty("id", required = true) id: String,
            @JsonProperty("description", required = true) description: String,
        ): RatingLevel = RatingLevel(id, description)
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
    @JsonProperty("expected_level_index")
    EXPECTED_LEVEL_INDEX,
}

/**
 * A single numeric score reported for a rating, together with the statistic it represents.
 * The value is finite and at least zero.
 */
@ApiStatus.Experimental
@JsonPropertyOrder("value", "statistic")
data class RatingScore @JsonCreator constructor(
    @JsonProperty("value", required = true) val value: Double,
    @JsonProperty("statistic", required = true) val statistic: RatingStatistic,
) {
    init {
        require(value.isFinite() && value >= 0.0) { "Rating score value must be finite and at least 0" }
    }

    /**
     * Rejects a JSON member this type doesn't define.
     *
     * @param name the unknown member's name
     * @param value the unknown member's value
     */
    @JsonAnySetter
    private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("RatingScore", name, value)
}

/**
 * The probability a provider reported for one level of a rating scale. The id is nonblank
 * and the probability is finite and between 0 and 1 inclusive.
 */
@ApiStatus.Experimental
@JsonPropertyOrder("levelId", "probability")
data class LevelProbability @JsonCreator constructor(
    @JsonProperty("levelId", required = true) val levelId: String,
    @JsonProperty("probability", required = true) val probability: Double,
) {
    init {
        require(levelId.isNotBlank()) { "Level id must not be blank" }
        require(probability.isFinite() && probability in 0.0..1.0) {
            "Probability must be finite and between 0 and 1"
        }
    }

    /**
     * Rejects a JSON member this type doesn't define.
     *
     * @param name the unknown member's name
     * @param value the unknown member's value
     */
    @JsonAnySetter
    private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("LevelProbability", name, value)
}

/**
 * The evidence a provider returned for a rating question: a selected level, a per-level
 * distribution, a score, or any combination of these. Each part comes from the provider's
 * response as reported. A selected level is present only when the provider selected one,
 * and a distribution only when the provider reported one. Validation against a
 * `RatingQuestionSpec` checks that a selected level and a distribution belong to the
 * question's scale.
 *
 * In JSON a rating result is an object whose `status` member names its class: `answered`,
 * `inconclusive` or `failure`.
 */
@ApiStatus.Experimental
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "status")
@JsonSubTypes(
    JsonSubTypes.Type(RatingResult.Answered::class, name = "answered"),
    JsonSubTypes.Type(RatingResult.Inconclusive::class, name = "inconclusive"),
    JsonSubTypes.Type(RatingResult.Failure::class, name = "failure"),
)
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
    @JsonPropertyOrder("selectedLevelId", "distribution", "score", "confidence", "provenance")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    class Answered @JvmOverloads constructor(
        @get:JsonIgnore val provenance: ModelProvenance,
        @get:JsonProperty("selectedLevelId") val selectedLevelId: String? = null,
        distribution: List<LevelProbability> = emptyList(),
        @get:JsonProperty("score") val score: RatingScore? = null,
        @get:JsonProperty("confidence") val confidence: Double? = null,
    ) : RatingResult {

        /**
         * The per-level probabilities the provider reported, in the order given. The list is
         * an unmodifiable copy, so changes to the list the caller passed in do not appear here.
         * JSON leaves out an empty distribution.
         */
        @get:JsonProperty("distribution")
        @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
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

        /**
         * Converts the provenance to its JSON form.
         *
         * @return the provenance as JSON
         */
        @JsonProperty("provenance")
        private fun provenanceJson(): ProvenanceJson = ProvenanceJson(provenance)

        /**
         * Rejects a JSON member this type doesn't define.
         *
         * @param name the unknown member's name
         * @param value the unknown member's value
         */
        @JsonAnySetter
        private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("RatingResult", name, value)

        private companion object {
            /**
             * Builds an answered rating from deserialized JSON fields. An explicit null
             * distribution reads the same as an absent one.
             *
             * @param provenance the model that answered, as read from JSON
             * @param selectedLevelId the level the provider selected, when it selects one
             * @param distribution the per-level probabilities, or null when absent
             * @param score the provider's score and the statistic it represents, when reported
             * @param confidence the provider's concentration measure, when reported
             * @return the answered rating
             */
            @JvmStatic
            @JsonCreator
            private fun fromJson(
                @JsonProperty("provenance", required = true) provenance: ProvenanceJson,
                @JsonProperty("selectedLevelId") selectedLevelId: String?,
                @JsonProperty("distribution") distribution: List<LevelProbability>?,
                @JsonProperty("score") score: RatingScore?,
                @JsonProperty("confidence") confidence: Double?,
            ): Answered = Answered(provenance.toProvenance(), selectedLevelId, distribution ?: emptyList(), score, confidence)
        }
    }

    /**
     * Insufficient evidence to answer the rating.
     */
    data class Inconclusive(@get:JsonIgnore val provenance: ModelProvenance) : RatingResult {

        /**
         * Converts the provenance to its JSON form.
         *
         * @return the provenance as JSON
         */
        @JsonProperty("provenance")
        private fun provenanceJson(): ProvenanceJson = ProvenanceJson(provenance)

        /**
         * Rejects a JSON member this type doesn't define.
         *
         * @param name the unknown member's name
         * @param value the unknown member's value
         */
        @JsonAnySetter
        private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("RatingResult", name, value)

        private companion object {
            /**
             * Builds an inconclusive rating from a deserialized JSON field.
             *
             * @param provenance the model that answered, as read from JSON
             * @return the inconclusive rating
             */
            @JvmStatic
            @JsonCreator
            private fun fromJson(@JsonProperty("provenance", required = true) provenance: ProvenanceJson): Inconclusive =
                Inconclusive(provenance.toProvenance())
        }
    }

    /**
     * An operational failure with no raw provider error or throwable retained.
     */
    data class Failure(@get:JsonIgnore val reason: FailureReason) : RatingResult {

        /**
         * Converts the reason to its JSON form.
         *
         * @return the reason as JSON
         */
        @JsonProperty("reason")
        private fun reasonJson(): FailureReasonJson = FailureReasonJson.of(reason)

        /**
         * Rejects a JSON member this type doesn't define.
         *
         * @param name the unknown member's name
         * @param value the unknown member's value
         */
        @JsonAnySetter
        private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("RatingResult", name, value)

        private companion object {
            /**
             * Builds a failure rating from a deserialized JSON field.
             *
             * @param reason why the request failed, as read from JSON
             * @return the failure rating
             */
            @JvmStatic
            @JsonCreator
            private fun fromJson(@JsonProperty("reason", required = true) reason: FailureReasonJson): Failure =
                Failure(reason.reason)
        }
    }
}
