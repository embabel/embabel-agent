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
import com.embabel.agent.core.Blackboard
import com.embabel.agent.core.ReplanRequestedException
import com.embabel.agent.core.Usage
import io.mockk.mockk
import io.mockk.verify
import com.embabel.agent.spi.loop.support.DefaultToolLoop
import com.embabel.chat.AssistantMessage
import com.embabel.chat.AssistantMessageWithToolCalls
import com.embabel.chat.Message
import com.embabel.chat.ToolCall
import com.embabel.chat.ToolResultMessage
import com.embabel.chat.UserMessage
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for [ToolLoop].
 * Uses mock SingleLlmCaller to simulate LLM responses including tool calls.
 */
class ToolLoopTest {

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

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
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

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
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

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
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

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
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

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
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
    inner class ReplanRequestTest {

        @Test
        fun `tool loop terminates gracefully when tool throws ReplanRequestedException`() {
            val replanningTool = MockTool(
                name = "routing_tool",
                description = "Routes to appropriate handler",
                onCall = {
                    // Tool decides a replan is needed
                    throw ReplanRequestedException(
                        reason = "User intent requires different action",
                    )
                }
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.toolCallResponse("call_1", "routing_tool", "{}"),
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Route me")),
                initialTools = listOf(replanningTool),
                outputParser = { it }
            )

            // Should terminate without error, indicating replan needed
            assertTrue(result.replanRequested, "Should indicate replan was requested")
            assertEquals("User intent requires different action", result.replanReason)
        }

        @Test
        fun `ReplanRequestedException can include blackboard updater`() {
            data class RoutingDecision(val targetAction: String, val confidence: Double)
            val routingDecision = RoutingDecision("handleSupport", 0.95)

            val replanningTool = MockTool(
                name = "intent_classifier",
                description = "Classifies user intent",
                onCall = {
                    throw ReplanRequestedException(
                        reason = "Classified as support request",
                        blackboardUpdater = { bb ->
                            bb.addObject(routingDecision)
                            bb.addObject("support")
                        }
                    )
                }
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.toolCallResponse("call_1", "intent_classifier", "{}"),
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("I need help")),
                initialTools = listOf(replanningTool),
                outputParser = { it }
            )

            assertTrue(result.replanRequested)
            // Verify the blackboardUpdater is present by invoking it
            val mockBlackboard = mockk<Blackboard>(relaxed = true)
            result.blackboardUpdater.accept(mockBlackboard)
            verify { mockBlackboard.addObject(routingDecision) }
            verify { mockBlackboard.addObject("support") }
        }

