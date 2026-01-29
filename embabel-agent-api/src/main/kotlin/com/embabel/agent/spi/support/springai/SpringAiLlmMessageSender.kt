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

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.support.toEmbabelUsage
import com.embabel.agent.spi.loop.LlmMessageResponse
import com.embabel.agent.spi.loop.LlmMessageSender
import com.embabel.chat.Message
import com.embabel.common.util.loggerFor
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.tool.ToolCallback

/**
 * Spring AI implementation of [LlmMessageSender].
 *
 * Makes a single LLM inference call using Spring AI's ChatModel.
 * Does NOT execute tools - just returns the response including any tool call requests.
 * Tool execution is handled by [com.embabel.agent.spi.loop.ToolLoop].
 *
 * @param chatModel The Spring AI ChatModel to use for LLM calls
 * @param chatOptions Options for the LLM call (temperature, etc.)
 */
internal class SpringAiLlmMessageSender(
    private val chatModel: ChatModel,
    private val chatOptions: ChatOptions,
) : LlmMessageSender {

    private val logger = loggerFor<SpringAiLlmMessageSender>()

    override fun call(
        messages: List<Message>,
        tools: List<Tool>,
    ): LlmMessageResponse {
        // Convert Embabel messages to Spring AI messages
        val springAiMessages = messages.map { it.toSpringAiMessage() }

        // Convert Embabel tools to Spring AI tool callbacks using existing adapter
        val toolCallbacks = tools.toSpringToolCallbacks()

        // Build prompt with tool definitions (but NOT tool execution)
        val prompt = Prompt(
            springAiMessages,
            buildChatOptionsWithTools(toolCallbacks),
        )

        // Call LLM - returns response which may include tool call requests
        val response: ChatResponse = chatModel.call(prompt)

        logger.debug("Prompt: {}\nResponse: {}", prompt, response)

        // Convert response to Embabel message
        // Note: Some providers (e.g., Bedrock) may return multiple generations where
        // the first is empty and the second contains tool calls. We need to find the
        // generation with tool calls, or fall back to the first one if none have them.
        // See: https://github.com/embabel/embabel-agent/issues/1350
        val assistantMessage = findGenerationWithToolCalls(response) ?: response.result.output
        val embabelMessage = assistantMessage.toEmbabelMessage()

        // Extract usage information
        val usage = response.metadata?.usage?.toEmbabelUsage()

        return LlmMessageResponse(
            message = embabelMessage,
            textContent = assistantMessage.text ?: "",
            usage = usage,
        )
    }

    /**
     * Find the best generation to use from the response.
     *
     * Some providers (e.g., Bedrock) may return multiple generations where
     * the first is empty and a subsequent one contains tool calls.
     *
     * Strategy:
     * 1. Collect all tool calls from all generations
     * 2. Collect all text content from all generations
     * 3. If there are tool calls, create a merged AssistantMessage with all tool calls and combined text
     * 4. If no tool calls, return null to fall back to first generation
     *
     * This ensures we don't lose valuable content (text or tool calls) from any generation.
     *
     * @return A merged AssistantMessage with all tool calls and text, or null if no tool calls found
     */
    private fun findGenerationWithToolCalls(response: ChatResponse): org.springframework.ai.chat.messages.AssistantMessage? {
        val allOutputs = response.results.map { it.output }

        // Collect all tool calls from all generations
        val allToolCalls = allOutputs
            .flatMap { it.toolCalls ?: emptyList() }

        if (allToolCalls.isEmpty()) {
            return null // No tool calls found, let caller use first generation
        }

        // Collect all non-empty text from all generations
        val allText = allOutputs
            .mapNotNull { it.text?.takeIf { text -> text.isNotBlank() } }
            .joinToString("\n")

        // Log if we're merging content from multiple generations
        val generationsWithToolCalls = allOutputs.count { !it.toolCalls.isNullOrEmpty() }
        val generationsWithText = allOutputs.count { !it.text.isNullOrBlank() }
        if (generationsWithToolCalls > 1 || generationsWithText > 1) {
            logger.debug(
                "Merging content from multiple generations: {} with tool calls, {} with text",
                generationsWithToolCalls,
                generationsWithText
            )
        }

        return org.springframework.ai.chat.messages.AssistantMessage.builder()
            .content(allText)
            .toolCalls(allToolCalls)
            .build()
    }

    /**
     * Build ChatOptions with tool definitions.
     * Tools are passed to the LLM so it knows what's available,
     * but we don't use Spring AI's automatic tool execution.
     */
    private fun buildChatOptionsWithTools(toolCallbacks: List<ToolCallback>): ChatOptions {
        if (toolCallbacks.isEmpty()) {
            return chatOptions
        }

        // Use ToolCallingChatOptions to pass tool definitions
        // IMPORTANT: Disable internal tool execution - we handle tools ourselves in DefaultToolLoop
        return org.springframework.ai.model.tool.ToolCallingChatOptions.builder()
            .model(chatOptions.model)
            .temperature(chatOptions.temperature)
            .maxTokens(chatOptions.maxTokens)
            .topP(chatOptions.topP)
            .topK(chatOptions.topK)
            .frequencyPenalty(chatOptions.frequencyPenalty)
            .presencePenalty(chatOptions.presencePenalty)
            .stopSequences(chatOptions.stopSequences)
            .toolCallbacks(toolCallbacks)
            .internalToolExecutionEnabled(false)
            .build()
    }
}
