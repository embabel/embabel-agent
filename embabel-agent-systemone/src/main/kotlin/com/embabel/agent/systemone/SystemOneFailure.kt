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
package com.embabel.agent.systemone

import com.embabel.common.ai.classification.FailureReason

/**
 * A failed System One call, reduced to fields that are safe to log. It never keeps the
 * underlying exception, because transport errors can quote the endpoint and bodies can echo the input.
 *
 * @property reason how the failure is reported to the caller
 * @property category a fixed name for what went wrong, such as `timeout` or `http_5xx`
 * @property status the HTTP status, when there was a response
 * @property requestId the provider's request id, when the response carried a well formed one
 */
internal class SystemOneFailure(
    val reason: FailureReason,
    val category: String,
    val status: Int? = null,
    val requestId: String? = null,
) : RuntimeException("System One call failed: $category", null, false, false) {

    /** The status class, such as `5xx`, or `none` without a response. */
    val statusFamily: String get() = family(status)

    companion object {
        const val CREDENTIAL = "credential"
        const val TIMEOUT = "timeout"
        const val CONNECTION = "connection"
        const val INVALID_RESPONSE = "invalid_response"
        const val OTHER = "other"

        fun family(status: Int?): String = status?.let { "${it / 100}xx" } ?: "none"

        fun forStatus(status: Int, requestId: String?): SystemOneFailure {
            val category = when (status) {
                429 -> "rate_limited"
                in 400..499 -> "http_4xx"
                in 500..599 -> "http_5xx"
                else -> OTHER
            }
            return SystemOneFailure(FailureReason.UNAVAILABLE, category, status, requestId)
        }

        fun invalidResponse(status: Int?, requestId: String?) =
            SystemOneFailure(FailureReason.INVALID_RESPONSE, INVALID_RESPONSE, status, requestId)

        fun unavailable(category: String) = SystemOneFailure(FailureReason.UNAVAILABLE, category)
    }
}
