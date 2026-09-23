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
import com.embabel.agent.decision.PreparedDecisionRequest
import com.embabel.agent.decision.RawDecisionOutcome
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
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
        if (remaining(request) == null) return deadline()
        val key = try { apiKey.get() } catch (_: Exception) { return unavailable() }
        if (key.isNullOrBlank() || key.contains('\r') || key.contains('\n')) return unavailable()
        val payload = try { codec.encode(request, model) } catch (_: Exception) { return rejected() }
        var firstStatus: Int? = null
        for (attempt in 0..1) {
            if (remaining(request) == null) return deadline()
            if (attempt == 1) {
                if (firstStatus !in setOf(429, 529)) return unavailable()
                val delay = Duration.ofMillis(100)
                val beforeDelay = remaining(request) ?: return deadline()
                if (beforeDelay < delay) return deadline()
                try { sleeper(delay) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return cancelled() }
                if (remaining(request) == null || Thread.currentThread().isInterrupted) return if (Thread.currentThread().isInterrupted) cancelled() else deadline()
            }
            val response = execute(payload, key, remaining(request) ?: return deadline())
            when (response) {
                is Attempt.Failure -> return response.outcome
                is Attempt.Response -> {
                    if (attempt == 0 && response.status in setOf(429, 529)) { firstStatus = response.status; continue }
                    if (response.status == 422) return rejected()
                    if (response.status != 200) return unavailable()
                    val decoded = codec.decode(response.body, request)
                    if (remaining(request) == null) return deadline()
                    return decoded
                }
            }
        }
        return unavailable()
    }

    private fun execute(payload: ByteArray, key: String, remaining: Duration): Attempt {
        attemptObserver(remaining)
        val request = HttpRequest.newBuilder(baseUri.resolve("/v1/systemone"))
            .timeout(remaining)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
            .build()
        val future = try {
            client.sendAsync(request, HttpResponse.BodyHandler { BoundedBodySubscriber(MAX_BODY_BYTES) })
        } catch (_: Exception) { return Attempt.Failure(unavailable()) }
        return try {
            val response = future.get(remaining.toNanos(), TimeUnit.NANOSECONDS)
            Attempt.Response(response.statusCode(), response.body())
        } catch (_: TimeoutException) {
            future.cancel(true); Attempt.Failure(deadline())
        } catch (_: InterruptedException) {
            future.cancel(true); Thread.currentThread().interrupt(); Attempt.Failure(cancelled())
        } catch (error: ExecutionException) {
            future.cancel(true)
            if (error.cause is BodyTooLargeException) Attempt.Failure(rejected()) else Attempt.Failure(unavailable())
        } catch (_: Exception) {
            future.cancel(true); Attempt.Failure(unavailable())
        }
    }

    private fun remaining(request: PreparedDecisionRequest): Duration? {
        val nanos = remainingNanos(request)
        return if (nanos > 0) Duration.ofNanos(nanos) else null
    }

    private fun unavailable() = RawDecisionOutcome.failure(CallFailure.Unavailable, DecisionSafeCode.UNAVAILABLE)
    private fun rejected() = RawDecisionOutcome.failure(CallFailure.RejectedRequest, DecisionSafeCode.REJECTED_REQUEST)
    private fun deadline() = RawDecisionOutcome.failure(CallFailure.DeadlineExceeded, DecisionSafeCode.DEADLINE_EXCEEDED)
    private fun cancelled() = RawDecisionOutcome.failure(CallFailure.Cancelled, DecisionSafeCode.CANCELLED)

    private sealed interface Attempt {
        class Response(val status: Int, val body: ByteArray) : Attempt
        class Failure(val outcome: RawDecisionOutcome) : Attempt
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

    private companion object { const val MAX_BODY_BYTES = 1_048_576 }
}
