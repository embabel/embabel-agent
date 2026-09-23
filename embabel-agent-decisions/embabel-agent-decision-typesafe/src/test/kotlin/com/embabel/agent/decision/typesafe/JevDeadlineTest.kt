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
import com.embabel.agent.decision.DecisionModel
import com.embabel.agent.decision.DecisionCompletion
import com.embabel.agent.decision.DecisionInstrumentation
import com.embabel.agent.decision.DecisionObservation
import com.embabel.agent.decision.DecisionObservationContext
import com.embabel.agent.decision.DecisionOutcome
import com.embabel.agent.decision.DecisionProvider
import com.embabel.agent.decision.DecisionRequest
import com.embabel.agent.decision.DecisionSafeCode
import com.embabel.agent.decision.DecisionTelemetryEvent
import com.embabel.agent.decision.PreparedDecisionRequest
import com.embabel.agent.decision.RawDecisionOutcome
import com.embabel.common.util.EmbabelObjectMapperHolder
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.io.UncheckedIOException
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

class JevDeadlineTest {
    @Test
    fun `maps an asynchronous JDK request timeout to the public deadline failure`() {
        val client = mockk<HttpClient>()
        every {
            client.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>())
        } returns CompletableFuture.failedFuture(HttpTimeoutException("synthetic timeout"))
        val transport = transport(client)
        val model = DecisionModel(DecisionProvider(transport::invoke))

        val result = model.ask(request()) as DecisionOutcome.Failure

