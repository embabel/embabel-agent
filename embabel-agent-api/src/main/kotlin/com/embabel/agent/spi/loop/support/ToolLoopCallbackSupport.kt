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
package com.embabel.agent.spi.loop.support

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.Usage
import com.embabel.agent.api.tool.callback.AfterIterationContext
import com.embabel.agent.api.tool.callback.AfterLlmCallContext
import com.embabel.agent.api.tool.callback.AfterToolCallContext
import com.embabel.agent.api.tool.callback.AfterToolResultContext
import com.embabel.agent.api.tool.callback.BeforeLlmCallContext
import com.embabel.agent.api.tool.callback.BeforeToolCallContext
import com.embabel.agent.api.tool.callback.ToolCallInspector
import com.embabel.chat.Message
import com.embabel.chat.ToolCall
import com.embabel.agent.api.tool.callback.ToolLoopInspector
import com.embabel.agent.api.tool.callback.ToolLoopTransformer
import org.slf4j.LoggerFactory

/**
 * Extension functions for applying [ToolLoopInspector] and [ToolLoopTransformer] callbacks.
 *
 * All inspector and transformer calls are wrapped in try-catch to ensure that exceptions
 * from observability code do not break tool loop execution.
 */

private val logger = LoggerFactory.getLogger("ToolCallbackSupport")

// =============================================================================
// Inspector Extensions (read-only notifications)
// =============================================================================

internal fun List<ToolLoopInspector>.notifyBeforeLlmCall(context: BeforeLlmCallContext) {
    forEach { inspector ->
        try {
            inspector.beforeLlmCall(context)
        } catch (e: Exception) {
            logger.warn("ToolLoopInspector ${inspector::class.simpleName} failed in beforeLlmCall (iteration ${context.iteration}): ${e.message}", e)
        }
    }
}

internal fun List<ToolLoopInspector>.notifyAfterLlmCall(context: AfterLlmCallContext) {
    forEach { inspector ->
        try {
            inspector.afterLlmCall(context)
        } catch (e: Exception) {
            logger.warn("ToolLoopInspector ${inspector::class.simpleName} failed in afterLlmCall (iteration ${context.iteration}): ${e.message}", e)
        }
    }
}

internal fun List<ToolLoopInspector>.notifyAfterToolResult(context: AfterToolResultContext) {
    forEach { inspector ->
        try {
            inspector.afterToolResult(context)
        } catch (e: Exception) {
            logger.warn("ToolLoopInspector ${inspector::class.simpleName} failed in afterToolResult for tool '${context.toolCall.name}' (iteration ${context.iteration}): ${e.message}", e)
        }
    }
}

internal fun List<ToolLoopInspector>.notifyAfterIteration(context: AfterIterationContext) {
    forEach { inspector ->
        try {
            inspector.afterIteration(context)
        } catch (e: Exception) {
            logger.warn("ToolLoopInspector ${inspector::class.simpleName} failed in afterIteration (iteration ${context.iteration}): ${e.message}", e)
        }
    }
}

// =============================================================================
// Tool Call Inspector Extensions (context without history/iteration)
// =============================================================================

internal fun List<ToolCallInspector>.notifyBeforeToolCall(context: BeforeToolCallContext) {
    forEach { inspector ->
        try {
            inspector.beforeToolCall(context)
        } catch (e: Exception) {
            logger.warn("ToolCallInspector ${inspector::class.simpleName} failed in beforeToolCall for tool '${context.toolCall.name}': ${e.message}", e)
        }
    }
}

internal fun List<ToolCallInspector>.notifyAfterToolCall(context: AfterToolCallContext) {
    forEach { inspector ->
        try {
            inspector.afterToolCall(context)
        } catch (e: Exception) {
            logger.warn("ToolCallInspector ${inspector::class.simpleName} failed in afterToolCall for tool '${context.toolCall.name}': ${e.message}", e)
        }
    }
}

// =============================================================================
// Transformer Extensions (may modify history/results)
// =============================================================================

internal fun List<ToolLoopTransformer>.applyBeforeLlmCall(context: BeforeLlmCallContext): List<Message> {
    var history = context.history
    for (transformer in this) {
        history = transformer.transformBeforeLlmCall(context.copy(history = history))
    }
    return history
}

internal fun List<ToolLoopTransformer>.applyAfterLlmCall(context: AfterLlmCallContext): Message {
    var response = context.response
    for (transformer in this) {
        response = transformer.transformAfterLlmCall(context.copy(response = response))
    }
    return response
}

internal fun List<ToolLoopTransformer>.applyAfterToolResult(context: AfterToolResultContext): String {
    var result = context.resultAsString
    for (transformer in this) {
        result = transformer.transformAfterToolResult(context.copy(resultAsString = result))
    }
    return result
}

internal fun List<ToolLoopTransformer>.applyAfterIteration(context: AfterIterationContext): List<Message> {
    var history = context.history
    for (transformer in this) {
        history = transformer.transformAfterIteration(context.copy(history = history))
    }
    return history
}

// =============================================================================
// Context Factory Functions
// =============================================================================

internal fun createBeforeLlmCallContext(
    history: List<Message>,
    iteration: Int,
    tools: List<Tool>,
    tokenEstimate: Int? = null,
) = BeforeLlmCallContext(
    history = history,
    iteration = iteration,
    tools = tools,
    tokenEstimate = tokenEstimate,
)

internal fun createAfterLlmCallContext(
    history: List<Message>,
    iteration: Int,
    response: Message,
    usage: Usage?,
) = AfterLlmCallContext(
    history = history,
    iteration = iteration,
    response = response,
    usage = usage,
)

internal fun createAfterToolResultContext(
    history: List<Message>,
    iteration: Int,
    toolCall: ToolCall,
    result: Tool.Result,
    resultAsString: String,
) = AfterToolResultContext(
    history = history,
    iteration = iteration,
    toolCall = toolCall,
    result = result,
    resultAsString = resultAsString,
)

internal fun createAfterIterationContext(
    history: List<Message>,
    iteration: Int,
    toolCallsInIteration: List<ToolCall>,
) = AfterIterationContext(
    history = history,
    iteration = iteration,
    toolCallsInIteration = toolCallsInIteration,
)
