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
package com.embabel.agent.spi.support.embedding

import com.embabel.agent.api.event.AgentProcessEmbeddingEvent
import com.embabel.agent.api.event.EmbeddingEvent
import com.embabel.agent.api.event.EmbeddingEventListener
import com.embabel.agent.api.event.EmbeddingInvocationEvent
import com.embabel.agent.api.event.EmbeddingRequestEvent
import com.embabel.agent.spi.support.springai.EmbeddingModelCallEvent
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.EmbeddingInvocation
import com.embabel.agent.core.Usage
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.SpringAiEmbeddingService
import com.embabel.common.ai.model.TokenCountEstimator
import org.slf4j.LoggerFactory
import org.springframework.ai.embedding.EmbeddingOptions
import org.springframework.ai.embedding.EmbeddingRequest
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Decorator around an [EmbeddingService] that emits embedding events and records
 * cost / token usage.
 *
 * Embedding events ([EmbeddingRequestEvent], [com.embabel.agent.api.event.EmbeddingResponseEvent])
 * are always emitted on the injected [EmbeddingEventListener], regardless of whether an
 * [AgentProcess] is on the thread. This is the channel through which standalone callers
 * (HTTP controllers, batch jobs, schedulers) receive embedding observability.
 *
 * If an [AgentProcess] is active on the thread, the same events are also wrapped
 * in [AgentProcessEmbeddingEvent] and dispatched on the process's
 * [com.embabel.agent.api.event.AgenticEventListener] (`onProcessEvent`), and the
 * resulting [EmbeddingInvocation] is recorded on the process for cost accounting.
 *
 * **Note**: [EmbeddingModelCallEvent] is only emitted when the underlying delegate is a
 * [SpringAiEmbeddingService] — it is captured at the `EmbeddingModel.call(EmbeddingRequest)`
 * seam. For non-Spring-AI delegates (e.g. ONNX, custom implementations), only the request
 * and response events are emitted.
 *
 * Listener exceptions are caught and logged so that a misbehaving listener cannot break the
 * in-process [AgentProcessEmbeddingEvent] dispatch, the underlying embedding call, or the
 * [EmbeddingInvocation] recording.
 *
 * Token sourcing strategy:
 *   1. If the underlying service is a [SpringAiEmbeddingService], we go through
 *      `model.call(EmbeddingRequest)` to recover `EmbeddingResponse.metadata.usage.promptTokens`
 *      — the provider's authoritative token count.
 *   2. Otherwise, or if the provider does not surface usage metadata, we fall back to
 *      [TokenCountEstimator] (default: character-heuristic ≈ 4 chars per token).
 */
internal class EmbeddingOperations(
    private val delegate: EmbeddingService,
    private val tokenCountEstimator: TokenCountEstimator<String> = TokenCountEstimator.heuristic(),
    private val listener: EmbeddingEventListener = EmbeddingEventListener.NOOP,
) : EmbeddingService by delegate {

    private val logger = LoggerFactory.getLogger(EmbeddingOperations::class.java)

    override fun embed(text: String): FloatArray =
        embedAndRecord(listOf(text)).first()

    override fun embed(texts: List<String>): List<FloatArray> =
        embedAndRecord(texts)

    private fun embedAndRecord(texts: List<String>): List<FloatArray> {
        val agentProcess = AgentProcess.get()
        val interactionId = UUID.randomUUID().toString()

        val requestEvent = EmbeddingRequestEvent(delegate, texts, interactionId)
        emit(requestEvent, agentProcess)

        val start = Instant.now()
        val callResult = callDelegate(texts, interactionId, agentProcess)
        val runningTime = Duration.between(start, Instant.now())

        // Spring AI's EmbeddingResponseMetadata.getUsage() returns an EmptyUsage (with 0 tokens)
        // rather than null when no usage was set, so we treat 0 as "no provider data" and
        // fall back to the local estimator.
        val tokens = callResult.usage?.promptTokens?.toInt()?.takeIf { it > 0 }
            ?: tokenCountEstimator.estimate(texts.joinToString(" "))
        val usage = Usage(promptTokens = tokens, completionTokens = 0, nativeUsage = callResult.usage)

        emit(requestEvent.responseEvent(usage, runningTime), agentProcess)

        agentProcess?.let { ap ->
            val invocation = EmbeddingInvocation(
                embeddingMetadata = delegate,
                usage = usage,
                agentName = ap.agent.name,
                timestamp = requestEvent.timestamp,
                runningTime = runningTime,
            )
            ap.recordEmbeddingInvocation(invocation)
            ap.processContext.onProcessEvent(
                EmbeddingInvocationEvent(
                    agentProcess = ap,
                    invocation = invocation,
                    interactionId = interactionId,
                )
            )
        }

        return callResult.embeddings
    }

    private fun emit(event: EmbeddingEvent, agentProcess: AgentProcess?) {
        try {
            listener.onEmbeddingEvent(event)
        } catch (ex: Exception) {
            logger.warn("Exception in EmbeddingEventListener", ex)
        }
        agentProcess?.processContext?.onProcessEvent(AgentProcessEmbeddingEvent(agentProcess, event))
    }

    private fun callDelegate(
        texts: List<String>,
        interactionId: String,
        agentProcess: AgentProcess?,
    ): CallResult {
        if (delegate is SpringAiEmbeddingService) {
            val request = EmbeddingRequest(texts, EmbeddingOptions.builder().build())
            emit(EmbeddingModelCallEvent(delegate, interactionId, request), agentProcess)
            val response = delegate.model.call(request)
            return CallResult(
                embeddings = response.results.map { it.output },
                usage = response.metadata?.usage,
            )
        }
        return CallResult(embeddings = delegate.embed(texts), usage = null)
    }

    private data class CallResult(
        val embeddings: List<FloatArray>,
        val usage: org.springframework.ai.chat.metadata.Usage?,
    )
}
