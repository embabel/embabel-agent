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
import com.embabel.agent.api.tool.callback.AfterIterationContext
import com.embabel.agent.api.tool.callback.AfterLlmCallContext
import com.embabel.agent.api.tool.callback.AfterToolCallContext
import com.embabel.agent.api.tool.callback.AfterToolResultContext
import com.embabel.agent.api.tool.callback.BeforeLlmCallContext
import com.embabel.agent.api.tool.callback.BeforeToolCallContext
import com.embabel.agent.api.tool.callback.BeforeToolExecutionContext
import com.embabel.agent.api.tool.callback.ToolCallDisposition
import com.embabel.agent.api.tool.callback.ToolLoopTransformer
import com.embabel.agent.api.tool.callback.ToolCallInspector
import com.embabel.agent.api.tool.callback.ToolLoopInspector
import com.embabel.agent.core.Usage
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Message
import com.embabel.chat.ToolCall
import com.embabel.chat.UserMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for exception isolation in ToolLoopCallbackSupport extension functions.
 *
 * Verifies that:
 * - Inspector exceptions don't break tool loop execution
 * - When one inspector throws, other inspectors still run
 * - All inspector methods have proper exception handling
 */
class ToolLoopCallbackSupportTest {

    @Nested
    inner class ToolLoopInspectorTests {

        @Test
        fun `notifyBeforeLlmCall isolates inspector exceptions`() {
            val events = mutableListOf<String>()
            val inspector1 = object : ToolLoopInspector {
                override fun beforeLlmCall(context: BeforeLlmCallContext) {
                    events.add("inspector1:beforeLlmCall")
                    throw RuntimeException("Inspector1 failure")
                }
            }
            val inspector2 = object : ToolLoopInspector {
                override fun beforeLlmCall(context: BeforeLlmCallContext) {
                    events.add("inspector2:beforeLlmCall")
                }
            }

            val inspectors = listOf(inspector1, inspector2)
            val context = createBeforeLlmCallContext(
                history = listOf(UserMessage("test")),
                iteration = 1,
                tools = emptyList(),
            )

            inspectors.notifyBeforeLlmCall(context)

            assertEquals(2, events.size)
            assertTrue(events.contains("inspector1:beforeLlmCall"))
            assertTrue(events.contains("inspector2:beforeLlmCall"))
        }

        @Test
        fun `notifyAfterLlmCall isolates inspector exceptions`() {
            val events = mutableListOf<String>()
            val inspector1 = object : ToolLoopInspector {
                override fun afterLlmCall(context: AfterLlmCallContext) {
                    events.add("inspector1:afterLlmCall")
                    throw RuntimeException("Inspector1 failure")
                }
            }
            val inspector2 = object : ToolLoopInspector {
                override fun afterLlmCall(context: AfterLlmCallContext) {
                    events.add("inspector2:afterLlmCall")
                }
            }

            val inspectors = listOf(inspector1, inspector2)
            val context = createAfterLlmCallContext(
                history = listOf(UserMessage("test")),
                iteration = 1,
                response = AssistantMessage("response"),
                usage = Usage(10, 20, null),
            )

            inspectors.notifyAfterLlmCall(context)

            assertEquals(2, events.size)
            assertTrue(events.contains("inspector1:afterLlmCall"))
            assertTrue(events.contains("inspector2:afterLlmCall"))
        }

        @Test
        fun `notifyAfterToolResult isolates inspector exceptions`() {
            val events = mutableListOf<String>()
            val inspector1 = object : ToolLoopInspector {
                override fun afterToolResult(context: AfterToolResultContext) {
                    events.add("inspector1:afterToolResult")
                    throw RuntimeException("Inspector1 failure")
                }
            }
            val inspector2 = object : ToolLoopInspector {
                override fun afterToolResult(context: AfterToolResultContext) {
                    events.add("inspector2:afterToolResult")
                }
            }

            val inspectors = listOf(inspector1, inspector2)
            val context = createAfterToolResultContext(
                history = listOf(UserMessage("test")),
                iteration = 1,
                toolCall = ToolCall(id = "1", name = "test_tool", arguments = "{}"),
                result = Tool.Result.text("result"),
                resultAsString = "result",
            )

            inspectors.notifyAfterToolResult(context)

            assertEquals(2, events.size)
            assertTrue(events.contains("inspector1:afterToolResult"))
            assertTrue(events.contains("inspector2:afterToolResult"))
        }

        @Test
        fun `notifyAfterIteration isolates inspector exceptions`() {
            val events = mutableListOf<String>()
            val inspector1 = object : ToolLoopInspector {
                override fun afterIteration(context: AfterIterationContext) {
                    events.add("inspector1:afterIteration")
                    throw RuntimeException("Inspector1 failure")
                }
            }
            val inspector2 = object : ToolLoopInspector {
                override fun afterIteration(context: AfterIterationContext) {
                    events.add("inspector2:afterIteration")
                }
            }

            val inspectors = listOf(inspector1, inspector2)
            val context = createAfterIterationContext(
                history = listOf(UserMessage("test")),
                iteration = 1,
                toolCallsInIteration = emptyList(),
            )

            inspectors.notifyAfterIteration(context)

            assertEquals(2, events.size)
            assertTrue(events.contains("inspector1:afterIteration"))
            assertTrue(events.contains("inspector2:afterIteration"))
        }
    }

