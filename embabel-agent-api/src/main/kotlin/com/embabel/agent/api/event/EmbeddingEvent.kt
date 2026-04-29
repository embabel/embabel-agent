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
package com.embabel.agent.api.event

import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.Usage
import com.embabel.common.ai.model.EmbeddingServiceMetadata
import com.embabel.common.core.types.Timed
import com.embabel.common.core.types.Timestamped
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Events emitted by an embedding service call.
 *
 * Independent of any [AgentProcess] so that downstream consumers (logging,
 * observability, billing) can subscribe even when the embedding service is
 * called outside an agent process — e.g. from an HTTP controller, a batch
 * job or a scheduler.
 *
 * When an agent process is active, the same event is also wrapped in an
 * [AgentProcessEmbeddingEvent] and dispatched on the standard
 * [AgenticEventListener] for symmetry with other in-process events.
 *
 * Mirrors the existing pattern used by `RagEvent` / `AgentProcessRagEvent`.
 */
interface EmbeddingEvent : Timestamped {

    /** Embedding model that produced this event. */
    val embeddingMetadata: EmbeddingServiceMetadata

    /** Texts being embedded. */
    val inputs: List<String>

    /**
     * Correlation id for this embedding interaction.
     * Shared across the request, model-call and response events for a single call.
     */
    val id: String
}

/**
 * Emitted before an embedding service call.
 */
class EmbeddingRequestEvent(
    override val embeddingMetadata: EmbeddingServiceMetadata,
    override val inputs: List<String>,
    override val id: String,
    override val timestamp: Instant = Instant.now(),
) : EmbeddingEvent {

    fun responseEvent(usage: Usage, runningTime: Duration): EmbeddingResponseEvent =
        EmbeddingResponseEvent(this, usage, runningTime)

    override fun toString(): String =
        "EmbeddingRequestEvent(id=$id, model=${embeddingMetadata.name}, inputs=${inputs.size})"
}

/**
 * Emitted after an embedding service call.
 *
 * Carries `usage` directly — embedding responses have no body to expose to listeners
 * (raw vectors are large, leak-prone, useless), only token usage matters.
 */
class EmbeddingResponseEvent internal constructor(
    val request: EmbeddingRequestEvent,
    val usage: Usage,
    override val runningTime: Duration,
    override val timestamp: Instant = Instant.now(),
) : EmbeddingEvent, Timed {

    override val embeddingMetadata: EmbeddingServiceMetadata get() = request.embeddingMetadata
    override val inputs: List<String> get() = request.inputs
    override val id: String get() = request.id

    override fun toString(): String =
        "EmbeddingResponseEvent(request=$request, usage=$usage, runningTime=$runningTime)"
}

/**
 * Re-dispatches an [EmbeddingEvent] on the [AgenticEventListener.onProcessEvent]
 * channel when an [AgentProcess] is active.
 *
 * `timestamp` marks the process-notification time and is intentionally distinct
 * from `embeddingEvent.timestamp` (the embedding call time). Correlate across
 * pipelines via `embeddingEvent.id`, not timestamps.
 */
class AgentProcessEmbeddingEvent(
    agentProcess: AgentProcess,
    val embeddingEvent: EmbeddingEvent,
) : AbstractAgentProcessEvent(agentProcess)

/**
 * Listens to [EmbeddingEvent]s.
 *
 * Always invoked, regardless of whether an [AgentProcess] is active —
 * this is the channel through which standalone (non-agent) callers receive
 * embedding observability.
 */
fun interface EmbeddingEventListener {

    fun onEmbeddingEvent(event: EmbeddingEvent)

    /** Combine two listeners into one. NOOP is absorbed (identity). */
    operator fun plus(other: EmbeddingEventListener): EmbeddingEventListener {
        if (this === NOOP) return other
        if (other === NOOP) return this
        return MulticastEmbeddingEventListener(listOf(this, other))
    }

    companion object {

        @JvmField
        val NOOP: EmbeddingEventListener = EmbeddingEventListener { }
    }
}

/**
 * Fan-out listener: dispatches each event to all registered listeners.
 * Built by the [EmbeddingEventListener.plus] operator and used by Spring DI
 * to fold multiple listener beans into the single one EmbeddingOperations expects.
 * Per-listener exceptions are caught and logged so one failure cannot break the chain.
 */
private class MulticastEmbeddingEventListener(
    private val listeners: List<EmbeddingEventListener>,
) : EmbeddingEventListener {

    override fun onEmbeddingEvent(event: EmbeddingEvent) {
        listeners.forEach {
            try {
                it.onEmbeddingEvent(event)
            } catch (ex: Exception) {
                LoggerFactory.getLogger(EmbeddingEventListener::class.java)
                    .warn("Exception in EmbeddingEventListener", ex)
            }
        }
    }
}