        assertThat(result.failure).isEqualTo(CallFailure.DeadlineExceeded)
        assertThat(result.safeCode).isEqualTo(DecisionSafeCode.DEADLINE_EXCEEDED)
    }

    @Test
    fun `keeps a non-timeout asynchronous transport failure unavailable`() {
        val client = mockk<HttpClient>()
        every {
            client.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>())
        } returns CompletableFuture.failedFuture(IOException("synthetic I O failure"))
        val transport = transport(client)
        val model = DecisionModel(DecisionProvider(transport::invoke))

        val result = model.ask(request()) as DecisionOutcome.Failure

        assertThat(result.failure).isEqualTo(CallFailure.Unavailable)
        assertThat(result.safeCode).isEqualTo(DecisionSafeCode.UNAVAILABLE)
    }

    @Test
    fun `keeps a connect timeout unavailable while the absolute deadline has budget`() {
        val client = mockk<HttpClient>()
        every {
            client.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>())
        } returns CompletableFuture.failedFuture(HttpConnectTimeoutException("synthetic connect timeout"))
        val transport = transport(client)
        val model = DecisionModel(DecisionProvider(transport::invoke))

        val result = model.ask(request()) as DecisionOutcome.Failure

        assertThat(result.failure).isEqualTo(CallFailure.Unavailable)
        assertThat(result.safeCode).isEqualTo(DecisionSafeCode.UNAVAILABLE)
    }

    @Test
    fun `maps a connect timeout to deadline only when the absolute deadline is exhausted`() {
        val client = mockk<HttpClient>()
        var dispatched = false
        every {
            client.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>())
        } answers {
            dispatched = true
            CompletableFuture.failedFuture(HttpConnectTimeoutException("synthetic connect timeout"))
        }
        val transport = transport(client) {
            if (dispatched) 0L else Duration.ofSeconds(1).toNanos()
        }
        val model = DecisionModel(DecisionProvider(transport::invoke))

        val result = model.ask(request()) as DecisionOutcome.Failure

        assertThat(result.failure).isEqualTo(CallFailure.DeadlineExceeded)
        assertThat(result.safeCode).isEqualTo(DecisionSafeCode.DEADLINE_EXCEEDED)
    }

    @Test
    fun `bounds exceptional cause traversal when the cause chain contains a cycle`() {
        val client = mockk<HttpClient>()
        val first = RuntimeException("first")
        val second = RuntimeException("second", first)
        first.initCause(second)
        every {
            client.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>())
        } returns CompletableFuture.failedFuture(first)
        val transport = transport(client)
        val model = DecisionModel(DecisionProvider(transport::invoke))

        val result = model.ask(request()) as DecisionOutcome.Failure

        assertThat(result.failure).isEqualTo(CallFailure.Unavailable)
        assertThat(result.safeCode).isEqualTo(DecisionSafeCode.UNAVAILABLE)
    }

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
    fun `emits one attempt per native send with bounded retry and transport classes`() {
        CaptureServer.sequence(CaptureServer.Reply(429, "{}"), CaptureServer.Reply(200, success())).use { server ->
            val instrumentation = RecordingInstrumentation()
            val outcome = TypeSafeDecisionModel.create(
                Supplier { "synthetic-bearer" },
                "requested-test",
                URI.create(server.baseUri),
            ).withInstrumentation(instrumentation).ask(request())

            assertThat(outcome).isInstanceOf(DecisionOutcome.Success::class.java)
            assertThat(server.requestCount).isEqualTo(2)
            assertThat(instrumentation.events).containsExactly(
                DecisionTelemetryEvent.PROVIDER_ATTEMPT,
                DecisionTelemetryEvent.TRANSPORT_RATE_LIMITED,
                DecisionTelemetryEvent.RETRY,
                DecisionTelemetryEvent.PROVIDER_ATTEMPT,
                DecisionTelemetryEvent.TRANSPORT_SUCCESS,
            )
        }
    }

    @Test
    fun `does not emit or dispatch a retry after cancellation is observed`() {
        CaptureServer.sequence(CaptureServer.Reply(429, "{}"), CaptureServer.Reply(200, success())).use { server ->
            val observed = ConcurrentLinkedQueue<DecisionTelemetryEvent>()
            val base = prepared()
            val recording = object : PreparedDecisionRequest by base {
                override fun event(event: DecisionTelemetryEvent) {
                    observed += event
                }
            }
            val transport = transport(server, sleeper = { error("retry sleep must not run") }) {
                if (server.requestCount == 0) {
                    Duration.ofSeconds(1).toNanos()
                } else {
                    Thread.currentThread().interrupt()
                    Duration.ofSeconds(1).toNanos()
                }
            }

            try {
                assertThatThrownBy { transport.invoke(recording) }
                    .isInstanceOf(InterruptedException::class.java)
                assertThat(server.requestCount).isEqualTo(1)
                assertThat(observed).containsExactly(
                    DecisionTelemetryEvent.PROVIDER_ATTEMPT,
                    DecisionTelemetryEvent.TRANSPORT_RATE_LIMITED,
                )
            } finally {
                Thread.interrupted()
            }
        }
    }

    @Test
    fun `emits safe rejection and unavailability events without response or exception data`() {
        listOf(
            CaptureServer.Reply(422, "SECRET_REJECTED") to DecisionTelemetryEvent.TRANSPORT_REJECTED,
            CaptureServer.Reply(503, "SECRET_UNAVAILABLE") to DecisionTelemetryEvent.TRANSPORT_UNAVAILABLE,
        ).forEach { (reply, expected) ->
            CaptureServer.sequence(reply).use { server ->
                val instrumentation = RecordingInstrumentation()
                TypeSafeDecisionModel.create(
                    Supplier { "synthetic-bearer" },
                    "requested-test",
                    URI.create(server.baseUri),
                ).withInstrumentation(instrumentation).ask(request())

                assertThat(instrumentation.events).containsExactly(
                    DecisionTelemetryEvent.PROVIDER_ATTEMPT,
                    expected,
                )
                assertThat(instrumentation.events.joinToString()).doesNotContain("SECRET")
            }
        }
    }

    @Test
    fun `marks a malformed successful response as transport success followed by facade rejection`() {
        CaptureServer.replying("{SECRET_MALFORMED").use { server ->
            val instrumentation = RecordingInstrumentation()
            TypeSafeDecisionModel.create(
                Supplier { "synthetic-bearer" },
                "requested-test",
                URI.create(server.baseUri),
            ).withInstrumentation(instrumentation).ask(request())

            assertThat(instrumentation.events).containsExactly(
                DecisionTelemetryEvent.PROVIDER_ATTEMPT,
                DecisionTelemetryEvent.TRANSPORT_SUCCESS,
                DecisionTelemetryEvent.REJECTION,
            )
            assertThat(instrumentation.events.joinToString()).doesNotContain("SECRET_MALFORMED")
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
    fun `retries a 529 response once`() {
        CaptureServer.sequence(CaptureServer.Reply(529, "{}"), CaptureServer.Reply(200, success())).use { server ->
            val result = TypeSafeDecisionModel.create(Supplier { "synthetic-bearer" }, "requested-test", URI.create(server.baseUri)).ask(request())
            assertThat(result).isInstanceOf(DecisionOutcome.Success::class.java)
            assertThat(server.requestCount).isEqualTo(2)
        }
    }

    @Test
    fun `maps terminal statuses and I O failures without retrying`() {
        listOf(401, 403, 500, 503).forEach { status ->
            CaptureServer.replying("{}", status).use { server ->
                val result = TypeSafeDecisionModel.create(Supplier { "synthetic-bearer" }, "requested-test", URI.create(server.baseUri)).ask(request()) as DecisionOutcome.Failure
                assertThat(result.failure).isEqualTo(CallFailure.Unavailable)
                assertThat(server.requestCount).isEqualTo(1)
            }
        }
        CaptureServer.replying("{}", 422).use { server ->
            val result = TypeSafeDecisionModel.create(Supplier { "synthetic-bearer" }, "requested-test", URI.create(server.baseUri)).ask(request()) as DecisionOutcome.Failure
            assertThat(result.failure).isEqualTo(CallFailure.RejectedRequest)
            assertThat(server.requestCount).isEqualTo(1)
        }
        val result = TypeSafeDecisionModel.create(Supplier { "synthetic-bearer" }, "requested-test", URI.create("http://127.0.0.1:1")).ask(request()) as DecisionOutcome.Failure
        assertThat(result.failure).isEqualTo(CallFailure.Unavailable)
    }

    @Test
    fun `never follows a redirect or sends credentials to its target`() {
        CaptureServer.replying("{}", 200).use { hostile ->
            CaptureServer.replying("{}", 302, mapOf("Location" to "${hostile.baseUri}/steal")).use { server ->
            val result = TypeSafeDecisionModel.create(Supplier { "synthetic-bearer" }, "requested-test", URI.create(server.baseUri)).ask(request()) as DecisionOutcome.Failure
            assertThat(result.failure).isEqualTo(CallFailure.Unavailable)
            assertThat(server.requestCount).isEqualTo(1)
                assertThat(hostile.requestCount).isZero()
                assertThat(hostile.requests).isEmpty()
            }
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

    @Test
    fun `does not dispatch blank unsafe or failing credentials`() {
        listOf<Supplier<String>>(
            Supplier { "" },
            Supplier { "synthetic\ncredential" },
            Supplier { throw IllegalStateException("SECRET_DO_NOT_LOG") },
        ).forEach { credentials ->
            CaptureServer.replying(success()).use { server ->
                val result = TypeSafeDecisionModel.create(credentials, "requested-test", URI.create(server.baseUri)).ask(request()) as DecisionOutcome.Failure
                assertThat(result.failure).isEqualTo(CallFailure.Unavailable)
                assertThat(server.requestCount).isZero()
                assertThat(result.record?.fields?.values.orEmpty().joinToString()).doesNotContain("SECRET_DO_NOT_LOG")
            }
        }
    }

    @Test
    fun `rejects every non visible ASCII credential before request construction`() {
        listOf("sk-\u0001SECRET", "sk-\u007fSECRET", "sk-secret value").forEach { credential ->
            CaptureServer.replying(success()).use { server ->
                val raw = JevTransport(
                    Supplier { credential },
                    "requested-test",
                    URI.create(server.baseUri),
                    HttpClient.newHttpClient(),
                    JevWireCodec(EmbabelObjectMapperHolder.createDefault(), "requested-test"),
                ).invoke(prepared())

                assertThat(raw.callFailure).isEqualTo(CallFailure.Unavailable)
                assertThat(server.requestCount).isZero()
                assertThat(raw.toString()).doesNotContain("SECRET")
            }
        }
    }

    @Test
    fun `classifies wrapped credential interruption without mistaking socket timeout for cancellation`() {
        assertThatThrownBy {
            JevTransport(
                Supplier { throw UncheckedIOException(InterruptedIOException("synthetic interruption")) },
                "requested-test",
                URI.create("https://jev.invalid"),
                HttpClient.newHttpClient(),
                JevWireCodec(EmbabelObjectMapperHolder.createDefault(), "requested-test"),
            ).invoke(prepared())
        }.isInstanceOf(InterruptedException::class.java)
        assertThat(Thread.currentThread().isInterrupted).isFalse()

        val timeout = JevTransport(
            Supplier { throw UncheckedIOException(java.net.SocketTimeoutException("synthetic timeout")) },
            "requested-test",
            URI.create("https://jev.invalid"),
            HttpClient.newHttpClient(),
            JevWireCodec(EmbabelObjectMapperHolder.createDefault(), "requested-test"),
        ).invoke(prepared())
        assertThat(timeout.callFailure).isEqualTo(CallFailure.Unavailable)
        assertThat(Thread.currentThread().isInterrupted).isFalse()
    }

    @Test
    fun `pre-send encoding and size rejections emit once without transport dispatch`() {
        listOf(
            mapOf("unsafe" to Double.NaN),
            mapOf("callerProjection" to "x".repeat(1_048_577)),
        ).forEach { state ->
            CaptureServer.replying(success()).use { server ->
                val events = ConcurrentLinkedQueue<DecisionTelemetryEvent>()
                val rejected = object : PreparedDecisionRequest by prepared() {
                    override val state = state
                    override fun event(event: DecisionTelemetryEvent) {
                        events += event
                    }
                }

                val raw = transport(server).invoke(rejected)

                assertThat(raw.callFailure).isEqualTo(CallFailure.RejectedRequest)
                assertThat(server.requestCount).isZero()
                assertThat(events).containsExactly(DecisionTelemetryEvent.REJECTION)
            }
        }
    }

    @Test
    fun `bounds stalled headers stalled bodies and oversized bodies by the single deadline`() {
        val headers = CountDownLatch(1)
        CaptureServer.custom { exchange -> headers.await(); exchange.sendResponseHeaders(200, 0) }.use { server ->
            val result = model(server, Duration.ofMillis(40)).ask(request()) as DecisionOutcome.Failure
            assertThat(result.failure).isEqualTo(CallFailure.DeadlineExceeded)
        }
        val body = CountDownLatch(1)
        CaptureServer.custom { exchange ->
            exchange.sendResponseHeaders(200, 0)
            body.await()
        }.use { server ->
            val result = model(server, Duration.ofMillis(40)).ask(request()) as DecisionOutcome.Failure
            assertThat(result.failure).isEqualTo(CallFailure.DeadlineExceeded)
        }
        CaptureServer.custom { exchange ->
            val payload = ByteArray(1_048_577) { 'x'.code.toByte() }
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }.use { server ->
            val result = model(server).ask(request()) as DecisionOutcome.Failure
            assertThat(result.failure).isEqualTo(CallFailure.RejectedRequest)
        }
    }

    @Test
    fun `does not spend a fresh deadline on retry and rejects a late decoded success`() {
        CaptureServer.sequence(CaptureServer.Reply(429, "{}"), CaptureServer.Reply(200, success())).use { server ->
            val raw = transport(server, sleeper = { throw AssertionError("must not sleep with consumed budget") }) {
                if (server.requestCount == 0) Duration.ofSeconds(1).toNanos() else Duration.ofMillis(99).toNanos()
            }.invoke(prepared())
            assertThat(raw.callFailure).isEqualTo(CallFailure.DeadlineExceeded)
            assertThat(server.requestCount).isEqualTo(1)
        }
        CaptureServer.replying(success()).use { server ->
            val raw = transport(server) { if (server.requestCount == 0) Duration.ofSeconds(1).toNanos() else 0L }.invoke(prepared())
            assertThat(raw.callFailure).isEqualTo(CallFailure.DeadlineExceeded)
        }
    }

    @Test
    fun `uses the remaining budget for the second attempt and cancellation wins`() {
        CaptureServer.sequence(CaptureServer.Reply(429, "{}"), CaptureServer.Reply(200, success())).use { server ->
            val observed = mutableListOf<Duration>()
            val raw = transport(server, sleeper = {}, observer = observed::add) {
                if (server.requestCount == 0) Duration.ofSeconds(1).toNanos() else Duration.ofMillis(200).toNanos()
            }.invoke(prepared())
            assertThat(raw.callFailure).isNull()
            assertThat(observed).containsExactly(Duration.ofSeconds(1), Duration.ofMillis(200))
        }
        CaptureServer.sequence(CaptureServer.Reply(429, "{}")).use { server ->
            assertThatThrownBy { transport(server, sleeper = { throw InterruptedException() }).invoke(prepared()) }
                .isInstanceOf(InterruptedException::class.java)
            assertThat(Thread.currentThread().isInterrupted).isFalse()
        }
    }

    @Test
    fun `cancels an in flight send and propagates worker interruption`() {
        val gate = CountDownLatch(1)
        CaptureServer.custom { gate.await() }.use { server ->
            val prepared = prepared()
            Thread.currentThread().interrupt()
            try {
                assertThatThrownBy { transport(server).invoke(prepared) }
                    .isInstanceOf(InterruptedException::class.java)
                assertThat(Thread.currentThread().isInterrupted).isTrue()
            } finally {
                Thread.interrupted()
            }
        }
    }

    @Test
    fun `closing an in flight model emits facade cancellation exactly once`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        CaptureServer.custom { exchange ->
            entered.countDown()
            release.await()
            exchange.sendResponseHeaders(200, 0)
        }.use { server ->
            val instrumentation = RecordingInstrumentation()
            val model = TypeSafeDecisionModel.create(
                Supplier { "synthetic-bearer" },
                "requested-test",
                URI.create(server.baseUri),
            ).withInstrumentation(instrumentation)
            val caller = CompletableFuture.supplyAsync { model.ask(request()) }

            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue()
            model.close()
            val outcome = caller.get(2, TimeUnit.SECONDS) as DecisionOutcome.Failure

            assertThat(outcome.failure).isEqualTo(CallFailure.Cancelled)
            assertThat(instrumentation.events).containsExactly(
                DecisionTelemetryEvent.PROVIDER_ATTEMPT,
                DecisionTelemetryEvent.CANCELLATION,
            )
            assertThat(instrumentation.events.count { it == DecisionTelemetryEvent.CANCELLATION }).isEqualTo(1)
            release.countDown()
        }
    }

    private fun request(): DecisionRequest {
        val builder = DecisionRequest.builder()
        builder.yesNo("q_yes", "question")
        return builder.build()
    }
    private fun model(server: CaptureServer, timeout: Duration = Duration.ofSeconds(2)) =
        TypeSafeDecisionModel.create(Supplier { "synthetic-bearer" }, "requested-test", URI.create(server.baseUri)).withDefaults(timeout, com.embabel.agent.decision.DecisionRecordPolicy.metadata())

    private fun prepared(): PreparedDecisionRequest {
        var captured: PreparedDecisionRequest? = null
        DecisionModel(DecisionProvider { request ->
            captured = request
            RawDecisionOutcome.failure(CallFailure.Disabled, DecisionSafeCode.DISABLED)
        }).ask(request())
        return requireNotNull(captured)
    }

    private fun transport(
        server: CaptureServer,
        sleeper: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
        observer: (Duration) -> Unit = {},
        remaining: (PreparedDecisionRequest) -> Long = { it.remainingNanos() },
    ) = JevTransport(
        Supplier { "synthetic-bearer" }, "requested-test", URI.create(server.baseUri), HttpClient.newHttpClient(),
        JevWireCodec(EmbabelObjectMapperHolder.createDefault(), "requested-test"), sleeper, remaining, observer,
    )

    private fun transport(
        client: HttpClient,
        remaining: (PreparedDecisionRequest) -> Long = { it.remainingNanos() },
    ) = JevTransport(
        Supplier { "synthetic-bearer" }, "requested-test", URI.create("https://jev.invalid"), client,
        JevWireCodec(EmbabelObjectMapperHolder.createDefault(), "requested-test"), remainingNanos = remaining,
    )

    private fun success() = """{"model":"resolved-test-v1","answers":{"q_yes":{"type":"noul","noul":0.75}},"usage":{"input_tokens":1,"output_tokens":1}}"""

    private class RecordingInstrumentation : DecisionInstrumentation {
        val events = ConcurrentLinkedQueue<DecisionTelemetryEvent>()

        override fun start(context: DecisionObservationContext): DecisionObservation = object : DecisionObservation {
            override fun <T> wrap(work: Callable<T>): Callable<T> = work
            override fun event(event: DecisionTelemetryEvent) {
                events += event
            }
            override fun complete(completion: DecisionCompletion) = Unit
            override fun close() = Unit
        }
    }
}

internal class CaptureServer private constructor(private val script: List<Reply>) : AutoCloseable {
    data class Reply(val status: Int, val body: String, val headers: Map<String, String> = emptyMap(), val custom: ((HttpExchange) -> Unit)? = null)
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
            reply.custom?.let { handler -> handler(exchange); return@createContext }
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
        fun custom(handler: (HttpExchange) -> Unit) = CaptureServer(listOf(Reply(200, "", custom = handler)))
    }
}
