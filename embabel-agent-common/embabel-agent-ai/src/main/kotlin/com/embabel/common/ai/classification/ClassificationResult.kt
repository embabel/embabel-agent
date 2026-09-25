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

import org.jetbrains.annotations.ApiStatus

/**
 * Provider-reported identity of the model that produced an assessment. Optional version and
 * request ID remain absent when unknown. This evidence does not assert calibration or resolve a model.
 * Providers must supply identifiers suitable for the caller to retain, never credentials or payloads.
 */
@ApiStatus.Experimental
data class ModelProvenance @JvmOverloads constructor(
    val modelName: String,
    val provider: String,
    val version: String? = null,
    val requestId: String? = null,
) {
    init {
        require(modelName.isNotBlank()) { "Model name must not be blank" }
        require(provider.isNotBlank()) { "Provider must not be blank" }
    }
}

/** Operational failure categories. Raw provider messages and throwables are deliberately excluded. */
@ApiStatus.Experimental
enum class FailureReason {
    /** The provider could not perform the assessment. */
    UNAVAILABLE,
    /** The provider response could not be interpreted as a valid assessment. */
    INVALID_RESPONSE,
}

/** A provider assessment. No match, insufficient evidence, and operational failure are distinct. */
@ApiStatus.Experimental
sealed interface ClassificationResult {
    /**
     * A canonical category ID with optional provider-reported confidence; no score is inferred.
     * Confidence is finite and in [0,1], without a calibration guarantee. Direct construction cannot
     * check membership; providers should use [ClassificationRequest.selected], and consumers can
     * validate against the request or use [CategoryMapping.map].
     */
    data class Selected @JvmOverloads constructor(
        val categoryId: String,
        val provenance: ModelProvenance,
        val confidence: Double? = null,
    ) : ClassificationResult {
        init {
            require(categoryId.isNotBlank()) { "Selected category ID must not be blank" }
            require(confidence == null || confidence.isFinite() && confidence in 0.0..1.0) {
                "Confidence must be finite and between 0 and 1"
            }
        }
    }

    /** A successful judgment that none of the requested categories matches. */
    data class NoMatch(val provenance: ModelProvenance) : ClassificationResult, MappedClassificationResult<Nothing>

    /** The provider could not reach a sufficiently supported judgment. */
    data class Inconclusive(val provenance: ModelProvenance) : ClassificationResult, MappedClassificationResult<Nothing>

    /** Assessment failed operationally; this is not a judgment about the input. */
    data class Failure(val reason: FailureReason) : ClassificationResult, MappedClassificationResult<Nothing>
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
