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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClientResponseException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException
import java.util.concurrent.TimeoutException

class TransportFailureDiagnosticsTest {
    private fun http(status: Int) = RestClientResponseException("private provider text", status, "status", null, null, null)

    @Test
    fun `categories cover direct and wrapped failures with an other fallback`() {
        val cases = listOf(
            http(429) to "rate_limited", http(400) to "http_4xx", http(503) to "http_5xx",
            HttpConnectTimeoutException("private") to "connection", HttpTimeoutException("private") to "timeout",
            SocketTimeoutException("private") to "timeout", TimeoutException("private") to "timeout",
            ConnectException("private") to "connection", UnknownHostException("private") to "connection",
            NoRouteToHostException("private") to "connection", IllegalStateException("private") to "other",
        )
        for ((failure, expected) in cases) {
            assertEquals(expected, TransportFailureDiagnostics.category(failure))
            assertEquals(expected, TransportFailureDiagnostics.category(RuntimeException("wrapper", failure)))
        }
    }

    @Test
    fun `HTTP and explicit rate limit evidence precede transport categories`() {
        val failure = http(503).apply { initCause(SocketTimeoutException()) }
        assertEquals(503, TransportFailureDiagnostics.httpStatus(RuntimeException(failure)))
        assertEquals("http_5xx", TransportFailureDiagnostics.category(failure))
        assertEquals("rate_limited", TransportFailureDiagnostics.category(failure, rateLimited = true))
        assertEquals(null, TransportFailureDiagnostics.httpStatus(IllegalStateException("HTTP 503 private text")))
    }

    @Test
    fun `cause traversal terminates on a cycle`() {
        val first = RuntimeException("first")
        val second = RuntimeException("second", first)
        first.initCause(second)
        assertEquals(listOf(first, second), TransportFailureDiagnostics.causes(first))
        assertEquals("other", TransportFailureDiagnostics.category(first))
        assertEquals(null, TransportFailureDiagnostics.httpStatus(first))
    }
}
