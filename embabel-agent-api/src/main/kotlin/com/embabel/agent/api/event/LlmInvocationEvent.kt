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
import com.embabel.agent.core.LlmInvocation

/**
 * Emitted once per individual LLM invocation (per-call, not per-loop).
 *
 * Survives CONCURRENT mode and validation/binding retries — each effective LLM call
 * produces exactly one event. Listener exceptions are isolated by the underlying
 * `notifyAfterLlmCall` try/catch, so a misbehaving listener cannot break the tool loop.
 *
 * In CONCURRENT mode, this event may be emitted from multiple threads simultaneously.
 * Stateful listeners (cost aggregation, rate caps, etc.) MUST use thread-safe structures.
 *
 * @property invocation per-call invocation record (model, usage, timestamp, cost)
 * @property interactionId unique identifier of the parent LlmInteraction
 */
class LlmInvocationEvent(
    agentProcess: AgentProcess,
    val invocation: LlmInvocation,
    val interactionId: String,
) : AbstractAgentProcessEvent(agentProcess) {

    override fun toString(): String =
        "LlmInvocationEvent(model=${invocation.llmMetadata.name}, " +
                "interactionId=$interactionId, usage=${invocation.usage}, cost=${invocation.cost()})"
}
