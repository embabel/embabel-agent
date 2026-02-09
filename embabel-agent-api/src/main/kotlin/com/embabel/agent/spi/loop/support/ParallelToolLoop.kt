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
import com.embabel.agent.api.tool.config.ToolLoopConfiguration.ParallelModeProperties
import com.embabel.agent.core.BlackboardUpdater
import com.embabel.agent.core.ReplanRequestedException
import com.embabel.agent.spi.loop.LlmMessageSender
import com.embabel.agent.spi.loop.ToolInjectionStrategy
import com.embabel.agent.spi.loop.ToolNotFoundException
import com.embabel.chat.ToolCall
import com.fasterxml.jackson.databind.ObjectMapper
import org.jetbrains.annotations.ApiStatus
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Experimental [com.embabel.agent.spi.loop.ToolLoop] implementation that executes
 * multiple tool calls from a single LLM response in parallel.
 *
 * Reduces latency for I/O-bound tool operations by running independent tools concurrently.
 *
 * Key behaviors:
 * - Parallel execution with collect-then-add pattern for thread-safe history
 * - First-wins strategy for [ReplanRequestedException] - stops loop on first replan request
 * - Injection strategy applied once after all tools complete (using last tool's context)
 * - Per-tool and batch timeouts from [ParallelModeProperties]
 *
 * @param llmMessageSender Framework-agnostic interface for making single LLM calls
 * @param objectMapper ObjectMapper for deserializing tool results
 * @param injectionStrategy Strategy for dynamically injecting tools
 * @param maxIterations Maximum number of tool loop iterations
 * @param toolDecorator Optional decorator applied to injected tools
 * @param executor ExecutorService for parallel tool execution
 * @param parallelConfig Configuration for parallel mode (timeouts, etc.)
 */
@ApiStatus.Experimental
internal class ParallelToolLoop(
    llmMessageSender: LlmMessageSender,
    objectMapper: ObjectMapper,
    injectionStrategy: ToolInjectionStrategy,
    maxIterations: Int,
    toolDecorator: ((Tool) -> Tool)?,
    private val executor: ExecutorService,
    private val parallelConfig: ParallelModeProperties,
) : DefaultToolLoop(
    llmMessageSender = llmMessageSender,
    objectMapper = objectMapper,
    injectionStrategy = injectionStrategy,
    maxIterations = maxIterations,
    toolDecorator = toolDecorator,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Executes tool calls in parallel using the configured executor.
     *
     * @param toolCalls the tool calls to process
     * @param state the current loop state
     * @return true if loop should continue, false if replan was requested
     */
    override fun processToolCalls(
        toolCalls: List<ToolCall>,
        state: LoopState,
    ): Boolean {
        // Single tool - delegate to sequential execution
        if (toolCalls.size == 1) {
            return super.processToolCalls(toolCalls, state)
        }

        logger.debug("Executing {} tools in parallel", toolCalls.size)
        val startTime = System.currentTimeMillis()

        // 1. Submit all tools with per-tool timeout
        val availableToolsSnapshot = state.availableTools.toList()
        val perToolTimeoutMs = parallelConfig.perToolTimeout.toMillis()

        val futures: List<CompletableFuture<ParallelToolResult>> = toolCalls.map { toolCall ->
            CompletableFuture.supplyAsync(
                { executeSingleToolCall(toolCall, availableToolsSnapshot) },
                executor,
            )
                .orTimeout(perToolTimeoutMs, TimeUnit.MILLISECONDS)
                .exceptionally { e ->
                    when (e.cause ?: e) {
                        is TimeoutException -> {
                            logger.warn("Tool '{}' timed out after {}ms", toolCall.name, perToolTimeoutMs)
                            ParallelToolResult.Timeout(toolCall)
                        }
                        else -> {
                            logger.error("Unexpected error for tool '{}'", toolCall.name, e)
                            ParallelToolResult.Error(toolCall, e.message ?: "Unknown error")
                        }
                    }
                }
        }

        // 2. Wait for all with batch timeout and collect results
        val results = try {
            CompletableFuture.allOf(*futures.toTypedArray())
                .orTimeout(parallelConfig.batchTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .thenApply { futures.map { f -> f.join() } }
                .join()
        } catch (e: Exception) {
            logger.warn("Batch timeout exceeded, collecting available results")
            futures.map { f -> f.getNow(ParallelToolResult.Timeout(toolCalls[futures.indexOf(f)])) }
        }

        val duration = System.currentTimeMillis() - startTime
        logger.debug("All {} tools completed in {}ms", toolCalls.size, duration)

        // 3. Check for replan request (first wins)
        val replanRequest = results.filterIsInstance<ParallelToolResult.ReplanRequest>().firstOrNull()
        if (replanRequest != null) {
            logger.debug("Tool '{}' requested replan: {}", replanRequest.toolCall.name, replanRequest.reason)
            state.replanRequested = true
            state.replanReason = replanRequest.reason
            state.blackboardUpdater = replanRequest.blackboardUpdater
            return false
        }

        // 4. Add results to history IN ORDER (preserves deterministic conversation)
        for (result in results) {
            when (result) {
                is ParallelToolResult.Success ->
                    addToolResultToHistory(result.toolCall, result.result, state)

                is ParallelToolResult.Error ->
                    addToolResultToHistory(result.toolCall, "Error: ${result.message}", state)

                is ParallelToolResult.Timeout ->
                    addToolResultToHistory(result.toolCall, "Error: Tool execution timed out", state)

                is ParallelToolResult.ReplanRequest -> {
                    // Already handled above
                }
            }
        }

        // 5. Apply injection strategy once (using last successful result's context)
        val lastSuccess = results.filterIsInstance<ParallelToolResult.Success>().lastOrNull()
        if (lastSuccess != null) {
            applyInjectionStrategy(lastSuccess.toolCall, lastSuccess.result, state)
        }

        return true
    }

    private fun executeSingleToolCall(
        toolCall: ToolCall,
        availableTools: List<Tool>,
    ): ParallelToolResult {
        val tool = findTool(availableTools, toolCall.name)
            ?: return ParallelToolResult.Error(
                toolCall,
                ToolNotFoundException(toolCall.name, availableTools.map { it.definition.name }).message
                    ?: "Tool not found: ${toolCall.name}",
            )

        return try {
            val result = executeToolCall(tool, toolCall)
            ParallelToolResult.Success(toolCall, result)
        } catch (e: ReplanRequestedException) {
            ParallelToolResult.ReplanRequest(toolCall, e.reason, e.blackboardUpdater)
        } catch (e: Exception) {
            logger.error("Tool '{}' execution failed", toolCall.name, e)
            ParallelToolResult.Error(toolCall, e.message ?: "Unknown error")
        }
    }

    /**
     * Result of a single tool execution in parallel mode.
     */
    private sealed class ParallelToolResult {
        abstract val toolCall: ToolCall

        data class Success(
            override val toolCall: ToolCall,
            val result: String,
        ) : ParallelToolResult()

        data class ReplanRequest(
            override val toolCall: ToolCall,
            val reason: String,
            val blackboardUpdater: BlackboardUpdater,
        ) : ParallelToolResult()

        data class Error(
            override val toolCall: ToolCall,
            val message: String,
        ) : ParallelToolResult()

        data class Timeout(
            override val toolCall: ToolCall,
        ) : ParallelToolResult()
    }
}
