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
package com.embabel.agent.spi.toolloop

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.Usage
import com.embabel.chat.Message

/**
 * Framework-agnostic result of a single LLM inference call.
 * Represents the assistant's response which may include tool calls.
 * @param message The full message object from the LLM
 * @param textContent The text content of the message
 * @param usage Optional usage information (tokens, etc.)
 */
data class LlmMessageResponse(
    val message: Message,
    val textContent: String,
    val usage: Usage? = null,
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

    /**c
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
