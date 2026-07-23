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
package com.embabel.agent.spi.support.streaming

import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.callback.ToolCallInspector
import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.ToolDecorator
import com.embabel.agent.spi.loop.streaming.LlmMessageStreamer
import com.embabel.agent.spi.loop.streaming.LlmStreamChunk
import com.embabel.agent.core.internal.streaming.StreamingLlmOperations
import com.embabel.agent.spi.support.PROMPT_ELEMENT_SEPARATOR
import com.embabel.agent.spi.support.ToolResolutionHelper
import com.embabel.agent.spi.support.buildConsolidatedPromptMessages
import com.embabel.agent.spi.support.buildPromptContributionsString
import com.embabel.agent.spi.support.guardrails.validateUserInput
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.ai.converters.streaming.StreamingJacksonOutputConverter
import com.embabel.common.core.streaming.StreamingEvent
import tools.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux

/**
 * Vendor-neutral implementation of [StreamingLlmOperations].
 *
 * This class provides streaming LLM operations without depending on any specific
 * LLM framework (Spring AI, LangChain4j, etc.). It delegates raw streaming to
 * [LlmMessageStreamer] and handles:
 * - Line buffering from raw chunks
 * - JSONL parsing to typed objects
 * - Thinking content extraction
 *
 * @param messageStreamer The streamer for raw LLM content
 * @param objectMapper ObjectMapper for JSON parsing
 * @param llmService The LLM service for prompt contributions
 * @param toolDecorator Decorator to make tools platform-aware
 */
