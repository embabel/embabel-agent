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
import com.embabel.agent.core.Usage
import com.embabel.chat.AssistantMessage
import com.embabel.chat.AssistantMessageWithToolCalls
import com.embabel.chat.SystemMessage
import com.embabel.chat.ToolCall
import com.embabel.chat.ToolResultMessage
import com.embabel.chat.UserMessage
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for simple tool loop callbacks.
 */
class SimpleToolLoopCallbacksTest {

    @Nested
    inner class ToolLoopLoggingInspectorTest {

        @Test
        fun `beforeLlmCall logs without exception`() {
            ToolLoopLoggingInspector().beforeLlmCall(
                BeforeLlmCallContext(
                    history = listOf(UserMessage("test")),
                    iteration = 1,
                    tools = emptyList(),
                )
            )
        }

        @Test
        fun `afterLlmCall logs without exception for plain response`() {
            ToolLoopLoggingInspector().afterLlmCall(
                AfterLlmCallContext(
                    history = listOf(UserMessage("test")),
                    iteration = 1,
                    response = AssistantMessage("response"),
                    usage = null,
                )
            )
        }

        @Test
        fun `afterLlmCall logs without exception for response with tool calls`() {
            ToolLoopLoggingInspector().afterLlmCall(
                AfterLlmCallContext(
                    history = listOf(UserMessage("test")),
                    iteration = 1,
                    response = AssistantMessageWithToolCalls(
                        content = "",
                        toolCalls = listOf(ToolCall(id = "1", name = "testTool", arguments = "{}")),
                    ),
                    usage = Usage(promptTokens = 100, completionTokens = 50, nativeUsage = null),
                )
            )
        }

        @Test
        fun `afterToolResult logs without exception`() {
            ToolLoopLoggingInspector().afterToolResult(
                AfterToolResultContext(
                    history = emptyList(),
                    iteration = 1,
                    toolCall = ToolCall(id = "1", name = "testTool", arguments = "{}"),
                    result = Tool.Result.text("result"),
                    resultAsString = "result",
                )
            )
        }

        @Test
        fun `afterIteration logs without exception`() {
            ToolLoopLoggingInspector().afterIteration(
                AfterIterationContext(
                    history = listOf(UserMessage("test")),
                    iteration = 1,
                    toolCallsInIteration = emptyList(),
                )
            )
        }

        @Test
        fun `supports all log levels`() {
            ToolLoopLoggingInspector.LogLevel.entries.forEach { level ->
                ToolLoopLoggingInspector(logLevel = level).beforeLlmCall(
                    BeforeLlmCallContext(
                        history = emptyList(),
                        iteration = 1,
                        tools = emptyList(),
                    )
                )
            }
        }
    }