    @Nested
    inner class ToolCallInspectorTests {

        @Test
        fun `notifyBeforeToolCall isolates inspector exceptions`() {
            val events = mutableListOf<String>()
            val inspector1 = object : ToolCallInspector {
                override fun beforeToolCall(context: BeforeToolCallContext) {
                    events.add("inspector1:beforeToolCall")
                    throw RuntimeException("Inspector1 failure")
                }
            }
            val inspector2 = object : ToolCallInspector {
                override fun beforeToolCall(context: BeforeToolCallContext) {
                    events.add("inspector2:beforeToolCall")
                }
            }

            val inspectors = listOf(inspector1, inspector2)
            val context = BeforeToolCallContext(
                toolCall = ToolCall(id = "1", name = "test_tool", arguments = "{}"),
            )

            inspectors.notifyBeforeToolCall(context)

            assertEquals(2, events.size)
            assertTrue(events.contains("inspector1:beforeToolCall"))
            assertTrue(events.contains("inspector2:beforeToolCall"))
        }

        @Test
        fun `notifyAfterToolCall isolates inspector exceptions`() {
            val events = mutableListOf<String>()
            val inspector1 = object : ToolCallInspector {
                override fun afterToolCall(context: AfterToolCallContext) {
                    events.add("inspector1:afterToolCall")
                    throw RuntimeException("Inspector1 failure")
                }
            }
            val inspector2 = object : ToolCallInspector {
                override fun afterToolCall(context: AfterToolCallContext) {
                    events.add("inspector2:afterToolCall")
                }
            }

            val inspectors = listOf(inspector1, inspector2)
            val context = AfterToolCallContext(
                toolCall = ToolCall(id = "1", name = "test_tool", arguments = "{}"),
                result = Tool.Result.text("result"),
                resultAsString = "result",
                durationMs = 100,
            )

            inspectors.notifyAfterToolCall(context)

            assertEquals(2, events.size)
            assertTrue(events.contains("inspector1:afterToolCall"))
            assertTrue(events.contains("inspector2:afterToolCall"))
        }
    }

    @Nested
    inner class BeforeToolCallTransformTests {

        private val tool = Tool.create("echo", "Echoes") { Tool.Result.text("echo") }

        private fun context(toolCall: ToolCall) = createBeforeToolExecutionContext(
            history = listOf(UserMessage("test")),
            iteration = 1,
            toolCall = toolCall,
            tool = tool,
        )

        @Test
        fun `no transformers proceed with the original call`() {
            val call = ToolCall("1", "echo", """{"a":1}""")

            val disposition = emptyList<ToolLoopTransformer>().applyBeforeToolCall(context(call))

            assertEquals(ToolCallDisposition.Proceed(call), disposition)
        }

        @Test
        fun `default transformer proceeds unchanged`() {
            val call = ToolCall("1", "echo", """{"a":1}""")
            val transformer = object : ToolLoopTransformer {}

            val disposition = listOf(transformer).applyBeforeToolCall(context(call))

            assertEquals(ToolCallDisposition.Proceed(call), disposition)
        }

        @Test
        fun `a rewritten call is passed to the next transformer and returned`() {
            val call = ToolCall("1", "echo", """{"a":1}""")
            val seenBySecond = mutableListOf<ToolCall>()
            val first = object : ToolLoopTransformer {
                override fun transformBeforeToolCall(context: BeforeToolExecutionContext) =
                    ToolCallDisposition.Proceed(context.toolCall.copy(arguments = """{"a":2}"""))
            }
            val second = object : ToolLoopTransformer {
                override fun transformBeforeToolCall(context: BeforeToolExecutionContext): ToolCallDisposition {
                    seenBySecond.add(context.toolCall)
                    return ToolCallDisposition.Proceed(context.toolCall)
                }
            }

            val disposition = listOf(first, second).applyBeforeToolCall(context(call))

            assertEquals(listOf(call.copy(arguments = """{"a":2}""")), seenBySecond)
            assertEquals(ToolCallDisposition.Proceed(call.copy(arguments = """{"a":2}""")), disposition)
        }

        @Test
        fun `the first short circuit ends the chain`() {
            val call = ToolCall("1", "echo", "{}")
            var secondCalled = false
            val first = object : ToolLoopTransformer {
                override fun transformBeforeToolCall(context: BeforeToolExecutionContext) =
                    ToolCallDisposition.ShortCircuit(Tool.Result.text("blocked"))
            }
            val second = object : ToolLoopTransformer {
                override fun transformBeforeToolCall(context: BeforeToolExecutionContext): ToolCallDisposition {
                    secondCalled = true
                    return ToolCallDisposition.Proceed(context.toolCall)
                }
            }

            val disposition = listOf(first, second).applyBeforeToolCall(context(call))

            assertEquals(ToolCallDisposition.ShortCircuit(Tool.Result.text("blocked")), disposition)
            assertTrue(!secondCalled, "second transformer must not run after a short circuit")
        }

        @Test
        fun `a transformer exception propagates`() {
            val call = ToolCall("1", "echo", "{}")
            val failing = object : ToolLoopTransformer {
                override fun transformBeforeToolCall(context: BeforeToolExecutionContext): ToolCallDisposition =
                    throw IllegalStateException("gate failed")
            }

            val e = assertThrows<IllegalStateException> { listOf(failing).applyBeforeToolCall(context(call)) }
            assertEquals("gate failed", e.message)
        }
    }
}
