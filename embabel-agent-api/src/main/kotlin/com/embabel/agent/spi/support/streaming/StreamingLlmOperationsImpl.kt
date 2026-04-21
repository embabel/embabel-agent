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
import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.ToolDecorator
import com.embabel.agent.spi.loop.streaming.LlmMessageStreamer
import com.embabel.agent.core.internal.streaming.StreamingLlmOperations
import com.embabel.agent.spi.support.PROMPT_ELEMENT_SEPARATOR
import com.embabel.agent.spi.support.ToolResolutionHelper
import com.embabel.agent.spi.support.buildConsolidatedPromptMessages
import com.embabel.agent.spi.support.buildPromptContributionsString
import com.embabel.agent.spi.support.guardrails.validateUserInput
import com.embabel.chat.Message
import com.embabel.chat.SystemMessage
import com.embabel.chat.UserMessage
import com.embabel.common.ai.converters.streaming.StreamingJacksonOutputConverter
import com.embabel.common.core.streaming.StreamingEvent
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

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
        // Build prompt contributions
        val promptContributions = buildPromptContributions(interaction)

        // Guardrails: Pre-validation of user input
        val userMessages = messages.filterIsInstance<UserMessage>()
        validateUserInput(userMessages, interaction, llmRequestEvent?.agentProcess?.blackboard)

        // Resolve and decorate tools
        val tools = resolveTools(interaction, agentProcess, action)

        // Build messages with contributions
        val messagesWithContributions = buildMessagesWithContributions(messages, promptContributions)

        // Stream raw chunks from LLM
        return messageStreamer.stream(messagesWithContributions, tools)
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
     * 1. Raw LLM chunks from [LlmMessageStreamer]
     * 2. Line buffering via [rawChunksToLines]
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
        // Create converter for JSONL parsing
        val streamingConverter = StreamingJacksonOutputConverter(
            clazz = outputClass,
            objectMapper = objectMapper,
            fieldFilter = interaction.fieldFilter
        )

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

        // Step 1: Raw chunk stream from LLM
        val rawChunkFlux: Flux<String> = messageStreamer.stream(messagesWithContributions, tools)
            .filter { it.isNotEmpty() }
            .doOnNext { chunk -> logger.trace("RAW CHUNK: '${chunk.replace("\n", "\\n")}'") }

        // Step 2: Transform raw chunks to complete newline-delimited lines
        val lineFlux: Flux<String> = rawChunkFlux
            .transform { chunkFlux -> rawChunksToLines(chunkFlux) }
            .doOnNext { line -> logger.trace("COMPLETE LINE: '$line'") }

        // Step 3: Convert lines to StreamingEvent (thinking + objects)
        return lineFlux.concatMap { line -> streamingConverter.convertStreamWithThinking(line) }
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

    /**
     * Convert raw streaming chunks to NDJSON lines.
     * Handles all cases: multiple \n in one chunk, no \n in chunk, line spanning many chunks.
     */
    private fun rawChunksToLines(raw: Flux<String>): Flux<String> {
        val buffer = StringBuilder()
        return raw.concatMap { chunk ->
            buffer.append(chunk)
            val lines = mutableListOf<String>()
            while (true) {
                val idx = buffer.indexOf('\n')
                if (idx < 0) break
                val line = buffer.substring(0, idx).trim()
                if (line.isNotEmpty()) lines.add(line)
                buffer.delete(0, idx + 1)
            }
            Flux.fromIterable(lines)
        }.doOnComplete {
            if (buffer.isNotEmpty()) {
                val finalLine = buffer.toString().trim()
                if (finalLine.isNotEmpty()) {
                    logger.trace("FINAL LINE: '$finalLine'")
                }
            }
        }.concatWith(
            Mono.fromSupplier { buffer.toString().trim() }
                .filter { it.isNotEmpty() }
        )
    }
}
