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
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.tool.metadata.ToolMetadata
import java.util.UUID

/**
 * Decorates a Spring AI [ToolCallback] to emit events to a [ToolCallInspector].
 *
 * Wraps the delegate callback and notifies the inspector before and after
 * tool execution, including timing and result information.
 *
 * Inspector exceptions are caught and logged to prevent observability code from
 * breaking tool execution.
 *
 * @param delegate The underlying ToolCallback to decorate
 * @param inspector The inspector to notify of tool execution events
 */
internal class SpringAiToolCallbackListener(
    private val delegate: ToolCallback,
    private val inspector: ToolCallInspector,
) : ToolCallback {

    private val logger = LoggerFactory.getLogger(SpringAiToolCallbackListener::class.java)

    override fun getToolDefinition(): ToolDefinition = delegate.toolDefinition

    override fun getToolMetadata(): ToolMetadata = delegate.toolMetadata

    override fun call(toolInput: String): String = call(toolInput, null)

    override fun call(toolInput: String, toolContext: ToolContext?): String {
        val toolName = delegate.toolDefinition.name()
        // Note: Spring AI's ToolCallback.call() does not provide the tool call ID,
        // so we generate a unique ID to allow before/after correlation and prevent collisions.
        val toolCall = ToolCall(id = UUID.randomUUID().toString(), name = toolName, arguments = toolInput)

        // Notify inspector, catching exceptions to prevent breaking tool execution
        try {
            inspector.beforeToolCall(BeforeToolCallContext(toolCall))
        } catch (e: Exception) {
            logger.warn("Inspector ${inspector::class.simpleName} failed in beforeToolCall for tool '$toolName': ${e.message}", e)
        }

        val startTime = System.currentTimeMillis()

        return try {
            val resultString = delegate.call(toolInput, toolContext)
            val durationMs = System.currentTimeMillis() - startTime

            try {
                inspector.afterToolCall(
                    AfterToolCallContext(
                        toolCall = toolCall,
                        result = Tool.Result.text(resultString),
                        resultAsString = resultString,
                        durationMs = durationMs,
                    )
                )
            } catch (e: Exception) {
                logger.warn("Inspector ${inspector::class.simpleName} failed in afterToolCall for tool '$toolName': ${e.message}", e)
            }
            resultString
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startTime
            val errorResult = Tool.Result.error(e.message ?: "Tool execution failed", e)

            try {
                inspector.afterToolCall(
                    AfterToolCallContext(
                        toolCall = toolCall,
                        result = errorResult,
                        resultAsString = "ERROR: ${e.message}",
                        durationMs = durationMs,
                    )
                )
            } catch (inspectorException: Exception) {
                logger.warn("Inspector ${inspector::class.simpleName} failed in afterToolCall for tool '$toolName': ${inspectorException.message}", inspectorException)
            }
            throw e
        }
    }
}

/**
 * Extension to wrap tool callbacks with multiple inspectors.
 *
 * Each inspector is called in a try-catch block to ensure that exceptions from
 * one inspector do not prevent other inspectors from running or break tool execution.
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
        val logger = LoggerFactory.getLogger(SpringAiToolCallbackListener::class.java)
        val inspector = object : ToolCallInspector {
            override fun beforeToolCall(context: BeforeToolCallContext) {
                inspectors.forEach { inspector ->
                    try {
                        inspector.beforeToolCall(context)
                    } catch (e: Exception) {
                        logger.warn(
                            "Inspector ${inspector::class.simpleName} failed in beforeToolCall for tool '${context.toolCall.name}': ${e.message}",
                            e
                        )
                    }
                }
            }

            override fun afterToolCall(context: AfterToolCallContext) {
                inspectors.forEach { inspector ->
                    try {
                        inspector.afterToolCall(context)
                    } catch (e: Exception) {
                        logger.warn(
                            "Inspector ${inspector::class.simpleName} failed in afterToolCall for tool '${context.toolCall.name}': ${e.message}",
                            e
                        )
                    }
                }
            }
        }
        map { SpringAiToolCallbackListener(it, inspector) }
    }
}
