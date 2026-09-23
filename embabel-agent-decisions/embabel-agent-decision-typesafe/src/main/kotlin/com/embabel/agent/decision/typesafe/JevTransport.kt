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
import com.embabel.agent.decision.DecisionSafeCode
import com.embabel.agent.decision.DecisionTelemetryEvent
import com.embabel.agent.decision.PreparedDecisionRequest
import com.embabel.agent.decision.RawDecisionOutcome
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.net.URI
import java.net.SocketTimeoutException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.time.Duration
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Supplier

/** Executes at most two bounded System One requests against the request's single deadline. */
internal class JevTransport(
    private val apiKey: Supplier<String>,
    private val model: String,
    private val baseUri: URI,
    private val client: HttpClient,
    private val codec: JevWireCodec,
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
    private val remainingNanos: (PreparedDecisionRequest) -> Long = { it.remainingNanos() },
    private val attemptObserver: (Duration) -> Unit = {},
) {
    fun invoke(request: PreparedDecisionRequest): RawDecisionOutcome {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        if (remaining(request) == null) return deadline()
        val key = try {
            apiKey.get()
        } catch (error: Exception) {
            if (error.isCancellation()) throw InterruptedException()
            return unavailable()
        }
        if (!key.isHeaderSafeCredential()) return unavailable()
        val payload = try { codec.encode(request, model) } catch (_: Exception) { return rejected() }
        if (payload.size > MAX_OUTBOUND_BYTES) return rejected()
        var firstStatus: Int? = null
        for (attempt in 0..1) {
            if (remaining(request) == null) return deadline()
            if (attempt == 1) {
                if (firstStatus !in setOf(429, 529)) return unavailable()
                val delay = Duration.ofMillis(100)
                val beforeDelay = remaining(request) ?: return deadline()
                if (beforeDelay < delay) return deadline()
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                request.event(DecisionTelemetryEvent.RETRY)
                try {
                    sleeper(delay)
                } catch (error: InterruptedException) {
                    throw error
                }
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                if (remaining(request) == null) return deadline()
            }
            val response = execute(payload, key, request, remaining(request) ?: return deadline())
            when (response) {
                is Attempt.Failure -> return response.outcome
                is Attempt.Response -> {
                    when (val disposition = classifyResponse(response, request)) {
                        is ResponseDisposition.RateLimited -> if (attempt == 0) {
                            firstStatus = disposition.status
                            continue
                        } else {
                            return unavailable()
                        }
                        is ResponseDisposition.Complete -> return disposition.outcome
                    }
                }
            }
        }
        return unavailable()
    }

    private fun classifyResponse(
        response: Attempt.Response,
        prepared: PreparedDecisionRequest,
    ): ResponseDisposition = when {
        response.status in setOf(429, 529) -> {
            prepared.event(DecisionTelemetryEvent.TRANSPORT_RATE_LIMITED)
            ResponseDisposition.RateLimited(response.status)
        }
        response.status == 422 -> {
            prepared.event(DecisionTelemetryEvent.TRANSPORT_REJECTED)
            ResponseDisposition.Complete(rejected())
        }
        response.status != 200 -> {
            prepared.event(DecisionTelemetryEvent.TRANSPORT_UNAVAILABLE)
            ResponseDisposition.Complete(unavailable())
        }
        else -> {
            prepared.event(DecisionTelemetryEvent.TRANSPORT_SUCCESS)
            val decoded = codec.decode(response.body, prepared)
            // Parsing can consume the remaining budget, so late evidence is never accepted.
            if (remaining(prepared) == null) {
                ResponseDisposition.Complete(deadline())
            } else {
                if (decoded.callFailure == CallFailure.RejectedRequest) prepared.event(DecisionTelemetryEvent.REJECTION)
                ResponseDisposition.Complete(decoded)
            }
        }
    }

    private fun execute(payload: ByteArray, key: String, prepared: PreparedDecisionRequest, attemptBudget: Duration): Attempt {
        attemptObserver(attemptBudget)
        val request = try {
            HttpRequest.newBuilder(baseUri.resolve("/v1/systemone"))
                .timeout(attemptBudget)
                .header("Authorization", "Bearer $key")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build()
        } catch (_: IllegalArgumentException) {
            return Attempt.Failure(unavailable())
        }
        val future = try {
            prepared.event(DecisionTelemetryEvent.PROVIDER_ATTEMPT)
            client.sendAsync(request, HttpResponse.BodyHandler { BoundedBodySubscriber(MAX_BODY_BYTES) })
        } catch (error: Exception) {
            if (error.isCancellation()) throw InterruptedException()
            prepared.event(DecisionTelemetryEvent.TRANSPORT_UNAVAILABLE)
            return Attempt.Failure(unavailable())
        }
        return try {
            val response = future.get(attemptBudget.toNanos(), TimeUnit.NANOSECONDS)
            Attempt.Response(response.statusCode(), response.body())
        } catch (_: TimeoutException) {
            future.cancel(true); Attempt.Failure(deadline())
        } catch (error: InterruptedException) {
            future.cancel(true)
            // The facade owns caller interrupt restoration and cancellation telemetry.
            throw error
        } catch (error: ExecutionException) {
            future.cancel(true)
            when {
                error.hasCause(BodyTooLargeException::class.java) -> {
                    prepared.event(DecisionTelemetryEvent.TRANSPORT_REJECTED)
                    Attempt.Failure(rejected())
                }
                error.isCancellation() -> {
                    // Wrapped worker interruption must reach the facade as cancellation.
                    throw InterruptedException()
                }
                error.hasCause(HttpConnectTimeoutException::class.java) -> {
                    if (remaining(prepared) == null) {
                        Attempt.Failure(deadline())
                    } else {
                        prepared.event(DecisionTelemetryEvent.TRANSPORT_UNAVAILABLE)
                        Attempt.Failure(unavailable())
                    }
                }
                error.hasCause(HttpTimeoutException::class.java) -> Attempt.Failure(deadline())
                else -> {
                    prepared.event(DecisionTelemetryEvent.TRANSPORT_UNAVAILABLE)
                    Attempt.Failure(unavailable())
                }
            }
        } catch (_: Exception) {
            future.cancel(true)
            prepared.event(DecisionTelemetryEvent.TRANSPORT_UNAVAILABLE)
            Attempt.Failure(unavailable())
        }
    }

    private fun remaining(request: PreparedDecisionRequest): Duration? {
        val nanos = remainingNanos(request)
        return if (nanos > 0) Duration.ofNanos(nanos) else null
    }

    private fun unavailable() = RawDecisionOutcome.failure(CallFailure.Unavailable, DecisionSafeCode.UNAVAILABLE)
    private fun rejected() = RawDecisionOutcome.failure(CallFailure.RejectedRequest, DecisionSafeCode.REJECTED_REQUEST)
    private fun deadline() = RawDecisionOutcome.failure(CallFailure.DeadlineExceeded, DecisionSafeCode.DEADLINE_EXCEEDED)

    private fun Throwable.hasCause(type: Class<out Throwable>): Boolean {
        var current: Throwable? = this
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        while (current != null && seen.add(current)) {
            if (type.isInstance(current)) return true
            current = current.cause
        }
        return false
    }

    private fun Throwable.isCancellation(): Boolean {
        if (Thread.currentThread().isInterrupted) return true
        var current: Throwable? = this
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        while (current != null && seen.add(current)) {
            if (current is InterruptedException || current is java.nio.channels.ClosedByInterruptException) return true
            if (current is InterruptedIOException && current !is SocketTimeoutException) return true
            current = current.cause
        }
        return false
    }

    private fun String?.isHeaderSafeCredential(): Boolean = !isNullOrBlank() && all { it.code in 0x21..0x7e }

    private sealed interface Attempt {
        class Response(val status: Int, val body: ByteArray) : Attempt
        class Failure(val outcome: RawDecisionOutcome) : Attempt
    }

    private sealed interface ResponseDisposition {
        class RateLimited(val status: Int) : ResponseDisposition
        class Complete(val outcome: RawDecisionOutcome) : ResponseDisposition
    }

    private class BodyTooLargeException : RuntimeException()

    private class BoundedBodySubscriber(private val limit: Int) : HttpResponse.BodySubscriber<ByteArray> {
        private val result = CompletableFuture<ByteArray>()
        private val bytes = ByteArrayOutputStream()
        private lateinit var subscription: Flow.Subscription

        override fun getBody(): CompletionStage<ByteArray> = result
        override fun onSubscribe(subscription: Flow.Subscription) {
            this.subscription = subscription
            subscription.request(Long.MAX_VALUE)
        }
        override fun onNext(items: List<ByteBuffer>) {
            for (item in items) {
                val size = item.remaining()
                if (bytes.size() + size > limit) {
                    subscription.cancel()
                    result.completeExceptionally(BodyTooLargeException())
                    return
                }
                val copy = ByteArray(size)
                item.get(copy)
                bytes.write(copy)
            }
        }
        override fun onError(throwable: Throwable) { result.completeExceptionally(throwable) }
        override fun onComplete() { result.complete(bytes.toByteArray()) }
    }

    private companion object {
        const val MAX_BODY_BYTES = 1_048_576
        const val MAX_OUTBOUND_BYTES = 1_048_576
    }
}
