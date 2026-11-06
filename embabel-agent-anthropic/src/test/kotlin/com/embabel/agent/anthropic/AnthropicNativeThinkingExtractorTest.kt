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

import com.embabel.common.core.thinking.ThinkingOrigin
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation

/**
 * Tests for [AnthropicNativeThinkingExtractor].
 *
 * ## Spring AI 2.0 representation
 *
 * In Spring AI 2.0, `AnthropicChatModel` stores thinking blocks primarily on
 * `AnthropicAssistantMessage.getThinkingContents()`, also mirrored in
 * `AssistantMessage.getMetadata()["anthropicThinkingContents"]` as a fallback.
 * Both paths hold `List<AnthropicChatModel.AnthropicThinkingContent>`. Because that class is
 * non-public, [AnthropicNativeThinkingExtractor] accesses it via reflection
 * using the public record-component accessors `thinking()` and `signature()`.
 *
 * [FakeThinkingContent] mimics that contract: its `thinking()` and `signature()`
 * are Kotlin `fun` declarations, which the JVM exposes without a "get" prefix —
 * matching how Java record-component accessors are named.
 *
 * ## Replaced approach (Spring AI ≤1.x)
 *
 * The old extractor filtered `ChatResponse.results` for generations whose
 * `output.metadata` contained a `"signature"` key and read thinking text from `.text`.
 * In Spring AI 2.0 the `"signature"` key may still appear but `.text` carries no
 * thinking text — the old approach always returns empty. Tests are replaced here entirely.
 */
class AnthropicNativeThinkingExtractorTest {

    /**
     * Stand-in for `AnthropicChatModel.AnthropicThinkingContent` (non-public Java record).
     *
     * Exposes `thinking()` and `signature()` as plain Kotlin functions so that
     * [AnthropicNativeThinkingExtractor]'s reflection-based extraction exercises the
     * same `getMethod("thinking")` / `getMethod("signature")` paths as the real type.
     */
    private class FakeThinkingContent(
        private val thinkingText: String,
        private val signatureValue: String?,
    ) {
        fun thinking(): String = thinkingText
        fun signature(): String? = signatureValue
    }

    /**
     * Stand-in for `AnthropicChatModel.AnthropicAssistantMessage` (non-public subclass).
     *
     * Declares `getThinkingContents()` so [AnthropicNativeThinkingExtractor.thinkingContentsFrom]
     * can find it via `getDeclaredMethod("getThinkingContents")`, mirroring the real Spring AI type.
     */
    private inner class FakeAnthropicAssistantMessage(
        private val thinkingItems: List<FakeThinkingContent>,
    ) : AssistantMessage("") {
        fun getThinkingContents(): List<FakeThinkingContent> = thinkingItems
    }

    private fun generation(
        text: String,
        thinkingContents: List<Any> = emptyList(),
        extraMetadata: Map<String, Any> = emptyMap(),
    ): Generation {
        val metadata: Map<String, Any> = when {
            thinkingContents.isNotEmpty() -> mapOf("anthropicThinkingContents" to thinkingContents) + extraMetadata
            extraMetadata.isNotEmpty() -> extraMetadata
            else -> emptyMap()
        }
        val message = mockk<AssistantMessage> {
            every { this@mockk.text } returns text
            every { this@mockk.metadata } returns metadata
        }
        return mockk<Generation> {
            every { output } returns message
        }
    }

    private fun generationViaMethod(thinkingItems: List<FakeThinkingContent>): Generation {
        val message = FakeAnthropicAssistantMessage(thinkingItems)
        return mockk<Generation> { every { output } returns message }
    }

    private fun chatResponse(vararg generations: Generation): ChatResponse = mockk {
        every { results } returns generations.toList()
    }