        @Test
        fun `tool loop includes conversation history when replan requested`() {
            var toolCallCount = 0
            val normalTool = MockTool(
                name = "gather_info",
                description = "Gathers information",
                onCall = {
                    toolCallCount++
                    Tool.Result.text("Info gathered")
                }
            )

            val replanningTool = MockTool(
                name = "decide",
                description = "Makes decision",
                onCall = {
                    throw ReplanRequestedException(reason = "Need different approach")
                }
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.toolCallResponse("call_1", "gather_info", "{}"),
                    MockLlmMessageSender.toolCallResponse("call_2", "decide", "{}"),
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Do something")),
                initialTools = listOf(normalTool, replanningTool),
                outputParser = { it }
            )

            assertTrue(result.replanRequested)
            assertEquals(1, toolCallCount) // First tool was called
            // Conversation should include: User, Assistant(tool call), ToolResult, Assistant(tool call)
            assertTrue(result.conversationHistory.size >= 3)
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

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
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

/**
 * Additional tests for improved coverage.
 */
class ToolLoopAdditionalTests {

    private val objectMapper = jacksonObjectMapper()

    @Nested
    inner class UsageAccumulationTest {

        @Test
        fun `execute accumulates usage across multiple iterations`() {
            val mockTool = MockTool(
                name = "test_tool",
                description = "A test tool",
                onCall = { Tool.Result.text("""{"status": "ok"}""") }
            )

            val mockCaller = MockLlmMessageSenderWithUsage(
                responses = listOf(
                    MockLlmMessageSenderWithUsage.toolCallResponseWithUsage(
                        toolCallId = "call_1",
                        toolName = "test_tool",
                        arguments = "{}",
                        promptTokens = 100,
                        completionTokens = 50,
                    ),
                    MockLlmMessageSenderWithUsage.textResponseWithUsage(
                        text = "Done!",
                        promptTokens = 150,
                        completionTokens = 30,
                    ),
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Do something")),
                initialTools = listOf(mockTool),
                outputParser = { it }
            )

            assertNotNull(result.totalUsage)
            assertEquals(250, result.totalUsage!!.promptTokens)
            assertEquals(80, result.totalUsage!!.completionTokens)
            assertEquals(330, result.totalUsage!!.totalTokens)
        }

        @Test
        fun `execute handles null usage gracefully`() {
            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.textResponse("Hello!")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Say hello")),
                initialTools = emptyList(),
                outputParser = { it }
            )

            assertNull(result.totalUsage)
        }

        @Test
        fun `execute handles first usage being null then subsequent with usage`() {
            val mockTool = MockTool(
                name = "test_tool",
                description = "A test tool",
                onCall = { Tool.Result.text("""{"status": "ok"}""") }
            )

            val mockCaller = object : LlmMessageSender {
                private var callCount = 0
                override fun call(messages: List<Message>, tools: List<Tool>): LlmMessageResponse {
                    callCount++
                    return when (callCount) {
                        1 -> LlmMessageResponse(
                            message = AssistantMessageWithToolCalls(
                                content = " ",
                                toolCalls = listOf(ToolCall("call_1", "test_tool", "{}")),
                            ),
                            textContent = "",
                            usage = null, // First call has no usage
                        )

                        else -> LlmMessageResponse(
                            message = AssistantMessage("Done!"),
                            textContent = "Done!",
                            usage = Usage(100, 50, null), // Second call has usage
                        )
                    }
                }
            }

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Do something")),
                initialTools = listOf(mockTool),
                outputParser = { it }
            )

            assertNotNull(result.totalUsage)
            assertEquals(100, result.totalUsage!!.promptTokens)
            assertEquals(50, result.totalUsage!!.completionTokens)
        }
    }

    @Nested
    inner class ToolResultTypesTest {

        @Test
        fun `execute handles Tool Result WithArtifact`() {
            val mockTool = MockTool(
                name = "artifact_tool",
                description = "Returns artifact",
                onCall = {
                    Tool.Result.WithArtifact(
                        content = """{"data": "processed"}""",
                        artifact = mapOf("key" to "value"),
                    )
                }
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.toolCallResponse("call_1", "artifact_tool", "{}"),
                    MockLlmMessageSender.textResponse("Artifact processed!")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Process artifact")),
                initialTools = listOf(mockTool),
                outputParser = { it }
            )

            assertEquals("Artifact processed!", result.result)
            // Verify tool result was added to conversation
            val toolResultMessage = result.conversationHistory.filterIsInstance<ToolResultMessage>().first()
            assertEquals("""{"data": "processed"}""", toolResultMessage.content)
        }

        @Test
        fun `execute handles Tool Result Error`() {
            val mockTool = MockTool(
                name = "error_tool",
                description = "Returns error",
                onCall = { Tool.Result.Error("Something went wrong") }
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.toolCallResponse("call_1", "error_tool", "{}"),
                    MockLlmMessageSender.textResponse("I see there was an error.")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Do something")),
                initialTools = listOf(mockTool),
                outputParser = { it }
            )

            assertEquals("I see there was an error.", result.result)
            // Verify error message was added to conversation
            val toolResultMessage = result.conversationHistory.filterIsInstance<ToolResultMessage>().first()
            assertEquals("Error: Something went wrong", toolResultMessage.content)
        }
    }

    @Nested
    inner class MultipleToolCallsTest {

        @Test
        fun `execute handles multiple tool calls in single response`() {
            val toolCallOrder = mutableListOf<String>()

            val weatherTool = MockTool(
                name = "get_weather",
                description = "Get weather",
                onCall = { args ->
                    toolCallOrder.add("weather")
                    Tool.Result.text("""{"temp": 72}""")
                }
            )

            val timeTool = MockTool(
                name = "get_time",
                description = "Get time",
                onCall = { args ->
                    toolCallOrder.add("time")
                    Tool.Result.text("""{"time": "3:00 PM"}""")
                }
            )

            val mockCaller = object : LlmMessageSender {
                private var called = false
                override fun call(messages: List<Message>, tools: List<Tool>): LlmMessageResponse {
                    if (!called) {
                        called = true
                        // Return multiple tool calls in single response
                        return LlmMessageResponse(
                            message = AssistantMessageWithToolCalls(
                                content = " ",
                                toolCalls = listOf(
                                    ToolCall("call_1", "get_weather", """{"city": "NYC"}"""),
                                    ToolCall("call_2", "get_time", """{"timezone": "EST"}"""),
                                ),
                            ),
                            textContent = "",
                        )
                    }
                    return LlmMessageResponse(
                        message = AssistantMessage("Weather is 72°F and time is 3:00 PM"),
                        textContent = "Weather is 72°F and time is 3:00 PM",
                    )
                }
            }

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("What's the weather and time?")),
                initialTools = listOf(weatherTool, timeTool),
                outputParser = { it }
            )

            assertEquals("Weather is 72°F and time is 3:00 PM", result.result)
            assertEquals(listOf("weather", "time"), toolCallOrder)
            assertEquals(2, result.totalIterations)

            // Should have 2 tool result messages
            val toolResults = result.conversationHistory.filterIsInstance<ToolResultMessage>()
            assertEquals(2, toolResults.size)
        }

