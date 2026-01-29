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
import org.springframework.ai.chat.messages.AssistantMessage as SpringAiAssistantMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt

/**
 * Tests for [SpringAiLlmMessageSender].
 */
class SpringAiLlmMessageSenderTest {

    /**
     * Tests for Bedrock-specific behavior where multiple generations may be returned.
     *
     * See: https://github.com/embabel/embabel-agent/issues/1350
     *
     * Bedrock can return multiple generations in a single response:
     * - First generation: Empty content, no tool calls
     * - Second generation: Has tool calls
     *
     * The message sender must find the generation with tool calls, not just use the first one.
     */
    @Nested
    inner class BedrockMultipleGenerationsTests {

        @Test
        fun `extracts tool calls from second generation when first is empty`() {
            // Arrange: Bedrock returns 2 generations - first empty, second with tool calls
            val emptyGeneration = Generation(
                SpringAiAssistantMessage("")
            )

            val toolCalls = listOf(
                SpringAiAssistantMessage.ToolCall(
                    "tooluse_ZoD1qN0iQP6ph6t2DzbhdQ",
                    "function",
                    "getThirdPartyKeyData",
                    """{"thirdPartyId": "1"}"""
                )
            )
            val generationWithToolCalls = Generation(
                SpringAiAssistantMessage.builder()
                    .content("")
                    .toolCalls(toolCalls)
                    .build()
            )

            val mockMetadata = mockk<ChatResponseMetadata> {
                every { usage } returns null
            }
            val chatResponse = mockk<ChatResponse> {
                every { result } returns emptyGeneration // First generation is empty
                every { results } returns listOf(emptyGeneration, generationWithToolCalls)
                every { metadata } returns mockMetadata
            }

            val chatModel = mockk<ChatModel> {
                every { call(any<Prompt>()) } returns chatResponse
            }

            val chatOptions = mockk<ChatOptions> {
                every { model } returns "test-model"
                every { temperature } returns null
                every { maxTokens } returns null
                every { topP } returns null
                every { topK } returns null
                every { frequencyPenalty } returns null
                every { presencePenalty } returns null
                every { stopSequences } returns null
            }

            val sender = SpringAiLlmMessageSender(chatModel, chatOptions)

            // Act
            val response = sender.call(
                messages = listOf(UserMessage("Test")),
                tools = emptyList()
            )

            // Assert: Should find the tool calls from the second generation
            assertThat(response.message).isInstanceOf(AssistantMessageWithToolCalls::class.java)
            val messageWithCalls = response.message as AssistantMessageWithToolCalls
            assertThat(messageWithCalls.toolCalls).hasSize(1)
            assertThat(messageWithCalls.toolCalls[0].name).isEqualTo("getThirdPartyKeyData")
        }

        @Test
        fun `extracts multiple tool calls from second generation`() {
            // Arrange: Bedrock returns multiple tool calls in second generation
            val emptyGeneration = Generation(
                SpringAiAssistantMessage("")
            )

            val toolCalls = listOf(
                SpringAiAssistantMessage.ToolCall(
                    "tooluse_1",
                    "function",
                    "getThirdPartyKeyData",
                    """{"thirdPartyId": "1"}"""
                ),
                SpringAiAssistantMessage.ToolCall(
                    "tooluse_2",
                    "function",
                    "getThirdPartyScopeInformation",
                    """{"thirdPartyId": "1"}"""
                ),
                SpringAiAssistantMessage.ToolCall(
                    "tooluse_3",
                    "function",
                    "getThirdPartyStatusInformation",
                    """{"thirdPartyId": "1"}"""
                )
            )
            val generationWithToolCalls = Generation(
                SpringAiAssistantMessage.builder()
                    .content("")
                    .toolCalls(toolCalls)
                    .build()
            )

            val mockMetadata = mockk<ChatResponseMetadata> {
                every { usage } returns null
            }
            val chatResponse = mockk<ChatResponse> {
                every { result } returns emptyGeneration
                every { results } returns listOf(emptyGeneration, generationWithToolCalls)
                every { metadata } returns mockMetadata
            }

            val chatModel = mockk<ChatModel> {
                every { call(any<Prompt>()) } returns chatResponse
            }

            val chatOptions = mockk<ChatOptions> {
                every { model } returns "test-model"
                every { temperature } returns null
                every { maxTokens } returns null
                every { topP } returns null
                every { topK } returns null
                every { frequencyPenalty } returns null
                every { presencePenalty } returns null
                every { stopSequences } returns null
            }

            val sender = SpringAiLlmMessageSender(chatModel, chatOptions)

            // Act
            val response = sender.call(
                messages = listOf(UserMessage("Test")),
                tools = emptyList()
            )

            // Assert: Should find all 3 tool calls
            assertThat(response.message).isInstanceOf(AssistantMessageWithToolCalls::class.java)
            val messageWithCalls = response.message as AssistantMessageWithToolCalls
            assertThat(messageWithCalls.toolCalls).hasSize(3)
            assertThat(messageWithCalls.toolCalls.map { it.name }).containsExactly(
                "getThirdPartyKeyData",
                "getThirdPartyScopeInformation",
                "getThirdPartyStatusInformation"
            )
        }

        @Test
        fun `works correctly with single generation containing tool calls`() {
            // Normal case: single generation with tool calls (OpenAI, Anthropic behavior)
            val toolCalls = listOf(
                SpringAiAssistantMessage.ToolCall(
                    "call-1",
                    "function",
                    "get_weather",
                    """{"location": "NYC"}"""
                )
            )
            val generation = Generation(
                SpringAiAssistantMessage.builder()
                    .content("Let me check that for you")
                    .toolCalls(toolCalls)
                    .build()
            )

            val mockMetadata = mockk<ChatResponseMetadata> {
                every { usage } returns null
            }
            val chatResponse = mockk<ChatResponse> {
                every { result } returns generation
                every { results } returns listOf(generation)
                every { metadata } returns mockMetadata
            }

            val chatModel = mockk<ChatModel> {
                every { call(any<Prompt>()) } returns chatResponse
            }

            val chatOptions = mockk<ChatOptions> {
                every { model } returns "test-model"
                every { temperature } returns null
                every { maxTokens } returns null
                every { topP } returns null
                every { topK } returns null
                every { frequencyPenalty } returns null
                every { presencePenalty } returns null
                every { stopSequences } returns null
            }

            val sender = SpringAiLlmMessageSender(chatModel, chatOptions)

            // Act
            val response = sender.call(
                messages = listOf(UserMessage("What's the weather?")),
                tools = emptyList()
            )

            // Assert
            assertThat(response.message).isInstanceOf(AssistantMessageWithToolCalls::class.java)
            val messageWithCalls = response.message as AssistantMessageWithToolCalls
            assertThat(messageWithCalls.textContent).isEqualTo("Let me check that for you")
            assertThat(messageWithCalls.toolCalls).hasSize(1)
            assertThat(messageWithCalls.toolCalls[0].name).isEqualTo("get_weather")
        }

        @Test
        fun `merges text from first generation with tool calls from second`() {
            // Edge case: text in first generation, tool calls in second
            // Should not lose the text content
            val textOnlyGeneration = Generation(
                SpringAiAssistantMessage.builder()
                    .content("I'll help you with that request.")
                    .build()
            )

            val toolCalls = listOf(
                SpringAiAssistantMessage.ToolCall(
                    "call-1",
                    "function",
                    "search_database",
                    """{"query": "test"}"""
                )
            )
            val toolCallsGeneration = Generation(
                SpringAiAssistantMessage.builder()
                    .content("")
                    .toolCalls(toolCalls)
                    .build()
            )

            val mockMetadata = mockk<ChatResponseMetadata> {
                every { usage } returns null
            }
            val chatResponse = mockk<ChatResponse> {
                every { result } returns textOnlyGeneration
                every { results } returns listOf(textOnlyGeneration, toolCallsGeneration)
                every { metadata } returns mockMetadata
            }

            val chatModel = mockk<ChatModel> {
                every { call(any<Prompt>()) } returns chatResponse
            }

            val chatOptions = mockk<ChatOptions> {
                every { model } returns "test-model"
                every { temperature } returns null
                every { maxTokens } returns null
                every { topP } returns null
                every { topK } returns null
                every { frequencyPenalty } returns null
                every { presencePenalty } returns null
                every { stopSequences } returns null
            }

            val sender = SpringAiLlmMessageSender(chatModel, chatOptions)

            // Act
            val response = sender.call(
                messages = listOf(UserMessage("Search for something")),
                tools = emptyList()
            )

            // Assert: Should have both text from gen1 AND tool calls from gen2
            assertThat(response.message).isInstanceOf(AssistantMessageWithToolCalls::class.java)
            val messageWithCalls = response.message as AssistantMessageWithToolCalls
            assertThat(messageWithCalls.textContent).isEqualTo("I'll help you with that request.")
            assertThat(messageWithCalls.toolCalls).hasSize(1)
            assertThat(messageWithCalls.toolCalls[0].name).isEqualTo("search_database")
        }

        @Test
        fun `merges tool calls from multiple generations`() {
            // Edge case: tool calls split across multiple generations
            // Should collect all tool calls
            val toolCalls1 = listOf(
                SpringAiAssistantMessage.ToolCall("call-1", "function", "get_weather", """{"location": "NYC"}""")
            )
            val generation1 = Generation(
                SpringAiAssistantMessage.builder()
                    .content("Checking weather...")
                    .toolCalls(toolCalls1)
                    .build()
            )

            val toolCalls2 = listOf(
                SpringAiAssistantMessage.ToolCall("call-2", "function", "get_time", """{"timezone": "EST"}""")
            )
            val generation2 = Generation(
                SpringAiAssistantMessage.builder()
                    .content("And time...")
                    .toolCalls(toolCalls2)
                    .build()
            )

            val mockMetadata = mockk<ChatResponseMetadata> {
                every { usage } returns null
            }
            val chatResponse = mockk<ChatResponse> {
                every { result } returns generation1
                every { results } returns listOf(generation1, generation2)
                every { metadata } returns mockMetadata
            }

            val chatModel = mockk<ChatModel> {
                every { call(any<Prompt>()) } returns chatResponse
            }

            val chatOptions = mockk<ChatOptions> {
                every { model } returns "test-model"
                every { temperature } returns null
                every { maxTokens } returns null
                every { topP } returns null
                every { topK } returns null
                every { frequencyPenalty } returns null
                every { presencePenalty } returns null
                every { stopSequences } returns null
            }

            val sender = SpringAiLlmMessageSender(chatModel, chatOptions)

            // Act
            val response = sender.call(
                messages = listOf(UserMessage("What's the weather and time?")),
                tools = emptyList()
            )

            // Assert: Should have all tool calls from both generations and merged text
            assertThat(response.message).isInstanceOf(AssistantMessageWithToolCalls::class.java)
            val messageWithCalls = response.message as AssistantMessageWithToolCalls
            assertThat(messageWithCalls.toolCalls).hasSize(2)
            assertThat(messageWithCalls.toolCalls.map { it.name }).containsExactly("get_weather", "get_time")
            assertThat(messageWithCalls.textContent).contains("Checking weather...")
            assertThat(messageWithCalls.textContent).contains("And time...")
        }
    }
}
