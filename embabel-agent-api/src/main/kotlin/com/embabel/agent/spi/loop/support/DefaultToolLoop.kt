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
import com.embabel.agent.api.tool.ToolControlFlowSignal
import com.embabel.agent.core.BlackboardUpdater
import com.embabel.agent.core.ReplanRequestedException
import com.embabel.agent.core.Usage
import com.embabel.agent.spi.loop.LlmMessageSender
import com.embabel.agent.spi.loop.MaxIterationsExceededException
import com.embabel.agent.spi.loop.ToolCallResult
import com.embabel.agent.spi.loop.ToolInjectionContext
import com.embabel.agent.spi.loop.ToolInjectionStrategy
import com.embabel.agent.spi.loop.ToolLoop
import com.embabel.agent.spi.loop.ToolLoopResult
import com.embabel.agent.spi.loop.ToolNotFoundException
import com.embabel.chat.AssistantMessageWithToolCalls
import com.embabel.chat.Message
import com.embabel.chat.ToolCall
import com.embabel.agent.api.tool.callback.ToolLoopInspector
import com.embabel.agent.api.tool.callback.ToolLoopTransformer
import com.embabel.chat.ToolResultMessage
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

/**
 * Default implementation of [com.embabel.agent.spi.loop.ToolLoop].
 *
 * @param llmMessageSender Framework-agnostic interface for making single LLM calls
 * @param objectMapper ObjectMapper for deserializing tool results
 * @param injectionStrategy Strategy for dynamically injecting tools
 * @param maxIterations Maximum number of tool loop iterations (default 20)
 * @param toolDecorator Optional decorator applied to tools when they are dynamically injected.
 * This ensures injected tools (e.g., from MatryoshkaTool) receive the same decoration
 * as initial tools, including event publication, observability, and error handling.
 * @param inspectors Read-only observers for tool loop lifecycle events
 * @param transformers Transformers for history compression, summarization, etc.
 */
