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
package com.embabel.chat

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Tests for tool-related message types.
 */
class ToolMessagesTest {

    @Nested
    inner class ToolCallTest {

        @Test
        fun `creates ToolCall with all properties`() {
            val toolCall = ToolCall(
                id = "call_123",
                name = "get_weather",
                arguments = """{"location": "NYC"}""",
            )

            assertEquals("call_123", toolCall.id)
            assertEquals("get_weather", toolCall.name)
            assertEquals("""{"location": "NYC"}""", toolCall.arguments)
        }

        @Test
        fun `ToolCall equals works correctly`() {
            val call1 = ToolCall("id1", "tool", "{}")
            val call2 = ToolCall("id1", "tool", "{}")
            val call3 = ToolCall("id2", "tool", "{}")

            assertEquals(call1, call2)
            assertNotEquals(call1, call3)
        }

        @Test
        fun `ToolCall hashCode is consistent`() {
            val call1 = ToolCall("id1", "tool", "{}")
            val call2 = ToolCall("id1", "tool", "{}")

            assertEquals(call1.hashCode(), call2.hashCode())
        }
    }

    @Nested
    inner class AssistantMessageWithToolCallsTest {

        @Test
        fun `creates message with tool calls`() {
            val toolCalls = listOf(
                ToolCall("call_1", "tool_a", "{}"),
                ToolCall("call_2", "tool_b", """{"arg": "value"}"""),
            )
            val message = AssistantMessageWithToolCalls(
                content = "Let me call some tools",
                toolCalls = toolCalls,
            )

            assertEquals("Let me call some tools", message.textContent)
            assertEquals(2, message.toolCalls.size)
            assertEquals("tool_a", message.toolCalls[0].name)
            assertEquals("tool_b", message.toolCalls[1].name)
        }

        @Test
        fun `creates message with minimal content`() {
            val message = AssistantMessageWithToolCalls(
                content = " ",
                toolCalls = listOf(ToolCall("id", "tool", "{}")),
            )

            assertEquals(" ", message.textContent)
            assertEquals(1, message.toolCalls.size)
        }

        @Test
        fun `creates message with empty content for tool-call-only responses`() {
            // LLMs often return tool calls without accompanying text
            val message = AssistantMessageWithToolCalls(
                content = "",
                toolCalls = listOf(ToolCall("id", "tool", "{}")),
            )

            assertEquals("", message.content)
            assertEquals("", message.textContent)
            assertTrue(message.parts.isEmpty())
            assertEquals(1, message.toolCalls.size)
        }

        @Test
        fun `creates message with default empty content`() {
            // Using default parameter
            val message = AssistantMessageWithToolCalls(
                toolCalls = listOf(ToolCall("id", "tool", "{}")),
            )

            assertEquals("", message.content)
            assertTrue(message.parts.isEmpty())
        }

        @Test
        fun `content property returns text from parts`() {
            val message = AssistantMessageWithToolCalls(
                content = "Some text",
                toolCalls = listOf(ToolCall("id", "tool", "{}")),
            )

            assertEquals("Some text", message.content)
            assertEquals(1, message.parts.size)
            assertTrue(message.parts[0] is TextPart)
        }

        @Test
        fun `message has assistant role`() {
            val message = AssistantMessageWithToolCalls(
                content = " ",
                toolCalls = listOf(ToolCall("id", "tool", "{}")),
            )

            assertEquals(Role.ASSISTANT, message.role)
        }

        @Test
        fun `toString includes tool names`() {
            val message = AssistantMessageWithToolCalls(
                content = " ",
                toolCalls = listOf(
                    ToolCall("1", "weather_tool", "{}"),
                    ToolCall("2", "time_tool", "{}"),
                ),
            )

            val str = message.toString()
            assertTrue(str.contains("weather_tool"))
            assertTrue(str.contains("time_tool"))
        }

        @Test
        fun `supports custom name and timestamp`() {
            val timestamp = Instant.parse("2024-01-15T10:30:00Z")
            val message = AssistantMessageWithToolCalls(
                content = "content",
                toolCalls = listOf(ToolCall("id", "tool", "{}")),
                name = "assistant-name",
                timestamp = timestamp,
            )

            assertEquals("assistant-name", message.name)
            assertEquals(timestamp, message.timestamp)
        }

        @Test
        fun `extends Message and implements AssistantContent`() {
            val message = AssistantMessageWithToolCalls(
                content = "test",
                toolCalls = listOf(ToolCall("id", "tool", "{}")),
            )

            assertTrue(message is Message)
            assertTrue(message is com.embabel.agent.domain.io.AssistantContent)
        }
    }

    @Nested
    inner class ToolResultMessageTest {

        @Test
        fun `creates message with tool result`() {
            val message = ToolResultMessage(
                toolCallId = "call_123",
                toolName = "get_weather",
                content = """{"temperature": 72}""",
            )

            assertEquals("call_123", message.toolCallId)
            assertEquals("get_weather", message.toolName)
            assertEquals("""{"temperature": 72}""", message.textContent)
        }

        @Test
        fun `has assistant role`() {
            val message = ToolResultMessage(
                toolCallId = "id",
                toolName = "tool",
                content = "result",
            )

            assertEquals(Role.ASSISTANT, message.role)
        }

        @Test
        fun `content is wrapped in TextPart`() {
            val message = ToolResultMessage(
                toolCallId = "id",
                toolName = "tool",
                content = "the result content",
            )

            assertEquals(1, message.parts.size)
            assertTrue(message.parts[0] is TextPart)
            assertEquals("the result content", (message.parts[0] as TextPart).text)
        }

        @Test
        fun `toString includes toolCallId and toolName`() {
            val message = ToolResultMessage(
                toolCallId = "unique_call_id",
                toolName = "my_tool",
                content = "result",
            )

            val str = message.toString()
            assertTrue(str.contains("unique_call_id"))
            assertTrue(str.contains("my_tool"))
        }

        @Test
        fun `supports custom timestamp`() {
            val timestamp = Instant.parse("2024-01-15T10:30:00Z")
            val message = ToolResultMessage(
                toolCallId = "id",
                toolName = "tool",
                content = "result",
                timestamp = timestamp,
            )

            assertEquals(timestamp, message.timestamp)
        }

        @Test
        fun `name is null`() {
            val message = ToolResultMessage(
                toolCallId = "id",
                toolName = "tool",
                content = "result",
            )

            assertNull(message.name)
        }
    }
}
