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

import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.event.LlmRequestEvent
import com.embabel.agent.spi.LlmInteraction
import com.embabel.agent.spi.streaming.StreamingLlmOperations
import com.embabel.agent.spi.support.springai.ChatClientLlmOperations
import com.embabel.agent.spi.support.springai.PROMPT_ELEMENT_SEPARATOR
import com.embabel.agent.spi.support.springai.toSpringAiMessage
import com.embabel.chat.Message
import com.embabel.common.ai.converters.streaming.StreamingJacksonOutputConverter
import com.embabel.common.core.streaming.StreamingEvent
import com.embabel.common.core.streaming.StreamingUtils
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux

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

    private val logger = LoggerFactory.getLogger(StreamingChatClientOperations::class.java)

    /**
     * Build prompt contributions string from interaction and LLM contributors.
     */
    private fun buildPromptContributions(interaction: LlmInteraction, llm: com.embabel.common.ai.model.Llm): String {
        return (interaction.promptContributors + llm.promptContributors)
            .joinToString(PROMPT_ELEMENT_SEPARATOR) { it.contribution() }
    }

    /**
     * Build Spring AI Prompt from messages and contributions.
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
        action: Action?
    ): Flux<String> {
        return doTransformStream(messages, interaction, null)
    }

    override fun <O> createObjectStream(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?
    ): Flux<O> {
        return doTransformObjectStream(messages, interaction, outputClass, null)
    }

    override fun <O> createObjectStreamWithThinking(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?
    ): Flux<StreamingEvent<O>> {
        return doTransformObjectStreamWithThinking(messages, interaction, outputClass, null)
    }

    override fun <O> createObjectStreamIfPossible(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?
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
        llmRequestEvent: LlmRequestEvent<String>?
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
        llmRequestEvent: LlmRequestEvent<O>?
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
        llmRequestEvent: LlmRequestEvent<O>?
    ): Flux<StreamingEvent<O>> {
        return doTransformObjectStreamInternal(
            messages = messages,
            interaction = interaction,
            outputClass = outputClass,
            llmRequestEvent = llmRequestEvent
        )
    }

    /**
     * Internal unified streaming implementation that handles the complete transformation pipeline.
     *
     * This method implements a robust 3-step transformation pipeline:
     * 1. **Raw LLM Chunks**: Receives arbitrary-sized chunks from LLM via Spring AI ChatClient
     * 2. **Line Buffering**: Accumulates chunks into complete logical lines using stateful LineBuffer
     * 3. **Event Generation**: Classifies lines as thinking vs objects, converts to StreamingEvent<O>
     *
     * **Architecture Principles:**
     * - **Single Source of Truth**: All streaming logic centralized here
     * - **Error Isolation**: Malformed lines don't break the entire stream
     * - **Order Preservation**: Events maintain LLM response order via concatMap
     * - **Backpressure Support**: Full Flux lifecycle support with reactive operators
     * - **Comprehensive Logging**: Detailed tracing for debugging streaming issues
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
     * - Batch line processing for efficiency
     * - Sequential processing within batches for order
     * - Minimal object allocation via reusable buffers
     * - Streaming-friendly: no blocking operations
     *
     * @return Unified Flux<StreamingEvent<O>> that public methods can filter as needed
     */
    private fun <O> doTransformObjectStreamInternal(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?
    ): Flux<StreamingEvent<O>> {
        // Common setup - delegate to ChatClientLlmOperations for LLM setup
        val llm = chatClientLlmOperations.getLlm(interaction)
        val chatClient = chatClientLlmOperations.createChatClient(llm)

        // Build prompt using helper methods
        val promptContributions = buildPromptContributions(interaction, llm)
        val springAiPrompt = buildSpringAiPrompt(messages, promptContributions)

        val chatOptions = llm.optionsConverter.convertOptions(interaction.llm)

        val streamingConverter = StreamingJacksonOutputConverter(
            clazz = outputClass,
            objectMapper = chatClientLlmOperations.objectMapper,
            propertyFilter = interaction.propertyFilter
        )

        // Step 1: Raw LLM chunk stream with lifecycle support
        val rawChunkStream: Flux<String> = chatClient
            .prompt(springAiPrompt)
            .toolCallbacks(interaction.toolCallbacks)
            .options(chatOptions)
            .stream()
            .content()
            .doOnSubscribe { logger.debug("Starting LLM streaming for interaction: ${interaction.id}") }
            .doOnNext { chunk -> logger.trace("Received LLM chunk: [${chunk.length} chars]") }
            .doOnError { error -> logger.error("LLM streaming error for interaction: ${interaction.id}", error) }
            .doOnComplete { logger.debug("LLM streaming completed for interaction: ${interaction.id}") }
            .doOnCancel { logger.debug("LLM streaming cancelled for interaction: ${interaction.id}") }

        // Step 2: Transform raw chunks to complete lines with proper stateful buffering
        //
        // This is the heart of chunk-to-line conversion. The scan operation maintains state
        // between chunk arrivals while emitting results as soon as complete lines are available.
        //
        // Scan State: Pair(LineBuffer, List<String>)
        // - LineBuffer: Accumulates text and tracks processed line count
        // - List<String>: Complete lines ready for processing (emitted each iteration)
        //
        // Process Flow:
        // 1. Chunk arrives: "partial text"  → buffer accumulates, no lines ready → emit Pair(buffer, [])
        // 2. Chunk arrives: " more\nline1\n" → buffer finds complete line → emit Pair(buffer, ["partial text more", "line1"])
        // 3. Chunk arrives: "partial2"     → buffer accumulates, no new lines → emit Pair(buffer, [])
        // 4. Chunk arrives: "\nline2\n"    → buffer finds 2 new lines → emit Pair(buffer, ["partial2", "line2"])
        //
        // Key Benefits:
        // - Asynchronous: Each chunk triggers immediate processing
        // - Stateful: Buffer remembers partial lines between chunks
        // - Progressive: Lines emitted as soon as they're complete
        // - Error-resilient: Bad chunks don't break the stream
        val lineStream: Flux<List<String>> = rawChunkStream
            .scan(Pair(LineBuffer(), emptyList<String>())) { (prevBuffer, _), chunk ->
                try {
                    // Step 2a: Add new chunk to accumulated buffer
                    val bufferWithChunk = prevBuffer.addChunk(chunk)

                    // Step 2b: Extract any complete lines that are now available
                    // This returns: (updatedBufferState, newlyCompletedLines)
                    val (updatedBuffer, readyLines) = bufferWithChunk.getReadyToProcessLines()

                    // Step 2c: Return new state for next iteration + lines to emit now
                    Pair(updatedBuffer, readyLines.filter { it.isNotBlank() })
                } catch (e: Exception) {
                    // Error handling: Keep previous buffer state, emit no lines
                    logger.warn("Error processing chunk in line buffer for interaction: ${interaction.id}", e)
                    Pair(prevBuffer, emptyList())
                }
            }
            .skip(1)  // Skip the initial empty state
            .map { (_, lines) -> lines }  // Extract just the lines
            .filter { lines -> lines.isNotEmpty() }  // Only emit when we have lines to process

        // Step 3: Transform line batches to streaming events with careful ordering and error isolation
        val eventStream: Flux<StreamingEvent<O>> = lineStream
            .concatMap { lineBatch ->
                // Process each batch sequentially to maintain order
                processLineBatch(lineBatch, streamingConverter, interaction)
            }
            .doOnComplete { logger.debug("Streaming event generation completed for interaction: ${interaction.id}") }

        return eventStream
    }

    /**
     * Processes a batch of lines with error isolation - ensures one bad line doesn't break the entire stream.
     *
     * Key principles:
     * 1. Maintain order: Lines are processed sequentially within the batch
     * 2. Error isolation: Parsing failures are isolated per line
     * 3. Partial success: Batch can emit some events even if some lines fail
     * 4. Backpressure-friendly: Uses Flux.concat for sequential processing
     *
     * @param lineBatch List of complete lines to process
     * @param streamingConverter The JSON converter
     * @param interaction For logging context
     * @return Flux of StreamingEvent<O> maintaining line order
     */
    private fun <O> processLineBatch(
        lineBatch: List<String>,
        streamingConverter: StreamingJacksonOutputConverter<O>,
        interaction: LlmInteraction
    ): Flux<StreamingEvent<O>> {
        if (lineBatch.isEmpty()) {
            return Flux.empty()
        }

        // Process each line in order, with individual error handling
        val lineFluxes = lineBatch.map { line ->
            classifyAndConvertLine(line, streamingConverter)
                .doOnNext { event ->
                    logger.trace("Processed line to event: ${event.javaClass.simpleName} for interaction: ${interaction.id}")
                }
                .onErrorResume { error ->
                    logger.warn("Failed to process line in batch for interaction: ${interaction.id}, line: '${line.take(100)}...', error: ${error.message}")
                    Flux.empty()  // Skip failed line but continue batch
                }
        }

        // Concatenate all line results in order (sequential processing)
        return Flux.concat(lineFluxes)
    }

    /**
     * Classifies a single line as thinking content vs object content and converts appropriately.
     *
     * This is the core classification logic that determines whether a line contains:
     * 1. Pure thinking content (no JSON)
     * 2. Pure object content (valid JSON)
     * 3. Mixed content (thinking + JSON)
     * 4. Invalid content (malformed)
     *
     * @param line The complete line from LLM response
     * @param streamingConverter The converter for parsing JSON objects
     * @return Flux of StreamingEvent<O> - may emit 0, 1, or 2 events (thinking + object)
     */
    private fun <O> classifyAndConvertLine(
        line: String,
        streamingConverter: StreamingJacksonOutputConverter<O>
    ): Flux<StreamingEvent<O>> {
        if (line.isBlank()) {
            return Flux.empty()
        }

        return try {
            when {
                StreamingUtils.isThinkingLine(line) -> {
                    // Line contains thinking content
                    val thinkingContent = StreamingUtils.extractThinkingContent(line)
                    Flux.just(StreamingEvent.Thinking(thinkingContent))
                }
                else -> {
                    // Line is object content - try to parse as JSON
                    convertLineToObject(line, streamingConverter)
                }
            }
        } catch (e: Exception) {
            // Classification failed - skip problematic line and continue stream
            logger.error(org.springframework.ai.util.LoggingMarkers.SENSITIVE_DATA_MARKER, "Failed to classify line, skipping: '{}...', error: {}", line.take(100), e.message)
            logger.debug("Classification error details", e)
            Flux.empty()
        }
    }


    /**
     * Converts a clean JSON line to a StreamingEvent.Object, with comprehensive error handling.
     *
     * This method handles the critical conversion from raw JSON text to typed objects.
     * Error handling strategy:
     * 1. Attempt conversion with proper JSONL formatting
     * 2. On failure, log details for debugging but don't break stream
     * 3. Return empty flux to continue processing other lines
     *
     * @param cleanLine JSON content without thinking blocks
     * @param streamingConverter The typed converter
     * @return Flux containing 0 or 1 StreamingEvent.Object
     */
    private fun <O> convertLineToObject(
        cleanLine: String,
        streamingConverter: StreamingJacksonOutputConverter<O>
    ): Flux<StreamingEvent<O>> {
        if (cleanLine.trim().isEmpty()) {
            return Flux.empty()
        }

        // Ensure proper JSONL formatting (line must end with \n for converter)
        val jsonlFormattedLine = if (cleanLine.endsWith("\n")) cleanLine else "$cleanLine\n"

        return streamingConverter.convertStream(jsonlFormattedLine)
            .map { obj -> StreamingEvent.Object(obj) as StreamingEvent<O> }
            .doOnNext { event ->
                logger.trace("Successfully converted JSON line to object: ${cleanLine.take(50)}...")
            }
            .onErrorResume { error ->
                logger.warn("Failed to parse JSON line as object: '${cleanLine.take(100)}...', error: ${error.message}")
                Flux.empty()
            }
    }
}

