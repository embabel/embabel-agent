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
import com.embabel.agent.core.ReplanRequestedException
import com.embabel.agent.spi.loop.LlmMessageResponse
import com.embabel.agent.spi.loop.LlmMessageSender
import com.embabel.agent.spi.loop.MockLlmMessageSender
import com.embabel.agent.spi.loop.MockTool
import com.embabel.agent.spi.loop.ToolInjectionContext
import com.embabel.agent.spi.loop.ToolInjectionStrategy
import com.embabel.chat.AssistantMessage
import com.embabel.chat.AssistantMessageWithToolCalls
import com.embabel.chat.Message
import com.embabel.chat.ToolCall
import com.embabel.chat.ToolResultMessage
import com.embabel.chat.UserMessage
import com.embabel.agent.spi.support.ExecutorAsyncer
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [ParallelToolLoop].
 */
class ParallelToolLoopTest {

    private val objectMapper = jacksonObjectMapper()
    private val executor = Executors.newFixedThreadPool(4)
    private val asyncer = ExecutorAsyncer(executor)
    private val parallelConfig = ParallelModeProperties(
        perToolTimeout = Duration.ofSeconds(5),
        batchTimeout = Duration.ofSeconds(10),
    )

    @Nested
    inner class SingleToolDelegationTest {

        @Test
        fun `single tool call delegates to sequential execution`() {
            val toolCalled = AtomicInteger(0)

            val mockTool = MockTool(
                name = "single_tool",
                description = "A single tool",
                onCall = {
                    toolCalled.incrementAndGet()
                    Tool.Result.text("""{"status": "ok"}""")
                }
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.toolCallResponse("call_1", "single_tool", "{}"),
                    MockLlmMessageSender.textResponse("Done!")
                )
            )

            val toolLoop = ParallelToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                injectionStrategy = ToolInjectionStrategy.NONE,
                maxIterations = 20,
                toolDecorator = null,
                asyncer = asyncer,
                parallelConfig = parallelConfig,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Test")),
                initialTools = listOf(mockTool),
                outputParser = { it }
            )

