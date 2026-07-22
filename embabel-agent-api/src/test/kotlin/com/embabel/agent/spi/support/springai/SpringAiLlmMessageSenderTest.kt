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

import com.embabel.agent.spi.loop.LlmMessageRequest
import com.embabel.agent.spi.loop.NativeStructuredOutputRequest
import com.embabel.agent.spi.loop.StructuredOutputRequest
import com.embabel.chat.AssistantMessageWithToolCalls
import com.embabel.chat.UserMessage
import com.embabel.common.ai.autoconfig.NativeStructuredOutputCapability
import com.embabel.common.ai.autoconfig.NativeSupport
import com.embabel.common.ai.model.NativeStructuredOutputMode
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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

    @Nested
    inner class SpringAiNativeStructuredOutputConfigurerTests {

        @Test
        fun `applies configured chat options from structured output configurer`() {
            val originalOptions = testChatOptions()
            val configuredOptions = testChatOptions()
            val capturedPrompt = slot<Prompt>()
            val generation = Generation(SpringAiAssistantMessage("done"))
            val mockMetadata = mockk<ChatResponseMetadata> {
                every { usage } returns null
            }
            val chatResponse = mockk<ChatResponse> {
                every { result } returns generation
                every { results } returns listOf(generation)
                every { metadata } returns mockMetadata
            }
            val chatModel = mockk<ChatModel> {
                every { call(capture(capturedPrompt)) } returns chatResponse
            }
            val structuredOutputRequest = StructuredOutputRequest(
                name = "Answer",
                schema = """{"type":"object"}""",
            )
            var configurerSawStructuredOutput: StructuredOutputRequest? = null
            var configurerSawNativeSupport: NativeSupport? = null
            val nativeSupport = NativeSupport(
                structuredOutput = NativeStructuredOutputCapability(
                    supported = true,
                    strategy = "response_format",
                )
            )
            val sender = SpringAiLlmMessageSender(
                chatModel = chatModel,
                chatOptions = originalOptions,
                nativeStructuredOutputConfigurer = SpringAiNativeStructuredOutputConfigurer { _, request, support, _ ->
                    configurerSawStructuredOutput = request
                    configurerSawNativeSupport = support
                    configuredOptions
                },
                nativeSupport = nativeSupport,
            )

            sender.call(
                LlmMessageRequest(
                    messages = listOf(UserMessage("Create answer")),
                    tools = emptyList(),
                    nativeStructuredOutputRequest = NativeStructuredOutputRequest(
                        structuredOutputRequest = structuredOutputRequest,
                    ),
                )
            )

            assertThat(configurerSawStructuredOutput).isEqualTo(structuredOutputRequest)
            assertThat(configurerSawNativeSupport).isEqualTo(nativeSupport)
            assertThat(capturedPrompt.captured.options).isSameAs(configuredOptions)
        }

        @Test
        fun `disables native structured output when mode is disabled`() {
            val originalOptions = testChatOptions()
            val configuredOptions = testChatOptions()
            val capturedPrompt = slot<Prompt>()
            val generation = Generation(SpringAiAssistantMessage("done"))
            val mockMetadata = mockk<ChatResponseMetadata> {
                every { usage } returns null
            }
            val chatResponse = mockk<ChatResponse> {
                every { result } returns generation
                every { results } returns listOf(generation)
                every { metadata } returns mockMetadata
            }
            val chatModel = mockk<ChatModel> {
                every { call(capture(capturedPrompt)) } returns chatResponse
            }
            var configurerSawStructuredOutput: StructuredOutputRequest? = null
            val nativeSupport = NativeSupport(
                structuredOutput = NativeStructuredOutputCapability(
                    supported = true,
                    strategy = "response_format",
                )
            )
            val sender = SpringAiLlmMessageSender(
                chatModel = chatModel,
                chatOptions = originalOptions,
                nativeStructuredOutputConfigurer = SpringAiNativeStructuredOutputConfigurer { _, request, _, _ ->
                    configurerSawStructuredOutput = request
                    configuredOptions
                },
                nativeSupport = nativeSupport,
            )

            sender.call(
                LlmMessageRequest(
                    messages = listOf(UserMessage("Create answer")),
                    tools = emptyList(),
                    nativeStructuredOutputRequest = NativeStructuredOutputRequest(
                        structuredOutputRequest = StructuredOutputRequest(
                            name = "Answer",
                            schema = """{"type":"object"}""",
                        ),
                        nativeStructuredOutputMode = NativeStructuredOutputMode.DISABLED,
                    ),
                )
            )

            assertThat(configurerSawStructuredOutput).isNull()
            assertThat(capturedPrompt.captured.options).isSameAs(configuredOptions)
        }

        @Test
        fun `disables native structured output when schema is incompatible`() {
            val originalOptions = testChatOptions()
            val capturedPrompt = slot<Prompt>()
            val generation = Generation(SpringAiAssistantMessage("done"))
            val mockMetadata = mockk<ChatResponseMetadata> {
                every { usage } returns null
            }
            val chatResponse = mockk<ChatResponse> {
                every { result } returns generation
                every { results } returns listOf(generation)
                every { metadata } returns mockMetadata
            }
            val chatModel = mockk<ChatModel> {
                every { call(capture(capturedPrompt)) } returns chatResponse
            }
            var configurerSawStructuredOutput: StructuredOutputRequest? = null
            val nativeSupport = NativeSupport(
                structuredOutput = NativeStructuredOutputCapability(
                    supported = true,
                    strategy = "response_format",
                )
            )
            val sender = SpringAiLlmMessageSender(
                chatModel = chatModel,
                chatOptions = originalOptions,
                nativeStructuredOutputConfigurer = SpringAiNativeStructuredOutputConfigurer { options, request, _, _ ->
                    configurerSawStructuredOutput = request
                    if (request == null) options else testChatOptions()
                },
                nativeSupport = nativeSupport,
            )

            sender.call(
                LlmMessageRequest(
                    messages = listOf(UserMessage("Create answer")),
                    tools = emptyList(),
                    nativeStructuredOutputRequest = NativeStructuredOutputRequest(
                        structuredOutputRequest = StructuredOutputRequest(
                            name = "MonthItem",
                            schema = """{"type":"object","properties":{"name":{"type":"string"},"temperature":{"type":"integer"}},"additionalProperties":false}""",
                        ),
                        nativeStructuredOutputMode = NativeStructuredOutputMode.ENABLED,
                    ),
                )
            )

            assertThat(configurerSawStructuredOutput).isNull()
            assertThat(capturedPrompt.captured.options).isSameAs(originalOptions)
        }
    }

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
                every { usage } returns mockk(relaxed = true)
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
                every { usage } returns mockk(relaxed = true)
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
                every { usage } returns mockk(relaxed = true)
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
                every { usage } returns mockk(relaxed = true)
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
                every { usage } returns mockk(relaxed = true)
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

        @Test
        fun `merges metadata from multiple generations and preserves thoughtSignatures`() {
            val thoughtSignaturesA = listOf(byteArrayOf(1, 2))
            val thoughtSignaturesB = listOf(byteArrayOf(3, 4))
            val generation1 = Generation(
                SpringAiAssistantMessage.builder()
                    .content("First part")
                    .toolCalls(
                        listOf(
                            SpringAiAssistantMessage.ToolCall(
                                "call-1",
                                "function",
                                "get_weather",
                                """{"location": "NYC"}"""
                            )
                        )
                    )
                    .properties(mapOf("thoughtSignatures" to thoughtSignaturesA, "chunk" to "one"))
                    .build()
            )
            val generation2 = Generation(
                SpringAiAssistantMessage.builder()
                    .content("Second part")
                    .toolCalls(
                        listOf(
                            SpringAiAssistantMessage.ToolCall(
                                "call-2",
                                "function",
                                "get_time",
                                """{"timezone": "EST"}"""
                            )
                        )
                    )
                    .properties(mapOf("thoughtSignatures" to thoughtSignaturesB, "chunk" to "two"))
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

            val response = sender.call(
                messages = listOf(UserMessage("What's the weather and time?")),
                tools = emptyList()
            )

            assertThat(response.message).isInstanceOf(AssistantMessageWithToolCalls::class.java)
            val messageWithCalls = response.message as AssistantMessageWithToolCalls
            assertThat(messageWithCalls.metadata).containsEntry("chunk", "two")
            val signatures = messageWithCalls.metadata["thoughtSignatures"] as? List<*>
            assertThat(signatures).isNotNull
            assertThat(signatures).hasSize(1)
            assertThat(signatures!![0]).isInstanceOf(ByteArray::class.java)
            assertThat(signatures[0] as ByteArray).containsExactly(3, 4)
        }
    }

    /**
     * Google GenAI (and similar providers) emit one Spring AI generation per response part.
     * With includeThoughts enabled, thought parts arrive first (metadata isThought=true)
     * and the final answer is a later non-thought generation. Using only ChatResponse.result
     * would discard the structured answer and break createObject/thinking structured output.
     *
     * See commit 04a45394 (thought signatures / includeThoughts) and Spring AI
     * GoogleGenAiChatModel.responseCandidateToGeneration.
     */
    @Nested
    inner class GoogleGenAiThoughtAndAnswerGenerationsTests {

        @Test
        fun `uses non-thought generation for structured answer when first generation is thought`() {
            // Prepare
            val thoughtGeneration = Generation(
                SpringAiAssistantMessage.builder()
                    .content("Let me reason carefully about Florida climate...")
                    .properties(mapOf("isThought" to true, "candidateIndex" to 0))
                    .build()
            )
            val answerJson = """{"name":"July","temperature":91}"""
            val answerGeneration = Generation(
                SpringAiAssistantMessage.builder()
                    .content(answerJson)
                    .properties(mapOf("isThought" to false, "candidateIndex" to 0))
                    .build()
            )

            val mockMetadata = mockk<ChatResponseMetadata> {
                every { usage } returns mockk(relaxed = true)
            }
            val chatResponse = mockk<ChatResponse> {
                every { result } returns thoughtGeneration
                every { results } returns listOf(thoughtGeneration, answerGeneration)
                every { metadata } returns mockMetadata
            }
            val chatModel = mockk<ChatModel> {
                every { call(any<Prompt>()) } returns chatResponse
            }
            val sender = SpringAiLlmMessageSender(chatModel, testChatOptions())

            // Execute
            val response = sender.call(
                messages = listOf(UserMessage("Hottest month in Florida?")),
                tools = emptyList(),
            )

            // Verify
            assertThat(response.textContent).isEqualTo(answerJson)
            assertThat(response.textContent).doesNotContain("Let me reason carefully")
        }

        @Test
        fun `merges thought and answer so thinking extraction can see both`() {
            // Prepare
            val thoughtGeneration = Generation(
                SpringAiAssistantMessage.builder()
                    .content("Internal analysis of seasonal temperatures.")
                    .properties(mapOf("isThought" to true))
                    .build()
            )
            val answerJson = """{"name":"August","temperature":90}"""
            val answerGeneration = Generation(
                SpringAiAssistantMessage.builder()
                    .content(answerJson)
                    .properties(mapOf("isThought" to false))
                    .build()
            )

            val mockMetadata = mockk<ChatResponseMetadata> {
                every { usage } returns mockk(relaxed = true)
            }
            val chatResponse = mockk<ChatResponse> {
                every { result } returns thoughtGeneration
                every { results } returns listOf(thoughtGeneration, answerGeneration)
                every { metadata } returns mockMetadata
            }
            val chatModel = mockk<ChatModel> {
                every { call(any<Prompt>()) } returns chatResponse
            }
            // Prefer answer-only content for structured parse; do not require thought prefix.
            val sender = SpringAiLlmMessageSender(chatModel, testChatOptions())

            // Execute
            val response = sender.call(
                messages = listOf(UserMessage("Hottest month?")),
                tools = emptyList(),
            )

            // Verify
            assertThat(response.textContent).contains(answerJson)
            assertThat(response.textContent.trim()).isEqualTo(answerJson)
        }

        @Test
        fun `falls back to first non-empty generation when isThought metadata is absent`() {
            // Prepare
            val emptyGeneration = Generation(SpringAiAssistantMessage(""))
            val answerGeneration = Generation(
                SpringAiAssistantMessage.builder()
                    .content("""{"ok":true}""")
                    .build()
            )

            val mockMetadata = mockk<ChatResponseMetadata> {
                every { usage } returns mockk(relaxed = true)
            }
            val chatResponse = mockk<ChatResponse> {
                every { result } returns emptyGeneration
                every { results } returns listOf(emptyGeneration, answerGeneration)
                every { metadata } returns mockMetadata
            }
            val chatModel = mockk<ChatModel> {
                every { call(any<Prompt>()) } returns chatResponse
            }
            val sender = SpringAiLlmMessageSender(chatModel, testChatOptions())

            // Execute
            val response = sender.call(
                messages = listOf(UserMessage("status")),
                tools = emptyList(),
            )

            // Verify
            assertThat(response.textContent).isEqualTo("""{"ok":true}""")
        }

        @Test
        fun `returns thought text when only thought generations are present`() {
            // Prepare
            val thoughtGeneration = Generation(
                SpringAiAssistantMessage.builder()
                    .content("Still thinking, no final answer part yet.")
                    .properties(mapOf("isThought" to true))
                    .build()
            )
            val emptyAnswer = Generation(
                SpringAiAssistantMessage.builder()
                    .content("")
                    .properties(mapOf("isThought" to false))
                    .build()
            )

            val mockMetadata = mockk<ChatResponseMetadata> {
                every { usage } returns mockk(relaxed = true)
            }
            val chatResponse = mockk<ChatResponse> {
                every { result } returns thoughtGeneration
                every { results } returns listOf(thoughtGeneration, emptyAnswer)
                every { metadata } returns mockMetadata
            }
            val chatModel = mockk<ChatModel> {
                every { call(any<Prompt>()) } returns chatResponse
            }
            val sender = SpringAiLlmMessageSender(chatModel, testChatOptions())

            // Execute
            val response = sender.call(
                messages = listOf(UserMessage("question")),
                tools = emptyList(),
            )

            // Verify
            // Prefer non-thought when present; if none, fall back to any non-blank text (thought).
            assertThat(response.textContent).isEqualTo("Still thinking, no final answer part yet.")
        }

        @Test
        fun `uses first non-thought answer generation for structured safety`() {
            // Prepare
            val thoughtGeneration = Generation(
                SpringAiAssistantMessage.builder()
                    .content("reasoning")
                    .properties(mapOf("isThought" to true))
                    .build()
            )
            val answerPart1 = Generation(
                SpringAiAssistantMessage.builder()
                    .content("""{"name":"July"}""")
                    .properties(mapOf("isThought" to false))
                    .build()
            )
            val answerPart2 = Generation(
                SpringAiAssistantMessage.builder()
                    .content("""{"temperature":91}""")
                    .properties(mapOf("isThought" to false))
                    .build()
            )

            val mockMetadata = mockk<ChatResponseMetadata> {
                every { usage } returns mockk(relaxed = true)
            }
            val chatResponse = mockk<ChatResponse> {
                every { result } returns thoughtGeneration
                every { results } returns listOf(thoughtGeneration, answerPart1, answerPart2)
                every { metadata } returns mockMetadata
            }
            val chatModel = mockk<ChatModel> {
                every { call(any<Prompt>()) } returns chatResponse
            }
            val sender = SpringAiLlmMessageSender(chatModel, testChatOptions())

            // Execute
            val response = sender.call(
                messages = listOf(UserMessage("question")),
                tools = emptyList(),
            )

            // Verify
            assertThat(response.textContent).isEqualTo("""{"name":"July"}""")
            assertThat(response.textContent).doesNotContain("reasoning")
            assertThat(response.textContent).doesNotContain("temperature")
        }

        @Test
        fun `prefers non-thought text when multi-gen includes tools and thought parts`() {
            // Prepare
            val thoughtGeneration = Generation(
                SpringAiAssistantMessage.builder()
                    .content("I should call a tool")
                    .properties(mapOf("isThought" to true, "thoughtSignatures" to listOf(byteArrayOf(1))))
                    .build()
            )
            val toolGeneration = Generation(
                SpringAiAssistantMessage.builder()
                    .content("""{"status":"calling"}""")
                    .toolCalls(
                        listOf(
                            SpringAiAssistantMessage.ToolCall(
                                "call-1",
                                "function",
                                "lookup_climate",
                                """{"region":"Florida"}""",
                            )
                        )
                    )
                    .properties(
                        mapOf(
                            "isThought" to false,
                            "thoughtSignatures" to listOf(byteArrayOf(9, 9)),
                        )
                    )
                    .build()
            )

            val mockMetadata = mockk<ChatResponseMetadata> {
                every { usage } returns mockk(relaxed = true)
            }
            val chatResponse = mockk<ChatResponse> {
                every { result } returns thoughtGeneration
                every { results } returns listOf(thoughtGeneration, toolGeneration)
                every { metadata } returns mockMetadata
            }
            val chatModel = mockk<ChatModel> {
                every { call(any<Prompt>()) } returns chatResponse
            }
            val sender = SpringAiLlmMessageSender(chatModel, testChatOptions())

            // Execute
            val response = sender.call(
                messages = listOf(UserMessage("climate?")),
                tools = emptyList(),
            )

            // Verify
            assertThat(response.message).isInstanceOf(AssistantMessageWithToolCalls::class.java)
            val withTools = response.message as AssistantMessageWithToolCalls
            assertThat(withTools.toolCalls).hasSize(1)
            assertThat(withTools.toolCalls[0].name).isEqualTo("lookup_climate")
            // Non-thought answer text is preferred over thought prose for structured conversion.
            assertThat(response.textContent).isEqualTo("""{"status":"calling"}""")
            // Metadata map merge is last-wins for thoughtSignatures (documented current contract).
            val signatures = withTools.metadata["thoughtSignatures"] as? List<*>
            assertThat(signatures).isNotNull
            assertThat(signatures!![0] as ByteArray).containsExactly(9, 9)
        }
    }

    /**
     * Regression tests for multi-generation ChatResponse handling after Google GenAI
     * thought-signature support (#1691) and non-thought answer selection (#1813).
     */
    @Nested
    inner class MultiGenerationRegressionTests {

        @Test
        fun `n-best or multi non-thought texts use first non-thought only for structured safety`() {
            // Prepare
            // Avoid joining two JSON payloads with newline (breaks Jackson).
            // Prefer first non-blank non-thought generation when no isThought=true parts exist.
            val candidateA = Generation(
                SpringAiAssistantMessage.builder()
                    .content("""{"name":"July","temperature":91}""")
                    .build()
            )
            val candidateB = Generation(
                SpringAiAssistantMessage.builder()
                    .content("""{"name":"August","temperature":90}""")
                    .build()
            )
            val sender = senderFor(listOf(candidateA, candidateB), resultGeneration = candidateA)

            // Execute
            val response = sender.call(
                messages = listOf(UserMessage("hottest month?")),
                tools = emptyList(),
            )

            // Verify
            assertThat(response.textContent)
                .describedAs("multi non-thought generations must not be newline-joined for structured bind safety")
                .isEqualTo("""{"name":"July","temperature":91}""")
            assertThat(response.textContent).doesNotContain("August")
        }

        @Test
        fun `isThought string true is treated as thought not answer`() {
            // Prepare
            // Robust against adapters that store isThought as String "true".
            val thoughtGeneration = Generation(
                SpringAiAssistantMessage.builder()
                    .content("string-typed thought prose only")
                    .properties(mapOf("isThought" to "true"))
                    .build()
            )
            val answerJson = """{"ok":true}"""
            val answerGeneration = Generation(
                SpringAiAssistantMessage.builder()
                    .content(answerJson)
                    .properties(mapOf("isThought" to "false"))
                    .build()
            )
            val sender = senderFor(listOf(thoughtGeneration, answerGeneration), resultGeneration = thoughtGeneration)

            // Execute
            val response = sender.call(
                messages = listOf(UserMessage("q")),
                tools = emptyList(),
            )

            // Verify
            assertThat(response.textContent)
                .describedAs("isThought=\"true\" (String) must not be treated as answer text")
                .isEqualTo(answerJson)
            assertThat(response.textContent).doesNotContain("string-typed thought")
        }

        @Test
        fun `isThought string true with surrounding whitespace is treated as thought not answer`() {
            // Prepare
            val thoughtGeneration = Generation(
                SpringAiAssistantMessage.builder()
                    .content("whitespace-padded thought prose")
                    .properties(mapOf("isThought" to " TRUE "))
                    .build()
            )
            val answerJson = """{"ok":true}"""
            val answerGeneration = Generation(
                SpringAiAssistantMessage.builder()
                    .content(answerJson)
                    .properties(mapOf("isThought" to "false"))
                    .build()
            )
            val sender = senderFor(listOf(thoughtGeneration, answerGeneration), resultGeneration = thoughtGeneration)

            // Execute
            val response = sender.call(
                messages = listOf(UserMessage("q")),
                tools = emptyList(),
            )

            // Verify
            assertThat(response.textContent).isEqualTo(answerJson)
            assertThat(response.textContent).doesNotContain("whitespace-padded thought")
        }

        @Test
        fun `thought then empty non-thought then JSON selects JSON only`() {
            // Prepare
            val thoughtGeneration = Generation(
                SpringAiAssistantMessage.builder()
                    .content("reasoning about climate")
                    .properties(mapOf("isThought" to true))
                    .build()
            )
            val emptyNonThought = Generation(
                SpringAiAssistantMessage.builder()
                    .content("   ")
                    .properties(mapOf("isThought" to false))
                    .build()
            )
            val answerJson = """{"name":"July","temperature":91}"""
            val answerGeneration = Generation(
                SpringAiAssistantMessage.builder()
                    .content(answerJson)
                    .properties(mapOf("isThought" to false))
                    .build()
            )
            val sender = senderFor(
                listOf(thoughtGeneration, emptyNonThought, answerGeneration),
                resultGeneration = thoughtGeneration,
            )

            // Execute
            val response = sender.call(
                messages = listOf(UserMessage("q")),
                tools = emptyList(),
            )

            // Verify
            assertThat(response.textContent).isEqualTo(answerJson)
            assertThat(response.textContent).doesNotContain("reasoning")
        }

        @Test
        fun `thought prose containing JSON-like snippets does not leak into structured answer text`() {
            // Prepare
            val thoughtGeneration = Generation(
                SpringAiAssistantMessage.builder()
                    .content("""Maybe the answer shape is {"name":"Fake","temperature":0}.""")
                    .properties(mapOf("isThought" to true))
                    .build()
            )
            val answerJson = """{"name":"July","temperature":91}"""
            val answerGeneration = Generation(
                SpringAiAssistantMessage.builder()
                    .content(answerJson)
                    .properties(mapOf("isThought" to false))
                    .build()
            )
            val sender = senderFor(listOf(thoughtGeneration, answerGeneration), resultGeneration = thoughtGeneration)

            // Execute
            val response = sender.call(
                messages = listOf(UserMessage("q")),
                tools = emptyList(),
            )

            // Verify
            assertThat(response.textContent).isEqualTo(answerJson)
            assertThat(response.textContent).doesNotContain("Fake")
        }

        @Test
        fun `empty ChatResponse results fail fast with clear error`() {
            // Prepare
            val mockMetadata = mockk<ChatResponseMetadata>(relaxed = true)
            val chatResponse = mockk<ChatResponse> {
                every { result } returns null
                every { results } returns emptyList()
                every { metadata } returns mockMetadata
            }
            val chatModel = mockk<ChatModel> {
                every { call(any<Prompt>()) } returns chatResponse
            }
            val sender = SpringAiLlmMessageSender(chatModel, testChatOptions())

            // Execute
            val thrown = assertThatThrownBy {
                sender.call(messages = listOf(UserMessage("q")), tools = emptyList())
            }

            // Verify
            thrown
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("no generations")
        }

        @Test
        fun `thoughtSignatures from thought gen survive when answer gen is selected without tools`() {
            // Prepare
            // Selecting non-thought answer text must not drop Google thoughtSignatures
            // needed for later tool turns (metadata merge across multi-part response).
            val signatures = listOf(byteArrayOf(1, 2, 3, 4))
            val thoughtGeneration = Generation(
                SpringAiAssistantMessage.builder()
                    .content("internal thought")
                    .properties(
                        mapOf(
                            "isThought" to true,
                            "thoughtSignatures" to signatures,
                        )
                    )
                    .build()
            )
            val answerJson = """{"name":"July"}"""
            val answerGeneration = Generation(
                SpringAiAssistantMessage.builder()
                    .content(answerJson)
                    .properties(mapOf("isThought" to false))
                    .build()
            )
            val sender = senderFor(listOf(thoughtGeneration, answerGeneration), resultGeneration = thoughtGeneration)

            // Execute
            val response = sender.call(messages = listOf(UserMessage("q")),tools = emptyList(),)

            // Verify
            assertThat(response.textContent).isEqualTo(answerJson)

            // After toEmbabelMessage, signatures must still be reachable for Google continuation.
            val embabelMeta = when (val msg = response.message) {
                is AssistantMessageWithToolCalls -> msg.metadata
                else -> emptyMap()
            }
            val preserved = embabelMeta["thoughtSignatures"] as? List<*>
            assertThat(preserved)
                .describedAs("thoughtSignatures from thought parts must survive answer selection without tools")
                .isNotNull
            assertThat(preserved!![0] as ByteArray).containsExactly(1, 2, 3, 4)
        }

        @Test
        fun `duplicate thoughtSignatures use last generation metadata`() {
            // Prepare
            val thoughtGeneration = Generation(
                SpringAiAssistantMessage.builder()
                    .content("internal thought")
                    .properties(
                        mapOf(
                            "isThought" to true,
                            "thoughtSignatures" to listOf(byteArrayOf(1, 1)),
                        )
                    )
                    .build()
            )
            val answerGeneration = Generation(
                SpringAiAssistantMessage.builder()
                    .content("""{"name":"July"}""")
                    .properties(
                        mapOf(
                            "isThought" to false,
                            "thoughtSignatures" to listOf(byteArrayOf(2, 2)),
                        )
                    )
                    .build()
            )
            val sender = senderFor(listOf(thoughtGeneration, answerGeneration), resultGeneration = thoughtGeneration)

            // Execute
            val response = sender.call(
                messages = listOf(UserMessage("q")),
                tools = emptyList(),
            )

            // Verify
            val embabelMeta = when (val msg = response.message) {
                is AssistantMessageWithToolCalls -> msg.metadata
                else -> emptyMap()
            }
            val preserved = embabelMeta["thoughtSignatures"] as? List<*>
            assertThat(preserved).isNotNull
            assertThat(preserved!![0] as ByteArray).containsExactly(2, 2)
        }

        @Test
        fun `tools on thought-marked gen still surface tool calls with non-thought answer text preferred`() {
            // Prepare
            val thoughtWithTools = Generation(
                SpringAiAssistantMessage.builder()
                    .content("I will call a tool")
                    .toolCalls(
                        listOf(
                            SpringAiAssistantMessage.ToolCall(
                                "call-thought",
                                "function",
                                "from_thought_part",
                                "{}",
                            )
                        )
                    )
                    .properties(mapOf("isThought" to true))
                    .build()
            )
            val answerGeneration = Generation(
                SpringAiAssistantMessage.builder()
                    .content("""{"phase":"answer"}""")
                    .properties(mapOf("isThought" to false))
                    .build()
            )
            val sender = senderFor(listOf(thoughtWithTools, answerGeneration), resultGeneration = thoughtWithTools)

            // Execute
            val response = sender.call(messages = listOf(UserMessage("q")), tools = emptyList(),)

            // Verify
            assertThat(response.message).isInstanceOf(AssistantMessageWithToolCalls::class.java)

            val withTools = response.message as AssistantMessageWithToolCalls
            assertThat(withTools.toolCalls.map { it.name }).contains("from_thought_part")
            assertThat(response.textContent)
                .describedAs("answer text should prefer non-thought generation even when tools sit on thought gen")
                .isEqualTo("""{"phase":"answer"}""")
        }

        @Test
        fun `tool calls survive when only available text is thought prose`() {
            // Prepare
            val thoughtWithTools = Generation(
                SpringAiAssistantMessage.builder()
                    .content("I should call a tool and not expose this thought")
                    .toolCalls(
                        listOf(
                            SpringAiAssistantMessage.ToolCall(
                                "call-thought-only",
                                "function",
                                "from_thought_only_part",
                                "{}",
                            )
                        )
                    )
                    .properties(mapOf("isThought" to true))
                    .build()
            )
            val sender = senderFor(listOf(thoughtWithTools), resultGeneration = thoughtWithTools)

            // Execute
            val response = sender.call(
                messages = listOf(UserMessage("q")),
                tools = emptyList(),
            )

            // Verify
            assertThat(response.message).isInstanceOf(AssistantMessageWithToolCalls::class.java)

            val withTools = response.message as AssistantMessageWithToolCalls
            assertThat(withTools.toolCalls.map { it.name }).contains("from_thought_only_part")
            assertThat(response.textContent).isBlank()
        }

        /**
         * Build a sender backed by a mocked [ChatModel] that returns exactly the supplied
         * generations. This keeps each regression test focused on response resolution policy
         * instead of repeating the same Spring AI mock setup.
         */
        private fun senderFor(
            generations: List<Generation>,
            resultGeneration: Generation,
        ): SpringAiLlmMessageSender {
            val mockMetadata = mockk<ChatResponseMetadata> {
                every { usage } returns mockk(relaxed = true)
            }
            val chatResponse = mockk<ChatResponse> {
                every { result } returns resultGeneration
                every { results } returns generations
                every { metadata } returns mockMetadata
            }
            val chatModel = mockk<ChatModel> {
                every { call(any<Prompt>()) } returns chatResponse
            }
            return SpringAiLlmMessageSender(chatModel, testChatOptions())
        }
    }

    private fun testChatOptions(): ChatOptions = mockk {
        every { model } returns "test-model"
        every { temperature } returns null
        every { maxTokens } returns null
        every { topP } returns null
        every { topK } returns null
        every { frequencyPenalty } returns null
        every { presencePenalty } returns null
        every { stopSequences } returns null
    }
}
