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
import com.embabel.agent.spi.loop.LlmMessageResponse
import com.embabel.agent.spi.loop.LlmMessageSender
import com.embabel.agent.spi.loop.MockLlmMessageSender
import com.embabel.agent.spi.loop.MockTool
import com.embabel.agent.spi.loop.support.DefaultToolLoop
import com.embabel.chat.AssistantMessage
import com.embabel.chat.AssistantMessageWithToolCalls
import com.embabel.chat.Message
import com.embabel.chat.SystemMessage
import com.embabel.chat.ToolCall
import com.embabel.chat.ToolResultMessage
import com.embabel.chat.UserMessage
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ToolLoopInspector] and [ToolLoopTransformer] callbacks.
 */
class ToolLoopCallbackTest {

    private val objectMapper = jacksonObjectMapper()

    @Nested
    inner class InspectorTest {

        @Test
        fun `beforeLlmCall is called before each LLM invocation`() {
            val beforeCalls = mutableListOf<BeforeLlmCallContext>()

            val inspector = object : ToolLoopInspector {
                override fun beforeLlmCall(context: BeforeLlmCallContext) {
                    beforeCalls.add(context)
                }
            }

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.Companion.textResponse("Hello!")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                inspectors = listOf(inspector),
            )

            toolLoop.execute(
                initialMessages = listOf(UserMessage("Say hello")),
                initialTools = emptyList(),
                outputParser = { it }
            )

            Assertions.assertEquals(1, beforeCalls.size)
            Assertions.assertEquals(1, beforeCalls[0].iteration)
            Assertions.assertEquals(1, beforeCalls[0].history.size)
        }

        @Test
        fun `afterLlmCall is called after LLM returns response`() {
            val afterLlmCalls = mutableListOf<AfterLlmCallContext>()

            val inspector = object : ToolLoopInspector {
                override fun afterLlmCall(context: AfterLlmCallContext) {
                    afterLlmCalls.add(context)
                }
            }

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.Companion.textResponse("Hello!")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                inspectors = listOf(inspector),
            )

            toolLoop.execute(
                initialMessages = listOf(UserMessage("Say hello")),
                initialTools = emptyList(),
                outputParser = { it }
            )