/**
 * Stateful buffer that handles the chunk-to-line mismatch in streaming LLM responses.
 *
 * ## Problem
 * LLM responses arrive as arbitrary physical chunks (512B, 1KB, etc.) that don't align
 * with logical JSONL lines. We need to extract complete lines for processing while avoiding
 * reprocessing the same content.
 *
 * ## Example
 * ```
 * LLM sends: "Analyzing...\n{\"id\":42}\nDone.\n"
 *
 * Chunk arrivals:
 *   "Analyzing" → buffer, no complete lines
 *   "...\n{\"id\":4" → "Analyzing..." complete, emit it
 *   "2}\nDone.\n" → "{\"id\":42}" and "Done." complete, emit both
 *
 * User receives:
 *   1. Thinking("Analyzing...")
 *   2. Object(id=42)
 *   3. Thinking("Done.")
 * ```
 */
private data class LineBuffer(
    private val accumulatedText: String = "",
    private val processedLineCount: Int = 0
) {

    /**
     * Adds a new chunk to the buffer.
     * @param chunk the new text chunk from LLM
     * @return new buffer state with the chunk appended
     */
    fun addChunk(chunk: String): LineBuffer = copy(
        accumulatedText = accumulatedText + chunk
    )

    /**
     * Gets complete lines that are ready to be processed by the converter.
     *
     * A line is ready for processing when it ends with `\n` AND hasn't been processed yet.
     * This method ensures no line is processed twice by tracking processed line count.
     *
     * ## Line Completeness Examples
     * ```
     * "line1\nline2"     → ["line1", "line2"]     → 1 ready (line1 only)
     * "line1\nline2\n"   → ["line1", "line2", ""] → 2 ready (line1, line2)
     * "line1\nline2\nli" → ["line1", "line2", "li"] → 2 ready (line1, line2)
     * ```
     *
     * @return pair of (updated buffer, lines ready for converter processing)
     */
    fun getReadyToProcessLines(): Pair<LineBuffer, List<String>> {
        val allLines = accumulatedText.lines()

        // Count complete lines (those ending with \n)
        // String.lines() quirk: "text\n" creates empty string at end
        val hasTrailingNewline = accumulatedText.endsWith("\n")
        val completeLineCount = if (accumulatedText.isEmpty()) {
            0  // No text = no lines
        } else if (hasTrailingNewline) {
            // "line1\nline2\n" → ["line1", "line2", ""]
            // Last element is empty, so size-1 gives us complete lines
            allLines.size - 1
        } else {
            // "line1\nline2\npartial" → ["line1", "line2", "partial"]
            // Last element is incomplete, so size-1 gives us complete lines
            maxOf(0, allLines.size - 1)
        }

        val newLines = allLines
            .take(completeLineCount)
            .drop(processedLineCount)
            .filterNot(String::isEmpty)

        val updatedBuffer = copy(processedLineCount = completeLineCount)

        return updatedBuffer to newLines
    }
}
