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
package com.embabel.agent.anthropic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ThinkingBlockOrderingInterceptor.fixMessages].
 * Tests operate on a Jackson tree directly — no Anthropic SDK types required.
 * The HTTP-layer wiring (asInterceptor / buildFixedRequest) is covered by
 * [LLMAnthropicThinkingIT.NativeAdaptiveThinking.sonnet55TwoToolsTriggersBlockOrdering].
 */
class ThinkingBlockOrderingInterceptorTest {

    private val mapper = ObjectMapper()

    // ---- helpers ----

    private fun messages(vararg jsonStrings: String): ArrayNode =
        mapper.createArrayNode().apply {
            jsonStrings.forEach { add(mapper.readTree(it)) }
        }

    private fun assistantMessage(vararg blocks: String): String {
        val content = blocks.joinToString(",", "[", "]")
        return """{"role":"assistant","content":$content}"""
    }

    private fun userMessage() = """{"role":"user","content":"What is the hottest month?"}"""

    private fun thinkingBlock(text: String, sig: String = "sig123") =
        """{"type":"thinking","thinking":"$text","signature":"$sig"}"""

    private fun textBlock(text: String) =
        """{"type":"text","text":"$text"}"""

    private fun toolUseBlock(id: String = "t1", name: String = "myTool") =
        """{"type":"tool_use","id":"$id","name":"$name","input":{}}"""

    // ---- test classes ----

    @Nested
    inner class NoChange {

        @Test
        fun `empty messages array — returns false`() {
            val msgs = mapper.createArrayNode()
            assertFalse(ThinkingBlockOrderingInterceptor.fixMessages(msgs))
        }

        @Test
        fun `user message only — not touched, returns false`() {
            val msgs = messages(userMessage())
            assertFalse(ThinkingBlockOrderingInterceptor.fixMessages(msgs))
        }

        @Test
        fun `assistant message with string content not array — skipped, returns false`() {
            // Plain string content has no blocks to reorder
            val msgs = messages("""{"role":"assistant","content":"plain string"}""")
            assertFalse(ThinkingBlockOrderingInterceptor.fixMessages(msgs))
        }

        @Test
        fun `thinking already before tool_use — no change, returns false`() {
            val msgs = messages(
                assistantMessage(thinkingBlock("I reason"), toolUseBlock())
            )
            assertFalse(ThinkingBlockOrderingInterceptor.fixMessages(msgs))
        }

        @Test
        fun `thinking then text then tool_use — already correct, returns false`() {
            val msgs = messages(
                assistantMessage(thinkingBlock("I reason"), textBlock("different text"), toolUseBlock())
            )
            assertFalse(ThinkingBlockOrderingInterceptor.fixMessages(msgs))
        }
    }

    @Nested
    inner class BlockReordering {

        @Test
        fun `text before thinking is moved after thinking`() {
            val msgs = messages(
                assistantMessage(
                    textBlock("different response text"),
                    thinkingBlock("internal reasoning"),
                    toolUseBlock(),
                )
            )

            assertTrue(ThinkingBlockOrderingInterceptor.fixMessages(msgs))

            val content = (msgs[0].get("content") as ArrayNode).toList()
            assertEquals("thinking", content[0].get("type").asText(), "thinking must be first")
            assertEquals("text",     content[1].get("type").asText(), "text must be second")
            assertEquals("tool_use", content[2].get("type").asText(), "tool_use must be last")
        }

        @Test
        fun `tool_use before thinking is moved after thinking`() {
            val msgs = messages(
                assistantMessage(toolUseBlock(), thinkingBlock("I reason"))
            )

            assertTrue(ThinkingBlockOrderingInterceptor.fixMessages(msgs))

            val content = (msgs[0].get("content") as ArrayNode).toList()
            assertEquals("thinking", content[0].get("type").asText())
            assertEquals("tool_use", content[1].get("type").asText())
        }
    }

    @Nested
    inner class DuplicateTextRemoval {

        @Test
        fun `text block duplicating thinking content is removed`() {
            // This reproduces the Spring AI bug: thinking text copied into AssistantMessage.text
            // becomes an extra text_block in the conversation history.
            val thinkingText = "July is the hottest month, I'll call both tools"
            val msgs = messages(
                assistantMessage(
                    textBlock(thinkingText),       // Spring AI added this duplicate
                    thinkingBlock(thinkingText),
                    toolUseBlock(),
                )
            )

            assertTrue(ThinkingBlockOrderingInterceptor.fixMessages(msgs))

            val content = (msgs[0].get("content") as ArrayNode).toList()
            // duplicate text_block removed — only thinking and tool_use remain
            assertEquals(2, content.size, "duplicate text block should be removed")
            assertEquals("thinking", content[0].get("type").asText())
            assertEquals("tool_use", content[1].get("type").asText())
        }

        @Test
        fun `text block with different content is kept and sorted after thinking`() {
            val msgs = messages(
                assistantMessage(
                    textBlock("A visible response shown to the user"),
                    thinkingBlock("internal reasoning"),
                    toolUseBlock(),
                )
            )

            assertTrue(ThinkingBlockOrderingInterceptor.fixMessages(msgs))

            val content = (msgs[0].get("content") as ArrayNode).toList()
            // all three blocks kept, just reordered
            assertEquals(3, content.size, "unique text block should be kept")
            assertEquals("thinking", content[0].get("type").asText())
            assertEquals("text",     content[1].get("type").asText())
            assertEquals("tool_use", content[2].get("type").asText())
            assertEquals("A visible response shown to the user", content[1].get("text").asText())
        }
    }

    @Nested
    inner class MultipleMessages {

        @Test
        fun `user messages between assistant messages are untouched`() {
            val thinkingText = "I reason"
            val msgs = messages(
                userMessage(),
                assistantMessage(textBlock(thinkingText), thinkingBlock(thinkingText), toolUseBlock()),
                userMessage(),  // simulates tool_result turn
            )

            assertTrue(ThinkingBlockOrderingInterceptor.fixMessages(msgs))
            // user messages must be unchanged
            assertEquals("user", msgs[0].get("role").asText())
            assertEquals("user", msgs[2].get("role").asText())
        }

        @Test
        fun `both assistant messages in a two-turn tool loop are fixed`() {
            // Simulates two tool-loop iterations, each with a wrongly ordered assistant turn
            val text = "thinking content"
            val msgs = messages(
                userMessage(),
                assistantMessage(textBlock(text), thinkingBlock(text), toolUseBlock("t1")),
                userMessage(),
                assistantMessage(textBlock(text), thinkingBlock(text), toolUseBlock("t2")),
            )

            assertTrue(ThinkingBlockOrderingInterceptor.fixMessages(msgs))

            // Both assistant turns (indices 1 and 3) must have thinking first
            listOf(1, 3).forEach { idx ->
                val content = (msgs[idx].get("content") as ArrayNode).toList()
                assertEquals(
                    "thinking", content[0].get("type").asText(),
                    "assistant message at index $idx: thinking must be first"
                )
            }
        }
    }
}
