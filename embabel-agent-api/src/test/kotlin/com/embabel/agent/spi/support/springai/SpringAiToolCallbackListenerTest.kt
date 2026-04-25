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
        fun `withInspectors handles inspector exceptions gracefully`() {
            val inspector1 = object : ToolCallInspector {
                override fun beforeToolCall(context: BeforeToolCallContext) {
                    throw RuntimeException("Inspector1 failure")
                }
            }

            val inspector2 = object : ToolCallInspector {
                override fun beforeToolCall(context: BeforeToolCallContext) {
                    // Should not be reached due to inspector1 throwing
                }
            }

            val callbacks = listOf(createMockCallback(name = "tool1"))
            val wrapped = callbacks.withInspectors(listOf(inspector1, inspector2))

            // Inspector exception should propagate
            try {
                wrapped[0].call("{}")
                throw AssertionError("Expected exception from inspector")
            } catch (e: RuntimeException) {
                assertEquals("Inspector1 failure", e.message)
            }
        }
    }

    @Nested
    inner class WithInspectorSingleTests {

        @Test
        fun `withInspector returns original list when inspector is null`() {
            val callbacks = listOf(
                createMockCallback(name = "tool1"),
                createMockCallback(name = "tool2"),
            )

            val result = callbacks.withInspector(null)

            assertEquals(callbacks, result)
        }

        @Test
        fun `withInspector wraps all callbacks when inspector is provided`() {
            val callbacks = listOf(
                createMockCallback(name = "tool1"),
                createMockCallback(name = "tool2"),
            )

            val inspector = object : ToolCallInspector {}
            val result = callbacks.withInspector(inspector)

            assertEquals(2, result.size)
            assertTrue(result[0] is SpringAiToolCallbackListener)
            assertTrue(result[1] is SpringAiToolCallbackListener)
        }

        @Test
        fun `withInspector notifies single inspector`() {
            val events = mutableListOf<String>()
            val inspector = object : ToolCallInspector {
                override fun beforeToolCall(context: BeforeToolCallContext) {
                    events.add("before")
                }

                override fun afterToolCall(context: AfterToolCallContext) {
                    events.add("after")
                }
            }

            val callbacks = listOf(createMockCallback(name = "tool1"))
            val wrapped = callbacks.withInspector(inspector)

            wrapped[0].call("{}")

            assertEquals(2, events.size)
            assertEquals("before", events[0])
            assertEquals("after", events[1])
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