        @Test
        fun `execute processes tool calls in order`() {
            val executionOrder = mutableListOf<String>()

            val tool1 = MockTool(
                name = "tool_a",
                description = "Tool A",
                onCall = {
                    executionOrder.add("A")
                    Tool.Result.text("A done")
                }
            )
            val tool2 = MockTool(
                name = "tool_b",
                description = "Tool B",
                onCall = {
                    executionOrder.add("B")
                    Tool.Result.text("B done")
                }
            )
            val tool3 = MockTool(
                name = "tool_c",
                description = "Tool C",
                onCall = {
                    executionOrder.add("C")
                    Tool.Result.text("C done")
                }
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
                                    ToolCall("3", "tool_c", "{}"),
                                ),
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

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
            )

            toolLoop.execute(
                initialMessages = listOf(UserMessage("Run all tools")),
                initialTools = listOf(tool1, tool2, tool3),
                outputParser = { it }
            )

            assertEquals(listOf("A", "B", "C"), executionOrder)
        }
    }

    @Nested
    inner class NonJsonToolResultsTest {

        @Test
        fun `execute handles plain text tool result`() {
            var contextReceived: ToolInjectionContext? = null

            val strategy = object : ToolInjectionStrategy {
                override fun evaluateToolResult(context: ToolInjectionContext): List<Tool> {
                    contextReceived = context
                    return emptyList()
                }
            }

            val mockTool = MockTool(
                name = "text_tool",
                description = "Returns plain text",
                onCall = { Tool.Result.text("This is plain text, not JSON") }
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.toolCallResponse("call_1", "text_tool", "{}"),
                    MockLlmMessageSender.textResponse("Got it!")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                injectionStrategy = strategy,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Get text")),
                initialTools = listOf(mockTool),
                outputParser = { it }
            )

            assertEquals("Got it!", result.result)
            assertNotNull(contextReceived)
            assertEquals("This is plain text, not JSON", contextReceived!!.lastToolCall.result)
            assertNull(contextReceived!!.lastToolCall.resultObject) // Not parseable as JSON
        }

        @Test
        fun `execute deserializes valid JSON tool result for strategy`() {
            var contextReceived: ToolInjectionContext? = null

            val strategy = object : ToolInjectionStrategy {
                override fun evaluateToolResult(context: ToolInjectionContext): List<Tool> {
                    contextReceived = context
                    return emptyList()
                }
            }

            val mockTool = MockTool(
                name = "json_tool",
                description = "Returns JSON",
                onCall = { Tool.Result.text("""{"status": "success", "count": 42}""") }
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.toolCallResponse("call_1", "json_tool", "{}"),
                    MockLlmMessageSender.textResponse("Got it!")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                injectionStrategy = strategy,
            )

            toolLoop.execute(
                initialMessages = listOf(UserMessage("Get JSON")),
                initialTools = listOf(mockTool),
                outputParser = { it }
            )

            assertNotNull(contextReceived)
            assertNotNull(contextReceived!!.lastToolCall.resultObject)
            val resultMap = contextReceived!!.lastToolCall.resultObject as Map<*, *>
            assertEquals("success", resultMap["status"])
            assertEquals(42, resultMap["count"])
        }
    }

    @Nested
    inner class ToolInjectionStrategyNoneTest {

        @Test
        fun `NONE strategy never injects tools`() {
            val mockTool = MockTool(
                name = "test_tool",
                description = "A test tool",
                onCall = { Tool.Result.text("""{"result": "done"}""") }
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.toolCallResponse("call_1", "test_tool", "{}"),
                    MockLlmMessageSender.textResponse("Done!")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                injectionStrategy = ToolInjectionStrategy.NONE,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Test")),
                initialTools = listOf(mockTool),
                outputParser = { it }
            )

            assertTrue(result.injectedTools.isEmpty())
        }
    }

    @Nested
    inner class ExceptionDetailsTest {

        @Test
        fun `ToolNotFoundException includes available tools in message`() {
            val tool1 = MockTool("available_tool_1", "First tool", { Tool.Result.text("{}") })
            val tool2 = MockTool("available_tool_2", "Second tool", { Tool.Result.text("{}") })

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.toolCallResponse("call_1", "nonexistent_tool", "{}")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
            )

            val exception = assertThrows<ToolNotFoundException> {
                toolLoop.execute(
                    initialMessages = listOf(UserMessage("Use a tool")),
                    initialTools = listOf(tool1, tool2),
                    outputParser = { it }
                )
            }

            assertEquals("nonexistent_tool", exception.requestedTool)
            assertEquals(listOf("available_tool_1", "available_tool_2"), exception.availableTools)
            assertTrue(exception.message!!.contains("nonexistent_tool"))
            assertTrue(exception.message!!.contains("available_tool_1"))
            assertTrue(exception.message!!.contains("available_tool_2"))
        }

        @Test
        fun `MaxIterationsExceededException includes iteration count in message`() {
            val mockTool = MockTool(
                name = "loop_tool",
                description = "A looping tool",
                onCall = { Tool.Result.text("""{"continue": true}""") }
            )

            val mockCaller = MockLlmMessageSender(
                responses = List(100) {
                    MockLlmMessageSender.toolCallResponse("call_$it", "loop_tool", "{}")
                }
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                maxIterations = 3,
            )

            val exception = assertThrows<MaxIterationsExceededException> {
                toolLoop.execute(
                    initialMessages = listOf(UserMessage("Loop forever")),
                    initialTools = listOf(mockTool),
                    outputParser = { it }
                )
            }

            assertEquals(3, exception.maxIterations)
            assertTrue(exception.message!!.contains("3"))
        }
    }

    @Nested
    inner class EmptyToolCallsTest {

        @Test
        fun `execute terminates when AssistantMessageWithToolCalls has empty toolCalls list`() {
            val mockCaller = object : LlmMessageSender {
                override fun call(messages: List<Message>, tools: List<Tool>): LlmMessageResponse {
                    // Return AssistantMessageWithToolCalls but with empty list
                    return LlmMessageResponse(
                        message = AssistantMessageWithToolCalls(
                            content = "I don't need any tools for this.",
                            toolCalls = emptyList(),
                        ),
                        textContent = "I don't need any tools for this.",
                    )
                }
            }

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Hello")),
                initialTools = emptyList(),
                outputParser = { it }
            )

            assertEquals("I don't need any tools for this.", result.result)
            assertEquals(1, result.totalIterations)
        }
    }

    @Nested
    inner class ConversationHistoryTest {

        @Test
        fun `conversation history includes all messages in correct order`() {
            val mockTool = MockTool(
                name = "test_tool",
                description = "Test tool",
                onCall = { Tool.Result.text("""{"result": "tool_output"}""") }
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.toolCallResponse("call_1", "test_tool", """{"input": "test"}"""),
                    MockLlmMessageSender.textResponse("Final answer")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(
                    com.embabel.chat.SystemMessage("You are a helpful assistant"),
                    UserMessage("Use the tool"),
                ),
                initialTools = listOf(mockTool),
                outputParser = { it }
            )

            assertEquals(5, result.conversationHistory.size)

            // Verify order: System, User, Assistant(tool call), ToolResult, Assistant(final)
            assertTrue(result.conversationHistory[0] is com.embabel.chat.SystemMessage)
            assertTrue(result.conversationHistory[1] is UserMessage)
            assertTrue(result.conversationHistory[2] is AssistantMessageWithToolCalls)
            assertTrue(result.conversationHistory[3] is ToolResultMessage)
            assertTrue(result.conversationHistory[4] is AssistantMessage)
        }

        @Test
        fun `tool result message contains correct metadata`() {
            val mockTool = MockTool(
                name = "metadata_tool",
                description = "Tool for testing metadata",
                onCall = { Tool.Result.text("""{"data": "value"}""") }
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.toolCallResponse(
                        "unique_call_id_123",
                        "metadata_tool",
                        """{"param": "value"}"""
                    ),
                    MockLlmMessageSender.textResponse("Done")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Test")),
                initialTools = listOf(mockTool),
                outputParser = { it }
            )

            val toolResultMessage = result.conversationHistory.filterIsInstance<ToolResultMessage>().first()
            assertEquals("unique_call_id_123", toolResultMessage.toolCallId)
            assertEquals("metadata_tool", toolResultMessage.toolName)
            assertEquals("""{"data": "value"}""", toolResultMessage.content)
        }
    }

    @Nested
    inner class ToolInjectionContextTest {

        @Test
        fun `injection strategy receives correct context`() {
            var capturedContext: ToolInjectionContext? = null

            val strategy = object : ToolInjectionStrategy {
                override fun evaluateToolResult(context: ToolInjectionContext): List<Tool> {
                    capturedContext = context
                    return emptyList()
                }
            }

            val mockTool = MockTool(
                name = "context_tool",
                description = "Tool for context testing",
                onCall = { Tool.Result.text("""{"status": "ok"}""") }
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.toolCallResponse("ctx_call", "context_tool", """{"arg": "value"}"""),
                    MockLlmMessageSender.textResponse("Done")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                injectionStrategy = strategy,
            )

            toolLoop.execute(
                initialMessages = listOf(UserMessage("Test context")),
                initialTools = listOf(mockTool),
                outputParser = { it }
            )

            assertNotNull(capturedContext)
            assertEquals("context_tool", capturedContext!!.lastToolCall.toolName)
            assertEquals("""{"arg": "value"}""", capturedContext!!.lastToolCall.toolInput)
            assertEquals("""{"status": "ok"}""", capturedContext!!.lastToolCall.result)
            assertEquals(1, capturedContext!!.iterationCount)
            assertEquals(1, capturedContext!!.currentTools.size)
            assertTrue(capturedContext!!.conversationHistory.isNotEmpty())
        }

        @Test
        fun `injected tools are available for subsequent calls`() {
            val injectedToolCalled = mutableListOf<Boolean>()

            val injectedTool = MockTool(
                name = "injected_tool",
                description = "Injected tool",
                onCall = {
                    injectedToolCalled.add(true)
                    Tool.Result.text("""{"injected": true}""")
                }
            )

            val strategy = object : ToolInjectionStrategy {
                private var injected = false
                override fun evaluateToolResult(context: ToolInjectionContext): List<Tool> {
                    if (!injected && context.lastToolCall.toolName == "initial_tool") {
                        injected = true
                        return listOf(injectedTool)
                    }
                    return emptyList()
                }
            }

            val initialTool = MockTool(
                name = "initial_tool",
                description = "Initial tool",
                onCall = { Tool.Result.text("""{"initial": true}""") }
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.toolCallResponse("call_1", "initial_tool", "{}"),
                    MockLlmMessageSender.toolCallResponse("call_2", "injected_tool", "{}"),
                    MockLlmMessageSender.textResponse("All done!")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                injectionStrategy = strategy,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Use both tools")),
                initialTools = listOf(initialTool),
                outputParser = { it }
            )

            assertEquals(1, injectedToolCalled.size)
            assertEquals(1, result.injectedTools.size)
            assertEquals("injected_tool", result.injectedTools[0].definition.name)
        }
    }
}

