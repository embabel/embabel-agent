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
package com.embabel.agent.spi.support.springai

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.callback.AfterToolCallContext
import com.embabel.agent.api.tool.callback.BeforeToolCallContext
import com.embabel.agent.api.tool.callback.ToolCallInspector
import com.embabel.chat.ToolCall
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.tool.metadata.ToolMetadata

/**
 * Decorates a Spring AI [ToolCallback] to emit events to a [ToolCallInspector].
 *
 * Wraps the delegate callback and notifies the inspector before and after
 * tool execution, including timing and result information.
 *
 * @param delegate The underlying ToolCallback to decorate
 * @param inspector The inspector to notify of tool execution events
 */
internal class SpringAiToolCallbackListener(
    private val delegate: ToolCallback,
    private val inspector: ToolCallInspector,
) : ToolCallback {

    override fun getToolDefinition(): ToolDefinition = delegate.toolDefinition

    override fun getToolMetadata(): ToolMetadata = delegate.toolMetadata

    override fun call(toolInput: String): String = call(toolInput, null)

    override fun call(toolInput: String, toolContext: ToolContext?): String {
        val toolName = delegate.toolDefinition.name()
        val toolCall = ToolCall(id = "", name = toolName, arguments = toolInput)

        inspector.beforeToolCall(BeforeToolCallContext(toolCall))
        val startTime = System.currentTimeMillis()

        return try {
            val resultString = delegate.call(toolInput, toolContext)
            val durationMs = System.currentTimeMillis() - startTime

            inspector.afterToolCall(
                AfterToolCallContext(
                    toolCall = toolCall,
                    result = Tool.Result.text(resultString),
                    resultAsString = resultString,
                    durationMs = durationMs,
                )
            )
            resultString
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startTime
            val errorResult = Tool.Result.error(e.message ?: "Tool execution failed", e)

            inspector.afterToolCall(
                AfterToolCallContext(
                    toolCall = toolCall,
                    result = errorResult,
                    resultAsString = "ERROR: ${e.message}",
                    durationMs = durationMs,
                )
            )
            throw e
        }
    }
}

/**
 * Extension to wrap tool callbacks with an inspector.
 *
 * @param inspector The inspector to notify, or null to return callbacks unchanged
 * @return List of callbacks wrapped with the inspector, or original list if inspector is null
 */
internal fun List<ToolCallback>.withInspector(
    inspector: ToolCallInspector?,
): List<ToolCallback> {
    return if (inspector != null) {
        map { SpringAiToolCallbackListener(it, inspector) }
    } else {
        this
    }
}

/**
 * Extension to wrap tool callbacks with multiple inspectors.
 *
 * @param inspectors The inspectors to notify
 * @return List of callbacks wrapped with inspectors, or original list if no inspectors
 */
internal fun List<ToolCallback>.withInspectors(
    inspectors: List<ToolCallInspector>,
): List<ToolCallback> {
    return if (inspectors.isEmpty()) {
        this
    } else {
        val inspector = object : ToolCallInspector {
            override fun beforeToolCall(context: BeforeToolCallContext) {
                inspectors.forEach { it.beforeToolCall(context) }
            }

            override fun afterToolCall(context: AfterToolCallContext) {
                inspectors.forEach { it.afterToolCall(context) }
            }
        }
        map { SpringAiToolCallbackListener(it, inspector) }
    }
}