    @Nested
    inner class SlidingWindowTransformerTest {

        @Test
        fun `returns history unchanged when under limit`() {
            val history = listOf(UserMessage("msg1"), AssistantMessage("msg2"))

            SlidingWindowTransformer(maxMessages = 5)
                .transformBeforeLlmCall(BeforeLlmCallContext(history, 1, emptyList()))
                .also { Assertions.assertEquals(history, it) }
        }

        @Test
        fun `truncates to maxMessages`() {
            val history = (1..5).map { if (it % 2 == 1) UserMessage("msg$it") else AssistantMessage("msg$it") }

            SlidingWindowTransformer(maxMessages = 3, preserveSystemMessages = false)
                .transformBeforeLlmCall(BeforeLlmCallContext(history, 1, emptyList()))
                .also { result ->
                    Assertions.assertEquals(3, result.size)
                    Assertions.assertEquals(listOf("msg3", "msg4", "msg5"), result.map { it.content })
                }
        }

        @Test
        fun `preserves system messages by type`() {
            val history = listOf(
                SystemMessage("system prompt"),
                UserMessage("msg1"),
                AssistantMessage("msg2"),
                UserMessage("msg3"),
                AssistantMessage("msg4"),
            )

            SlidingWindowTransformer(maxMessages = 3, preserveSystemMessages = true)
                .transformBeforeLlmCall(BeforeLlmCallContext(history, 1, emptyList()))
                .also { result ->
                    Assertions.assertEquals(3, result.size)
                    Assertions.assertEquals(1, result.filterIsInstance<SystemMessage>().size)
                    Assertions.assertEquals(listOf("system prompt", "msg3", "msg4"), result.map { it.content })
                }
        }

        @Test
        fun `preserves multiple system messages`() {
            val history = listOf(
                SystemMessage("system1"),
                UserMessage("msg1"),
                SystemMessage("system2"),
                AssistantMessage("msg2"),
                UserMessage("msg3"),
                AssistantMessage("msg4"),
            )

            SlidingWindowTransformer(maxMessages = 4, preserveSystemMessages = true)
                .transformBeforeLlmCall(BeforeLlmCallContext(history, 1, emptyList()))
                .also { result ->
                    Assertions.assertEquals(4, result.size)
                    Assertions.assertEquals(2, result.filterIsInstance<SystemMessage>().size)
                    Assertions.assertEquals(listOf("system1", "system2", "msg3", "msg4"), result.map { it.content })
                }
        }

        @Test
        fun `transformAfterIteration also applies windowing`() {
            val history = listOf(
                UserMessage("msg1"),
                AssistantMessage("msg2"),
                UserMessage("msg3"),
            )

            SlidingWindowTransformer(maxMessages = 2, preserveSystemMessages = false)
                .transformAfterIteration(AfterIterationContext(history, 1, emptyList()))
                .also { result ->
                    Assertions.assertEquals(listOf("msg2", "msg3"), result.map { it.content })
                }
        }

        @Test
        fun `preserveSystemMessages defaults to true`() {
            val history = listOf(
                SystemMessage("system"),
                UserMessage("msg1"),
                AssistantMessage("msg2"),
                UserMessage("msg3"),
            )

            SlidingWindowTransformer(maxMessages = 2)
                .transformBeforeLlmCall(BeforeLlmCallContext(history, 1, emptyList()))
                .also { result ->
                    Assertions.assertEquals(2, result.size)
                    Assertions.assertTrue(result.first() is SystemMessage)
                    Assertions.assertEquals(listOf("system", "msg3"), result.map { it.content })
                }
        }

        @Test
        fun `drops orphaned ToolResultMessages after truncation`() {
            // Scenario: truncation cuts between AssistantMessageWithToolCalls and its ToolResults
            // Before truncation: [System, User, Assistant+Calls, ToolResult1, ToolResult2, Assistant+Calls, ToolResult3]
            // After naive takeLast(4): [System, ToolResult2, Assistant+Calls, ToolResult3] <- ToolResult2 is ORPHANED
            // After fix: [System, Assistant+Calls, ToolResult3] <- orphan dropped
            val history = listOf(
                SystemMessage("system"),
                UserMessage("user input"),
                AssistantMessageWithToolCalls("", listOf(ToolCall("call_1", "tool1", "{}"), ToolCall("call_2", "tool2", "{}"))),
                ToolResultMessage("call_1", "tool1", "result1"),
                ToolResultMessage("call_2", "tool2", "result2"),
                AssistantMessageWithToolCalls("", listOf(ToolCall("call_3", "tool3", "{}"))),
                ToolResultMessage("call_3", "tool3", "result3"),
            )

            SlidingWindowTransformer(maxMessages = 4, preserveSystemMessages = true)
                .transformBeforeLlmCall(BeforeLlmCallContext(history, 1, emptyList()))
                .also { result ->
                    // First non-system message must NOT be ToolResultMessage
                    val firstNonSystem = result.firstOrNull { it !is SystemMessage }
                    Assertions.assertFalse(
                        firstNonSystem is ToolResultMessage,
                        "First non-system message should not be ToolResultMessage, was: $firstNonSystem"
                    )
                    // Should contain the valid AssistantMessageWithToolCalls and its ToolResult
                    Assertions.assertTrue(
                        result.any { it is AssistantMessageWithToolCalls },
                        "Should contain AssistantMessageWithToolCalls"
                    )
                }
        }

        @Test
        fun `drops multiple orphaned ToolResultMessages`() {
            // All ToolResultMessages at the start (after system) should be dropped
            val history = listOf(
                SystemMessage("system"),
                UserMessage("user"),
                AssistantMessageWithToolCalls("", listOf(ToolCall("c1", "t1", "{}"), ToolCall("c2", "t2", "{}"), ToolCall("c3", "t3", "{}"))),
                ToolResultMessage("c1", "t1", "r1"),
                ToolResultMessage("c2", "t2", "r2"),
                ToolResultMessage("c3", "t3", "r3"),
                AssistantMessage("final answer"),
            )

            // maxMessages=4 with 1 system -> takeLast(3) from non-system = [ToolResult2, ToolResult3, Assistant]
            // After fix: only [Assistant] remains (or more if we keep valid messages)
            SlidingWindowTransformer(maxMessages = 4, preserveSystemMessages = true)
                .transformBeforeLlmCall(BeforeLlmCallContext(history, 1, emptyList()))
                .also { result ->
                    val firstNonSystem = result.firstOrNull { it !is SystemMessage }
                    Assertions.assertFalse(
                        firstNonSystem is ToolResultMessage,
                        "First non-system message should not be ToolResultMessage after dropping orphans"
                    )
                }
        }

        @Test
        fun `preserves valid tool call and result pairs`() {
            // When truncation doesn't orphan anything, should work normally
            val history = listOf(
                SystemMessage("system"),
                UserMessage("user"),
                AssistantMessageWithToolCalls("", listOf(ToolCall("call_1", "tool1", "{}"))),
                ToolResultMessage("call_1", "tool1", "result1"),
                AssistantMessage("answer"),
            )

            SlidingWindowTransformer(maxMessages = 4, preserveSystemMessages = true)
                .transformBeforeLlmCall(BeforeLlmCallContext(history, 1, emptyList()))
                .also { result ->
                    // Should keep: System, AssistantWithCalls, ToolResult, Assistant (4 messages fit, minus 1 user)
                    // Actually with 5 messages and max 4: System + takeLast(3) = System, ToolResult, Assistant
                    // Hmm, this would orphan the ToolResult... let me recalculate
                    // 5 messages, 1 system, 4 non-system, max=4, remainingSlots=3
                    // takeLast(3) = [AssistantWithCalls, ToolResult, Assistant]
                    // First non-system is AssistantWithCalls - valid!
                    Assertions.assertEquals(4, result.size)
                    Assertions.assertTrue(result[0] is SystemMessage)
                    Assertions.assertTrue(result[1] is AssistantMessageWithToolCalls)
                    Assertions.assertTrue(result[2] is ToolResultMessage)
                    Assertions.assertTrue(result[3] is AssistantMessage)
                }
        }

        @Test
        fun `handles empty result after dropping all orphans`() {
            // Edge case: all non-system messages are orphaned ToolResults
            val history = listOf(
                SystemMessage("system"),
                ToolResultMessage("c1", "t1", "r1"),
                ToolResultMessage("c2", "t2", "r2"),
            )

            SlidingWindowTransformer(maxMessages = 3, preserveSystemMessages = true)
                .transformBeforeLlmCall(BeforeLlmCallContext(history, 1, emptyList()))
                .also { result ->
                    // Should at least have the system message
                    Assertions.assertTrue(result.isNotEmpty(), "Should not be completely empty")
                    // All ToolResults should be dropped
                    Assertions.assertTrue(
                        result.none { it is ToolResultMessage },
                        "All orphaned ToolResults should be dropped"
                    )
                }
        }

        @Test
        fun `drops orphans with preserveSystemMessages false`() {
            // Test the preserveSystemMessages=false branch with orphaned ToolResults
            val history = listOf(
                SystemMessage("system"),
                UserMessage("user"),
                AssistantMessageWithToolCalls("", listOf(ToolCall("c1", "t1", "{}"))),
                ToolResultMessage("c1", "t1", "r1"),
                AssistantMessage("answer"),
            )

            // maxMessages=3, preserveSystemMessages=false -> takeLast(3) = [ToolResult, Assistant, ???]
            // Wait, that's only 5 messages, takeLast(3) = [ToolResult, AssistantMessage, ???]
            // Actually: takeLast(3) from [System, User, Assistant+Calls, ToolResult, Assistant]
            // = [Assistant+Calls, ToolResult, Assistant] - no orphans here
            // Let me create a scenario that creates orphans with preserveSystemMessages=false
            val historyWithOrphans = listOf(
                UserMessage("user1"),
                AssistantMessageWithToolCalls("", listOf(ToolCall("c1", "t1", "{}"), ToolCall("c2", "t2", "{}"))),
                ToolResultMessage("c1", "t1", "r1"),
                ToolResultMessage("c2", "t2", "r2"),
                AssistantMessageWithToolCalls("", listOf(ToolCall("c3", "t3", "{}"))),
                ToolResultMessage("c3", "t3", "r3"),
            )

            // takeLast(3) = [ToolResult(c2), Assistant+Calls(c3), ToolResult(c3)]
            // ToolResult(c2) is orphaned
            SlidingWindowTransformer(maxMessages = 3, preserveSystemMessages = false)
                .transformBeforeLlmCall(BeforeLlmCallContext(historyWithOrphans, 1, emptyList()))
                .also { result ->
                    val firstMsg = result.firstOrNull()
                    Assertions.assertFalse(
                        firstMsg is ToolResultMessage,
                        "First message should not be orphaned ToolResultMessage, was: $firstMsg"
                    )
                    Assertions.assertTrue(
                        result.first() is AssistantMessageWithToolCalls,
                        "First message should be AssistantMessageWithToolCalls"
                    )
                }
        }

        @Test
        fun `no orphans when first non-system message is valid`() {
            // Test case where firstValidIndex == 0 (no orphans to drop)
            // First non-system message is UserMessage, not ToolResultMessage
            val history = listOf(
                SystemMessage("system"),
                UserMessage("user"),
                AssistantMessageWithToolCalls("", listOf(ToolCall("c1", "t1", "{}"))),
                ToolResultMessage("c1", "t1", "r1"),
            )

            SlidingWindowTransformer(maxMessages = 4, preserveSystemMessages = true)
                .transformBeforeLlmCall(BeforeLlmCallContext(history, 1, emptyList()))
                .also { result ->
                    // No truncation needed, no orphans - should return unchanged
                    Assertions.assertEquals(4, result.size)
                    Assertions.assertEquals(history, result)
                }
        }

        @Test
        fun `no orphans when valid sequence starts with AssistantMessage`() {
            // Another firstValidIndex == 0 case: starts with AssistantMessage
            val history = listOf(
                SystemMessage("system"),
                AssistantMessage("greeting"),
                UserMessage("question"),
            )

            SlidingWindowTransformer(maxMessages = 3, preserveSystemMessages = true)
                .transformBeforeLlmCall(BeforeLlmCallContext(history, 1, emptyList()))
                .also { result ->
                    Assertions.assertEquals(history, result)
                }
        }
    }

