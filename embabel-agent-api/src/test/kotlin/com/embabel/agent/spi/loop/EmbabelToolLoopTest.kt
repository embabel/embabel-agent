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
package com.embabel.agent.spi.loop

import com.embabel.agent.api.tool.Tool
import com.embabel.chat.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for [EmbabelToolLoop].
 * Uses mock SingleLlmCaller to simulate LLM responses including tool calls.
 */
class EmbabelToolLoopTest {

    private val objectMapper = jacksonObjectMapper()

    @Nested
    inner class BasicExecutionTest {

        @Test
        fun `execute returns result when LLM responds without tool calls`() {
            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.textResponse("Hello, world!")
                )
            )

            val toolLoop = DefaultEmbabelToolLoop(
                llmCaller = mockCaller,
                objectMapper = objectMapper,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Say hello")),
                initialTools = emptyList(),
                outputParser = { it }
            )

            assertEquals("Hello, world!", result.result)
            assertEquals(1, result.totalIterations)
            assertEquals(2, result.conversationHistory.size) // User + Assistant
            assertTrue(result.injectedTools.isEmpty())
        }

        @Test
        fun `execute parses output using provided parser`() {
            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.textResponse("42")
                )
            )

            val toolLoop = DefaultEmbabelToolLoop(
                llmCaller = mockCaller,
                objectMapper = objectMapper,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("What is 6 times 7?")),
                initialTools = emptyList(),
                outputParser = { it.toInt() }
            )

            assertEquals(42, result.result)
        }
    }

    @Nested
    inner class ToolExecutionTest {

        @Test
        fun `execute calls tool when LLM requests it`() {
            val toolCalled = mutableListOf<String>()

            val mockTool = MockTool(
                name = "get_weather",
                description = "Get the weather",
                onCall = { args ->
                    toolCalled.add(args)
                    Tool.Result.text("""{"temperature": 72, "condition": "sunny"}""")
                }
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    // First response: LLM calls the tool
                    MockLlmMessageSender.toolCallResponse(
                        toolCallId = "call_123",
                        toolName = "get_weather",
                        arguments = """{"location": "San Francisco"}"""
                    ),
                    // Second response: LLM provides final answer
                    MockLlmMessageSender.textResponse("The weather in San Francisco is 72°F and sunny.")
                )
            )

            val toolLoop = DefaultEmbabelToolLoop(
                llmCaller = mockCaller,
                objectMapper = objectMapper,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("What's the weather in San Francisco?")),
                initialTools = listOf(mockTool),
                outputParser = { it }
            )

            assertEquals("The weather in San Francisco is 72°F and sunny.", result.result)
            assertEquals(2, result.totalIterations)
            assertEquals(1, toolCalled.size)
            assertEquals("""{"location": "San Francisco"}""", toolCalled[0])
        }

        @Test
        fun `execute throws ToolNotFoundException for unknown tool`() {
            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.toolCallResponse(
                        toolCallId = "call_123",
                        toolName = "unknown_tool",
                        arguments = "{}"
                    )
                )
            )

            val toolLoop = DefaultEmbabelToolLoop(
                llmCaller = mockCaller,
                objectMapper = objectMapper,
            )

            val exception = assertThrows<ToolNotFoundException> {
                toolLoop.execute(
                    initialMessages = listOf(UserMessage("Do something")),
                    initialTools = emptyList(),
                    outputParser = { it }
                )
            }

            assertEquals("unknown_tool", exception.requestedTool)
            assertTrue(exception.availableTools.isEmpty())
        }
    }

    @Nested
    inner class ToolInjectionTest {

        @Test
        fun `execute injects tools via strategy after tool call`() {
            var strategyEvaluated = false
            val injectedTool = MockTool(
                name = "bonus_tool",
                description = "A bonus tool",
                onCall = { Tool.Result.text("""{"status": "ok"}""") }
            )

            val strategy = object : ToolInjectionStrategy {
                override fun evaluateToolResult(context: ToolInjectionContext): List<Tool> {
                    strategyEvaluated = true
                    // Inject a new tool after the first tool call
                    return if (context.lastToolCall.toolName == "initial_tool") {
                        listOf(injectedTool)
                    } else {
                        emptyList()
                    }
                }
            }

            val initialTool = MockTool(
                name = "initial_tool",
                description = "Initial tool",
                onCall = { Tool.Result.text("""{"result": "done"}""") }
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.toolCallResponse("call_1", "initial_tool", "{}"),
                    MockLlmMessageSender.textResponse("All done!")
                )
            )

            val toolLoop = DefaultEmbabelToolLoop(
                llmCaller = mockCaller,
                objectMapper = objectMapper,
                injectionStrategy = strategy,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Do something")),
                initialTools = listOf(initialTool),
                outputParser = { it }
            )

            assertTrue(strategyEvaluated)
            assertEquals(1, result.injectedTools.size)
            assertEquals("bonus_tool", result.injectedTools[0].definition.name)
        }
    }

    @Nested
    inner class MaxIterationsTest {

        @Test
        fun `execute throws MaxIterationsExceededException when limit reached`() {
            // Model always wants to call a tool, never completes
            val mockTool = MockTool(
                name = "loop_tool",
                description = "A looping tool",
                onCall = { Tool.Result.text("""{"status": "continue"}""") }
            )

            val mockCaller = MockLlmMessageSender(
                responses = List(10) {
                    MockLlmMessageSender.toolCallResponse("call_$it", "loop_tool", "{}")
                }
            )

            val toolLoop = DefaultEmbabelToolLoop(
                llmCaller = mockCaller,
                objectMapper = objectMapper,
                maxIterations = 5,
            )

            val exception = assertThrows<MaxIterationsExceededException> {
                toolLoop.execute(
                    initialMessages = listOf(UserMessage("Keep looping")),
                    initialTools = listOf(mockTool),
                    outputParser = { it }
                )
            }

            assertEquals(5, exception.maxIterations)
        }
    }
}

/**
 * Mock SingleLlmCaller for testing that returns predetermined responses.
 */
internal class MockLlmMessageSender(
    private val responses: List<LlmMessageResponse>,
) : LlmMessageSender {

    private var callIndex = 0

    override fun call(messages: List<Message>, tools: List<Tool>): LlmMessageResponse {
        if (callIndex >= responses.size) {
            throw IllegalStateException("MockSingleLlmCaller ran out of responses")
        }
        return responses[callIndex++]
    }

    companion object {
        fun textResponse(text: String): LlmMessageResponse {
            return LlmMessageResponse(
                message = AssistantMessage(text),
                textContent = text,
            )
        }

        fun toolCallResponse(toolCallId: String, toolName: String, arguments: String): LlmMessageResponse {
            val toolCall = ToolCall(toolCallId, toolName, arguments)
            return LlmMessageResponse(
                message = AssistantMessageWithToolCalls(
                    content = " ", // Space required - TextPart doesn't allow empty
                    toolCalls = listOf(toolCall),
                ),
                textContent = "",
            )
        }
    }
}

/**
 * Mock Tool for testing using Embabel's Tool interface.
 */
class MockTool(
    private val name: String,
    private val description: String,
    private val onCall: (String) -> Tool.Result,
) : Tool {

    override val definition: Tool.Definition = Tool.Definition(
        name = name,
        description = description,
        inputSchema = Tool.InputSchema.empty(),
    )

    override fun call(input: String): Tool.Result = onCall(input)
}
