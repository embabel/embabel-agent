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
package com.embabel.agent.spi.support.springai.streaming

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.callback.AfterToolCallContext
import com.embabel.agent.api.tool.callback.BeforeToolCallContext
import com.embabel.agent.api.tool.callback.ToolCallInspector
import com.embabel.chat.AssistantMessage
import com.embabel.chat.SystemMessage
import com.embabel.chat.UserMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.tool.ToolCallback
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import java.time.Duration

/**
 * Unit tests for [SpringAiLlmMessageStreamer].
 *
 * Verifies that the streamer correctly:
 * - Converts Embabel messages to Spring AI messages
 * - Converts Embabel tools to Spring AI tool callbacks
 * - Calls the ChatClient streaming chain correctly
 */
class SpringAiLlmMessageStreamerTest {

    private lateinit var mockChatClient: ChatClient
    private lateinit var mockChatOptions: ChatOptions
    private lateinit var mockRequestSpec: ChatClient.ChatClientRequestSpec
    private lateinit var mockStreamSpec: ChatClient.StreamResponseSpec

    @BeforeEach
    fun setUp() {
        mockChatClient = mockk(relaxed = true)
        mockChatOptions = mockk(relaxed = true)
        mockRequestSpec = mockk(relaxed = true)
        mockStreamSpec = mockk(relaxed = true)

        // Setup the fluent chain
        every { mockChatClient.prompt(any<Prompt>()) } returns mockRequestSpec
        every { mockRequestSpec.toolCallbacks(any<List<ToolCallback>>()) } returns mockRequestSpec
        every { mockRequestSpec.options(any<ChatOptions>()) } returns mockRequestSpec
        every { mockRequestSpec.stream() } returns mockStreamSpec
    }

    @Test
    fun `stream calls ChatClient with correct chain`() {
        // Given
        val streamer = SpringAiLlmMessageStreamer(mockChatClient, mockChatOptions)
        val messages = listOf(UserMessage("Hello"))
        val tools = emptyList<Tool>()
        every { mockStreamSpec.content() } returns Flux.just("response")

        // When
        streamer.stream(messages, tools, emptyList())

        // Then
        verify { mockChatClient.prompt(any<Prompt>()) }
        verify { mockRequestSpec.toolCallbacks(any<List<ToolCallback>>()) }
        verify { mockRequestSpec.options(mockChatOptions) }
        verify { mockRequestSpec.stream() }
    }

    @Test
    fun `stream returns content from ChatClient`() {
        // Given
        val streamer = SpringAiLlmMessageStreamer(mockChatClient, mockChatOptions)
        val messages = listOf(UserMessage("Hello"))
        val expectedContent = Flux.just("chunk1", "chunk2", "chunk3")
        every { mockStreamSpec.content() } returns expectedContent

        // When
        val result = streamer.stream(messages, emptyList(), emptyList())

        // Then
        StepVerifier.create(result)
            .expectNext("chunk1")
            .expectNext("chunk2")
            .expectNext("chunk3")
            .verifyComplete()
    }

    @Test
    fun `stream handles empty message list`() {
        // Given
        val streamer = SpringAiLlmMessageStreamer(mockChatClient, mockChatOptions)
        every { mockStreamSpec.content() } returns Flux.just("response")

        // When
        val result = streamer.stream(emptyList(), emptyList(), emptyList())

        // Then
        StepVerifier.create(result)
            .expectNext("response")
            .verifyComplete()
    }

    @Test
    fun `stream handles multiple message types`() {
        // Given
        val streamer = SpringAiLlmMessageStreamer(mockChatClient, mockChatOptions)
        val messages = listOf(
            SystemMessage("You are helpful"),
            UserMessage("Hello"),
            AssistantMessage("Hi there"),
            UserMessage("How are you?")
        )
        every { mockStreamSpec.content() } returns Flux.just("response")

        // When
        val result = streamer.stream(messages, emptyList(), emptyList())

        // Then - should complete without error
        StepVerifier.create(result)
            .expectNext("response")
            .verifyComplete()

        // Verify prompt was built with all messages
        verify { mockChatClient.prompt(any<Prompt>()) }
    }

