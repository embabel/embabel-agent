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
import com.embabel.agent.api.tool.callback.AfterToolResultContext
import com.embabel.agent.api.tool.callback.BeforeLlmCallContext
import com.embabel.chat.Message
import com.embabel.chat.ToolCall
import com.embabel.agent.api.tool.callback.ToolLoopInspector
import com.embabel.agent.api.tool.callback.ToolLoopTransformer

/**
 * Extension functions for applying [ToolLoopInspector] and [ToolLoopTransformer] callbacks.
 */

// =============================================================================
// Inspector Extensions (read-only notifications)
// =============================================================================

internal fun List<ToolLoopInspector>.notifyBeforeLlmCall(context: BeforeLlmCallContext) {
    forEach { it.beforeLlmCall(context) }
}

internal fun List<ToolLoopInspector>.notifyAfterLlmCall(context: AfterLlmCallContext) {
    forEach { it.afterLlmCall(context) }
}

internal fun List<ToolLoopInspector>.notifyAfterToolResult(context: AfterToolResultContext) {
    forEach { it.afterToolResult(context) }
}

internal fun List<ToolLoopInspector>.notifyAfterIteration(context: AfterIterationContext) {
    forEach { it.afterIteration(context) }
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
