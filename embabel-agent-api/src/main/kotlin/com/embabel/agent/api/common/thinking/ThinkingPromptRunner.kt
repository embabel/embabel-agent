/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.api.common.thinking

import com.embabel.agent.api.common.PromptRunnerOperations
import com.embabel.chat.AssistantMessage
import com.embabel.chat.ChatResponseWithThinking
import com.embabel.chat.Message
import com.embabel.common.core.types.ZeroToOne

/**
 * Fallback wrapper implementation for thinking-aware prompt operations.
 *
 * This class wraps a standard [PromptRunnerOperations] to provide thinking block
 * extraction capabilities. It delegates to the underlying runner and provides
 * graceful degradation with empty thinking blocks when thinking extraction
 * is not available.
 *
 * Used only for non-OperationContextPromptRunner implementations.
 *
 * @param base The underlying prompt runner to enhance with thinking capabilities
 */
internal class ThinkingPromptRunner(
    private val base: PromptRunnerOperations,
) : ThinkingPromptRunnerOperations {

    override fun <T> createObjectIfPossible(
        messages: List<Message>,
        outputClass: Class<T>,
    ): ChatResponseWithThinking<T?> {
        val result = base.createObjectIfPossible(messages, outputClass)
        return ChatResponseWithThinking(
            result = result,
            thinkingBlocks = emptyList()
        )
    }

    override fun <T> createObject(
        messages: List<Message>,
        outputClass: Class<T>,
    ): ChatResponseWithThinking<T> {
        val result = base.createObject(messages, outputClass)
        return ChatResponseWithThinking(
            result = result,
            thinkingBlocks = emptyList()
        )
    }

    override fun respond(
        messages: List<Message>,
    ): ChatResponseWithThinking<AssistantMessage> {
        val result = base.respond(messages)
        return ChatResponseWithThinking(
            result = result,
            thinkingBlocks = emptyList()
        )
    }

    override fun evaluateCondition(
        condition: String,
        context: String,
        confidenceThreshold: ZeroToOne,
    ): ChatResponseWithThinking<Boolean> {
        val result = base.evaluateCondition(condition, context, confidenceThreshold)
        return ChatResponseWithThinking(
            result = result,
            thinkingBlocks = emptyList()
        )
    }
}
