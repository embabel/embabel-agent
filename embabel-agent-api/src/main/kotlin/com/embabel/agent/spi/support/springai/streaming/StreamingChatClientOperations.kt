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
package com.embabel.agent.spi.support.springai.streaming

import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.spi.LlmInteraction
import com.embabel.agent.spi.streaming.StreamingLlmOperations
import com.embabel.agent.spi.support.springai.ChatClientLlmOperations
import com.embabel.agent.spi.support.springai.PROMPT_ELEMENT_SEPARATOR
import com.embabel.agent.spi.support.springai.toSpringAiMessage
import com.embabel.chat.Message
import com.embabel.common.ai.converters.streaming.StreamingJacksonOutputConverter
import com.embabel.common.core.streaming.StreamingEvent
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Streaming implementation that provides real-time LLM response processing with unified event streams.
 *
 * Delegates to ChatClientLlmOperations for core LLM functionality while adding sophisticated
 * streaming capabilities that handle chunk-to-line buffering, thinking content classification,
 * and typed object parsing.
 *
 * **Core Capabilities:**
 * - **Raw Text Streaming**: Direct access to LLM chunks as they arrive
 * - **Typed Object Streaming**: Real-time JSONL parsing to typed objects
 * - **Mixed Content Streaming**: Combined thinking + object events in unified stream
 * - **Error Resilience**: Individual line failures don't break the stream
 * - **Backpressure Support**: Full reactive streaming with lifecycle management
 *
 * **Unified Architecture:**
 * All streaming methods are built on a single internal pipeline that emits `StreamingEvent<T>`,
 * allowing consistent behavior and the flexibility to filter events as needed by different use cases.
 */
