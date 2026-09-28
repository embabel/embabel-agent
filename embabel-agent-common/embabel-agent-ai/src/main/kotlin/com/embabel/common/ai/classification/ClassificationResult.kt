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
package com.embabel.common.ai.classification

import com.embabel.common.ai.decision.rejectUnknownMember
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.jetbrains.annotations.ApiStatus

/**
 * Provider-reported identity of the model that produced an assessment. Optional version and
 * request ID remain absent when unknown. This evidence does not assert calibration or resolve a model.
 * Providers must supply identifiers suitable for the caller to retain, never credentials or payloads.
 *
 * JSON leaves out an absent version or request ID.
 */
@ApiStatus.Experimental
@JsonPropertyOrder("modelName", "provider", "version", "requestId")
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ModelProvenance @JvmOverloads constructor(
    @get:JsonProperty("modelName") val modelName: String,
    @get:JsonProperty("provider") val provider: String,
    @get:JsonProperty("version") val version: String? = null,
    @get:JsonProperty("requestId") val requestId: String? = null,
) {
    init {
        require(modelName.isNotBlank()) { "Model name must not be blank" }
        require(provider.isNotBlank()) { "Provider must not be blank" }
    }

    /**
     * Rejects a JSON member this type doesn't define.
     *
     * @param name the unknown member's name
     * @param value the unknown member's value
     */
    @JsonAnySetter
    private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("ModelProvenance", name, value)

    private companion object {
        /**
         * Builds a provenance from deserialized JSON fields.
         *
         * @param modelName the model's name
         * @param provider the provider's name
         * @param version the model version, or null when absent
         * @param requestId the provider's request ID, or null when absent
         * @return the provenance
         */
        @JvmStatic
        @JsonCreator
        private fun fromJson(
            @JsonProperty("modelName", required = true) modelName: String,
            @JsonProperty("provider", required = true) provider: String,
            @JsonProperty("version") version: String?,
            @JsonProperty("requestId") requestId: String?,
        ): ModelProvenance = ModelProvenance(modelName, provider, version, requestId)
    }
}

/**
 * Operational failure categories. Raw provider messages and throwables are deliberately excluded.
 * JSON writes each one as its lower-case name, such as `invalid_response`.
 */
@ApiStatus.Experimental
enum class FailureReason {
    /** The provider could not perform the assessment. */
    @JsonProperty("unavailable")
    UNAVAILABLE,

    /** The provider response could not be interpreted as a valid assessment. */
    @JsonProperty("invalid_response")
    INVALID_RESPONSE,
}

/**
 * A provider assessment. No match, insufficient evidence, and operational failure are distinct.
 *
 * In JSON a classification result is an object whose `status` member names its class: `selected`,
 * `no_match`, `inconclusive` or `failure`.
 */
@ApiStatus.Experimental
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "status")
@JsonSubTypes(
    JsonSubTypes.Type(ClassificationResult.Selected::class, name = "selected"),
    JsonSubTypes.Type(ClassificationResult.NoMatch::class, name = "no_match"),
    JsonSubTypes.Type(ClassificationResult.Inconclusive::class, name = "inconclusive"),
    JsonSubTypes.Type(ClassificationResult.Failure::class, name = "failure"),
)
sealed interface ClassificationResult {
    /**
     * A canonical category ID with optional provider-reported confidence; no score is inferred.
     * Confidence is finite and in [0,1], without a calibration guarantee. Direct construction cannot
     * check membership; providers should use [ClassificationRequest.selected], and consumers can
     * validate against the request or use [CategoryMapping.map].
     */
    @JsonPropertyOrder("categoryId", "confidence", "provenance")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class Selected @JvmOverloads constructor(
        @get:JsonProperty("categoryId") val categoryId: String,
        @get:JsonProperty("provenance") val provenance: ModelProvenance,
        @get:JsonProperty("confidence") val confidence: Double? = null,
    ) : ClassificationResult {
        init {
            require(categoryId.isNotBlank()) { "Selected category ID must not be blank" }
            require(confidence == null || confidence.isFinite() && confidence in 0.0..1.0) {
                "Confidence must be finite and between 0 and 1"
            }
        }

        /**
         * Rejects a JSON member this type doesn't define.
         *
         * @param name the unknown member's name
         * @param value the unknown member's value
         */
        @JsonAnySetter
        private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("ClassificationResult", name, value)

        private companion object {
            /**
             * Builds a selection from deserialized JSON fields.
             *
             * @param categoryId the selected category ID
             * @param confidence the provider's confidence, or null when absent
             * @param provenance the model that selected it
             * @return the selection
             */
            @JvmStatic
            @JsonCreator
            private fun fromJson(
                @JsonProperty("categoryId", required = true) categoryId: String,
                @JsonProperty("confidence") confidence: Double?,
                @JsonProperty("provenance", required = true) provenance: ModelProvenance,
            ): Selected = Selected(categoryId, provenance, confidence)
        }
    }

    /** A successful judgment that none of the requested categories matches. */
    data class NoMatch @JsonCreator constructor(
        @JsonProperty("provenance", required = true) val provenance: ModelProvenance,
    ) : ClassificationResult, MappedClassificationResult<Nothing> {
        /**
         * Rejects a JSON member this type doesn't define.
         *
         * @param name the unknown member's name
         * @param value the unknown member's value
         */
        @JsonAnySetter
        private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("ClassificationResult", name, value)
    }

    /** The provider could not reach a sufficiently supported judgment. */
    data class Inconclusive @JsonCreator constructor(
        @JsonProperty("provenance", required = true) val provenance: ModelProvenance,
    ) : ClassificationResult, MappedClassificationResult<Nothing> {
        /**
         * Rejects a JSON member this type doesn't define.
         *
         * @param name the unknown member's name
         * @param value the unknown member's value
         */
        @JsonAnySetter
        private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("ClassificationResult", name, value)
    }

    /** Assessment failed operationally; this is not a judgment about the input. */
    data class Failure @JsonCreator constructor(
        @JsonProperty("reason", required = true) val reason: FailureReason,
    ) : ClassificationResult, MappedClassificationResult<Nothing> {
        /**
         * Rejects a JSON member this type doesn't define.
         *
         * @param name the unknown member's name
         * @param value the unknown member's value
         */
        @JsonAnySetter
        private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("ClassificationResult", name, value)
    }
}

/**
 * A classification mapped to a caller-owned value. Non-selection variants are the original
 * [ClassificationResult.NoMatch], [ClassificationResult.Inconclusive], and [ClassificationResult.Failure].
 */
@ApiStatus.Experimental
sealed interface MappedClassificationResult<out T : Any> {
    /** The caller-owned value and the unmodified provider evidence that selected it. */
    data class Selected<T : Any>(val value: T, val selection: ClassificationResult.Selected) : MappedClassificationResult<T>
}
