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

import com.embabel.agent.domain.io.AssistantContent
import com.embabel.common.util.trim
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
 * When an LLM requests tool calls, it may or may not include text content.
 * This class handles both cases - empty content is valid for tool-call-only responses.
 */
class AssistantMessageWithToolCalls @JvmOverloads constructor(
    content: String = "",
    val toolCalls: List<ToolCall>,
    name: String? = null,
    timestamp: Instant = Instant.now(),
) : BaseMessage(
    role = Role.ASSISTANT,
    // Only include TextPart if content is non-empty
    parts = if (content.isNotEmpty()) listOf(TextPart(content)) else emptyList(),
    name = name,
    timestamp = timestamp,
), AssistantContent {

    /**
     * Get the text content, or empty string if none.
     */
    override val content: String
        get() = parts.filterIsInstance<TextPart>().joinToString("") { it.text }

    override fun toString(): String {
        return "${javaClass.simpleName}(toolCalls=${toolCalls.map { it.name }})"
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
) : BaseMessage(
    role = Role.ASSISTANT,
    parts = listOf(TextPart(content)),
    name = null,
    timestamp = timestamp,
) {
    override fun toString(): String {
        return "ToolResultMessage(toolCallId='$toolCallId', toolName='$toolName', content='${
            trim(
                s = content,
                max = 70,
                keepRight = 3,
            )
        }')"
    }
}