internal class StreamingLlmOperationsImpl(
    private val messageStreamer: LlmMessageStreamer,
    private val objectMapper: ObjectMapper,
    private val llmService: LlmService<*>,
    private val toolDecorator: ToolDecorator,
) : StreamingLlmOperations {

    private val logger = LoggerFactory.getLogger(StreamingLlmOperationsImpl::class.java)

    // ========================================
    // Public API methods
    // ========================================

    override fun generateStream(
        messages: List<Message>,
        interaction: LlmInteraction,
        agentProcess: AgentProcess,
        action: Action?,
    ): Flux<String> {
        return doTransformStream(messages, interaction, null, agentProcess, action)
    }

    override fun generateStreamWithThinking(
        messages: List<Message>,
        interaction: LlmInteraction,
        agentProcess: AgentProcess,
        action: Action?,
    ): Flux<StreamingEvent<String>> =
        doTransformStreamWithThinking(messages, interaction, null, agentProcess, action)

    override fun <O> createObjectStream(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?,
    ): Flux<O> {
        return doTransformObjectStream(messages, interaction, outputClass, null, agentProcess, action)
    }

    override fun <O> createObjectStreamIfPossible(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?,
    ): Flux<Result<O>> {
        return createObjectStream(messages, interaction, outputClass, agentProcess, action)
            .map { Result.success(it) }
            .onErrorResume { throwable ->
                Flux.just(Result.failure(throwable))
            }
    }

    override fun <O> createObjectStreamWithThinking(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?,
    ): Flux<StreamingEvent<O>> {
        return doTransformObjectStreamWithThinking(messages, interaction, outputClass, null, agentProcess, action)
    }

    // ========================================
    // Low-level transform methods
    // ========================================

    override fun doTransformStream(
        messages: List<Message>,
        interaction: LlmInteraction,
        llmRequestEvent: LlmRequestEvent<String>?,
        agentProcess: AgentProcess?,
        action: Action?,
    ): Flux<String> {
        return streamResponse(
            messages = messages,
            interaction = interaction,
            llmRequestEvent = llmRequestEvent,
            agentProcess = agentProcess,
            action = action,
        ) { preparedMessages, tools, inspectors ->
            messageStreamer.stream(preparedMessages, tools, inspectors)
        }
    }

    override fun doTransformStreamWithThinking(
        messages: List<Message>,
        interaction: LlmInteraction,
        llmRequestEvent: LlmRequestEvent<String>?,
        agentProcess: AgentProcess?,
        action: Action?,
    ): Flux<StreamingEvent<String>> {
        val chunks: Flux<LlmStreamChunk> = streamResponse(
            messages = messages,
            interaction = interaction,
            llmRequestEvent = llmRequestEvent,
            agentProcess = agentProcess,
            action = action,
        ) { preparedMessages, tools, inspectors ->
            messageStreamer.streamWithThinking(preparedMessages, tools, inspectors)
        }
        return chunks.toTaggedThinkingEvents()
    }

    override fun <O> doTransformObjectStream(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?,
        agentProcess: AgentProcess?,
        action: Action?,
    ): Flux<O> {
        return doTransformObjectStreamInternal(
            messages = messages,
            interaction = interaction,
            outputClass = outputClass,
            llmRequestEvent = llmRequestEvent,
            agentProcess = agentProcess,
            action = action,
        )
            .filter { it.isObject() }
            .map { (it as StreamingEvent.Object).item }
    }

    override fun <O> doTransformObjectStreamWithThinking(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?,
        agentProcess: AgentProcess?,
        action: Action?,
    ): Flux<StreamingEvent<O>> {
        return doTransformObjectStreamInternal(
            messages = messages,
            interaction = interaction,
            outputClass = outputClass,
            llmRequestEvent = llmRequestEvent,
            agentProcess = agentProcess,
            action = action,
        )
    }

    // ========================================
    // Internal implementation
    // ========================================

    /**
     * Internal unified streaming implementation that handles the complete transformation pipeline.
     *
     * Pipeline:
     * 1. Structured LLM chunks from [LlmMessageStreamer]
     * 2. Per-subscription line buffering via [toThinkingStreamItems]
     * 3. Event generation via [StreamingJacksonOutputConverter]
     */
    private fun <O> doTransformObjectStreamInternal(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        @Suppress("UNUSED_PARAMETER")
        llmRequestEvent: LlmRequestEvent<O>?,
        agentProcess: AgentProcess?,
        action: Action?,
    ): Flux<StreamingEvent<O>> {
        // Create converter for JSONL parsing.
        // Spring AI 2.0's StreamingJacksonOutputConverter requires T : Any;
        // erase O via Class<Any> for the construction, cast back for downstream Flux<O>/StreamingEvent<O>.
        @Suppress("UNCHECKED_CAST")
        val outputClassAny = outputClass as Class<Any>
        @Suppress("UNCHECKED_CAST")
        val streamingConverter = StreamingJacksonOutputConverter<Any>(
            clazz = outputClassAny,
            objectMapper = objectMapper,
            fieldFilter = interaction.fieldFilter,
            thinkingEnabled = interaction.llm.thinking?.enabled ?: false,
        ) as StreamingJacksonOutputConverter<O>

        // Build prompt contributions with streaming format instructions
        val promptContributions = buildPromptContributions(interaction)
        val streamingFormatInstructions = streamingConverter.getFormat()
        logger.debug("STREAMING FORMAT INSTRUCTIONS: $streamingFormatInstructions")
        val fullPromptContributions = if (promptContributions.isNotEmpty()) {
            "$promptContributions$PROMPT_ELEMENT_SEPARATOR$streamingFormatInstructions"
        } else {
            streamingFormatInstructions
        }

        // Guardrails: Pre-validation of user input
        val userMessages = messages.filterIsInstance<UserMessage>()
        validateUserInput(userMessages, interaction, llmRequestEvent?.agentProcess?.blackboard)

        // Resolve and decorate tools
        val tools = resolveTools(interaction, agentProcess, action)

        // Build messages with contributions
        val messagesWithContributions = buildMessagesWithContributions(messages, fullPromptContributions)

        // Provider thinking is kept as an ordered event before text from the same chunk.
        val rawChunkFlux = messageStreamer.streamWithThinking(messagesWithContributions, tools, interaction.toolCallInspectors)
            .filter { it.textContent.isNotEmpty() || it.thinkingContent.any { thinking -> thinking.isNotBlank() } }
            .doOnNext { chunk -> logger.trace("RAW STRUCTURED CHUNK: $chunk") }

        return rawChunkFlux.toThinkingStreamItems()
            .doOnNext { item -> logger.trace("STREAM ITEM: $item") }
            .concatMap { item ->
                when (item) {
                    is ThinkingStreamItem.Thinking -> Flux.just(StreamingEvent.Thinking(item.content))
                    is ThinkingStreamItem.Text -> streamingConverter.convertStreamWithThinking(item.content)
                }
            }
    }

    private fun <T> streamResponse(
        messages: List<Message>,
        interaction: LlmInteraction,
        llmRequestEvent: LlmRequestEvent<*>?,
        agentProcess: AgentProcess?,
        action: Action?,
        stream: (List<Message>, List<Tool>, List<ToolCallInspector>) -> Flux<T>,
    ): Flux<T> {
        val promptContributions = buildPromptContributions(interaction)
        val userMessages = messages.filterIsInstance<UserMessage>()
        validateUserInput(userMessages, interaction, llmRequestEvent?.agentProcess?.blackboard)
        val tools = resolveTools(interaction, agentProcess, action)
        val messagesWithContributions = buildMessagesWithContributions(messages, promptContributions)
        return stream(messagesWithContributions, tools, interaction.toolCallInspectors)
    }

    // ========================================
    // Helper methods
    // ========================================

    /**
     * Build prompt contributions string from interaction and LLM contributors.
     */
    private fun buildPromptContributions(interaction: LlmInteraction): String =
        buildPromptContributionsString(interaction.promptContributors, llmService.promptContributors)

    /**
     * Build message list with prompt contributions and any existing system messages
     * consolidated into a single system message at the beginning.
     */
    private fun buildMessagesWithContributions(
        messages: List<Message>,
        promptContributions: String,
    ): List<Message> = buildConsolidatedPromptMessages(messages, promptContributions)

    /**
     * Resolve and decorate tools using [ToolResolutionHelper].
     */
    private fun resolveTools(
        interaction: LlmInteraction,
        agentProcess: AgentProcess?,
        action: Action?,
    ): List<Tool> {
        return ToolResolutionHelper.resolveAndDecorate(interaction, agentProcess, action, toolDecorator)
    }

}
