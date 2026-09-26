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
import java.util.Collections

private const val MAX_RECORD_BYTES = 1_048_576

@ApiStatus.Experimental
class DecisionRecordPolicy private constructor(val mode: RecordMode, val maxBytes: Int?, val allowlist: Set<String>) {
    companion object {
        private val FULL_RECORD_FIELDS = setOf("answerIds")
        @JvmStatic fun none() = DecisionRecordPolicy(RecordMode.NONE, null, emptySet())
        @JvmStatic fun metadata() = DecisionRecordPolicy(RecordMode.METADATA, null, emptySet())
        @JvmStatic fun full(maxBytes: Int, allowlist: Set<String>): DecisionRecordPolicy {
            require(maxBytes in 2..MAX_RECORD_BYTES) { "maxBytes must be between 2 and $MAX_RECORD_BYTES" }
            require(allowlist.isNotEmpty()) { "full records require an explicit allowlist" }
            val copied = allowlist.map { require(it.isNotBlank() && '*' !in it) { "allowlist entries must be explicit" }; it }.toSet()
            require(copied.all { it in FULL_RECORD_FIELDS }) { "unsupported record field in allowlist" }
            return DecisionRecordPolicy(RecordMode.FULL, maxBytes, Collections.unmodifiableSet(copied))
        }
    }
}

@ApiStatus.Experimental interface DecisionRecord { val mode: RecordMode; val fields: Map<String, String> }
