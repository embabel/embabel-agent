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

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.Usage
import com.embabel.chat.Message
import com.embabel.chat.ToolCall

/**
 * Marker interface for tool loop lifecycle callbacks.
 *
 * Implementations can be either:
 * - [ToolLoopInspector]: Read-only observers for logging, metrics, debugging
 * - [ToolLoopTransformer]: Read-write transformers for history compression, summarization
 *
 * @see ToolLoopInspector
 * @see ToolLoopTransformer
 */
interface ToolLoopCallback

/**
 * Read-only observer for tool loop lifecycle events.
 * Use for logging, metrics, debugging - does not modify state.
 */
interface ToolLoopInspector : ToolLoopCallback {

    /** Called before each LLM invocation. Default no-op. */
    fun beforeLlmCall(context: BeforeLlmCallContext) = Unit

    /** Called after LLM returns a response, before processing tool calls. Default no-op. */
    fun afterLlmCall(context: AfterLlmCallContext) = Unit

    /** Called after each tool produces a result. Default no-op. */
    fun afterToolResult(context: AfterToolResultContext) = Unit

    /** Called after each complete iteration (all tool calls processed). Default no-op. */
    fun afterIteration(context: AfterIterationContext) = Unit
}

/**
 * Transforms message history or tool results during tool loop execution.
 * Use for compression, summarization, windowing.
 */
interface ToolLoopTransformer : ToolLoopCallback {

    /** Transform history before sending to LLM. Return modified list. */
    fun transformBeforeLlmCall(context: BeforeLlmCallContext): List<Message> = context.history

    /** Transform LLM response before adding to history. Return modified message. */
    fun transformAfterLlmCall(context: AfterLlmCallContext): Message = context.response

    /** Transform tool result before adding to history. Return modified string. */
    fun transformAfterToolResult(context: AfterToolResultContext): String = context.resultAsString

    /** Transform history after iteration completes. Return modified list. */
    fun transformAfterIteration(context: AfterIterationContext): List<Message> = context.history

    /**
     * Decide what happens to a tool call before it runs. Return [ToolCallDisposition.Proceed]
     * to execute it, optionally with rewritten arguments, or [ToolCallDisposition.ShortCircuit]
     * to skip it and hand the LLM a result directly. Default: proceed unchanged.
     *
     * Transformers run in registration order; each sees the call the previous one returned,
     * and the first short circuit ends the chain. A short-circuited result still passes through
     * [transformAfterToolResult] and the after-tool-result inspectors. Throwing a
     * [com.embabel.agent.api.tool.ToolControlFlowSignal], for example an
     * `AwaitableResponseException`, propagates as it would from the tool itself. Any other
     * exception also propagates: unlike inspectors, transformers are not isolated.
     *
     * Applied by the blocking and parallel tool loops only. The streaming loop supports
     * inspectors only, by design, and does not call this method.
     */
    fun transformBeforeToolCall(context: BeforeToolExecutionContext): ToolCallDisposition =
        ToolCallDisposition.Proceed(context.toolCall)
}

/**
 * Outcome of [ToolLoopTransformer.transformBeforeToolCall].
 */
sealed interface ToolCallDisposition {

    /** Run the tool with this call. Same as the input unless the arguments were rewritten. */
    data class Proceed(val toolCall: ToolCall) : ToolCallDisposition

    /** Do not run the tool; [result] goes to the LLM as the tool result. */
    data class ShortCircuit(val result: Tool.Result) : ToolCallDisposition
}

/**
 * Base class for all tool loop callback contexts.
 * Provides common properties shared across all callback events.
 *
 * @property history Current conversation history (messages)
 * @property iteration Current iteration number (1-based)
 */
abstract class CallbackContext(
    open val history: List<Message>,
    open val iteration: Int,
)

/**
 * Context provided before each LLM call in the tool loop.
 *
 * @property history Current conversation history (messages)
 * @property iteration Current iteration number (1-based)
 * @property tools Available tools for this call
 * @property tokenEstimate Estimated token count for history, if available
 */
data class BeforeLlmCallContext(
    override val history: List<Message>,
    override val iteration: Int,
    val tools: List<Tool>,
    val tokenEstimate: Int? = null,
) : CallbackContext(history, iteration)

/**
 * Context provided after LLM returns a response, before tool calls are processed.
 *
 * @property history Current conversation history (before response is added)
 * @property iteration Current iteration number (1-based)
 * @property response The LLM response (may be AssistantMessage or AssistantMessageWithToolCalls)
 * @property usage Token usage for this LLM call, if available
 */
data class AfterLlmCallContext(
    override val history: List<Message>,
    override val iteration: Int,
    val response: Message,
    val usage: Usage?,
) : CallbackContext(history, iteration)

/**
 * Common interface for tool result context, shared between streaming and non-streaming callbacks.
 *
 * Provides access to the tool call details and result without loop-specific context
 * (history, iteration) that is only available in non-streaming mode.
 *
 * @property toolCall The tool call that was executed
 * @property result The typed tool result (may be [Tool.Result.Error] on failure)
 * @property resultAsString String representation of the result
 */
interface ToolResultContext {
    val toolCall: ToolCall
    val result: Tool.Result
    val resultAsString: String
}

/**
 * Context provided after each tool execution in the tool loop.
 *
 * @property history Current conversation history (before this result is added)
 * @property iteration Current iteration number (1-based)
 * @property toolCall The tool call that was executed
 * @property result The typed tool result (may be [Tool.Result.Error] on failure)
 * @property resultAsString String representation of the result
 */
data class AfterToolResultContext(
    override val history: List<Message>,
    override val iteration: Int,
    override val toolCall: ToolCall,
    override val result: Tool.Result,
    override val resultAsString: String,
) : CallbackContext(history, iteration), ToolResultContext

/**
 * Context provided to [ToolLoopTransformer.transformBeforeToolCall], before a tool runs.
 * Distinct from the inspector's lightweight [BeforeToolCallContext], which has no loop state.
 *
 * @property history Current conversation history
 * @property iteration Current iteration number (1-based)
 * @property toolCall The call the LLM requested: id, name and arguments
 * @property tool The resolved tool, decorators included
 */
data class BeforeToolExecutionContext(
    override val history: List<Message>,
    override val iteration: Int,
    val toolCall: ToolCall,
    val tool: Tool,
) : CallbackContext(history, iteration)

/**
 * Context provided after each complete iteration in the tool loop.
 * An iteration completes when all tool calls from a single LLM response have been processed.
 *
 * @property history Current conversation history (including all tool results from this iteration)
 * @property iteration Current iteration number (1-based)
 * @property toolCallsInIteration Tool calls that were processed in this iteration
 */
data class AfterIterationContext(
    override val history: List<Message>,
    override val iteration: Int,
    val toolCallsInIteration: List<ToolCall>,
) : CallbackContext(history, iteration)
