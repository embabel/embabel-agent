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
package com.embabel.common.ai.model

import org.jetbrains.annotations.ApiStatus
import org.springframework.web.client.RestClientResponseException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeoutException

/**
 * Bounded transport categories for diagnostics. These do not choose a retry policy or a result.
 * Unknown exception types produce `other`; exception messages never become category values.
 * Adapter-specific invalid-response and SDK rate-limit recognition belong to the caller.
 */
@ApiStatus.Experimental
object TransportFailureDiagnostics {
    /**
     * Visits each cause once, from the outer failure inward, using identity to stop on cycles.
     *
     * @param failure the outer failure
     * @return its finite cause chain, including the failure itself
     */
    @JvmStatic
    fun causes(failure: Throwable): List<Throwable> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        return buildList {
            var current: Throwable? = failure
            while (current != null && seen.add(current)) {
                add(current)
                current = current.cause
            }
        }
    }

    /**
     * Finds the first typed Spring HTTP status in the cause chain without interpreting messages.
     *
     * @param failure the failure to inspect
     * @return the status code, or null when the chain has no typed HTTP failure
     */
    @JvmStatic
    fun httpStatus(failure: Throwable): Int? =
        causes(failure).filterIsInstance<RestClientResponseException>().firstOrNull()?.statusCode?.value()

    /**
     * Classifies rate limits, HTTP errors, connection failures and timeouts in that order.
     * A connection timeout precedes the broader HTTP timeout type. Unrecognized failures use `other`.
     *
     * @param failure the failure to inspect
     * @param rateLimited additional rate-limit evidence supplied by a provider adapter
     * @return a fixed category string suitable for diagnostics
     */
    @JvmStatic
    @JvmOverloads
    fun category(failure: Throwable, rateLimited: Boolean = false): String {
        val chain = causes(failure)
        val status = httpStatus(failure)
        return when {
            rateLimited || status == 429 -> "rate_limited"
            status != null && status in 400..499 -> "http_4xx"
            status != null && status in 500..599 -> "http_5xx"
            chain.any { it is HttpConnectTimeoutException } -> "connection"
            chain.any { it is SocketTimeoutException || it is HttpTimeoutException || it is TimeoutException } -> "timeout"
            chain.any { it is ConnectException || it is UnknownHostException || it is NoRouteToHostException } -> "connection"
            else -> "other"
        }
    }
}
