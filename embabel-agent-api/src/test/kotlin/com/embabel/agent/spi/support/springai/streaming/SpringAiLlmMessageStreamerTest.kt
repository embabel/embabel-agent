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
import com.embabel.agent.spi.loop.streaming.LlmInferenceStreamEvent
import com.embabel.chat.AssistantMessageWithToolCalls
import com.embabel.chat.UserMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import reactor.core.publisher.Flux
import reactor.test.StepVerifier

class SpringAiLlmMessageStreamerTest {

    private lateinit var chatModel: ChatModel
    private lateinit var chatOptions: ToolCallingChatOptions
    private val capturedPrompt = slot<Prompt>()

    @BeforeEach
    fun setUp() {
        chatModel = mockk()
        chatOptions = ToolCallingChatOptions.builder().build()
        capturedPrompt.clear()
    }

    @Test
    fun `streams content and emits assembled completion`() {
        every { chatModel.stream(capture(capturedPrompt)) } returns Flux.just(
            ChatResponse(listOf(Generation(AssistantMessage("hello"))))
        )
        val streamer = SpringAiLlmMessageStreamer(chatModel, chatOptions)

        StepVerifier.create(streamer.streamInference(listOf(UserMessage("Hi")), emptyList()))
            .expectNext(LlmInferenceStreamEvent.Content("hello"))
            .assertNext { event ->
                val completion = assertInstanceOf(LlmInferenceStreamEvent.Complete::class.java, event)
                assertEquals("hello", completion.message.content)
            }
            .verifyComplete()
    }

    @Test
    fun `preserves provider identified native thinking`() {
        val thinking = AssistantMessage.builder()
            .content("native reasoning")
            .properties(mapOf("thinking" to true))
            .build()
        every { chatModel.stream(capture(capturedPrompt)) } returns Flux.just(
            ChatResponse(listOf(Generation(thinking)))
        )
        val streamer = SpringAiLlmMessageStreamer(chatModel, chatOptions)

        StepVerifier.create(streamer.streamInference(listOf(UserMessage("Hi")), emptyList()))
            .expectNext(LlmInferenceStreamEvent.Thinking("native reasoning"))
            .assertNext { event ->
                assertInstanceOf(LlmInferenceStreamEvent.Complete::class.java, event)
            }
            .verifyComplete()
    }

    @Test
    fun `preserves native thinking text through original raw signature`() {
        val thinking = AssistantMessage.builder()
            .content("native reasoning")
            .properties(mapOf("thinking" to true))
            .build()
        every { chatModel.stream(capture(capturedPrompt)) } returns Flux.just(
            ChatResponse(listOf(Generation(thinking)))
        )
        val streamer = SpringAiLlmMessageStreamer(chatModel, chatOptions)

        StepVerifier.create(streamer.stream(listOf(UserMessage("Hi")), emptyList(), emptyList()))
            .expectNext("native reasoning")
            .verifyComplete()
    }

    @Test
    fun `preserves the original raw content streaming signature`() {
        every { chatModel.stream(capture(capturedPrompt)) } returns Flux.just(
            ChatResponse(listOf(Generation(AssistantMessage("hello"))))
        )
        val streamer = SpringAiLlmMessageStreamer(chatModel, chatOptions)

        StepVerifier.create(streamer.stream(listOf(UserMessage("Hi")), emptyList(), emptyList()))
            .expectNext("hello")
            .verifyComplete()
    }

    @Test
    fun `assembles tool calls without executing them`() {
        val call = AssistantMessage.ToolCall("call-1", "function", "lookup", "{\"id\":42}")
        val output = AssistantMessage.builder().content("").toolCalls(listOf(call)).build()
        every { chatModel.stream(capture(capturedPrompt)) } returns Flux.just(
            ChatResponse(listOf(Generation(output)))
        )
        val tool = Tool.of("lookup", "Lookup an item") { Tool.Result.text("should not execute") }
        val streamer = SpringAiLlmMessageStreamer(chatModel, chatOptions)

        StepVerifier.create(streamer.streamInference(listOf(UserMessage("Lookup 42")), listOf(tool)))
            .assertNext { event ->
                val completion = assertInstanceOf(LlmInferenceStreamEvent.Complete::class.java, event)
                val message = assertInstanceOf(AssistantMessageWithToolCalls::class.java, completion.message)
                assertEquals("lookup", message.toolCalls.single().name)
                assertEquals("{\"id\":42}", message.toolCalls.single().arguments)
            }
            .verifyComplete()

        val options = capturedPrompt.captured.options as ToolCallingChatOptions
        assertEquals(listOf("lookup"), options.toolCallbacks.map { it.toolDefinition.name() })
    }

    @Test
    fun `propagates model streaming errors`() {
        every { chatModel.stream(any<Prompt>()) } returns Flux.error<ChatResponse>(IllegalStateException("failure"))
        val streamer = SpringAiLlmMessageStreamer(chatModel, chatOptions)

        StepVerifier.create(streamer.streamInference(listOf(UserMessage("Hi")), emptyList()))
            .expectErrorMessage("failure")
            .verify()
    }
}
