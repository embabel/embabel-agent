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
package com.embabel.chat

import java.time.Instant

/**
 * Represents a tool call requested by the assistant.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

/**
 * An assistant message that includes tool calls.
 * Extends AssistantMessage to include the tool calls the LLM wants to make.
 */
class AssistantMessageWithToolCalls @JvmOverloads constructor(
    content: String = "",
    val toolCalls: List<ToolCall>,
    name: String? = null,
    timestamp: Instant = Instant.now(),
) : AssistantMessage(
    content = content,
    name = name,
    timestamp = timestamp,
) {
    override fun toString(): String {
        return "AssistantMessageWithToolCalls(toolCalls=${toolCalls.map { it.name }})"
    }
}

/**
 * Message containing the result of a tool execution.
 * This is sent back to the LLM after executing a tool.
 */
class ToolResultMessage @JvmOverloads constructor(
    val toolCallId: String,
    val toolName: String,
    content: String,
    override val timestamp: Instant = Instant.now(),
) : Message(
    role = Role.ASSISTANT,
    parts = listOf(TextPart(content)),
    name = null,
    timestamp = timestamp,
) {
    override fun toString(): String {
        return "ToolResultMessage(toolCallId='$toolCallId', toolName='$toolName')"
    }
}
