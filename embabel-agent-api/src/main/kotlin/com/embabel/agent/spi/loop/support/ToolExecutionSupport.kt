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
import com.embabel.agent.api.tool.ToolCallContext
import com.embabel.agent.api.tool.callback.AfterToolCallContext
import com.embabel.agent.api.tool.callback.BeforeToolCallContext
import com.embabel.agent.api.tool.callback.ToolCallInspector
import com.embabel.agent.spi.loop.ToolCallResult
import com.embabel.agent.spi.loop.ToolInjectionContext
import com.embabel.agent.spi.loop.ToolInjectionStrategy
import com.embabel.chat.Message
import com.embabel.chat.ToolCall
import org.slf4j.Logger
import tools.jackson.databind.ObjectMapper

/** Result of provider-independent tool execution. */
internal data class ExecutedToolCall(
    val result: Tool.Result,
    val content: String,
)

/** Execute a tool and publish its before and after inspection callbacks. */
internal fun executeTool(
    tool: Tool,
    toolCall: ToolCall,
    toolCallContext: ToolCallContext,
    toolCallInspectors: List<ToolCallInspector>,
): ExecutedToolCall {
    toolCallInspectors.notifyBeforeToolCall(BeforeToolCallContext(toolCall))
    val started = System.currentTimeMillis()
    val result = tool.call(toolCall.arguments, toolCallContext)
    val content = when (result) {
        is Tool.Result.Text -> result.content
        is Tool.Result.WithArtifact -> result.content
        is Tool.Result.Error -> "Error: ${result.message}"
    }
    toolCallInspectors.notifyAfterToolCall(
        AfterToolCallContext(
            toolCall = toolCall,
            result = result,
            resultAsString = content,
            durationMs = System.currentTimeMillis() - started,
        )
    )
    return ExecutedToolCall(result, content)
}

/**
 * Evaluate and apply dynamic tool changes after a tool call.
 *
 * Centralizing decoration, deduplication, and tracking keeps injection semantics
 * consistent for every tool-loop implementation.
 */
internal fun applyToolInjection(
    toolCall: ToolCall,
    resultContent: String,
    conversationHistory: List<Message>,
    availableTools: MutableList<Tool>,
    iteration: Int,
    injectionStrategy: ToolInjectionStrategy,
    objectMapper: ObjectMapper,
    toolDecorator: ((Tool) -> Tool)?,
    injectedTools: MutableList<Tool>? = null,
    removedTools: MutableList<Tool>? = null,
    logger: Logger? = null,
) {
    val injection = injectionStrategy.evaluate(
        ToolInjectionContext(
            conversationHistory = conversationHistory,
            currentTools = availableTools,
            lastToolCall = ToolCallResult(
                toolName = toolCall.name,
                toolInput = toolCall.arguments,
                result = resultContent,
                resultObject = deserializeToolResult(resultContent, objectMapper, logger),
            ),
            iterationCount = iteration,
        )
    )
    if (!injection.hasChanges()) return

    val removedNames = injection.toolsToRemove.map { it.definition.name }.toSet()
    availableTools.removeIf { it.definition.name in removedNames }
    removedTools?.addAll(injection.toolsToRemove)
    if (injection.toolsToRemove.isNotEmpty()) {
        logger?.info(
            "Strategy removed {} tools after {}: {}",
            injection.toolsToRemove.size,
            toolCall.name,
            removedNames,
        )
    }

    val existingNames = availableTools.map { it.definition.name }.toSet()
    val additions = injection.toolsToAdd
        .map { toolDecorator?.invoke(it) ?: it }
        .filter { it.definition.name !in existingNames }
    if (injection.toolsToAdd.isNotEmpty() && additions.isEmpty()) {
        logger?.debug(
            "All {} tools already present after {}, skipping",
            injection.toolsToAdd.size,
            toolCall.name,
        )
    }
    availableTools.addAll(additions)
    injectedTools?.addAll(additions)
    if (additions.isNotEmpty()) {
        logger?.info(
            "Strategy injected {} tools after {}: {}",
            additions.size,
            toolCall.name,
            additions.map { it.definition.name },
        )
    }
}

private fun deserializeToolResult(
    content: String,
    objectMapper: ObjectMapper,
    logger: Logger?,
): Any? {
    val trimmed = content.trimStart()
    if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) return null
    return try {
        objectMapper.readValue(content, Any::class.java)
    } catch (e: Exception) {
        logger?.debug("Could not deserialize tool result as JSON: {}", e.message)
        null
    }
}
