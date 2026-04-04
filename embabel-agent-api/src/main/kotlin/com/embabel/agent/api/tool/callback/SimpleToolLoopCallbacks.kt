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
package com.embabel.agent.api.tool.callback

import com.embabel.chat.AssistantMessageWithToolCalls
import com.embabel.chat.Message
import com.embabel.chat.SystemMessage
import com.embabel.chat.ToolResultMessage
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Inspector that logs tool loop lifecycle events.
 *
 * @param logLevel The level at which to log events
 * @param logger The logger to use (defaults to ToolLoopLoggingInspector's logger)
 */
class ToolLoopLoggingInspector(
    private val logLevel: LogLevel = LogLevel.DEBUG,
    private val logger: Logger = LoggerFactory.getLogger(ToolLoopLoggingInspector::class.java),
) : ToolLoopInspector {

    enum class LogLevel { TRACE, DEBUG, INFO }

    override fun beforeLlmCall(context: BeforeLlmCallContext) {
        log("beforeLlmCall: iteration=${context.iteration}, historySize=${context.history.size}, tools=${context.tools.size}")
    }

    override fun afterLlmCall(context: AfterLlmCallContext) {
        val toolCalls = (context.response as? AssistantMessageWithToolCalls)?.toolCalls?.size ?: 0
        val contentLength = context.response.content.length
        val usage = context.usage?.let { "prompt=${it.promptTokens}, completion=${it.completionTokens}" } ?: "n/a"
        log("afterLlmCall: iteration=${context.iteration}, toolCalls=$toolCalls, contentLength=$contentLength, usage=$usage")
    }

    override fun afterToolResult(context: AfterToolResultContext) {
        log("afterToolResult: iteration=${context.iteration}, tool=${context.toolCall.name}, resultLength=${context.resultAsString.length}")
    }

    override fun afterIteration(context: AfterIterationContext) {
        log("afterIteration: iteration=${context.iteration}, toolCalls=${context.toolCallsInIteration.size}")
    }

    private fun log(message: String) {
        when (logLevel) {
            LogLevel.TRACE -> logger.trace(message)
            LogLevel.DEBUG -> logger.debug(message)
            LogLevel.INFO -> logger.info(message)
        }
    }
}

/**
 * Transformer that maintains a sliding window of messages to manage context size.
 *
 * Applies windowing in both [transformBeforeLlmCall] and [transformAfterIteration]
 * to ensure bounded memory and context window usage.
 *
 * ## Tool Call/Result Grouping
 *
 * LLM APIs (OpenAI, Anthropic, Google, etc.) require that every [ToolResultMessage]
 * must be preceded by an [AssistantMessageWithToolCalls] containing the corresponding
 * tool call ID. This is a universal constraint across all major providers.
 *
 * When truncation occurs, the sliding window might cut between an
 * [AssistantMessageWithToolCalls] and its [ToolResultMessage]s, leaving "orphaned"
 * tool results that would cause API errors like:
 *
 * ```
 * "messages with role 'tool' must be a response to a preceding message with 'tool_calls'"
 * ```
 *
 * This transformer automatically drops any orphaned [ToolResultMessage]s that appear
 * at the start of the non-system message sequence after truncation.
 *
 * ### Example
 *
 * Before truncation (7 messages):
 * ```
 * [System, User, Assistant+Calls(1,2), ToolResult(1), ToolResult(2), Assistant+Calls(3), ToolResult(3)]
 * ```
 *
 * After naive `takeLast(4)` with 1 system message (INVALID):
 * ```
 * [System, ToolResult(2), Assistant+Calls(3), ToolResult(3)]
 *          ↑ ORPHAN - no parent Assistant+Calls for call_2
 * ```
 *
 * After fix (VALID):
 * ```
 * [System, Assistant+Calls(3), ToolResult(3)]
 *          ↑ Valid start - parent exists for ToolResult(3)
 * ```
 *
 * @param maxMessages Maximum number of messages to retain
 * @param preserveSystemMessages When true, preserves all SystemMessages regardless of position
 */
class SlidingWindowTransformer(
    private val maxMessages: Int,
    private val preserveSystemMessages: Boolean = true,
) : ToolLoopTransformer {

    private val logger = LoggerFactory.getLogger(SlidingWindowTransformer::class.java)

    override fun transformBeforeLlmCall(context: BeforeLlmCallContext): List<Message> =
        applyWindow(context.history)

    override fun transformAfterIteration(context: AfterIterationContext): List<Message> =
        applyWindow(context.history)

    /**
     * Applies the sliding window algorithm to the message history.
     *
     * ## Algorithm
     *
     * 1. **Truncation Phase**: If history exceeds [maxMessages], truncate to fit:
     *    - When [preserveSystemMessages] is true: keep all [SystemMessage]s,
     *      then `takeLast` from non-system messages to fill remaining slots
     *    - When false: simply `takeLast(maxMessages)` from entire history
     *
     * 2. **Orphan Removal Phase**: After truncation (or even without it),
     *    remove any orphaned [ToolResultMessage]s from the start of the
     *    non-system sequence. This ensures the result is always valid for
     *    LLM API submission.
     *
     * ## Why Orphan Removal is Always Applied
     *
     * Orphaned [ToolResultMessage]s can exist in two scenarios:
     * - **After truncation**: The cut point fell between an [AssistantMessageWithToolCalls]
     *   and its [ToolResultMessage]s
     * - **Malformed input**: The history was constructed incorrectly
     *
     * By always checking for orphans, this method guarantees a valid output
     * regardless of input quality.
     *
     * @param history The message history to apply windowing to
     * @return A valid message list with at most [maxMessages] messages and no orphaned tool results
     */
    private fun applyWindow(history: List<Message>): List<Message> {
        val truncated = if (history.size <= maxMessages) {
            history
        } else if (preserveSystemMessages) {
            val systemMessages = history.filterIsInstance<SystemMessage>()
            val nonSystemMessages = history.filter { it !is SystemMessage }
            val remainingSlots = (maxMessages - systemMessages.size).coerceAtLeast(0)
            systemMessages + nonSystemMessages.takeLast(remainingSlots)
        } else {
            history.takeLast(maxMessages)
        }

        return dropOrphanedToolResults(truncated)
    }

    /**
     * Drops orphaned [ToolResultMessage]s from the start of the non-system message sequence.
     *
     * ## The Orphan Problem
     *
     * A [ToolResultMessage] is orphaned when it appears without a preceding
     * [AssistantMessageWithToolCalls] that contains the matching tool call ID.
     * All major LLM APIs reject such messages:
     *
     * - **OpenAI**: "messages with role 'tool' must be a response to a preceding message with 'tool_calls'"
     * - **Anthropic**: tool_result blocks must follow tool_use blocks
     * - **Google Gemini**: functionResponse must follow functionCall
     *
     * ## Algorithm
     *
     * 1. Separate system messages (always preserved) from non-system messages
     * 2. Find the index of the first non-[ToolResultMessage] in the non-system sequence
     * 3. Drop all [ToolResultMessage]s before that index (they are orphans)
     * 4. Recombine: system messages + valid non-system messages
     *
     * ## Example
     *
     * Input:  `[System, ToolResult(1), ToolResult(2), Assistant+Calls(3), ToolResult(3)]`
     * Output: `[System, Assistant+Calls(3), ToolResult(3)]`
     *
     * The first valid starting point is `Assistant+Calls(3)`, so `ToolResult(1)` and
     * `ToolResult(2)` are dropped as orphans.
     *
     * @param messages The message list to sanitize
     * @return Message list with orphaned tool results removed from the start
     */
    private fun dropOrphanedToolResults(messages: List<Message>): List<Message> {
        val systemMessages = messages.filterIsInstance<SystemMessage>()
        val nonSystemMessages = messages.filter { it !is SystemMessage }

        // Find the index of the first non-ToolResultMessage
        val firstValidIndex = nonSystemMessages.indexOfFirst { it !is ToolResultMessage }

        if (firstValidIndex <= 0) {
            // No orphans (firstValidIndex == 0) or all are ToolResults (firstValidIndex == -1)
            return if (firstValidIndex == -1) {
                // All non-system messages are ToolResults - drop them all
                logger.debug("Dropped all {} orphaned ToolResultMessages", nonSystemMessages.size)
                systemMessages
            } else {
                messages
            }
        }

        // Drop orphaned ToolResults at the start
        val validMessages = nonSystemMessages.drop(firstValidIndex)
        logger.debug("Dropped {} orphaned ToolResultMessages", firstValidIndex)

        return systemMessages + validMessages
    }
}

/**
 * Transformer that truncates tool results exceeding a maximum length.
 *
 * Useful for preventing large tool outputs from consuming excessive context.
 *
 * @param maxLength Maximum length of tool result string (default 10,000)
 * @param truncationMarker Marker appended to truncated results
 * @param logLevel Optional log level for truncation events (null = no logging)
 * @param logger Logger to use when logLevel is set
 */
class ToolResultTruncatingTransformer(
    private val maxLength: Int = 10_000,
    private val truncationMarker: String? = null,
    private val logLevel: ToolLoopLoggingInspector.LogLevel? = null,
    private val logger: Logger = LoggerFactory.getLogger(ToolResultTruncatingTransformer::class.java),
) : ToolLoopTransformer {

    override fun transformAfterToolResult(context: AfterToolResultContext): String =
        context.resultAsString.let { result ->
            if (result.length > maxLength) {
                logTruncation(context.toolCall.name, result.length)
                result.take(maxLength) + (truncationMarker ?: "\n... [truncated, $maxLength chars shown]")
            } else {
                result
            }
        }

    private fun logTruncation(toolName: String, originalLength: Int) {
        val message = "Truncated '$toolName' result: $originalLength -> $maxLength chars"
        when (logLevel) {
            ToolLoopLoggingInspector.LogLevel.TRACE -> logger.trace(message)
            ToolLoopLoggingInspector.LogLevel.DEBUG -> logger.debug(message)
            ToolLoopLoggingInspector.LogLevel.INFO -> logger.info(message)
            null -> Unit // logging disabled
        }
    }
}
