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
import com.embabel.agent.spi.loop.LlmMessageRequest
import com.embabel.agent.spi.loop.LlmMessageResponse
import com.embabel.agent.spi.loop.LlmMessageSender
import com.embabel.agent.spi.loop.RequestAwareLlmMessageSender
import com.embabel.agent.spi.loop.StructuredOutputRequest
import com.embabel.chat.Message
import com.embabel.common.ai.autoconfig.NativeSupport
import com.embabel.common.ai.model.LlmMetadata
import com.embabel.common.util.loggerFor
import com.embabel.agent.spi.support.nativeoutput.shouldUseNativeStructuredOutput
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
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
 * @param toolResponseContentAdapter Adapts tool response content for provider-specific
 *        format requirements (e.g., JSON wrapping for Google GenAI)
 */
internal class SpringAiLlmMessageSender(
    private val chatModel: ChatModel,
    private val chatOptions: ChatOptions,
    private val toolResponseContentAdapter: ToolResponseContentAdapter = ToolResponseContentAdapter.PASSTHROUGH,
    private val nativeStructuredOutputConfigurer: SpringAiNativeStructuredOutputConfigurer =
        SpringAiNativeStructuredOutputConfigurer.NOOP,
    private val nativeSupport: NativeSupport? = null,
    private val llmMetadata: LlmMetadata? = null,
) : RequestAwareLlmMessageSender {

    private val logger = loggerFor<SpringAiLlmMessageSender>()

    override fun call(
        messages: List<Message>,
        tools: List<Tool>,
    ): LlmMessageResponse = call(
        LlmMessageRequest(
            messages = messages,
            tools = tools,
        )
    )

    override fun call(request: LlmMessageRequest): LlmMessageResponse {
        // Convert Embabel messages to Spring AI messages, applying provider-specific
        // tool response formatting (e.g., JSON wrapping for Google GenAI)
        val springAiMessages = request.messages
            .map { it.toSpringAiMessage(toolResponseContentAdapter) }
            .mergeConsecutiveToolResponses()

        // Convert Embabel tools to Spring AI tool callbacks using existing adapter
        val toolCallbacks = request.tools.toSpringToolCallbacks()

        // Build prompt with tool definitions (but NOT tool execution)
        val effectiveStructuredOutput = if (nativeSupport.shouldUseNativeStructuredOutput(request)) {
            logger.debug("Native structured output enabled for this call")
            request.nativeStructuredOutputRequest?.structuredOutputRequest
        } else {
            logger.debug("Native structured output disabled for this call; using fallback path")
            null
        }

        val prompt = Prompt(
            springAiMessages,
            buildChatOptions(effectiveStructuredOutput, toolCallbacks),
        )

        // Call LLM - returns response which may include tool call requests
        val response: ChatResponse = chatModel.call(prompt)

        logger.debug("Prompt: {}\nResponse: {}", prompt, response)

        // Convert response to Embabel message.

        // Providers may return multiple generations in one ChatResponse:
        // - Bedrock: empty first generation, tool calls on a later one (#1350)
        // - Google GenAI includeThoughts: thought parts first (isThought=true), answer later.

        // Using only ChatResponse.result (first generation) discards later answer text
        // and breaks structured output / createObject after thought-signature support.
        val assistantMessage = resolveAssistantMessage(response)
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
     * Resolve a Spring AI response into the assistant message Embabel should store and inspect.
     *
     * Spring AI exposes provider response parts as generations. For providers that split a
     * single answer across generations, this method preserves the pieces Embabel needs while
     * avoiding unsafe concatenation of alternative structured answers.
     *
     * Strategy:
     *
     * 1. Collect tool calls and metadata from every generation.
     * 2. Prefer non-thought text (Google GenAI sets metadata `isThought=true` on thought parts).
     *    Fall back to the first non-blank generation text when `isThought` is absent.
     * 3. If tool calls exist, return a merged message with all tool calls and selected text.
     * 4. If only text exists, return selected answer text with merged metadata.
     */
    private fun resolveAssistantMessage(
        response: ChatResponse,
    ): AssistantMessage {
        val allOutputs = response.results.map { it.output }
        require(allOutputs.isNotEmpty()) { "ChatResponse contained no generations" }

        val allToolCalls = allOutputs.flatMap { it.toolCalls ?: emptyList() }
        val allMetaData: Map<String, Any> = allOutputs
            .mapNotNull { it.metadata }
            .fold(emptyMap()) { acc, metadata -> acc + metadata }

        val answerText = if (allToolCalls.isNotEmpty()) {
            selectToolCallText(allOutputs)
        } else {
            selectAnswerText(allOutputs)
        }
        val generationsWithToolCalls = allOutputs.count { !it.toolCalls.isNullOrEmpty() }
        val generationsWithText = allOutputs.count { !it.text.isNullOrBlank() }
        if (generationsWithToolCalls > 1 || generationsWithText > 1) {
            logger.debug(
                "Resolving multi-generation ChatResponse: {} with tool calls, {} with text, selected answer length={}",
                generationsWithToolCalls,
                generationsWithText,
                answerText.length,
            )
        }

        if (allToolCalls.isNotEmpty()) {
            return AssistantMessage.builder()
                .content(answerText)
                .toolCalls(allToolCalls)
                .properties(allMetaData)
                .build()
        }

        return AssistantMessage.builder()
            .content(answerText)
            .properties(allMetaData)
            .build()
    }

    /**
     * Select text that should be treated as the model answer for structured conversion.
     *
     * Google GenAI with includeThoughts emits one generation per part; thought parts are
     * marked with metadata isThought=true and must not be used alone as the JSON payload.
     * When multiple non-thought texts are present, the first one is selected because those
     * generations may be alternative candidates rather than chunks of one JSON document.
     */
    private fun selectAnswerText(
        allOutputs: List<AssistantMessage>,
    ): String {
        val nonThoughtTexts = allOutputs
            .filterNot { isThoughtGeneration(it) }
            .mapNotNull { it.text?.takeIf { text -> text.isNotBlank() } }
        if (nonThoughtTexts.isNotEmpty()) {
            return nonThoughtTexts.first()
        }
        // No non-thought text (or provider does not mark thoughts): use first non-blank content
        return allOutputs
            .mapNotNull { it.text?.takeIf { text -> text.isNotBlank() } }
            .firstOrNull()
            ?: ""
    }

    /**
     * Select text for responses that include tool calls.
     *
     * Tool-call responses need to keep tool calls from all generations. Text handling is more
     * conservative: if no generation is marked as thought, all non-blank text is joined to
     * preserve Bedrock-style split responses. If thought markers are present, thought text is
     * removed so structured answer content and tool continuation metadata stay coherent.
     */
    private fun selectToolCallText(
        allOutputs: List<AssistantMessage>,
    ): String {
        val textOutputs = allOutputs.mapNotNull { it.text?.takeIf { text -> text.isNotBlank() } }
        if (allOutputs.none { isThoughtGeneration(it) }) {
            return textOutputs.joinToString("\n")
        }
        return allOutputs
            .filterNot { isThoughtGeneration(it) }
            .mapNotNull { it.text?.takeIf { text -> text.isNotBlank() } }
            .joinToString("\n")
    }

    /**
     * Return true when a generation is provider-marked as model thinking rather than final
     * assistant answer text.
     *
     * Spring AI's Google GenAI adapter uses Boolean `true`; trimmed string values are accepted
     * so metadata copied through less strongly typed paths is still filtered correctly.
     */
    private fun isThoughtGeneration(
        message: AssistantMessage,
    ): Boolean = when (val isThought = message.metadata?.get(IS_THOUGHT_METADATA_KEY)) {
        true -> true
        is String -> isThought.trim().equals("true", ignoreCase = true)
        else -> false
    }

    companion object {
        /**
         * Metadata key set by Spring AI Google GenAI on thought parts
         * (`GoogleGenAiChatModel.responseCandidateToGeneration`).
         */
        const val IS_THOUGHT_METADATA_KEY: String = "isThought"
    }

    /**
     * Build ChatOptions with tool definitions.
     * Tools are passed to the LLM so it knows what's available,
     * but we don't use Spring AI's automatic tool execution.
     *
     * This method preserves provider-specific options (like Anthropic's cache settings)
     * by using the provider's copy() method when the options already implement ToolCallingChatOptions.
     */
    private fun buildChatOptions(
        structuredOutput: StructuredOutputRequest?,
        toolCallbacks: List<ToolCallback>,
    ): ChatOptions {
        val optionsWithTools = buildChatOptionsWithTools(toolCallbacks)
        return nativeStructuredOutputConfigurer.configure(
            options = optionsWithTools,
            structuredOutput = structuredOutput,
            nativeSupport = nativeSupport,
            llm = llmMetadata,
        )
    }

    private fun buildChatOptionsWithTools(toolCallbacks: List<ToolCallback>): ChatOptions {
        if (toolCallbacks.isEmpty()) {
            return chatOptions
        }

        // If chatOptions already implements ToolCallingChatOptions (e.g., AnthropicChatOptions,
        // OpenAiChatOptions, etc.), use its copy() method to preserve all provider-specific settings
        if (chatOptions is ToolCallingChatOptions) {
            // Spring AI 2.0 GA: toolCallbacks are read-only on ToolCallingChatOptions, so use
            // mutate() to derive a new instance, preserving the provider-specific subtype (e.g.
            // Anthropic cache settings) while attaching our tool callbacks. The per-request
            // internalToolExecutionEnabled flag was removed in 2.0.0; disabling Spring AI's
            // automatic tool execution is now configured on the ChatModel (ToolCallingManager /
            // ToolExecutionEligibilityPredicate) so embabel's DefaultToolLoop stays in control.
            return chatOptions.mutate()
                .toolCallbacks(toolCallbacks)
                .build()
        }

        // Fallback: Create generic ToolCallingChatOptions.
        // We handle tools ourselves in DefaultToolLoop. Spring AI 2.0 GA removed the per-request
        // internalToolExecutionEnabled flag; internal execution is disabled on the ChatModel.
        return ToolCallingChatOptions.builder()
            .model(chatOptions.model)
            .temperature(chatOptions.temperature)
            .maxTokens(chatOptions.maxTokens)
            .topP(chatOptions.topP)
            .topK(chatOptions.topK)
            .frequencyPenalty(chatOptions.frequencyPenalty)
            .presencePenalty(chatOptions.presencePenalty)
            .stopSequences(chatOptions.stopSequences)
            .toolCallbacks(toolCallbacks)
            .build()
    }
}
