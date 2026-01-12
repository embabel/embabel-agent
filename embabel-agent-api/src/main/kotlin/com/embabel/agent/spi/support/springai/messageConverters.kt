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

import com.embabel.chat.*
import org.springframework.ai.content.Media
import org.springframework.core.io.ByteArrayResource
import org.springframework.util.MimeTypeUtils
import org.springframework.ai.chat.messages.AssistantMessage as SpringAiAssistantMessage
import org.springframework.ai.chat.messages.Message as SpringAiMessage
import org.springframework.ai.chat.messages.SystemMessage as SpringAiSystemMessage
import org.springframework.ai.chat.messages.UserMessage as SpringAiUserMessage

/**
 * Convert one of our messages to a Spring AI message with multimodal support.
 *
 * Note: Tool-related messages (AssistantMessageWithToolCalls, ToolResultMessage)
 * are converted to their text-only equivalents for now. Full tool call message
 * support will be added in Phase 3 of progressive tools implementation.
 */
fun Message.toSpringAiMessage(): SpringAiMessage {
    val metadata: Map<String, Any> = emptyMap()
    return when (this) {
        // Tool messages are converted to basic text for now
        is AssistantMessageWithToolCalls -> SpringAiAssistantMessage(this.textContent)
        is ToolResultMessage -> SpringAiAssistantMessage("[Tool: ${this.toolName}] ${this.textContent}")

        is AssistantMessage -> SpringAiAssistantMessage(this.textContent)

        is SystemMessage -> SpringAiSystemMessage.builder()
            .text(this.textContent)
            .metadata(metadata)
            .build()

        is UserMessage -> {
            val builder = SpringAiUserMessage.builder()

            // Collect all media (Spring AI UserMessage.Builder.media() takes a List<Media>)
            val mediaList = this.parts.filterIsInstance<ImagePart>().map { imagePart ->
                try {
                    val mimeType = MimeTypeUtils.parseMimeType(imagePart.mimeType)
                    val resource = ByteArrayResource(imagePart.data)
                    Media(mimeType, resource)
                } catch (e: Exception) {
                    throw IllegalArgumentException(
                        "Failed to process image part with MIME type: ${imagePart.mimeType}", e
                    )
                }
            }

            // Set text content (concatenate all text parts, or use empty string for image-only)
            val textContent = this.textContent.ifEmpty { " " } // Spring AI requires non-empty text
            builder.text(textContent)

            // Add all media as a single list
            if (mediaList.isNotEmpty()) {
                builder.media(mediaList)
            }

            builder.metadata(metadata).build()
        }
    }
}

/**
 * Convert a Spring AI AssistantMessage to an Embabel message.
 * Handles both regular messages and messages with tool calls.
 */
fun SpringAiAssistantMessage.toEmbabelMessage(): Message {
    val toolCalls = this.toolCalls
    return if (toolCalls.isNullOrEmpty()) {
        AssistantMessage(content = this.text ?: "")
    } else {
        AssistantMessageWithToolCalls(
            content = this.text ?: "",
            toolCalls = toolCalls.map { ToolCall(it.id(), it.name(), it.arguments()) }
        )
    }
}
