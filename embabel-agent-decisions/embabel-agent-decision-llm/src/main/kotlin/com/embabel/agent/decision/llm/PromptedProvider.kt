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
package com.embabel.agent.decision.llm

import com.embabel.agent.decision.api.CallFailure
import com.embabel.agent.decision.api.DecisionProvider
import com.embabel.agent.decision.api.DecisionSafeCode
import com.embabel.agent.decision.api.DecisionTelemetryEvent
import com.embabel.agent.decision.api.PreparedDecisionRequest
import com.embabel.agent.decision.api.RawDecisionOutcome
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.loop.LlmMessageRequest
import com.embabel.agent.spi.loop.NativeStructuredOutputRequest
import com.embabel.agent.spi.loop.RequestAwareLlmMessageSender
import com.embabel.agent.spi.loop.StructuredOutputRequest
import com.embabel.common.ai.model.LlmOptions
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.nio.channels.ClosedByInterruptException
import java.time.Duration
import java.util.Collections
import java.util.IdentityHashMap

/**
 * One prompted provider invocation.
 *
 * A provider attempt is one call to the selected message sender. Model-internal HTTP retries remain
 * owned by the LLM service and are intentionally not represented as separate attempts here.
 */
internal class PromptedProvider(
    private val service: LlmService<*>,
    private val options: LlmOptions,
    private val codec: PromptedWireCodec,
    private val requestedModel: String,
    private val serviceProvider: String,
) : DecisionProvider {
    override fun invoke(request: PreparedDecisionRequest): RawDecisionOutcome {
        if (!hasRemaining(request)) return deadlineFailure()
        val schema = codec.schema()
        if (!hasRemaining(request)) return deadlineFailure()
        val messages = try {
            codec.messages(request)
        } catch (_: UnsafePreparedStateException) {
            return rejectBeforeSend(request)
        }
        if (codec.outboundBytes(schema, messages) > MAX_OUTBOUND_BYTES) return rejectBeforeSend(request)
        if (!hasRemaining(request)) return deadlineFailure()

        val sender = try {
            service.createMessageSender(optionsFor(request))
        } catch (error: Exception) {
            if (error.isCancellation()) throw InterruptedException()
            return RawDecisionOutcome.failure(CallFailure.Unavailable, DecisionSafeCode.UNAVAILABLE)
        }
        if (!hasRemaining(request)) return deadlineFailure()

        val response = try {
            request.event(DecisionTelemetryEvent.PROVIDER_ATTEMPT)
            if (sender is RequestAwareLlmMessageSender) {
                sender.call(
                    LlmMessageRequest(
                        messages,
                        emptyList(),
                        NativeStructuredOutputRequest(StructuredOutputRequest("decision_response", schema)),
                    ),
                )
            } else {
                sender.call(messages, emptyList())
            }
        } catch (error: InterruptedException) {
            // The facade owns caller interrupt restoration and cancellation telemetry.
            throw error
        } catch (error: Exception) {
            return classifySenderFailure(request, error)
        }
        request.event(DecisionTelemetryEvent.TRANSPORT_SUCCESS)
        // A sender can return after its timeout; never parse or accept late evidence.
        if (!hasRemaining(request)) return deadlineFailure()
        return codec.parse(
            response.textContent,
            request,
            serviceProvider,
            requestedModel,
            response.usage?.promptTokens,
            response.usage?.completionTokens,
        ).also { outcome ->
            if (outcome.callFailure == CallFailure.RejectedRequest) {
                request.event(DecisionTelemetryEvent.REJECTION)
            }
        }
    }

    private fun classifySenderFailure(
        request: PreparedDecisionRequest,
        error: Exception,
    ): RawDecisionOutcome {
        if (error.isCancellation()) {
            // Wrapped worker interruption must reach the facade as cancellation.
            throw InterruptedException()
        }
        request.event(DecisionTelemetryEvent.TRANSPORT_UNAVAILABLE)
        return RawDecisionOutcome.failure(CallFailure.Unavailable, DecisionSafeCode.UNAVAILABLE)
    }

    private fun Throwable.isCancellation(): Boolean {
        if (Thread.currentThread().isInterrupted) return true
        var current: Throwable? = this
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        while (current != null && seen.add(current)) {
            if (current is InterruptedException || current is ClosedByInterruptException) return true
            if (current is InterruptedIOException && current !is SocketTimeoutException) return true
            current = current.cause
        }
        return false
    }

    private fun optionsFor(request: PreparedDecisionRequest): LlmOptions {
        val remaining = Duration.ofNanos(request.remainingNanos().coerceAtLeast(1))
        val timeout = options.timeout?.takeIf { it < remaining } ?: remaining
        return options.copy().withTimeout(timeout)
    }

    private fun hasRemaining(request: PreparedDecisionRequest): Boolean = request.remainingNanos() > 0

    private fun rejected() =
        RawDecisionOutcome.failure(CallFailure.RejectedRequest, DecisionSafeCode.REJECTED_REQUEST)

    private fun rejectBeforeSend(request: PreparedDecisionRequest): RawDecisionOutcome {
        request.event(DecisionTelemetryEvent.REJECTION)
        return rejected()
    }

    private fun deadlineFailure() =
        RawDecisionOutcome.failure(CallFailure.DeadlineExceeded, DecisionSafeCode.DEADLINE_EXCEEDED)

    private companion object {
        const val MAX_OUTBOUND_BYTES = 1_048_576
    }
}
