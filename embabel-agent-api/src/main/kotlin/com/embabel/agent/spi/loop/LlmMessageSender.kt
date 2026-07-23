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
package com.embabel.agent.spi.loop

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.Usage
import com.embabel.chat.Message
import com.embabel.common.ai.model.NativeStructuredOutputMode

/**
 * Native structured-output payload for a single call.
 *
 * This keeps the schema request and its per-call policy together so the caller does
 * not need to manage two separate fields.
 */
data class NativeStructuredOutputRequest @JvmOverloads constructor(
    val structuredOutputRequest: StructuredOutputRequest,
    val nativeStructuredOutputMode: NativeStructuredOutputMode = NativeStructuredOutputMode.DEFAULT,
)

/**
 * Provider-neutral request for native structured output.
 *
 * The schema is raw JSON Schema. It is intentionally separate from prompt format
 * instructions so provider adapters can translate it into their own payload shape.
 *
 * @param strict whether providers that support strict schema enforcement should request it.
 * Providers without such a setting may ignore this.
 */
data class StructuredOutputRequest @JvmOverloads constructor(
    val name: String,
    val schema: String,
    val description: String? = null,
    val strict: Boolean = true,
)

/**
 * Framework-agnostic request for a single LLM inference call.
 */
data class LlmMessageRequest @JvmOverloads constructor(
    val messages: List<Message>,
    val tools: List<Tool>,
    /**
     * Provider-neutral native structured-output request for this call.
     *
     * This carries the schema payload together with the per-call policy that decides
     * whether the native path should be used, defaulting to automatic runtime selection.
     */
    val nativeStructuredOutputRequest: NativeStructuredOutputRequest? = null,
)

/**
 * Framework-agnostic result of a single LLM inference call.
 * Represents the assistant's response which may include tool calls.
 * @param message The full message object from the LLM
 * @param textContent The text content of the message
 * @param usage Optional usage information (tokens, etc.)
 */
data class LlmMessageResponse @JvmOverloads constructor(
    val message: Message,
    val textContent: String,
    val usage: Usage? = null,
    val thinkingContent: List<String> = emptyList(),
)

/**
 * Framework-agnostic interface for making a single LLM inference call.
 *
 * Implementations handle the actual LLM communication (Spring AI, LangChain4j, etc.)
 * but do NOT execute tools - they just return the LLM's response including any
 * tool call requests.
 *
 * This allows the tool loop to be framework-agnostic while delegating the
 * actual LLM communication to framework-specific implementations.
 */
fun interface LlmMessageSender {

    /**
     * Make a single LLM inference call.
     *
     * @param messages The conversation history
     * @param tools Available tools (for the LLM to know what's available)
     * @return The assistant's response message
     */
    fun call(
        messages: List<Message>,
        tools: List<Tool>,
    ): LlmMessageResponse
}

/**
 * Optional extension interface for senders that can consume request-level metadata.
 */
interface RequestAwareLlmMessageSender : LlmMessageSender {

    fun call(request: LlmMessageRequest): LlmMessageResponse
}
