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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.DefaultToolDefinition
import org.springframework.ai.tool.metadata.DefaultToolMetadata

/**
 * Unit tests for [SpringAiToolCallbackListener] and the [withInspectors] extension.
 *
 * Verifies that:
 * - Single inspector wrapping notifies before/after tool calls
 * - Multiple inspectors are called in order with same context
 * - Duration measurement works correctly
 * - Error results are captured properly
 * - Empty inspector list returns original callbacks unchanged
 */
class SpringAiToolCallbackListenerTest {

    @Nested
    inner class SingleInspectorTests {

        @Test
        fun `listener notifies inspector before and after tool call`() {
            val events = mutableListOf<String>()
            val inspector = object : ToolCallInspector {
                override fun beforeToolCall(context: BeforeToolCallContext) {
                    events.add("before:${context.toolCall.name}")
                }

                override fun afterToolCall(context: AfterToolCallContext) {
                    events.add("after:${context.toolCall.name}:${context.durationMs}")
                }
            }

            val delegate = createMockCallback(name = "test_tool", result = "success")
            val listener = SpringAiToolCallbackListener(delegate, inspector)

            listener.call("{}")

            assertEquals(2, events.size)
            assertEquals("before:test_tool", events[0])
            assertTrue(events[1].startsWith("after:test_tool:"))
        }

        @Test
        fun `listener measures execution duration`() {
            var recordedDuration: Long = 0
            val inspector = object : ToolCallInspector {
                override fun afterToolCall(context: AfterToolCallContext) {
                    recordedDuration = context.durationMs
                }
            }

            val delegate = createMockCallback(name = "slow_tool", delayMs = 50)
            val listener = SpringAiToolCallbackListener(delegate, inspector)

            listener.call("{}")

            assertTrue(recordedDuration >= 50, "Duration should be at least 50ms, was $recordedDuration")
        }

        @Test
        fun `listener captures error result when delegate throws`() {
            var capturedResult: Tool.Result? = null
            val inspector = object : ToolCallInspector {
                override fun afterToolCall(context: AfterToolCallContext) {
                    capturedResult = context.result
                }
            }

            val delegate = createMockCallback(name = "failing_tool", throwOnCall = RuntimeException("boom"))
            val listener = SpringAiToolCallbackListener(delegate, inspector)

            try {
                listener.call("{}")
            } catch (e: RuntimeException) {
                // Expected
            }

            assertTrue(capturedResult is Tool.Result.Error)
            assertEquals("boom", (capturedResult as Tool.Result.Error).message)
        }

        @Test
        fun `listener delegates toolDefinition to underlying callback`() {
            val inspector = object : ToolCallInspector {}
            val delegate = createMockCallback(name = "test_tool", description = "Test description")
            val listener = SpringAiToolCallbackListener(delegate, inspector)

            assertEquals("test_tool", listener.toolDefinition.name())
            assertEquals("Test description", listener.toolDefinition.description())
        }

        @Test
        fun `listener delegates toolMetadata to underlying callback`() {
            val inspector = object : ToolCallInspector {}
            val delegate = createMockCallback(name = "test_tool", returnDirect = true)
            val listener = SpringAiToolCallbackListener(delegate, inspector)

            assertTrue(listener.toolMetadata.returnDirect())
        }

        @Test
        fun `listener isolates inspector exception in beforeToolCall`() {
            val inspector = object : ToolCallInspector {
                override fun beforeToolCall(context: BeforeToolCallContext) {
                    throw RuntimeException("Inspector failure")
                }
            }
            val delegate = createMockCallback(name = "test_tool", result = "success")
            val listener = SpringAiToolCallbackListener(delegate, inspector)

            // Tool should still execute despite inspector exception
            val result = listener.call("{}")
            assertEquals("success", result)
        }

        @Test
        fun `listener isolates inspector exception in afterToolCall`() {
            var afterToolCallCalled = false
            val inspector = object : ToolCallInspector {
                override fun afterToolCall(context: AfterToolCallContext) {
                    afterToolCallCalled = true
                    throw RuntimeException("Inspector failure")
                }
            }
            val delegate = createMockCallback(name = "test_tool", result = "success")
            val listener = SpringAiToolCallbackListener(delegate, inspector)

            // Tool should return result despite inspector exception in afterToolCall
            val result = listener.call("{}")
            assertEquals("success", result)
            assertTrue(afterToolCallCalled, "afterToolCall should have been called")
        }
    }