internal class StreamingChatClientOperations(
    private val chatClientLlmOperations: ChatClientLlmOperations,
) : StreamingLlmOperations {

    // once streaming feature gets stable set log level to TRACE
    private val logger = LoggerFactory.getLogger(StreamingChatClientOperations::class.java)

    /**
     * Build prompt contributions string from interaction and LLM contributors.
     * Consider helper
     */
    private fun buildPromptContributions(interaction: LlmInteraction, llm: com.embabel.common.ai.model.Llm): String {
        return (interaction.promptContributors + llm.promptContributors)
            .joinToString(PROMPT_ELEMENT_SEPARATOR) { it.contribution() }
    }

    /**
     * Build Spring AI Prompt from messages and contributions.
     * Consider helper
     */
    private fun buildSpringAiPrompt(messages: List<Message>, promptContributions: String): Prompt {
        return Prompt(
            buildList {
                if (promptContributions.isNotEmpty()) {
                    add(SystemMessage(promptContributions))
                }
                addAll(messages.map { it.toSpringAiMessage() })
            }
        )
    }

    override fun generateStream(
        messages: List<Message>,
        interaction: LlmInteraction,
        agentProcess: AgentProcess,
        action: Action?,
    ): Flux<String> {
        return doTransformStream(messages, interaction, null)
    }

    override fun <O> createObjectStream(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?,
    ): Flux<O> {
        return doTransformObjectStream(messages, interaction, outputClass, null)
    }

    override fun <O> createObjectStreamWithThinking(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?,
    ): Flux<StreamingEvent<O>> {
        return doTransformObjectStreamWithThinking(messages, interaction, outputClass, null)
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

    override fun doTransformStream(
        messages: List<Message>,
        interaction: LlmInteraction,
        llmRequestEvent: LlmRequestEvent<String>?,
    ): Flux<String> {
        // Use ChatClientLlmOperations to get LLM and create ChatClient
        val llm = chatClientLlmOperations.getLlm(interaction)
        val chatClient = chatClientLlmOperations.createChatClient(llm)

        // Build prompt using helper methods
        val promptContributions = buildPromptContributions(interaction, llm)
        val springAiPrompt = buildSpringAiPrompt(messages, promptContributions)

        val chatOptions = llm.optionsConverter.convertOptions(interaction.llm)

        return chatClient
            .prompt(springAiPrompt)
            .toolCallbacks(interaction.toolCallbacks)
            .options(chatOptions)
            .stream()
            .content()
    }

    /**
     * Creates a stream of typed objects from LLM JSONL responses, with thinking content suppressed.
     *
     * This method provides a clean object-only stream by filtering the internal unified stream
     * to exclude thinking content and extract only typed objects.
     *
     * **Stream Characteristics:**
     * - **Input**: Raw LLM chunks containing JSONL + thinking content
     * - **Processing**: Chunks → Lines → Events → Objects (thinking filtered out)
     * - **Output**: `Flux<O>` containing only parsed typed objects
     * - **Error Handling**: Malformed JSON is skipped; stream continues
     * - **Backpressure**: Supports standard Flux operators and subscription patterns
     *
     * **Example Usage:**
     * ```kotlin
     * val objectStream: Flux<User> = doTransformObjectStream(messages, interaction, User::class.java, null)
     *
     * objectStream
     *     .doOnNext { user -> println("Received user: ${user.name}") }
     *     .doOnError { error -> logger.error("Stream error", error) }
     *     .doOnComplete { println("Stream completed") }
     *     .subscribe()
     * ```
     *
     * **Difference from doTransformObjectStreamWithThinking:**
     * - This method: Returns `Flux<O>` with only objects (thinking suppressed)
     * - WithThinking: Returns `Flux<StreamingEvent<O>>` with both thinking and objects
     *
     * @param messages The conversation messages to send to LLM
     * @param interaction LLM configuration and context
     * @param outputClass The target class for object deserialization
     * @param llmRequestEvent Optional event for tracking/observability
     * @return Flux of typed objects, thinking content filtered out
     */
    override fun <O> doTransformObjectStream(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?,
    ): Flux<O> {
        return doTransformObjectStreamInternal(
            messages = messages,
            interaction = interaction,
            outputClass = outputClass,
            llmRequestEvent = llmRequestEvent
        )
            .filter { it.isObject() }
            .map { (it as StreamingEvent.Object).item }
    }

    /**
     * Creates a mixed stream containing both LLM thinking content and typed objects.
     *
     * This method returns the full unified stream without filtering, allowing users to receive
     * both thinking events (LLM reasoning) and object events (parsed JSON data) in the order
     * they appear in the LLM response.
     *
     * **Stream Characteristics:**
     * - **Input**: Raw LLM chunks containing JSONL + thinking content
     * - **Processing**: Chunks → Lines → Events (both thinking and objects preserved)
     * - **Output**: `Flux<StreamingEvent<O>>` with mixed content
     * - **Event Types**: `StreamingEvent.Thinking(content)` and `StreamingEvent.Object(data)`
     * - **Error Handling**: Malformed JSON treated as thinking content; stream continues
     *
     * **Example Usage:**
     * ```kotlin
     * val mixedStream: Flux<StreamingEvent<User>> = doTransformObjectStreamWithThinking(...)
     *
     * mixedStream.subscribe { event ->
     *     when {
     *         event.isThinking() -> println("LLM thinking: ${event.getThinking()}")
     *         event.isObject() -> println("User object: ${event.getObject()}")
     *     }
     * }
     * ```
     *
     * **User Filtering Options:**
     * ```kotlin
     * // Get only thinking content:
     * val thinkingOnly = mixedStream.filter { it.isThinking() }.map { it.getThinking()!! }
     *
     * // Get only objects (equivalent to doTransformObjectStream):
     * val objectsOnly = mixedStream.filter { it.isObject() }.map { it.getObject()!! }
     * ```
     *
     * @param messages The conversation messages to send to LLM
     * @param interaction LLM configuration and context
     * @param outputClass The target class for object deserialization
     * @param llmRequestEvent Optional event for tracking/observability
     * @return Flux of StreamingEvent<O> containing both thinking and object events
     */
    override fun <O> doTransformObjectStreamWithThinking(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?,
    ): Flux<StreamingEvent<O>> {
        return doTransformObjectStreamInternal(
            messages = messages,
            interaction = interaction,
            outputClass = outputClass,
            llmRequestEvent = llmRequestEvent
        )
    }

    /**
     * Internal unified streaming implementation - workhorse -that handles the complete transformation pipeline.
     *
     * This method implements a robust 3-step transformation pipeline:
     * 1. **Raw LLM Chunks**: Receives arbitrary-sized chunks from LLM via Spring AI ChatClient
     * 2. **Line Buffering**: Accumulates chunks into complete logical lines using stateful LineBuffer
     * 3. **Event Generation**: Classifies lines as thinking vs objects, converts to StreamingEvent<O>
     *
     * **Design Principles:**
     * - **Single Source of Truth**: All streaming logic centralized here
     * - **Error Isolation**: Malformed lines don't break the entire stream
     * - **Order Preservation**: Events maintain LLM response order via concatMap
     * - **Backpressure Support**: Full Flux lifecycle support with reactive operators
     *
     * **Event Types Generated:**
     * - `StreamingEvent.Thinking(content)`: LLM reasoning text (from `<think>` blocks or prefix thinking)
     * - `StreamingEvent.Object(data)`: Parsed typed objects from JSONL content
     *
     * **Error Handling Strategy:**
     * - Chunk processing errors: Skip chunk, continue stream
     * - Line classification errors: Treat as thinking content
     * - JSON parsing errors: Skip line, continue processing
     * - Stream continues on individual failures to maximize data recovery
     *
     * **Performance Characteristics:**
     * - Streaming-friendly: no blocking operations
     *
     * @return Unified Flux<StreamingEvent<O>> that public methods can filter as needed
     */
    private fun <O> doTransformObjectStreamInternal(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        @Suppress("UNUSED_PARAMETER")
        llmRequestEvent: LlmRequestEvent<O>?,
    ): Flux<StreamingEvent<O>> {
        // Common setup - delegate to ChatClientLlmOperations for LLM setup
        val llm = chatClientLlmOperations.getLlm(interaction)
        // Chat Client
        val chatClient = chatClientLlmOperations.createChatClient(llm)
        // Chat Options, additional potential option "streaming"
        val chatOptions = llm.optionsConverter.convertOptions(interaction.llm)

        val streamingConverter = StreamingJacksonOutputConverter(
            clazz = outputClass,
            objectMapper = chatClientLlmOperations.objectMapper,
            propertyFilter = interaction.propertyFilter
        )

        // Build prompt using helper methods, including streaming format instructions
        val promptContributions = buildPromptContributions(interaction, llm)
        val streamingFormatInstructions = streamingConverter.getFormat()
        logger.debug("STREAMING FORMAT INSTRUCTIONS: $streamingFormatInstructions")
        val fullPromptContributions = if (promptContributions.isNotEmpty()) {
            "$promptContributions$PROMPT_ELEMENT_SEPARATOR$streamingFormatInstructions"
        } else {
            streamingFormatInstructions
        }
        val springAiPrompt = buildSpringAiPrompt(messages, fullPromptContributions)


        // Step 1: Original raw chunk stream from LLM
        val rawChunkFlux: Flux<String> = chatClient
            .prompt(springAiPrompt)
            .toolCallbacks(interaction.toolCallbacks)
            .options(chatOptions)
            .stream()
            .content()
            .filter { it.isNotEmpty() }
            .doOnNext { chunk -> logger.debug("RAW CHUNK: '${chunk.replace("\n", "\\n")}'") }

        // Step 2: Transform raw chunks to complete newline-delimited lines
        val lineFlux: Flux<String> = rawChunkFlux
            .transform { chunkFlux -> rawChunksToLines(chunkFlux) }
            .doOnNext { line -> logger.debug("COMPLETE LINE: '$line'") }

        // Step 3: Final flux of StreamingEvent (thinking + objects)
        val event = lineFlux
            .concatMap { line -> streamingConverter.convertStreamWithThinking(line) }

        return event
    }

    /**
     * Convert raw streaming chunks → NDJSON lines
     * Handles all general cases:
     * - multiple \n in one chunk
     * - no \n in chunk
     * - line spanning many chunks
     */
    fun rawChunksToLines(raw: Flux<String>): Flux<String> {
        val buffer = StringBuffer()
        return raw.concatMap { chunk ->  // ONLY CHANGE: handle → concatMap
            buffer.append(chunk)
            val lines = mutableListOf<String>()
            while (true) {
                val idx = buffer.indexOf('\n')
                if (idx < 0) break
                val line = buffer.substring(0, idx).trim()
                if (line.isNotEmpty()) lines.add(line)
                buffer.delete(0, idx + 1)
            }

            Flux.fromIterable(lines)  // emit multiple lines

        }.doOnComplete {
            // Log any remaining buffer content when stream ends
            if (buffer.isNotEmpty()) {
                val finalLine = buffer.toString().trim()
                if (finalLine.isNotEmpty()) {
                    logger.debug("FINAL LINE: '$finalLine'")
                }
            }
        }.concatWith(
            // final emit
            Mono.fromSupplier { buffer.toString().trim() }
                .filter { it.isNotEmpty() }
        )
    }


}
