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
package com.embabel.agent.spi.loop.streaming

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.callback.ToolCallInspector
import com.embabel.agent.core.Usage
import com.embabel.agent.spi.loop.LlmMessageSender
import com.embabel.agent.spi.loop.ToolLoop
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Message
import reactor.core.publisher.Flux

/**
 * Provider-neutral events emitted by one streaming LLM inference call.
 *
 * [Content] events are forwarded as soon as they arrive. [Complete] is emitted once,
 * after provider-specific fragments (including partial tool calls) have been assembled.
 *
 * This SPI type is deliberately distinct from
 * [com.embabel.common.core.streaming.StreamingEvent], which is the public, converted
 * application stream of thinking and typed objects. These events exist below that layer:
 * [DefaultStreamingToolLoop] consumes [Complete] internally and forwards only content.
 */
sealed interface LlmInferenceStreamEvent {
    data class Content(val text: String) : LlmInferenceStreamEvent

    data class Complete(
        val message: Message,
        val usage: Usage? = null,
    ) : LlmInferenceStreamEvent
}

/**
 * Framework-agnostic interface for streaming LLM inference.
 *
 * Streaming counterpart of [LlmMessageSender]. Implementations handle the actual
 * LLM communication (Spring AI, LangChain4j, etc.). The original [stream] contract
 * remains the single abstract method for source and binary compatibility. The new
 * [streamInference] method exposes the assembled terminal message required by an
 * Embabel-owned streaming tool loop.
 *
 * **Key Differences from Non-Streaming:**
 * - Emits content incrementally, followed by the assembled assistant message
 * - Does not execute tools; [ToolLoop] remains under Embabel's control
 * - Provider adapters are responsible for assembling partial tool-call fragments
 *
 * @see LlmMessageSender for non-streaming equivalent
 */
fun interface LlmMessageStreamer {

    /**
     * Stream raw content chunks using the original provider-managed tool contract.
     *
     * @param messages The conversation history
     * @param tools Available tools for the LLM to invoke during streaming
     * @param toolCallInspectors Inspectors for provider-managed tool execution
     * @return Flux of raw content chunks
     */
    fun stream(
        messages: List<Message>,
        tools: List<Tool>,
        toolCallInspectors: List<ToolCallInspector>,
    ): Flux<String>

    /**
     * Stream one inference without delegating tool execution to the provider.
     *
     * Provider adapters should override this method to emit an assembled [Complete]
     * event containing any requested tool calls. The default bridge keeps existing
     * [LlmMessageStreamer] implementations usable: their raw stream is replayed as
     * [Content] and summarized as a terminal assistant message. Such legacy adapters
     * retain their provider-managed tool behavior and cannot expose assembled tool calls
     * to [DefaultStreamingToolLoop] until they override this method.
     */
    fun streamInference(
        messages: List<Message>,
        tools: List<Tool>,
    ): Flux<LlmInferenceStreamEvent> = Flux.defer {
        val content = stream(messages, tools, emptyList()).cache()
        Flux.concat(
            content.map<LlmInferenceStreamEvent> { LlmInferenceStreamEvent.Content(it) },
            content.collectList().map<LlmInferenceStreamEvent> {
                LlmInferenceStreamEvent.Complete(AssistantMessage(it.joinToString("")))
            },
        )
    }
}