    @Nested
    inner class ExtractsThinking {

        @Test
        fun `extracts thinking block from anthropicThinkingContents metadata`() {
            val response = chatResponse(
                generation(
                    text = """{"name":"July","temperature":98}""",
                    thinkingContents = listOf(
                        FakeThinkingContent("Let me analyze which month is hottest...", "sig-abc123"),
                    ),
                ),
            )

            val blocks = AnthropicNativeThinkingExtractor.extract(response)

            assertEquals(1, blocks.size)
            assertEquals("Let me analyze which month is hottest...", blocks[0].content)
        }

        @Test
        fun `extracted block has NATIVE origin`() {
            val response = chatResponse(
                generation(
                    text = "answer",
                    thinkingContents = listOf(FakeThinkingContent("reasoning...", "sig-xyz")),
                ),
            )

            val blocks = AnthropicNativeThinkingExtractor.extract(response)

            assertEquals(ThinkingOrigin.NATIVE, blocks[0].origin)
        }

        @Test
        fun `extracted block preserves signature for multi-turn continuity`() {
            val response = chatResponse(
                generation(
                    text = "answer",
                    thinkingContents = listOf(FakeThinkingContent("thinking...", "sig-abc123")),
                ),
            )

            val blocks = AnthropicNativeThinkingExtractor.extract(response)

            assertEquals("sig-abc123", blocks[0].metadata["signature"])
        }

        @Test
        fun `extracts multiple thinking blocks from one generation`() {
            val response = chatResponse(
                generation(
                    text = "answer",
                    thinkingContents = listOf(
                        FakeThinkingContent("first thought", "sig-1"),
                        FakeThinkingContent("second thought", "sig-2"),
                    ),
                ),
            )

            val blocks = AnthropicNativeThinkingExtractor.extract(response)

            assertEquals(2, blocks.size)
            assertEquals("first thought", blocks[0].content)
            assertEquals("second thought", blocks[1].content)
        }

        @Test
        fun `extracts thinking blocks across multiple generations`() {
            val response = chatResponse(
                generation(
                    text = "tool call response",
                    thinkingContents = listOf(FakeThinkingContent("iteration 1 thought", "sig-iter1")),
                ),
                generation(
                    text = "final answer",
                    thinkingContents = listOf(FakeThinkingContent("iteration 2 thought", "sig-iter2")),
                ),
            )

            val blocks = AnthropicNativeThinkingExtractor.extract(response)

            assertEquals(2, blocks.size)
            assertEquals("iteration 1 thought", blocks[0].content)
            assertEquals("iteration 2 thought", blocks[1].content)
        }

        @Test
        fun `extracts thinking via getThinkingContents() declared method`() {
            val response = chatResponse(
                generationViaMethod(listOf(FakeThinkingContent("native via method", "sig-method"))),
            )

            val blocks = AnthropicNativeThinkingExtractor.extract(response)

            assertEquals(1, blocks.size)
            assertEquals("native via method", blocks[0].content)
            assertEquals(ThinkingOrigin.NATIVE, blocks[0].origin)
            assertEquals("sig-method", blocks[0].metadata["signature"])
        }

        @Test
        fun `handles null signature gracefully`() {
            val response = chatResponse(
                generation(
                    text = "answer",
                    thinkingContents = listOf(FakeThinkingContent("thinking without signature", null)),
                ),
            )

            val blocks = AnthropicNativeThinkingExtractor.extract(response)

            assertEquals(1, blocks.size)
            assertTrue(blocks[0].metadata.isEmpty())
        }
    }

    @Nested
    inner class IgnoresNonThinking {

        @Test
        fun `returns empty when no generation has anthropicThinkingContents`() {
            val response = chatResponse(
                generation(text = """{"answer":"Bob"}"""),
            )

            assertTrue(AnthropicNativeThinkingExtractor.extract(response).isEmpty())
        }

        @Test
        fun `ignores blank thinking text — Anthropic can return empty thinking blocks`() {
            val response = chatResponse(
                generation(
                    text = "answer",
                    thinkingContents = listOf(FakeThinkingContent("   ", "sig-xyz")),
                ),
            )

            assertTrue(AnthropicNativeThinkingExtractor.extract(response).isEmpty())
        }

        @Test
        fun `returns empty for empty results list`() {
            val response = chatResponse()

            assertTrue(AnthropicNativeThinkingExtractor.extract(response).isEmpty())
        }

        @Test
        fun `does not extract from old Spring AI 1x signature metadata key`() {
            // The old ≤1.x approach stored thinking blocks as separate generations with a
            // "signature" key in generation.output.metadata. Spring AI 2.0 dropped this
            // representation. Ensure we do NOT accidentally match on that key.
            val message = mockk<AssistantMessage> {
                every { text } returns "old thinking text"
                every { metadata } returns mapOf("signature" to "sig-legacy")
            }
            val legacyGeneration = mockk<Generation> { every { output } returns message }
            val response = chatResponse(legacyGeneration)

            assertTrue(AnthropicNativeThinkingExtractor.extract(response).isEmpty())
        }
    }

    /**
     * Spring AI 2.0 Anthropic returns 2 generations for a thinking + tool-use response:
     *   Gen 0 — plain `AssistantMessage` with `"signature"` metadata only (no thinking text)
     *   Gen 1 — `AnthropicAssistantMessage` with `"anthropicThinkingContents"` metadata
     *
     * The extractor must yield blocks only from Gen 1.
     */
    @Nested
    inner class TwoGenerationStructure {

        @Test
        fun `gen0 with signature-only metadata yields no blocks`() {
            val response = chatResponse(
                generation(text = "", extraMetadata = mapOf("signature" to "sig-legacy-gen0")),
            )

            assertTrue(AnthropicNativeThinkingExtractor.extract(response).isEmpty())
        }

        @Test
        fun `gen0 signature-only plus gen1 with anthropicThinkingContents yields one block from gen1`() {
            val gen0 = generation(text = "", extraMetadata = mapOf("signature" to "sig-gen0"))
            val gen1 = generation(
                text = """{"name":"July","temperature":91}""",
                thinkingContents = listOf(FakeThinkingContent("July is hottest...", "sig-gen1")),
            )
            val response = chatResponse(gen0, gen1)

            val blocks = AnthropicNativeThinkingExtractor.extract(response)

            assertEquals(1, blocks.size)
            assertEquals("July is hottest...", blocks[0].content)
            assertEquals("sig-gen1", blocks[0].metadata["signature"])
        }
    }
}