    @Nested
    inner class MultipleInspectorsTests {

        @Test
        fun `withInspectors returns original list when inspectors list is empty`() {
            val callbacks = listOf(
                createMockCallback(name = "tool1"),
                createMockCallback(name = "tool2"),
            )

            val result = callbacks.withInspectors(emptyList())

            assertEquals(callbacks, result)
        }

        @Test
        fun `withInspectors wraps all callbacks with composite inspector`() {
            val callbacks = listOf(
                createMockCallback(name = "tool1"),
                createMockCallback(name = "tool2"),
            )

            val inspector = object : ToolCallInspector {}
            val result = callbacks.withInspectors(listOf(inspector))

            assertEquals(2, result.size)
            assertTrue(result[0] is SpringAiToolCallbackListener)
            assertTrue(result[1] is SpringAiToolCallbackListener)
            assertEquals("tool1", result[0].toolDefinition.name())
            assertEquals("tool2", result[1].toolDefinition.name())
        }

        @Test
        fun `withInspectors notifies all inspectors in order`() {
            val events = mutableListOf<String>()
            val inspector1 = object : ToolCallInspector {
                override fun beforeToolCall(context: BeforeToolCallContext) {
                    events.add("inspector1:before")
                }

                override fun afterToolCall(context: AfterToolCallContext) {
                    events.add("inspector1:after")
                }
            }

            val inspector2 = object : ToolCallInspector {
                override fun beforeToolCall(context: BeforeToolCallContext) {
                    events.add("inspector2:before")
                }

                override fun afterToolCall(context: AfterToolCallContext) {
                    events.add("inspector2:after")
                }
            }

            val callbacks = listOf(createMockCallback(name = "tool1"))
            val wrapped = callbacks.withInspectors(listOf(inspector1, inspector2))

            wrapped[0].call("{}")

            assertEquals(4, events.size)
            assertEquals("inspector1:before", events[0])
            assertEquals("inspector2:before", events[1])
            assertEquals("inspector1:after", events[2])
            assertEquals("inspector2:after", events[3])
        }

        @Test
        fun `withInspectors passes same context to all inspectors`() {
            val beforeContexts = mutableListOf<BeforeToolCallContext>()
            val afterContexts = mutableListOf<AfterToolCallContext>()

            val inspector1 = object : ToolCallInspector {
                override fun beforeToolCall(context: BeforeToolCallContext) {
                    beforeContexts.add(context)
                }

                override fun afterToolCall(context: AfterToolCallContext) {
                    afterContexts.add(context)
                }
            }

            val inspector2 = object : ToolCallInspector {
                override fun beforeToolCall(context: BeforeToolCallContext) {
                    beforeContexts.add(context)
                }

                override fun afterToolCall(context: AfterToolCallContext) {
                    afterContexts.add(context)
                }
            }

            val callbacks = listOf(createMockCallback(name = "tool1"))
            val wrapped = callbacks.withInspectors(listOf(inspector1, inspector2))

            wrapped[0].call("{}")

            assertEquals(2, beforeContexts.size)
            assertEquals(2, afterContexts.size)
            assertEquals(beforeContexts[0].toolCall, beforeContexts[1].toolCall)
            assertEquals(afterContexts[0].toolCall, afterContexts[1].toolCall)
            assertEquals(afterContexts[0].result, afterContexts[1].result)
        }

        @Test
        fun `withInspectors isolates inspector exceptions and continues execution`() {
            val events = mutableListOf<String>()
            val inspector1 = object : ToolCallInspector {
                override fun beforeToolCall(context: BeforeToolCallContext) {
                    events.add("inspector1:before")
                    throw RuntimeException("Inspector1 failure")
                }

                override fun afterToolCall(context: AfterToolCallContext) {
                    events.add("inspector1:after")
                }
            }

            val inspector2 = object : ToolCallInspector {
                override fun beforeToolCall(context: BeforeToolCallContext) {
                    events.add("inspector2:before")
                }

                override fun afterToolCall(context: AfterToolCallContext) {
                    events.add("inspector2:after")
                }
            }

            val callbacks = listOf(createMockCallback(name = "tool1", result = "success"))
            val wrapped = callbacks.withInspectors(listOf(inspector1, inspector2))

            // Tool execution should succeed despite inspector1 throwing
            val result = wrapped[0].call("{}")
            assertEquals("success", result)

            // Inspector2 should still run even though inspector1 threw
            assertTrue(events.contains("inspector1:before"), "Inspector1 beforeToolCall should run")
            assertTrue(events.contains("inspector2:before"), "Inspector2 beforeToolCall should still run after inspector1 exception")
            assertTrue(events.contains("inspector1:after"), "Inspector1 afterToolCall should run")
            assertTrue(events.contains("inspector2:after"), "Inspector2 afterToolCall should run")
        }

        @Test
        fun `withInspectors handles exceptions in afterToolCall without breaking tool execution`() {
            val events = mutableListOf<String>()
            val inspector1 = object : ToolCallInspector {
                override fun afterToolCall(context: AfterToolCallContext) {
                    events.add("inspector1:after")
                    throw RuntimeException("Inspector1 afterToolCall failure")
                }
            }

            val inspector2 = object : ToolCallInspector {
                override fun afterToolCall(context: AfterToolCallContext) {
                    events.add("inspector2:after")
                }
            }

            val callbacks = listOf(createMockCallback(name = "tool1", result = "success"))
            val wrapped = callbacks.withInspectors(listOf(inspector1, inspector2))

            // Tool execution should succeed and return result despite afterToolCall exception
            val result = wrapped[0].call("{}")
            assertEquals("success", result)

            // Both inspectors should run
            assertTrue(events.contains("inspector1:after"), "Inspector1 afterToolCall should run")
            assertTrue(events.contains("inspector2:after"), "Inspector2 afterToolCall should still run after inspector1 exception")
        }
    }

    private fun createMockCallback(
        name: String,
        description: String = "",
        result: String = "ok",
        returnDirect: Boolean = false,
        delayMs: Long = 0,
        throwOnCall: Exception? = null,
    ): ToolCallback {
        return object : ToolCallback {
            override fun getToolDefinition() = DefaultToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema("{}")
                .build()

            override fun getToolMetadata() = DefaultToolMetadata.builder()
                .returnDirect(returnDirect)
                .build()

            override fun call(toolInput: String): String {
                if (delayMs > 0) {
                    Thread.sleep(delayMs)
                }
                if (throwOnCall != null) throw throwOnCall
                return result
            }
        }
    }
}