            Assertions.assertEquals(1, afterLlmCalls.size)
            Assertions.assertEquals(1, afterLlmCalls[0].iteration)
            Assertions.assertEquals("Hello!", afterLlmCalls[0].response.content)
        }

        @Test
        fun `afterLlmCall receives response with tool calls`() {
            val afterLlmCalls = mutableListOf<AfterLlmCallContext>()

            val inspector = object : ToolLoopInspector {
                override fun afterLlmCall(context: AfterLlmCallContext) {
                    afterLlmCalls.add(context)
                }
            }

            val mockTool = MockTool(
                name = "test_tool",
                description = "Test tool",
                onCall = { Tool.Result.text("""{"status": "ok"}""") }
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.Companion.toolCallResponse("call_1", "test_tool", "{}"),
                    MockLlmMessageSender.Companion.textResponse("Done!")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                inspectors = listOf(inspector),
            )

            toolLoop.execute(
                initialMessages = listOf(UserMessage("Use the tool")),
                initialTools = listOf(mockTool),
                outputParser = { it }
            )

            Assertions.assertEquals(2, afterLlmCalls.size)
            // First response has tool calls
            Assertions.assertTrue(afterLlmCalls[0].response is AssistantMessageWithToolCalls)
            val toolCallResponse = afterLlmCalls[0].response as AssistantMessageWithToolCalls
            Assertions.assertEquals(1, toolCallResponse.toolCalls.size)
            Assertions.assertEquals("test_tool", toolCallResponse.toolCalls[0].name)
            // Second response is final answer
            Assertions.assertEquals("Done!", afterLlmCalls[1].response.content)
        }

        @Test
        fun `afterIteration is called with empty toolCalls when LLM returns final answer`() {
            val afterIterationCalls = mutableListOf<AfterIterationContext>()

            val inspector = object : ToolLoopInspector {
                override fun afterIteration(context: AfterIterationContext) {
                    afterIterationCalls.add(context)
                }
            }

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.Companion.textResponse("Hello!")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                inspectors = listOf(inspector),
            )

            toolLoop.execute(
                initialMessages = listOf(UserMessage("Say hello")),
                initialTools = emptyList(),
                outputParser = { it }
            )

            // afterIteration should be called even when no tool calls
            Assertions.assertEquals(1, afterIterationCalls.size)
            Assertions.assertEquals(1, afterIterationCalls[0].iteration)
            Assertions.assertTrue(afterIterationCalls[0].toolCallsInIteration.isEmpty())
        }

        @Test
        fun `afterToolResult is called after each tool execution`() {
            val afterToolCalls = mutableListOf<AfterToolResultContext>()

            val inspector = object : ToolLoopInspector {
                override fun afterToolResult(context: AfterToolResultContext) {
                    afterToolCalls.add(context)
                }
            }

            val mockTool = MockTool(
                name = "test_tool",
                description = "Test tool",
                onCall = { Tool.Result.text("""{"status": "ok"}""") }
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.Companion.toolCallResponse("call_1", "test_tool", "{}"),
                    MockLlmMessageSender.Companion.textResponse("Done!")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                inspectors = listOf(inspector),
            )

            toolLoop.execute(
                initialMessages = listOf(UserMessage("Use the tool")),
                initialTools = listOf(mockTool),
                outputParser = { it }
            )

            Assertions.assertEquals(1, afterToolCalls.size)
            Assertions.assertEquals("test_tool", afterToolCalls[0].toolCall.name)
            Assertions.assertEquals("""{"status": "ok"}""", afterToolCalls[0].resultAsString)
            Assertions.assertEquals(1, afterToolCalls[0].iteration)
        }

        @Test
        fun `afterIteration is called after each complete iteration`() {
            val afterIterationCalls = mutableListOf<AfterIterationContext>()

            val inspector = object : ToolLoopInspector {
                override fun afterIteration(context: AfterIterationContext) {
                    afterIterationCalls.add(context)
                }
            }

            val mockTool = MockTool(
                name = "test_tool",
                description = "Test tool",
                onCall = { Tool.Result.text("""{"status": "ok"}""") }
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.Companion.toolCallResponse("call_1", "test_tool", "{}"),
                    MockLlmMessageSender.Companion.textResponse("Done!")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                inspectors = listOf(inspector),
            )

            toolLoop.execute(
                initialMessages = listOf(UserMessage("Use the tool")),
                initialTools = listOf(mockTool),
                outputParser = { it }
            )

            // Now called twice: once after tool execution, once for early exit (final answer)
            Assertions.assertEquals(2, afterIterationCalls.size)
            // First iteration: with tool call
            Assertions.assertEquals(1, afterIterationCalls[0].iteration)
            Assertions.assertEquals(1, afterIterationCalls[0].toolCallsInIteration.size)
            Assertions.assertEquals("test_tool", afterIterationCalls[0].toolCallsInIteration[0].name)
            // Second iteration: early exit (no tool calls)
            Assertions.assertEquals(2, afterIterationCalls[1].iteration)
            Assertions.assertTrue(afterIterationCalls[1].toolCallsInIteration.isEmpty())
        }

        @Test
        fun `multiple inspectors are all notified`() {
            val inspector1Calls = mutableListOf<String>()
            val inspector2Calls = mutableListOf<String>()

            val inspector1 = object : ToolLoopInspector {
                override fun beforeLlmCall(context: BeforeLlmCallContext) {
                    inspector1Calls.add("beforeLlmCall")
                }
                override fun afterLlmCall(context: AfterLlmCallContext) {
                    inspector1Calls.add("afterLlmCall")
                }
                override fun afterToolResult(context: AfterToolResultContext) {
                    inspector1Calls.add("afterToolResult")
                }
                override fun afterIteration(context: AfterIterationContext) {
                    inspector1Calls.add("afterIteration")
                }
            }

            val inspector2 = object : ToolLoopInspector {
                override fun beforeLlmCall(context: BeforeLlmCallContext) {
                    inspector2Calls.add("beforeLlmCall")
                }
                override fun afterLlmCall(context: AfterLlmCallContext) {
                    inspector2Calls.add("afterLlmCall")
                }
                override fun afterToolResult(context: AfterToolResultContext) {
                    inspector2Calls.add("afterToolResult")
                }
                override fun afterIteration(context: AfterIterationContext) {
                    inspector2Calls.add("afterIteration")
                }
            }

            val mockTool = MockTool(
                name = "test_tool",
                description = "Test tool",
                onCall = { Tool.Result.text("""{"status": "ok"}""") }
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.Companion.toolCallResponse("call_1", "test_tool", "{}"),
                    MockLlmMessageSender.Companion.textResponse("Done!")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                inspectors = listOf(inspector1, inspector2),
            )

            toolLoop.execute(
                initialMessages = listOf(UserMessage("Use the tool")),
                initialTools = listOf(mockTool),
                outputParser = { it }
            )

            // Flow: beforeLlmCall -> afterLlmCall -> afterToolResult -> afterIteration -> beforeLlmCall -> afterLlmCall -> afterIteration(early exit)
            Assertions.assertEquals(
                listOf(
                    "beforeLlmCall",
                    "afterLlmCall",
                    "afterToolResult",
                    "afterIteration",
                    "beforeLlmCall",
                    "afterLlmCall",
                    "afterIteration"
                ), inspector1Calls
            )
            Assertions.assertEquals(
                listOf(
                    "beforeLlmCall",
                    "afterLlmCall",
                    "afterToolResult",
                    "afterIteration",
                    "beforeLlmCall",
                    "afterLlmCall",
                    "afterIteration"
                ), inspector2Calls
            )
        }

        @Test
        fun `inspector receives tools in context`() {
            var capturedTools: List<Tool>? = null

            val inspector = object : ToolLoopInspector {
                override fun beforeLlmCall(context: BeforeLlmCallContext) {
                    capturedTools = context.tools
                }
            }

            val mockTool = MockTool(
                name = "my_tool",
                description = "My tool",
                onCall = { Tool.Result.text("{}") }
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.Companion.textResponse("Hello!")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                inspectors = listOf(inspector),
            )

            toolLoop.execute(
                initialMessages = listOf(UserMessage("Hello")),
                initialTools = listOf(mockTool),
                outputParser = { it }
            )

            Assertions.assertNotNull(capturedTools)
            Assertions.assertEquals(1, capturedTools!!.size)
            Assertions.assertEquals("my_tool", capturedTools!![0].definition.name)
        }
    }

    @Nested
    inner class TransformerTest {

        @Test
        fun `transformBeforeLlmCall can modify history before LLM call`() {
            var originalHistorySize: Int? = null

            val transformer = object : ToolLoopTransformer {
                override fun transformBeforeLlmCall(context: BeforeLlmCallContext): List<Message> {
                    originalHistorySize = context.history.size
                    // Add a system message to the history
                    return listOf(SystemMessage("Injected system message")) + context.history
                }
            }

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.Companion.textResponse("Hello!")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                transformers = listOf(transformer),
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Say hello")),
                initialTools = emptyList(),
                outputParser = { it }
            )

            Assertions.assertEquals(1, originalHistorySize)
            // History should now have: SystemMessage (injected), UserMessage, AssistantMessage
            Assertions.assertEquals(3, result.conversationHistory.size)
            Assertions.assertTrue(result.conversationHistory[0] is SystemMessage)
        }

        @Test
        fun `transformAfterLlmCall can observe and pass through response`() {
            var capturedResponse: Message? = null

            val transformer = object : ToolLoopTransformer {
                override fun transformAfterLlmCall(context: AfterLlmCallContext): Message {
                    capturedResponse = context.response
                    return context.response
                }
            }

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.Companion.textResponse("Hello!")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                transformers = listOf(transformer),
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Say hello")),
                initialTools = emptyList(),
                outputParser = { it }
            )

            Assertions.assertNotNull(capturedResponse)
            Assertions.assertEquals("Hello!", capturedResponse!!.content)
            Assertions.assertEquals("Hello!", result.conversationHistory.last().content)
        }

        @Test
        fun `transformAfterToolResult can modify tool result`() {
            var originalResult: String? = null

            val transformer = object : ToolLoopTransformer {
                override fun transformAfterToolResult(context: AfterToolResultContext): String {
                    originalResult = context.resultAsString
                    return "TRANSFORMED: ${context.resultAsString}"
                }
            }

            val mockTool = MockTool(
                name = "test_tool",
                description = "Test tool",
                onCall = { Tool.Result.text("""{"status": "ok"}""") }
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.Companion.toolCallResponse("call_1", "test_tool", "{}"),
                    MockLlmMessageSender.Companion.textResponse("Done!")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                transformers = listOf(transformer),
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Use the tool")),
                initialTools = listOf(mockTool),
                outputParser = { it }
            )

            Assertions.assertEquals("""{"status": "ok"}""", originalResult)
            // Check that the tool result message has the transformed content
            val toolResultMessage = result.conversationHistory
                .filterIsInstance<ToolResultMessage>()
                .first()
            Assertions.assertEquals("""TRANSFORMED: {"status": "ok"}""", toolResultMessage.content)
        }

        @Test
        fun `transformAfterIteration can modify history after iteration`() {
            val iterationHistorySizes = mutableListOf<Int>()

            val transformer = object : ToolLoopTransformer {
                override fun transformAfterIteration(context: AfterIterationContext): List<Message> {
                    iterationHistorySizes.add(context.history.size)
                    // Keep only the last 2 messages
                    return if (context.history.size > 2) {
                        context.history.takeLast(2)
                    } else {
                        context.history
                    }
                }
            }

            val mockTool = MockTool(
                name = "test_tool",
                description = "Test tool",
                onCall = { Tool.Result.text("""{"status": "ok"}""") }
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.Companion.toolCallResponse("call_1", "test_tool", "{}"),
                    MockLlmMessageSender.Companion.textResponse("Done!")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                transformers = listOf(transformer),
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Use the tool")),
                initialTools = listOf(mockTool),
                outputParser = { it }
            )

            // Called twice: after tool execution and after early exit
            Assertions.assertEquals(2, iterationHistorySizes.size)
            // First iteration: User, Assistant(tool call), ToolResult = 3
            Assertions.assertEquals(3, iterationHistorySizes[0])
            // Second iteration (early exit): reduced to 2, then "Done!" added = 3
            Assertions.assertEquals(3, iterationHistorySizes[1])
            // Final history: transformer reduced last iteration to 2
            Assertions.assertEquals(2, result.conversationHistory.size)
        }

        @Test
        fun `multiple transformers are applied in order`() {
            val transformer1 = object : ToolLoopTransformer {
                override fun transformAfterToolResult(context: AfterToolResultContext): String {
                    return "[T1:${context.resultAsString}]"
                }
            }

            val transformer2 = object : ToolLoopTransformer {
                override fun transformAfterToolResult(context: AfterToolResultContext): String {
                    return "[T2:${context.resultAsString}]"
                }
            }

            val mockTool = MockTool(
                name = "test_tool",
                description = "Test tool",
                onCall = { Tool.Result.text("original") }
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.Companion.toolCallResponse("call_1", "test_tool", "{}"),
                    MockLlmMessageSender.Companion.textResponse("Done!")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                transformers = listOf(transformer1, transformer2),
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Use the tool")),
                initialTools = listOf(mockTool),
                outputParser = { it }
            )

            val toolResultMessage = result.conversationHistory
                .filterIsInstance<ToolResultMessage>()
                .first()
            // T1 applied first, then T2
            Assertions.assertEquals("[T2:[T1:original]]", toolResultMessage.content)
        }

        @Test
        fun `transformers can implement sliding window compression`() {
            val maxHistorySize = 3

            val slidingWindowTransformer = object : ToolLoopTransformer {
                override fun transformBeforeLlmCall(context: BeforeLlmCallContext): List<Message> {
                    return if (context.history.size > maxHistorySize) {
                        context.history.takeLast(maxHistorySize)
                    } else {
                        context.history
                    }
                }
            }

            val mockTool = MockTool(
                name = "test_tool",
                description = "Test tool",
                onCall = { Tool.Result.text("ok") }
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.Companion.toolCallResponse("call_1", "test_tool", "{}"),
                    MockLlmMessageSender.Companion.toolCallResponse("call_2", "test_tool", "{}"),
                    MockLlmMessageSender.Companion.textResponse("Done!")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                transformers = listOf(slidingWindowTransformer),
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Use the tool twice")),
                initialTools = listOf(mockTool),
                outputParser = { it }
            )

            // Sliding window keeps history small
            // Final history includes the last assistant message
            Assertions.assertTrue(result.conversationHistory.size <= maxHistorySize + 1)
        }
    }

    @Nested
    inner class CombinedInspectorAndTransformerTest {

        @Test
        fun `inspectors and transformers can be used together`() {
            val inspectorCalls = mutableListOf<String>()
            val transformerCalls = mutableListOf<String>()

            val inspector = object : ToolLoopInspector {
                override fun beforeLlmCall(context: BeforeLlmCallContext) {
                    inspectorCalls.add("beforeLlmCall:${context.history.size}")
                }
            }

            val transformer = object : ToolLoopTransformer {
                override fun transformBeforeLlmCall(context: BeforeLlmCallContext): List<Message> {
                    transformerCalls.add("transformBeforeLlmCall:${context.history.size}")
                    return context.history
                }
            }

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.Companion.textResponse("Hello!")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                inspectors = listOf(inspector),
                transformers = listOf(transformer),
            )

            toolLoop.execute(
                initialMessages = listOf(UserMessage("Hello")),
                initialTools = emptyList(),
                outputParser = { it }
            )

            // Both should be called
            Assertions.assertEquals(1, inspectorCalls.size)
            Assertions.assertEquals(1, transformerCalls.size)
            Assertions.assertEquals("beforeLlmCall:1", inspectorCalls[0])
            Assertions.assertEquals("transformBeforeLlmCall:1", transformerCalls[0])
        }
    }

    @Nested
    inner class MultipleToolCallsPerIterationTest {

        @Test
        fun `afterToolResult is called for each tool in a multi-tool response`() {
            val toolResultContexts = mutableListOf<AfterToolResultContext>()

            val inspector = object : ToolLoopInspector {
                override fun afterToolResult(context: AfterToolResultContext) {
                    toolResultContexts.add(context)
                }
            }

            val tool1 = MockTool(
                name = "tool_a",
                description = "Tool A",
                onCall = { Tool.Result.text("A result") }
            )

            val tool2 = MockTool(
                name = "tool_b",
                description = "Tool B",
                onCall = { Tool.Result.text("B result") }
            )

            val mockCaller = object : LlmMessageSender {
                private var called = false
                override fun call(messages: List<Message>, tools: List<Tool>): LlmMessageResponse {
                    if (!called) {
                        called = true
                        return LlmMessageResponse(
                            message = AssistantMessageWithToolCalls(
                                content = " ",
                                toolCalls = listOf(
                                    ToolCall("1", "tool_a", "{}"),
                                    ToolCall("2", "tool_b", "{}"),
                                ),
                            ),
                            textContent = "",
                        )
                    }
                    return LlmMessageResponse(
                        message = AssistantMessage("Done"),
                        textContent = "Done",
                    )
                }
            }

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                inspectors = listOf(inspector),
            )

            toolLoop.execute(
                initialMessages = listOf(UserMessage("Use both tools")),
                initialTools = listOf(tool1, tool2),
                outputParser = { it }
            )

            Assertions.assertEquals(2, toolResultContexts.size)
            Assertions.assertEquals("tool_a", toolResultContexts[0].toolCall.name)
            Assertions.assertEquals("A result", toolResultContexts[0].resultAsString)
            Assertions.assertEquals("tool_b", toolResultContexts[1].toolCall.name)
            Assertions.assertEquals("B result", toolResultContexts[1].resultAsString)
        }

        @Test
        fun `afterIteration receives all tool calls from the iteration`() {
            val afterIterationCalls = mutableListOf<AfterIterationContext>()

            val inspector = object : ToolLoopInspector {
                override fun afterIteration(context: AfterIterationContext) {
                    afterIterationCalls.add(context)
                }
            }

            val tool1 = MockTool(
                name = "tool_a",
                description = "Tool A",
                onCall = { Tool.Result.text("A result") }
            )

            val tool2 = MockTool(
                name = "tool_b",
                description = "Tool B",
                onCall = { Tool.Result.text("B result") }
            )

            val mockCaller = object : LlmMessageSender {
                private var called = false
                override fun call(messages: List<Message>, tools: List<Tool>): LlmMessageResponse {
                    if (!called) {
                        called = true
                        return LlmMessageResponse(
                            message = AssistantMessageWithToolCalls(
                                content = " ",
                                toolCalls = listOf(
                                    ToolCall("1", "tool_a", "{}"),
                                    ToolCall("2", "tool_b", "{}"),
                                ),
                            ),
                            textContent = "",
                        )
                    }
                    return LlmMessageResponse(
                        message = AssistantMessage("Done"),
                        textContent = "Done",
                    )
                }
            }

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                inspectors = listOf(inspector),
            )

            toolLoop.execute(
                initialMessages = listOf(UserMessage("Use both tools")),
                initialTools = listOf(tool1, tool2),
                outputParser = { it }
            )

            // Called twice: first iteration with tool calls, second for early exit
            Assertions.assertEquals(2, afterIterationCalls.size)
            // First iteration has the tool calls
            val firstIterationToolCalls = afterIterationCalls[0].toolCallsInIteration
            Assertions.assertEquals(2, firstIterationToolCalls.size)
            Assertions.assertEquals("tool_a", firstIterationToolCalls[0].name)
            Assertions.assertEquals("tool_b", firstIterationToolCalls[1].name)
            // Second iteration (early exit) has no tool calls
            Assertions.assertTrue(afterIterationCalls[1].toolCallsInIteration.isEmpty())
        }
    }
}