    @Nested
    inner class ToolResultTruncatingTransformerTest {

        private fun contextWithResult(result: String) = AfterToolResultContext(
            history = emptyList(),
            iteration = 1,
            toolCall = ToolCall(id = "1", name = "test", arguments = "{}"),
            result = Tool.Result.text(result),
            resultAsString = result,
        )

        @Test
        fun `returns result unchanged when under limit`() {
            ToolResultTruncatingTransformer(maxLength = 100)
                .transformAfterToolResult(contextWithResult("short result"))
                .also { Assertions.assertEquals("short result", it) }
        }

        @Test
        fun `truncates result exceeding limit`() {
            ToolResultTruncatingTransformer(maxLength = 10)
                .transformAfterToolResult(contextWithResult("this is a very long result that should be truncated"))
                .also { result ->
                    Assertions.assertTrue(result.startsWith("this is a "))
                    Assertions.assertTrue(result.contains("[truncated"))
                }
        }

        @Test
        fun `uses custom truncation marker`() {
            ToolResultTruncatingTransformer(maxLength = 10, truncationMarker = "...CUT...")
                .transformAfterToolResult(contextWithResult("this is a very long result"))
                .also { Assertions.assertEquals("this is a ...CUT...", it) }
        }

        @Test
        fun `result at exact limit is not truncated`() {
            ToolResultTruncatingTransformer(maxLength = 5)
                .transformAfterToolResult(contextWithResult("12345"))
                .also { Assertions.assertEquals("12345", it) }
        }

        @Test
        fun `default maxLength is 10000`() {
            val exactLimit = "x".repeat(10000)

            ToolResultTruncatingTransformer()
                .transformAfterToolResult(contextWithResult(exactLimit))
                .also { Assertions.assertEquals(exactLimit, it) }
        }

        @Test
        fun `truncates at default maxLength`() {
            val overLimit = "x".repeat(10001)

            ToolResultTruncatingTransformer()
                .transformAfterToolResult(contextWithResult(overLimit))
                .also { result ->
                    Assertions.assertTrue(result.contains("[truncated"))
                }
        }
    }
}
