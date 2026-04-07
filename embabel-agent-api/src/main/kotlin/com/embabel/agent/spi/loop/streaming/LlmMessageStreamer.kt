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
import com.embabel.agent.spi.loop.LlmMessageSender
import com.embabel.agent.spi.loop.ToolLoop
import com.embabel.chat.Message
import reactor.core.publisher.Flux

/**
 * Framework-agnostic interface for streaming LLM inference.
 *
 * Streaming counterpart of [LlmMessageSender]. Implementations handle the actual
 * LLM communication (Spring AI, LangChain4j, etc.) and return a reactive stream
 * of raw content chunks.
 *
 * **Key Differences from Non-Streaming:**
 * - Returns `Flux<String>` instead of `LlmMessageResponse`
 * - Tool execution is managed by the underlying framework (e.g., Spring AI)
 *   since the streaming API is opaque - we cannot inject a custom [ToolLoop]
 * - Only observation of tool execution is possible (via callbacks in Phase 2)
 *
 * @see LlmMessageSender for non-streaming equivalent
 */
fun interface LlmMessageStreamer {

    /**
     * Stream raw content chunks from the LLM.
     *
     * The returned Flux emits content as it arrives from the LLM.
     * Tool calls are handled internally by the underlying framework.
     *
     * @param messages The conversation history
     * @param tools Available tools for the LLM to invoke during streaming
     * @return Flux of raw content chunks
     */
    fun stream(
        messages: List<Message>,
        tools: List<Tool>,
    ): Flux<String>
}
