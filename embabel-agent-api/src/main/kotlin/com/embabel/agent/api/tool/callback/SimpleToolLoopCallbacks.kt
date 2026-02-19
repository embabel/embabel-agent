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
 * @param maxMessages Maximum number of messages to retain
 * @param preserveSystemMessages When true, preserves all SystemMessages regardless of position
 */
class SlidingWindowTransformer(
    private val maxMessages: Int,
    private val preserveSystemMessages: Boolean = true,
) : ToolLoopTransformer {

    override fun transformBeforeLlmCall(context: BeforeLlmCallContext): List<Message> =
        applyWindow(context.history)

    override fun transformAfterIteration(context: AfterIterationContext): List<Message> =
        applyWindow(context.history)

    private fun applyWindow(history: List<Message>): List<Message> {
        if (history.size <= maxMessages) return history

        return if (preserveSystemMessages) {
            val systemMessages = history.filterIsInstance<SystemMessage>()
            val nonSystemMessages = history.filter { it !is SystemMessage }
            val remainingSlots = (maxMessages - systemMessages.size).coerceAtLeast(0)
            systemMessages + nonSystemMessages.takeLast(remainingSlots)
        } else {
            history.takeLast(maxMessages)
        }
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