internal open class DefaultToolLoop(
    private val llmMessageSender: LlmMessageSender,
    private val objectMapper: ObjectMapper,
    private val injectionStrategy: ToolInjectionStrategy = ToolInjectionStrategy.NONE,
    private val maxIterations: Int = 20,
    private val toolDecorator: ((Tool) -> Tool)? = null,
    protected val inspectors: List<ToolLoopInspector> = emptyList(),
    protected val transformers: List<ToolLoopTransformer> = emptyList(),
) : ToolLoop {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun <O> execute(
        initialMessages: List<Message>,
        initialTools: List<Tool>,
        outputParser: (String) -> O,
    ): ToolLoopResult<O> {
        val state = LoopState(
            conversationHistory = initialMessages.toMutableList(),
            availableTools = initialTools.toMutableList(),
        )

        while (state.iterations < maxIterations) {
            state.iterations++
            logger.debug("Tool loop iteration {} with {} available tools", state.iterations, state.availableTools.size)

            /* -------------------------------------------------
             * Apply beforeLlmCall callbacks - START
             * ------------------------------------------------- */
            val beforeContext = createBeforeLlmCallContext(
                history = state.conversationHistory.toList(),
                iteration = state.iterations,
                tools = state.availableTools.toList(),
            )
            inspectors.notifyBeforeLlmCall(beforeContext)
            val transformedHistory = transformers.applyBeforeLlmCall(beforeContext)
            if (transformedHistory != state.conversationHistory) {
                state.conversationHistory.clear()
                state.conversationHistory.addAll(transformedHistory)
            }
            /* -------------------------------------------------
             * Apply beforeLlmCall callbacks - END
             * ------------------------------------------------- */

            val callResult = llmMessageSender.call(state.conversationHistory, state.availableTools)
            accumulateUsage(callResult.usage, state)
9
            /* -------------------------------------------------
             * Apply afterLlmCall callbacks - START
             * ------------------------------------------------- */
            val afterLlmContext = createAfterLlmCallContext(
                history = state.conversationHistory.toList(),
                iteration = state.iterations,
                response = callResult.message,
                usage = callResult.usage,
            )
            inspectors.notifyAfterLlmCall(afterLlmContext)
            val transformedResponse = transformers.applyAfterLlmCall(afterLlmContext)
            /* -------------------------------------------------
             * Apply afterLlmCall callbacks - END
             * ------------------------------------------------- */

            state.conversationHistory.add(transformedResponse)

            logger.debug(
                "ToolLoop returned. Passed messages:\n{}\nResult: {}",
                state.conversationHistory.joinToString("\n") { "\t" + it },
                transformedResponse,
            )

            if (!hasToolCalls(transformedResponse)) {
                /* -------------------------------------------------
                 * Apply afterIteration callbacks for early exit - START
                 * LLM returned final answer without tool calls.
                 * Call afterIteration with empty toolCalls for consistency,
                 * allowing inspectors to observe loop completion and
                 * transformers to perform final history cleanup.
                 * ------------------------------------------------- */
                val earlyExitContext = createAfterIterationContext(
                    history = state.conversationHistory.toList(),
                    iteration = state.iterations,
                    toolCallsInIteration = emptyList(),
                )
                inspectors.notifyAfterIteration(earlyExitContext)
                val historyAfterEarlyExit = transformers.applyAfterIteration(earlyExitContext)
                if (historyAfterEarlyExit != state.conversationHistory) {
                    state.conversationHistory.clear()
                    state.conversationHistory.addAll(historyAfterEarlyExit)
                }
                /* -------------------------------------------------
                 * Apply afterIteration callbacks for early exit - END
                 * ------------------------------------------------- */

                logCompletion(state.iterations)
                return buildResult(transformedResponse.content, outputParser, state)
            }

            val assistantMessage = transformedResponse as AssistantMessageWithToolCalls
            val shouldContinue = processToolCalls(assistantMessage.toolCalls, state)
            if (!shouldContinue) {
                logger.info("Tool loop terminated for replan after {} iterations", state.iterations)
                return buildResult("", outputParser, state)
            }

            /* -------------------------------------------------
             * Apply afterIteration callbacks - START
             * ------------------------------------------------- */
            val afterIterContext = createAfterIterationContext(
                history = state.conversationHistory.toList(),
                iteration = state.iterations,
                toolCallsInIteration = assistantMessage.toolCalls,
            )
            inspectors.notifyAfterIteration(afterIterContext)
            val historyAfterIteration = transformers.applyAfterIteration(afterIterContext)
            if (historyAfterIteration != state.conversationHistory) {
                state.conversationHistory.clear()
                state.conversationHistory.addAll(historyAfterIteration)
            }
            /* -------------------------------------------------
             * Apply afterIteration callbacks - END
             * ------------------------------------------------- */
        }

        throw MaxIterationsExceededException(maxIterations)
    }

    private fun hasToolCalls(message: Message): Boolean =
        message is AssistantMessageWithToolCalls && message.toolCalls.isNotEmpty()

    private fun logCompletion(iterations: Int) {
        val message = "Tool loop completed after {} iterations"
        if (iterations == 1) {
            logger.debug(message, iterations)
        } else {
            logger.info(message, iterations)
        }
    }

    private fun accumulateUsage(usage: Usage?, state: LoopState) {
        usage?.let {
            state.accumulatedUsage = state.accumulatedUsage?.plus(it) ?: it
        }
    }

    private fun <O> buildResult(
        finalText: String,
        outputParser: (String) -> O,
        state: LoopState,
    ): ToolLoopResult<O> = ToolLoopResult(
        result = outputParser(finalText),
        conversationHistory = state.conversationHistory,
        totalIterations = state.iterations,
        injectedTools = state.injectedTools,
        removedTools = state.removedTools,
        totalUsage = state.accumulatedUsage,
        replanRequested = state.replanRequested,
        replanReason = state.replanReason,
        blackboardUpdater = state.blackboardUpdater,
    )

    /**
     * Process all tool calls from a single LLM response.
     * Override to change execution strategy (e.g., parallel execution).
     *
     * @param toolCalls the tool calls to process
     * @param state the current loop state
     * @return true if loop should continue, false if replan was requested
     */
    protected open fun processToolCalls(
        toolCalls: List<ToolCall>,
        state: LoopState,
    ): Boolean {
        for (toolCall in toolCalls) {
            val shouldContinue = processToolCall(toolCall, state)
            if (!shouldContinue) return false
        }
        return true
    }

    /**
     * Process a single tool call.
     */
    protected fun processToolCall(
        toolCall: ToolCall,
        state: LoopState,
    ): Boolean {
        val tool = findTool(state.availableTools, toolCall.name)
            ?: throw ToolNotFoundException(toolCall.name, state.availableTools.map { it.definition.name })

        return try {
            val (result, resultContent) = executeToolCall(tool, toolCall)
            applyInjectionStrategy(toolCall, resultContent, state)
            addToolResultToHistory(toolCall, result, resultContent, state)
            true
        } catch (e: ReplanRequestedException) {
            logger.info("Tool '{}' requested replan: {}", toolCall.name, e.reason)
            state.replanRequested = true
            state.replanReason = e.reason
            state.blackboardUpdater = e.blackboardUpdater
            false
        } catch (e: Exception) {
            if (e is ToolControlFlowSignal) {
                // Other control flow signals (e.g., UserInputRequiredException) must propagate
                throw e
            }
            throw e
        }
    }

    protected fun executeToolCall(
        tool: Tool,
        toolCall: ToolCall,
    ): Pair<Tool.Result, String> {
        logger.debug("Executing tool: {} with input: {}", toolCall.name, toolCall.arguments)
        val result = tool.call(toolCall.arguments)
        val content = when (result) {
            is Tool.Result.Text -> result.content
            is Tool.Result.WithArtifact -> result.content
            is Tool.Result.Error -> "Error: ${result.message}"
        }
        return result to content
    }

    protected fun applyInjectionStrategy(
        toolCall: ToolCall,
        resultContent: String,
        state: LoopState,
    ) {
        val context = ToolInjectionContext(
            conversationHistory = state.conversationHistory,
            currentTools = state.availableTools,
            lastToolCall = ToolCallResult(
                toolName = toolCall.name,
                toolInput = toolCall.arguments,
                result = resultContent,
                resultObject = tryDeserialize(resultContent),
            ),
            iterationCount = state.iterations,
        )

        val injectionResult = injectionStrategy.evaluate(context)
        if (!injectionResult.hasChanges()) return

        removeTools(injectionResult.toolsToRemove, toolCall.name, state)
        addTools(injectionResult.toolsToAdd, toolCall.name, state)
    }

    private fun removeTools(
        toolsToRemove: List<Tool>,
        afterToolName: String,
        state: LoopState,
    ) {
        if (toolsToRemove.isEmpty()) return

        val namesToRemove = toolsToRemove.map { it.definition.name }.toSet()
        state.availableTools.removeIf { it.definition.name in namesToRemove }
        state.removedTools.addAll(toolsToRemove)
        logger.info("Strategy removed {} tools after {}: {}", toolsToRemove.size, afterToolName, namesToRemove)
    }

    private fun addTools(
        toolsToAdd: List<Tool>,
        afterToolName: String,
        state: LoopState,
    ) {
        if (toolsToAdd.isEmpty()) return

        val decoratedTools = if (toolDecorator != null) {
            toolsToAdd.map { toolDecorator.invoke(it) }
        } else {
            toolsToAdd
        }

        // Deduplicate: skip tools whose name already exists in the available set
        val existingNames = state.availableTools.map { it.definition.name }.toSet()
        val newTools = decoratedTools.filter { it.definition.name !in existingNames }

        if (newTools.isEmpty()) {
            logger.debug("All {} tools already present after {}, skipping", decoratedTools.size, afterToolName)
            return
        }

        state.availableTools.addAll(newTools)
        state.injectedTools.addAll(newTools)
        logger.info(
            "Strategy injected {} tools after {}: {}",
            newTools.size,
            afterToolName,
            newTools.map { it.definition.name }
        )
    }

    protected fun addToolResultToHistory(
        toolCall: ToolCall,
        result: Tool.Result,
        resultContent: String,
        state: LoopState,
    ) {
        /* -------------------------------------------------
         * Apply afterToolResult callbacks - START
         * ------------------------------------------------- */
        val afterToolContext = createAfterToolResultContext(
            history = state.conversationHistory.toList(),
            iteration = state.iterations,
            toolCall = toolCall,
            result = result,
            resultAsString = resultContent,
        )
        inspectors.notifyAfterToolResult(afterToolContext)
        val transformedResult = transformers.applyAfterToolResult(afterToolContext)
        /* -------------------------------------------------
         * Apply afterToolResult callbacks - END
         * ------------------------------------------------- */

        state.conversationHistory.add(
            ToolResultMessage(
                toolCallId = toolCall.id,
                toolName = toolCall.name,
                content = transformedResult,
            )
        )
    }

    protected class LoopState(
        val conversationHistory: MutableList<Message>,
        val availableTools: MutableList<Tool>,
        val injectedTools: MutableList<Tool> = mutableListOf(),
        val removedTools: MutableList<Tool> = mutableListOf(),
        var accumulatedUsage: Usage? = null,
        var iterations: Int = 0,
        var replanRequested: Boolean = false,
        var replanReason: String? = null,
        var blackboardUpdater: BlackboardUpdater = BlackboardUpdater {},
    )

    /**
     * Find a tool by name.
     */
    protected fun findTool(tools: List<Tool>, name: String): Tool? {
        return tools.find { it.definition.name == name }
    }

    /**
     * Try to deserialize a JSON result string.
     * Only attempts parsing if the result looks like JSON (starts with `{` or `[`).
     */
    private fun tryDeserialize(jsonResult: String): Any? {
        val trimmed = jsonResult.trimStart()
        if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) {
            return null
        }
        return try {
            objectMapper.readValue(jsonResult, Any::class.java)
        } catch (e: Exception) {
            logger.debug("Could not deserialize tool result as JSON: {}", e.message)
            null
        }
    }
}