            assertEquals("Done!", result.result)
            assertEquals(1, toolCalled.get())
        }
    }

    @Nested
    inner class ParallelExecutionTest {

        @Test
        fun `multiple tool calls execute in parallel`() {
            val executionThreads = ConcurrentHashMap<String, String>()
            val startLatch = CountDownLatch(1)
            val toolsStarted = CountDownLatch(3)

            val tool1 = MockTool(
                name = "tool_a",
                description = "Tool A",
                onCall = {
                    toolsStarted.countDown()
                    startLatch.await(5, TimeUnit.SECONDS)
                    executionThreads["tool_a"] = Thread.currentThread().name
                    Tool.Result.text("""{"tool": "a"}""")
                }
            )

            val tool2 = MockTool(
                name = "tool_b",
                description = "Tool B",
                onCall = {
                    toolsStarted.countDown()
                    startLatch.await(5, TimeUnit.SECONDS)
                    executionThreads["tool_b"] = Thread.currentThread().name
                    Tool.Result.text("""{"tool": "b"}""")
                }
            )

            val tool3 = MockTool(
                name = "tool_c",
                description = "Tool C",
                onCall = {
                    toolsStarted.countDown()
                    startLatch.await(5, TimeUnit.SECONDS)
                    executionThreads["tool_c"] = Thread.currentThread().name
                    Tool.Result.text("""{"tool": "c"}""")
                }
            )

            val mockCaller = multiToolCallSender(
                listOf(
                    ToolCall("1", "tool_a", "{}"),
                    ToolCall("2", "tool_b", "{}"),
                    ToolCall("3", "tool_c", "{}"),
                )
            )

            val toolLoop = ParallelToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                injectionStrategy = ToolInjectionStrategy.NONE,
                maxIterations = 20,
                toolDecorator = null,
                asyncer = asyncer,
                parallelConfig = parallelConfig,
            )

            // Start test in separate thread so we can release the latch
            val resultFuture = Executors.newSingleThreadExecutor().submit<String> {
                val result = toolLoop.execute(
                    initialMessages = listOf(UserMessage("Run all")),
                    initialTools = listOf(tool1, tool2, tool3),
                    outputParser = { it }
                )
                result.result
            }

            // Wait for all tools to start (proves they're running in parallel)
            assertTrue(toolsStarted.await(5, TimeUnit.SECONDS), "All tools should start concurrently")

            // Release all tools to complete
            startLatch.countDown()

            val result = resultFuture.get(10, TimeUnit.SECONDS)
            assertEquals("All done", result)
            assertEquals(3, executionThreads.size)
        }

        @Test
        fun `results are added to history in original order`() {
            val completionOrder = mutableListOf<String>()

            // Tool B completes first, Tool A completes last
            val toolA = MockTool(
                name = "tool_a",
                description = "Tool A (slow)",
                onCall = {
                    Thread.sleep(100)
                    synchronized(completionOrder) { completionOrder.add("a") }
                    Tool.Result.text("""{"tool": "a"}""")
                }
            )

            val toolB = MockTool(
                name = "tool_b",
                description = "Tool B (fast)",
                onCall = {
                    Thread.sleep(10)
                    synchronized(completionOrder) { completionOrder.add("b") }
                    Tool.Result.text("""{"tool": "b"}""")
                }
            )

            val mockCaller = multiToolCallSender(
                listOf(
                    ToolCall("1", "tool_a", "{}"),
                    ToolCall("2", "tool_b", "{}"),
                )
            )

            val toolLoop = ParallelToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                injectionStrategy = ToolInjectionStrategy.NONE,
                maxIterations = 20,
                toolDecorator = null,
                asyncer = asyncer,
                parallelConfig = parallelConfig,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Run both")),
                initialTools = listOf(toolA, toolB),
                outputParser = { it }
            )

            // Tool B completed first
            assertEquals(listOf("b", "a"), completionOrder)

            // But history has them in original order (A before B)
            val toolResults = result.conversationHistory.filterIsInstance<ToolResultMessage>()
            assertEquals(2, toolResults.size)
            assertEquals("tool_a", toolResults[0].toolName)
            assertEquals("tool_b", toolResults[1].toolName)
        }
    }

    @Nested
    inner class TimeoutTest {

        @Test
        fun `per-tool timeout produces timeout result`() {
            val shortTimeoutConfig = ParallelModeProperties(
                perToolTimeout = Duration.ofMillis(100),
                batchTimeout = Duration.ofSeconds(10),
            )

            val slowTool = MockTool(
                name = "slow_tool",
                description = "Very slow tool",
                onCall = {
                    Thread.sleep(5000) // Way longer than timeout
                    Tool.Result.text("""{"status": "ok"}""")
                }
            )

            val fastTool = MockTool(
                name = "fast_tool",
                description = "Fast tool",
                onCall = { Tool.Result.text("""{"status": "fast"}""") }
            )

            val mockCaller = multiToolCallSender(
                listOf(
                    ToolCall("1", "slow_tool", "{}"),
                    ToolCall("2", "fast_tool", "{}"),
                )
            )

            val toolLoop = ParallelToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                injectionStrategy = ToolInjectionStrategy.NONE,
                maxIterations = 20,
                toolDecorator = null,
                asyncer = asyncer,
                parallelConfig = shortTimeoutConfig,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Run both")),
                initialTools = listOf(slowTool, fastTool),
                outputParser = { it }
            )

            val toolResults = result.conversationHistory.filterIsInstance<ToolResultMessage>()
            assertEquals(2, toolResults.size)

            // Slow tool should have timeout error
            assertTrue(toolResults[0].content.contains("timed out"))

            // Fast tool should have succeeded
            assertEquals("""{"status": "fast"}""", toolResults[1].content)
        }
    }

    @Nested
    inner class ReplanRequestTest {

        @Test
        fun `first replan request wins and stops loop`() {
            val replanOrder = mutableListOf<String>()

            val replanTool1 = MockTool(
                name = "replan_a",
                description = "First replanner",
                onCall = {
                    Thread.sleep(10)
                    synchronized(replanOrder) { replanOrder.add("a") }
                    throw ReplanRequestedException(reason = "Reason A")
                }
            )

            val replanTool2 = MockTool(
                name = "replan_b",
                description = "Second replanner",
                onCall = {
                    Thread.sleep(50)
                    synchronized(replanOrder) { replanOrder.add("b") }
                    throw ReplanRequestedException(reason = "Reason B")
                }
            )

            val mockCaller = multiToolCallSender(
                listOf(
                    ToolCall("1", "replan_a", "{}"),
                    ToolCall("2", "replan_b", "{}"),
                )
            )

            val toolLoop = ParallelToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                injectionStrategy = ToolInjectionStrategy.NONE,
                maxIterations = 20,
                toolDecorator = null,
                asyncer = asyncer,
                parallelConfig = parallelConfig,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Both replan")),
                initialTools = listOf(replanTool1, replanTool2),
                outputParser = { it }
            )

            assertTrue(result.replanRequested)
            // First tool in order wins (not first to complete)
            assertEquals("Reason A", result.replanReason)
        }

        @Test
        fun `successful tools complete even when one requests replan`() {
            val successToolCalled = AtomicInteger(0)

            val successTool = MockTool(
                name = "success_tool",
                description = "Successful tool",
                onCall = {
                    successToolCalled.incrementAndGet()
                    Tool.Result.text("""{"status": "ok"}""")
                }
            )

            val replanTool = MockTool(
                name = "replan_tool",
                description = "Replan tool",
                onCall = {
                    Thread.sleep(50) // Complete after success tool
                    throw ReplanRequestedException(reason = "Need replan")
                }
            )

            val mockCaller = multiToolCallSender(
                listOf(
                    ToolCall("1", "success_tool", "{}"),
                    ToolCall("2", "replan_tool", "{}"),
                )
            )

            val toolLoop = ParallelToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                injectionStrategy = ToolInjectionStrategy.NONE,
                maxIterations = 20,
                toolDecorator = null,
                asyncer = asyncer,
                parallelConfig = parallelConfig,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Mixed")),
                initialTools = listOf(successTool, replanTool),
                outputParser = { it }
            )

            assertTrue(result.replanRequested)
            assertEquals(1, successToolCalled.get()) // Success tool was called
        }
    }

    @Nested
    inner class ErrorHandlingTest {

        @Test
        fun `tool error is captured and added to history`() {
            val errorTool = MockTool(
                name = "error_tool",
                description = "Throws exception",
                onCall = { throw RuntimeException("Something went wrong") }
            )

            val successTool = MockTool(
                name = "success_tool",
                description = "Works fine",
                onCall = { Tool.Result.text("""{"status": "ok"}""") }
            )

            val mockCaller = multiToolCallSender(
                listOf(
                    ToolCall("1", "error_tool", "{}"),
                    ToolCall("2", "success_tool", "{}"),
                )
            )

            val toolLoop = ParallelToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                injectionStrategy = ToolInjectionStrategy.NONE,
                maxIterations = 20,
                toolDecorator = null,
                asyncer = asyncer,
                parallelConfig = parallelConfig,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Run both")),
                initialTools = listOf(errorTool, successTool),
                outputParser = { it }
            )

            val toolResults = result.conversationHistory.filterIsInstance<ToolResultMessage>()
            assertEquals(2, toolResults.size)

            assertTrue(toolResults[0].content.contains("Error:"))
            assertEquals("""{"status": "ok"}""", toolResults[1].content)
        }
    }

    @Nested
    inner class InjectionStrategyTest {

        @Test
        fun `injection strategy applied once using last successful result`() {
            var strategyCallCount = 0
            var lastToolName: String? = null

            val strategy = object : ToolInjectionStrategy {
                override fun evaluateToolResult(context: ToolInjectionContext): List<Tool> {
                    strategyCallCount++
                    lastToolName = context.lastToolCall.toolName
                    return emptyList()
                }
            }

            val toolA = MockTool(
                name = "tool_a",
                description = "Tool A",
                onCall = { Tool.Result.text("""{"tool": "a"}""") }
            )

            val toolB = MockTool(
                name = "tool_b",
                description = "Tool B",
                onCall = { Tool.Result.text("""{"tool": "b"}""") }
            )

            val mockCaller = multiToolCallSender(
                listOf(
                    ToolCall("1", "tool_a", "{}"),
                    ToolCall("2", "tool_b", "{}"),
                )
            )

            val toolLoop = ParallelToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                injectionStrategy = strategy,
                maxIterations = 20,
                toolDecorator = null,
                asyncer = asyncer,
                parallelConfig = parallelConfig,
            )

            toolLoop.execute(
                initialMessages = listOf(UserMessage("Run both")),
                initialTools = listOf(toolA, toolB),
                outputParser = { it }
            )

            // Strategy called once (after all tools complete)
            assertEquals(1, strategyCallCount)
            // Using last tool's context
            assertEquals("tool_b", lastToolName)
        }
    }

    /**
     * Creates a mock sender that returns multiple tool calls, then a final text response.
     */
    private fun multiToolCallSender(toolCalls: List<ToolCall>): LlmMessageSender {
        return object : LlmMessageSender {
            private var called = false

            override fun call(messages: List<Message>, tools: List<Tool>): LlmMessageResponse {
                if (!called) {
                    called = true
                    return LlmMessageResponse(
                        message = AssistantMessageWithToolCalls(
                            content = " ",
                            toolCalls = toolCalls,
                        ),
                        textContent = "",
                    )
                }
                return LlmMessageResponse(
                    message = AssistantMessage("All done"),
                    textContent = "All done",
                )
            }
        }
    }
}
