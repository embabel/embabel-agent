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
package com.embabel.agent.decision.api

import org.jetbrains.annotations.ApiStatus

@ApiStatus.Experimental enum class DecisionKind { YES_NO, CHOICE, RATING }
@ApiStatus.Experimental enum class EvidenceKind { DISTRIBUTION, VERBALIZED }
@ApiStatus.Experimental enum class RecordMode { NONE, METADATA, FULL }
@ApiStatus.Experimental enum class DecisionSafeCode { DISABLED, UNAVAILABLE, REJECTED_REQUEST, DEADLINE_EXCEEDED, CANCELLED, UNSUPPORTED, MISSING, INVALID }
@ApiStatus.Experimental enum class CallFailure { Disabled, Unavailable, RejectedRequest, DeadlineExceeded, Cancelled, Unsupported }
@ApiStatus.Experimental enum class KeyFailure { Missing, Invalid, Unsupported }

@ApiStatus.Experimental
class DecisionOption<T> private constructor(val id: String, val value: T, val label: String) {
    init {
        require(id.isNotBlank() && id.toByteArray().size <= 256 && id.none { it.isWhitespace() }) { "support id must be a nonblank opaque token" }
        require(label.isNotBlank()) { "label must not be blank" }
        DecisionRequestLimits.string(label, "support label")
    }
    companion object { @JvmStatic fun <T> of(id: String, value: T, label: String) = DecisionOption(id, value, label) }
}

@ApiStatus.Experimental sealed interface DecisionKey<T> { val id: String }
@ApiStatus.Experimental interface YesNoKey : DecisionKey<Boolean>
@ApiStatus.Experimental interface ChoiceKey<T> : DecisionKey<T>
@ApiStatus.Experimental interface RatingKey<T> : DecisionKey<T>
