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
package com.embabel.agent.decision.typesafe

import com.embabel.agent.decision.CallFailure
import com.embabel.agent.decision.DecisionOutcome
import com.embabel.agent.decision.DecisionRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

class JevDeadlineTest {
    @Test
    fun `retries 429 once and resolves the credential only once`() {
        CaptureServer.sequence(CaptureServer.Reply(429, "{}"), CaptureServer.Reply(200, success())).use { server ->
            var credentialCalls = 0
            val model = TypeSafeDecisionModel.create(Supplier { credentialCalls++; "synthetic-bearer" }, "requested-test", URI.create(server.baseUri))
            val result = model.ask(request())
            assertThat(result).isInstanceOf(DecisionOutcome.Success::class.java)
            assertThat(server.requestCount).isEqualTo(2)
            assertThat(credentialCalls).isEqualTo(1)
        }
    }

    @Test
    fun `retries 529 once and never makes a third request`() {
        CaptureServer.sequence(CaptureServer.Reply(529, "{}"), CaptureServer.Reply(429, "{}"), CaptureServer.Reply(200, success())).use { server ->
            val result = TypeSafeDecisionModel.create(Supplier { "synthetic-bearer" }, "requested-test", URI.create(server.baseUri)).ask(request()) as DecisionOutcome.Failure
            assertThat(result.failure).isEqualTo(CallFailure.Unavailable)
            assertThat(server.requestCount).isEqualTo(2)
        }
    }

    @Test
    fun `never follows a redirect or sends credentials to its target`() {
        CaptureServer.replying("{}", 302, mapOf("Location" to "http://127.0.0.1:1/steal")).use { server ->
            val result = TypeSafeDecisionModel.create(Supplier { "synthetic-bearer" }, "requested-test", URI.create(server.baseUri)).ask(request()) as DecisionOutcome.Failure
            assertThat(result.failure).isEqualTo(CallFailure.Unavailable)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun `does not send when the facade deadline is already exhausted`() {
        CaptureServer.replying(success()).use { server ->
            val result = TypeSafeDecisionModel.create(Supplier { "synthetic-bearer" }, "requested-test", URI.create(server.baseUri))
                .ask(DecisionRequest.builder().timeout(Duration.ofNanos(1)).apply { yesNo("q_yes", "question") }.build()) as DecisionOutcome.Failure
            assertThat(result.failure).isEqualTo(CallFailure.DeadlineExceeded)
            assertThat(server.requestCount).isZero()
        }
    }

    private fun request(): DecisionRequest {
        val builder = DecisionRequest.builder()
        builder.yesNo("q_yes", "question")
        return builder.build()
    }
    private fun success() = """{"model":"resolved-test-v1","answers":{"q_yes":{"type":"noul","noul":0.75}},"usage":{"input_tokens":1,"output_tokens":1}}"""
}

internal class CaptureServer private constructor(private val script: List<Reply>) : AutoCloseable {
    data class Reply(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())
    data class Captured(val method: String, val path: String, val headers: Map<String, List<String>>, val body: String)

    private val cursor = AtomicInteger()
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val requests = CopyOnWriteArrayList<Captured>()
    val baseUri: String get() = "http://127.0.0.1:${server.address.port}"
    val requestCount: Int get() = cursor.get()

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            val reply = script[minOf(cursor.getAndIncrement(), script.lastIndex)]
            requests += Captured(exchange.requestMethod, exchange.requestURI.path, exchange.requestHeaders, exchange.requestBody.readBytes().decodeToString())
            reply.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            exchange.responseHeaders.add("Content-Type", "application/json")
            val bytes = reply.body.toByteArray()
            exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    override fun close() { server.stop(0); executor.shutdownNow() }

    companion object {
        fun replying(body: String, status: Int = 200, headers: Map<String, String> = emptyMap()) = CaptureServer(listOf(Reply(status, body, headers)))
        fun sequence(vararg replies: Reply) = CaptureServer(replies.toList())
    }
}
