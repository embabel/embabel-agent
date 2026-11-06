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
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.jetbrains.annotations.ApiStatus

/**
 * Text and a nonblank proposition to assess against it. Input may be empty.
 * The default string representation omits both text fields.
 */
@ApiStatus.Experimental
class PropositionRequest(val input: String, val proposition: String) {
    init {
        require(proposition.isNotBlank()) { "Proposition must not be blank" }
    }
}

/**
 * A proposition assessment keeps false answers, insufficient evidence, and failures distinct.
 *
 * In JSON a proposition result is an object whose `status` member names its class: `answered`,
 * `inconclusive` or `failure`.
 */
@ApiStatus.Experimental
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "status")
@JsonSubTypes(
    JsonSubTypes.Type(PropositionResult.Answered::class, name = "answered"),
    JsonSubTypes.Type(PropositionResult.Inconclusive::class, name = "inconclusive"),
    JsonSubTypes.Type(PropositionResult.Failure::class, name = "failure"),
)
sealed interface PropositionResult {
    /**
     * A supported Boolean answer. Optional pTrue is the provider-reported probability that the
     * proposition is true, including for false answers. It is finite in [0,1], never inferred,
     * and does not imply calibration or an application decision threshold.
     */
    @JsonPropertyOrder("answer", "pTrue", "provenance")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class Answered @JvmOverloads constructor(
        @get:JsonProperty("answer") val answer: Boolean,
        @get:JsonProperty("provenance") val provenance: ModelProvenance,
        @get:JsonProperty("pTrue") val pTrue: Double? = null,
    ) : PropositionResult {
        init {
            require(pTrue == null || pTrue.isFinite() && pTrue in 0.0..1.0) {
                "Probability of truth must be finite and between 0 and 1"
            }
        }

        /**
         * Rejects a JSON member this type doesn't define.
         *
         * @param name the unknown member's name
         * @param value the unknown member's value
         */
        @JsonAnySetter
        private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("PropositionResult", name, value)

        private companion object {
            /**
             * Builds an answer from deserialized JSON fields.
             *
             * @param answer whether the proposition holds
             * @param pTrue the provider's probability that the proposition is true, or null when absent
             * @param provenance the model that answered
             * @return the answer
             */
            @JvmStatic
            @JsonCreator
            private fun fromJson(
                @JsonProperty("answer", required = true) answer: Boolean,
                @JsonProperty("pTrue") pTrue: Double?,
                @JsonProperty("provenance", required = true) provenance: ModelProvenance,
            ): Answered = Answered(answer, provenance, pTrue)
        }
    }

    /** Insufficient evidence to answer the proposition. */
    data class Inconclusive @JsonCreator constructor(
        @JsonProperty("provenance", required = true) val provenance: ModelProvenance,
    ) : PropositionResult {
        /**
         * Rejects a JSON member this type doesn't define.
         *
         * @param name the unknown member's name
         * @param value the unknown member's value
         */
        @JsonAnySetter
        private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("PropositionResult", name, value)
    }

    /** An operational failure with no raw provider error or throwable retained. */
    data class Failure @JsonCreator constructor(
        @JsonProperty("reason", required = true) val reason: FailureReason,
    ) : PropositionResult {
        /**
         * Rejects a JSON member this type doesn't define.
         *
         * @param name the unknown member's name
         * @param value the unknown member's value
         */
        @JsonAnySetter
        private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("PropositionResult", name, value)
    }
}
