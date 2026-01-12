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
import com.embabel.agent.spi.loop.*
import com.embabel.chat.AssistantMessageWithToolCalls
import com.embabel.chat.Message
import com.embabel.chat.ToolResultMessage
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

/**
 * Default implementation of [com.embabel.agent.spi.loop.ToolLoop].
 *
 * @param llmCaller Framework-agnostic interface for making single LLM calls
 * @param objectMapper ObjectMapper for deserializing tool results
 * @param injectionStrategy Strategy for dynamically injecting tools
 * @param maxIterations Maximum number of tool loop iterations (default 20)
 */
internal class DefaultToolLoop(
    private val llmCaller: LlmMessageSender,
    private val objectMapper: ObjectMapper,
    private val injectionStrategy: ToolInjectionStrategy = ToolInjectionStrategy.Companion.NONE,
    private val maxIterations: Int = 20,
) : ToolLoop {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun <O> execute(
        initialMessages: List<Message>,
        initialTools: List<Tool>,
        outputParser: (String) -> O,
    ): ToolLoopResult<O> {
        val conversationHistory = initialMessages.toMutableList()
        val availableTools = initialTools.toMutableList()
        val injectedTools = mutableListOf<Tool>()
        var accumulatedUsage: Usage? = null
        var iterations = 0

        while (iterations < maxIterations) {
            iterations++
            logger.debug("Tool loop iteration {} with {} available tools", iterations, availableTools.size)

            // 1. Call LLM (single inference, no internal tool loop)
            val callResult = llmCaller.call(conversationHistory, availableTools)

            // 2. Accumulate usage
            callResult.usage?.let { usage ->
                accumulatedUsage = accumulatedUsage?.plus(usage) ?: usage
            }

            // 3. Add assistant message to history
            val assistantMessage = callResult.message
            conversationHistory.add(assistantMessage)

            // 4. Check if LLM wants to call tools
            if (assistantMessage !is AssistantMessageWithToolCalls || assistantMessage.toolCalls.isEmpty()) {
                // No tool calls - LLM is done, parse final response
                val finalText = callResult.textContent
                logger.debug("Tool loop completed after {} iterations", iterations)

                val result = outputParser(finalText)
                return ToolLoopResult(
                    result = result,
                    conversationHistory = conversationHistory,
                    totalIterations = iterations,
                    injectedTools = injectedTools,
                    totalUsage = accumulatedUsage,
                )
            }

            // 5. Execute each tool call
            for (toolCall in assistantMessage.toolCalls) {
                val tool = findTool(availableTools, toolCall.name)
                    ?: throw ToolNotFoundException(toolCall.name, availableTools.map { it.definition.name })

                // 5a. Execute the tool
                logger.debug("Executing tool: {} with input: {}", toolCall.name, toolCall.arguments)
                val toolResult = tool.call(toolCall.arguments)
                val resultContent = when (toolResult) {
                    is Tool.Result.Text -> toolResult.content
                    is Tool.Result.WithArtifact -> toolResult.content
                    is Tool.Result.Error -> "Error: ${toolResult.message}"
                }

                // 5b. Try to deserialize result for strategy inspection
                val resultObject = tryDeserialize(resultContent)

                // 5c. Evaluate injection strategy
                val context = ToolInjectionContext(
                    conversationHistory = conversationHistory,
                    currentTools = availableTools,
                    lastToolCall = ToolCallResult(
                        toolName = toolCall.name,
                        toolInput = toolCall.arguments,
                        result = resultContent,
                        resultObject = resultObject,
                    ),
                    iterationCount = iterations,
                )

                val newTools = injectionStrategy.evaluateToolResult(context)
                if (newTools.isNotEmpty()) {
                    availableTools.addAll(newTools)
                    injectedTools.addAll(newTools)

                    logger.info(
                        "Strategy injected {} tools after {}: {}",
                        newTools.size,
                        toolCall.name,
                        newTools.map { it.definition.name }
                    )
                }

                // 5d. Add tool result to history
                val toolResultMessage = ToolResultMessage(
                    toolCallId = toolCall.id,
                    toolName = toolCall.name,
                    content = resultContent,
                )
                conversationHistory.add(toolResultMessage)
            }

            // 6. Continue loop - LLM will see updated history and tools
        }

        throw MaxIterationsExceededException(maxIterations)
    }

    /**
     * Find a tool by name.
     */
    private fun findTool(tools: List<Tool>, name: String): Tool? {
        return tools.find { it.definition.name == name }
    }

    /**
     * Try to deserialize a JSON result string.
     */
    private fun tryDeserialize(jsonResult: String): Any? {
        return try {
            objectMapper.readValue(jsonResult, Any::class.java)
        } catch (e: Exception) {
            logger.debug("Could not deserialize tool result as JSON: {}", e.message)
            null
        }
    }
}
