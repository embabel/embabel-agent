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

import com.embabel.agent.api.common.TerminationScope
import com.embabel.agent.api.common.TerminationSignal
import com.embabel.agent.api.tool.TerminateActionException
import com.embabel.agent.api.tool.TerminateAgentException
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.support.AbstractAgentProcess
import com.embabel.agent.spi.loop.support.DefaultToolLoop
import com.embabel.chat.UserMessage
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for termination signal handling in tool loop.
 *
 * The tool loop checks for termination signals between tool calls.
 * Only ACTION scope signals are handled by the tool loop.
 * AGENT scope signals are ignored here - they are handled at the agent process level.
 */
class ToolLoopTerminationTest {

    private val objectMapper = jacksonObjectMapper()

    @AfterEach
    fun cleanup() {
        AgentProcess.remove()
    }

    @Nested
    inner class ActionTerminationSignal {

        /**
         * Verifies that ACTION scope signal stops the tool loop immediately.
         *
         * When a tool sets TerminationScope.ACTION signal, the tool loop should:
         * - Stop processing remaining tool calls
         * - Throw TerminateActionException
         * - Allow the agent to continue with the next action
         */
        @Test
        fun `ACTION scope signal throws TerminateActionException`() {
            val toolCallOrder = mutableListOf<String>()
            val mockProcess = setupMockAgentProcess()

            val tool1 = MockTool(
                name = "tool_one",
                description = "First tool",
                onCall = {
                    toolCallOrder.add("tool_one")
                    // Set ACTION termination signal - tool loop should stop after this
                    mockProcess.terminateAction("Stop this action")
                    Tool.Result.text("Tool one done")
                }
            )

            val tool2 = MockTool(
                name = "tool_two",
                description = "Second tool - should NOT be called",
                onCall = {
                    toolCallOrder.add("tool_two")
                    Tool.Result.text("Tool two done")
                }
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    // First LLM response: call tool_one
                    MockLlmMessageSender.toolCallResponse("call_1", "tool_one", "{}"),
                    // Second LLM response: call tool_two (should not be reached)
                    MockLlmMessageSender.toolCallResponse("call_2", "tool_two", "{}"),
                    MockLlmMessageSender.textResponse("Done")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
            )

            val exception = assertThrows<TerminateActionException> {
                toolLoop.execute(
                    initialMessages = listOf(UserMessage("Do two things")),
                    initialTools = listOf(tool1, tool2),
                    outputParser = { it }
                )
            }

            assertEquals("Stop this action", exception.reason)
            // Only tool_one should have been called; tool_two skipped due to termination
            assertEquals(1, toolCallOrder.size)
            assertEquals("tool_one", toolCallOrder[0])
        }

        /**
         * Verifies that AGENT scope signal allows the tool loop to complete normally.
         *
         * When a tool sets TerminationScope.AGENT signal, the tool loop should:
         * - Continue processing all tool calls normally
         * - Complete the current action
         * - The signal is handled later by AbstractAgentProcess before the next tick
         */
        @Test
        fun `tool loop completes when AGENT signal is set`() {
            val toolCallOrder = mutableListOf<String>()
            val mockProcess = setupMockAgentProcess()

            val tool1 = MockTool(
                name = "tool_one",
                description = "First tool",
                onCall = {
                    toolCallOrder.add("tool_one")
                    // Set AGENT termination signal - tool loop should NOT stop
                    mockProcess.terminateAgent("Stop the agent")
                    Tool.Result.text("Tool one done")
                }
            )

            val tool2 = MockTool(
                name = "tool_two",
                description = "Second tool - SHOULD be called",
                onCall = {
                    toolCallOrder.add("tool_two")
                    Tool.Result.text("Tool two done")
                }
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    // First LLM response: call tool_one
                    MockLlmMessageSender.toolCallResponse("call_1", "tool_one", "{}"),
                    // Second LLM response: call tool_two (should be reached - AGENT signal ignored)
                    MockLlmMessageSender.toolCallResponse("call_2", "tool_two", "{}"),
                    MockLlmMessageSender.textResponse("Done")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Do two things")),
                initialTools = listOf(tool1, tool2),
                outputParser = { it }
            )

            assertEquals("Done", result.result)
            // Both tools should have been called - AGENT signal doesn't stop tool loop
            assertEquals(2, toolCallOrder.size)
            assertTrue(toolCallOrder.contains("tool_one"))
            assertTrue(toolCallOrder.contains("tool_two"))
        }

        /**
         * Verifies tool loop works when no AgentProcess context is available.
         *
         * This can happen in unit tests or edge cases.
         * The tool loop should gracefully handle null AgentProcess.
         */
        @Test
        fun `tool loop continues when no AgentProcess context`() {
            val toolCalled = mutableListOf<String>()

            val tool = MockTool(
                name = "test_tool",
                description = "Test tool",
                onCall = {
                    toolCalled.add("test_tool")
                    Tool.Result.text("Done")
                }
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.toolCallResponse("call_1", "test_tool", "{}"),
                    MockLlmMessageSender.textResponse("Complete")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
            )

            // No AgentProcess.set() - context is null

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Do something")),
                initialTools = listOf(tool),
                outputParser = { it }
            )

            assertEquals("Complete", result.result)
            assertEquals(1, toolCalled.size)
        }
    }

    @Nested
    inner class ImmediateTermination {

        /**
         * Verifies that TerminateActionException from a tool propagates immediately.
         * Remaining tools in the sequence should NOT be called.
         */
        @Test
        fun `TerminateActionException propagates and stops tool loop`() {
            val toolCallOrder = mutableListOf<String>()

            val tool1 = MockTool(
                name = "tool_one",
                description = "First tool - throws TerminateActionException",
                onCall = {
                    toolCallOrder.add("tool_one")
                    throw TerminateActionException("Action terminated by tool")
                }
            )

            val tool2 = MockTool(
                name = "tool_two",
                description = "Second tool - should NOT be called",
                onCall = {
                    toolCallOrder.add("tool_two")
                    Tool.Result.text("Tool two done")
                }
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.toolCallResponse("call_1", "tool_one", "{}"),
                    MockLlmMessageSender.toolCallResponse("call_2", "tool_two", "{}"),
                    MockLlmMessageSender.textResponse("Done")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
            )

            val exception = assertThrows<TerminateActionException> {
                toolLoop.execute(
                    initialMessages = listOf(UserMessage("Do two things")),
                    initialTools = listOf(tool1, tool2),
                    outputParser = { it }
                )
            }

            assertEquals("Action terminated by tool", exception.reason)
            assertEquals(1, toolCallOrder.size)
            assertEquals("tool_one", toolCallOrder[0])
        }

        /**
         * Verifies that TerminateAgentException from a tool propagates immediately.
         * Remaining tools in the sequence should NOT be called.
         */
        @Test
        fun `TerminateAgentException propagates and stops tool loop`() {
            val toolCallOrder = mutableListOf<String>()

            val tool1 = MockTool(
                name = "tool_one",
                description = "First tool - throws TerminateAgentException",
                onCall = {
                    toolCallOrder.add("tool_one")
                    throw TerminateAgentException("Agent terminated by tool")
                }
            )

            val tool2 = MockTool(
                name = "tool_two",
                description = "Second tool - should NOT be called",
                onCall = {
                    toolCallOrder.add("tool_two")
                    Tool.Result.text("Tool two done")
                }
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.toolCallResponse("call_1", "tool_one", "{}"),
                    MockLlmMessageSender.toolCallResponse("call_2", "tool_two", "{}"),
                    MockLlmMessageSender.textResponse("Done")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
            )

            val exception = assertThrows<TerminateAgentException> {
                toolLoop.execute(
                    initialMessages = listOf(UserMessage("Do two things")),
                    initialTools = listOf(tool1, tool2),
                    outputParser = { it }
                )
            }

            assertEquals("Agent terminated by tool", exception.reason)
            assertEquals(1, toolCallOrder.size)
            assertEquals("tool_one", toolCallOrder[0])
        }
    }

    /**
     * Sets up a mock AbstractAgentProcess with termination request support.
     */
    private fun setupMockAgentProcess(): AbstractAgentProcess {
        var terminationRequest: TerminationSignal? = null

        val mockProcess = mockk<AbstractAgentProcess>(relaxed = true)

        every { mockProcess.terminationRequest } answers { terminationRequest }
        every { mockProcess.terminateAgent(any()) } answers {
            terminationRequest = TerminationSignal(TerminationScope.AGENT, firstArg())
        }
        every { mockProcess.terminateAction(any()) } answers {
            terminationRequest = TerminationSignal(TerminationScope.ACTION, firstArg())
        }
        every { mockProcess.resetTerminationRequest() } answers { terminationRequest = null }

        AgentProcess.set(mockProcess)
        return mockProcess
    }
}
