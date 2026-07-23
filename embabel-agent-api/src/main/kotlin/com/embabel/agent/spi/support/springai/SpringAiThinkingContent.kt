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
package com.embabel.agent.spi.support.springai

import com.embabel.agent.spi.loop.streaming.LlmStreamChunk
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation

internal data class SpringAiGenerationContent(
    val output: AssistantMessage,
    val textContent: String,
    val thinkingContent: List<String>,
    val thinkingOnly: Boolean,
)

internal fun Generation.normalizedContent(): SpringAiGenerationContent {
    val output = output
    val messageMetadata = output.metadata
    val content = output.text.orEmpty()
    val anthropicThinking = messageMetadata["thinking"] == true || messageMetadata.containsKey("signature")
    val redactedThinking = content.isEmpty() && output.toolCalls.isEmpty() && messageMetadata["data"] is String
    val thinkingOnly = anthropicThinking || redactedThinking
    val thinkingContent = buildList {
        if (thinkingOnly) {
            content.takeIf { it.isNotBlank() }?.let(::add)
            (messageMetadata["data"] as? String)?.takeIf { it.isNotBlank() }?.let(::add)
        } else {
            (messageMetadata["reasoningContent"] as? String)?.takeIf { it.isNotBlank() }?.let(::add)
            (messageMetadata["thinking"] as? String)?.takeIf { it.isNotBlank() }?.let(::add)
            (metadata.get<Any>("thinking") as? String)?.takeIf { it.isNotBlank() }?.let(::add)
        }
    }.distinct()
    return SpringAiGenerationContent(
        output = output,
        textContent = if (thinkingOnly) "" else content,
        thinkingContent = thinkingContent,
        thinkingOnly = thinkingOnly,
    )
}

internal fun ChatResponse.toLlmStreamChunks(): List<LlmStreamChunk> =
    results.mapNotNull { generation ->
        val content = generation.normalizedContent()
        LlmStreamChunk(
            textContent = content.textContent,
            thinkingContent = content.thinkingContent,
        ).takeUnless { it.textContent.isEmpty() && it.thinkingContent.isEmpty() }
    }