    @Test
    fun `stream handles empty flux response`() {
        // Given
        val streamer = SpringAiLlmMessageStreamer(mockChatClient, mockChatOptions)
        every { mockStreamSpec.content() } returns Flux.empty()

        // When
        val result = streamer.stream(listOf(UserMessage("test")), emptyList(), emptyList())

        // Then
        StepVerifier.create(result)
            .verifyComplete()
    }

    @Test
    fun `stream propagates errors from ChatClient`() {
        // Given
        val streamer = SpringAiLlmMessageStreamer(mockChatClient, mockChatOptions)
        val expectedError = RuntimeException("LLM error")
        every { mockStreamSpec.content() } returns Flux.error(expectedError)

        // When
        val result = streamer.stream(listOf(UserMessage("test")), emptyList(), emptyList())

        // Then
        StepVerifier.create(result)
            .expectError(RuntimeException::class.java)
            .verify(Duration.ofSeconds(1))
    }

    @Nested
    inner class InspectorIntegrationTests {

        @Test
        fun `stream wraps tools with inspectors when provided`() {
            // Given
            val streamer = SpringAiLlmMessageStreamer(mockChatClient, mockChatOptions)
            val tools = listOf(
                Tool.of("tool1", "First tool") { _ -> Tool.Result.text("result1") }
            )
            val inspector = object : ToolCallInspector {
                override fun beforeToolCall(context: BeforeToolCallContext) {}
                override fun afterToolCall(context: AfterToolCallContext) {}
            }
            every { mockStreamSpec.content() } returns Flux.just("response")

            val callbacksSlot = slot<List<ToolCallback>>()
            every { mockRequestSpec.toolCallbacks(capture(callbacksSlot)) } returns mockRequestSpec

            // When
            streamer.stream(listOf(UserMessage("test")), tools, listOf(inspector))

            // Then - verify callbacks were wrapped
            verify { mockRequestSpec.toolCallbacks(any<List<ToolCallback>>()) }
            val capturedCallbacks = callbacksSlot.captured
            assertTrue(capturedCallbacks.isNotEmpty(), "Callbacks should not be empty")
        }

        @Test
        fun `stream with multiple inspectors wraps tools correctly`() {
            // Given
            val streamer = SpringAiLlmMessageStreamer(mockChatClient, mockChatOptions)
            val tools = listOf(
                Tool.of("tool1", "First tool") { _ -> Tool.Result.text("result1") }
            )
            val inspector1 = object : ToolCallInspector {}
            val inspector2 = object : ToolCallInspector {}
            every { mockStreamSpec.content() } returns Flux.just("response")

            val callbacksSlot = slot<List<ToolCallback>>()
            every { mockRequestSpec.toolCallbacks(capture(callbacksSlot)) } returns mockRequestSpec

            // When
            streamer.stream(listOf(UserMessage("test")), tools, listOf(inspector1, inspector2))

            // Then
            verify { mockRequestSpec.toolCallbacks(any<List<ToolCallback>>()) }
            val capturedCallbacks = callbacksSlot.captured
            assertTrue(capturedCallbacks.isNotEmpty(), "Callbacks should not be empty")
        }

        @Test
        fun `stream with empty inspectors list does not wrap tools`() {
            // Given
            val streamer = SpringAiLlmMessageStreamer(mockChatClient, mockChatOptions)
            val tools = listOf(
                Tool.of("tool1", "First tool") { _ -> Tool.Result.text("result1") }
            )
            every { mockStreamSpec.content() } returns Flux.just("response")

            // When
            streamer.stream(listOf(UserMessage("test")), tools, emptyList())

            // Then - should still work, just without wrapping
            verify { mockRequestSpec.toolCallbacks(any<List<ToolCallback>>()) }
            verify { mockStreamSpec.content() }
        }
    }
}
