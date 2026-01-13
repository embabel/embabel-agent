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
package com.embabel.agent.api.common.thinking.support

import com.embabel.agent.api.common.thinking.ThinkingPromptRunnerOperations
import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.support.springai.ChatClientLlmOperations
import com.embabel.chat.AssistantMessage
import com.embabel.common.core.thinking.ThinkingResponse
import com.embabel.chat.Message
import com.embabel.common.core.thinking.ThinkingException
import com.embabel.common.core.types.ZeroToOne

/**
 * Implementation of thinking-aware prompt operations.
 *
 * This class provides thinking block extraction by delegating directly to
 * ChatClientLlmOperations SPI layer's doTransformWithThinking methods.
 *
 * ## Architecture
 *
 * Following the pattern established by StreamingPromptRunnerOperationsImpl:
 *
 * ```
 * ThinkingPromptRunnerOperationsImpl → ChatClientLlmOperations.doTransformWithThinking
 * ```
 *
 * @param chatClientOperations The underlying ChatClient operations that support thinking extraction
 * @param interaction The LLM interaction configuration including options and tools
 * @param messages The conversation messages accumulated so far
 * @param agentProcess The agent process context for this operation
 * @param action The action context if running within an action
 */
internal class ThinkingPromptRunnerOperationsImpl(
    private val chatClientOperations: ChatClientLlmOperations,
    private val interaction: LlmInteraction,
    private val messages: List<Message>,
    private val agentProcess: AgentProcess,
    private val action: Action?,
) : ThinkingPromptRunnerOperations {

    override fun <T> createObjectIfPossible(
        messages: List<Message>,
        outputClass: Class<T>,
    ): ThinkingResponse<T?> {
        val combinedMessages = this.messages + messages
        val result = chatClientOperations.doTransformWithThinkingIfPossible(
            messages = combinedMessages,
            interaction = interaction,
            outputClass = outputClass,
            llmRequestEvent = null
        )

        return when {
            result.isSuccess -> {
                val successResponse = result.getOrThrow()
                ThinkingResponse<T?>(
                    result = successResponse.result,
                    thinkingBlocks = successResponse.thinkingBlocks
                )
            }

            else -> {
                // Preserve thinking blocks even when object creation fails
                val exception = result.exceptionOrNull()
                val thinkingBlocks = if (exception is ThinkingException) {
                    exception.thinkingBlocks
                } else {
                    emptyList()
                }
                ThinkingResponse<T?>(
                    result = null,
                    thinkingBlocks = thinkingBlocks
                )
            }
        }
    }

    override fun <T> createObject(
        messages: List<Message>,
        outputClass: Class<T>,
    ): ThinkingResponse<T> {
        val combinedMessages = this.messages + messages
        return chatClientOperations.doTransformWithThinking(
            messages = combinedMessages,
            interaction = interaction,
            outputClass = outputClass,
            llmRequestEvent = null
        )
    }

    override fun respond(
        messages: List<Message>,
    ): ThinkingResponse<AssistantMessage> {
        return createObject(messages, AssistantMessage::class.java)
    }

    override fun evaluateCondition(
        condition: String,
        context: String,
        confidenceThreshold: ZeroToOne,
    ): ThinkingResponse<Boolean> {
        val prompt =
            """
            Evaluate this condition given the context.
            Return "result": whether you think it is true, your confidence level from 0-1,
            and an explanation of what you base this on.

            # Condition
            $condition

            # Context
            $context
            """.trimIndent()

        val response = createObject(
            messages = listOf(com.embabel.chat.UserMessage(prompt)),
            outputClass = com.embabel.agent.experimental.primitive.Determination::class.java,
        )

        val result = response.result?.let {
            it.result && it.confidence >= confidenceThreshold
        } ?: false

        return ThinkingResponse(
            result = result,
            thinkingBlocks = response.thinkingBlocks
        )
    }
}