/**
 * Mock LlmMessageSender that supports usage information.
 */
internal class MockLlmMessageSenderWithUsage(
    private val responses: List<LlmMessageResponse>,
) : LlmMessageSender {

    private var callIndex = 0

    override fun call(messages: List<Message>, tools: List<Tool>): LlmMessageResponse {
        if (callIndex >= responses.size) {
            throw IllegalStateException("MockLlmMessageSenderWithUsage ran out of responses")
        }
        return responses[callIndex++]
    }

    companion object {
        fun textResponseWithUsage(text: String, promptTokens: Int, completionTokens: Int): LlmMessageResponse {
            return LlmMessageResponse(
                message = AssistantMessage(text),
                textContent = text,
                usage = Usage(promptTokens, completionTokens, null),
            )
        }

        fun toolCallResponseWithUsage(
            toolCallId: String,
            toolName: String,
            arguments: String,
            promptTokens: Int,
            completionTokens: Int,
        ): LlmMessageResponse {
            val toolCall = ToolCall(toolCallId, toolName, arguments)
            return LlmMessageResponse(
                message = AssistantMessageWithToolCalls(
                    content = " ",
                    toolCalls = listOf(toolCall),
                ),
                textContent = "",
                usage = Usage(promptTokens, completionTokens, null),
            )
        }
    }
}
