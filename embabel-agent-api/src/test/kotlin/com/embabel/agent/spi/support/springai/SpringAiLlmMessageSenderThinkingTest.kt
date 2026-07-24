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

import com.embabel.chat.AssistantMessageWithToolCalls
import com.embabel.chat.UserMessage
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt

class SpringAiLlmMessageSenderThinkingTest {

    @Nested
    inner class ProviderMetadata {

        @Test
        fun `extracts OpenAI reasoning content without changing final text`() {
            val response = call(
                generation(
                    text = "final answer",
                    properties = mapOf("reasoningContent" to "openai reasoning"),
                )
            )

            assertThat(response.textContent).isEqualTo("final answer")
            assertThat(response.thinkingContent).containsExactly("openai reasoning")
        }

        @Test
        fun `deduplicates Ollama thinking exposed in message and generation metadata`() {
            val response = call(
                generation(
                    text = "final answer",
                    properties = mapOf("thinking" to "ollama reasoning"),
                    metadata = mapOf("thinking" to "ollama reasoning"),
                )
            )

            assertThat(response.textContent).isEqualTo("final answer")
            assertThat(response.thinkingContent).containsExactly("ollama reasoning")
        }

        @Test
        fun `ignores non-string thinking generation metadata`() {
            val response = call(
                generation(
                    text = "final answer",
                    metadata = mapOf("thinking" to true),
                )
            )

            assertThat(response.textContent).isEqualTo("final answer")
            assertThat(response.thinkingContent).isEmpty()
        }

        @Test
        fun `separates Anthropic blocking thinking generation from final output`() {
            val response = call(
                generation(
                    text = "anthropic reasoning",
                    properties = mapOf("signature" to "opaque-signature"),
                ),
                generation(text = "final answer"),
            )

            assertThat(response.textContent).isEqualTo("final answer")
            assertThat(response.thinkingContent).containsExactly("anthropic reasoning")
        }

        @Test
        fun `preserves equal thinking blocks from separate generations`() {
            val response = call(
                generation(
                    text = "repeated reasoning",
                    properties = mapOf("signature" to "first-signature"),
                ),
                generation(
                    text = "repeated reasoning",
                    properties = mapOf("signature" to "second-signature"),
                ),
                generation(text = "final answer"),
            )

            assertThat(response.thinkingContent)
                .containsExactly("repeated reasoning", "repeated reasoning")
        }

        @Test
        fun `forwards Anthropic redacted thinking data without using it as final text`() {
            val response = call(
                generation(
                    text = "",
                    properties = mapOf("data" to "encrypted-thinking-data"),
                ),
                generation(text = "final answer"),
            )

            assertThat(response.textContent).isEqualTo("final answer")
            assertThat(response.thinkingContent).containsExactly("encrypted-thinking-data")
        }

        @Test
        fun `does not merge Anthropic thinking into tool call text`() {
            val toolCall = AssistantMessage.ToolCall("call-1", "function", "lookup", "{}")
            val response = call(
                generation(
                    text = "private reasoning",
                    properties = mapOf("signature" to "opaque-signature"),
                ),
                generation(
                    text = "calling lookup",
                    toolCalls = listOf(toolCall),
                ),
            )

            assertThat(response.textContent).isEqualTo("calling lookup")
            assertThat(response.thinkingContent).containsExactly("private reasoning")
            assertThat(response.message).isInstanceOf(AssistantMessageWithToolCalls::class.java)
        }
    }

    private fun call(vararg generations: Generation) = SpringAiLlmMessageSender(
        chatModel = mockk<ChatModel> {
            every { call(any<Prompt>()) } returns ChatResponse(generations.toList())
        },
        chatOptions = mockk<ChatOptions>(relaxed = true),
    ).call(
        messages = listOf(UserMessage("question")),
        tools = emptyList(),
    )

    private fun generation(
        text: String,
        properties: Map<String, Any> = emptyMap(),
        metadata: Map<String, Any> = emptyMap(),
        toolCalls: List<AssistantMessage.ToolCall> = emptyList(),
    ): Generation = Generation(
        AssistantMessage.builder()
            .content(text)
            .properties(properties)
            .toolCalls(toolCalls)
            .build(),
        ChatGenerationMetadata.builder().metadata(metadata).build(),
    )
}
