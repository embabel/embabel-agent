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

/** Text and a proposition to assess against that text; neither implies extraction of an object. */
@ApiStatus.Experimental
data class PropositionRequest(val input: String, val proposition: String)

/** A proposition assessment keeps false answers, insufficient evidence, and failures distinct. */
@ApiStatus.Experimental
sealed interface PropositionResult {
    /**
     * A supported Boolean answer. Optional pTrue is the provider-reported probability that the
     * proposition is true, including for false answers. It is finite in [0,1], never inferred,
     * and does not imply calibration or an application decision threshold.
     */
    data class Answered @JvmOverloads constructor(
        val answer: Boolean,
        val provenance: ModelProvenance,
        val pTrue: Double? = null,
    ) : PropositionResult {
        init {
            require(pTrue == null || pTrue.isFinite() && pTrue in 0.0..1.0) {
                "Probability of truth must be finite and between 0 and 1"
            }
        }
    }

    /** Insufficient evidence to answer the proposition. */
    data class Inconclusive(val provenance: ModelProvenance) : PropositionResult

    /** An operational failure with no raw provider error or throwable retained. */
    data class Failure(val reason: FailureReason) : PropositionResult
}
