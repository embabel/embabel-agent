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

import com.embabel.agent.spi.loop.streaming.LlmStreamChunk
import com.embabel.chat.UserMessage
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.tool.ToolCallback
import reactor.core.publisher.Flux
import reactor.test.StepVerifier

class SpringAiLlmMessageStreamerThinkingTest {

    private lateinit var requestSpec: ChatClient.ChatClientRequestSpec
    private lateinit var streamSpec: ChatClient.StreamResponseSpec
    private lateinit var streamer: SpringAiLlmMessageStreamer

    @BeforeEach
    fun setUp() {
        val chatClient = mockk<ChatClient>()
        requestSpec = mockk()
        streamSpec = mockk()
        every { chatClient.prompt(any<Prompt>()) } returns requestSpec
        every { requestSpec.toolCallbacks(any<List<ToolCallback>>()) } returns requestSpec
        every { requestSpec.stream() } returns streamSpec
        streamer = SpringAiLlmMessageStreamer(chatClient, ToolCallingChatOptions.builder().build())
    }

    @Test
    fun `streams OpenAI reasoning alongside final content`() {
        every { streamSpec.chatResponse() } returns Flux.just(
            ChatResponse(
                listOf(
                    generation(
                        text = "answer",
                        properties = mapOf("reasoningContent" to "reasoning"),
                    )
                )
            )
        )

        StepVerifier.create(streamer.streamWithThinking(listOf(UserMessage("question")), emptyList(), emptyList()))
            .expectNext(LlmStreamChunk(textContent = "answer", thinkingContent = listOf("reasoning")))
            .verifyComplete()
    }

    @Test
    fun `streams Ollama thinking once when both metadata locations contain it`() {
        every { streamSpec.chatResponse() } returns Flux.just(
            ChatResponse(
                listOf(
                    generation(
                        text = "answer",
                        properties = mapOf("thinking" to "reasoning"),
                        metadata = mapOf("thinking" to "reasoning"),
                    )
                )
            )
        )

        StepVerifier.create(streamer.streamWithThinking(listOf(UserMessage("question")), emptyList(), emptyList()))
            .expectNext(LlmStreamChunk(textContent = "answer", thinkingContent = listOf("reasoning")))
            .verifyComplete()
    }

    @Test
    fun `keeps Anthropic thinking final output and redacted data in provider order`() {
        every { streamSpec.chatResponse() } returns Flux.just(
            ChatResponse(listOf(generation(text = "reasoning", properties = mapOf("thinking" to true)))),
            ChatResponse(listOf(generation(text = "", properties = mapOf("data" to "encrypted-data")))),
            ChatResponse(listOf(generation(text = "answer"))),
        )

        StepVerifier.create(streamer.streamWithThinking(listOf(UserMessage("question")), emptyList(), emptyList()))
            .expectNext(LlmStreamChunk(textContent = "", thinkingContent = listOf("reasoning")))
            .expectNext(LlmStreamChunk(textContent = "", thinkingContent = listOf("encrypted-data")))
            .expectNext(LlmStreamChunk(textContent = "answer"))
            .verifyComplete()
    }

    private fun generation(
        text: String,
        properties: Map<String, Any> = emptyMap(),
        metadata: Map<String, Any> = emptyMap(),
    ): Generation = Generation(
        AssistantMessage.builder().content(text).properties(properties).build(),
        ChatGenerationMetadata.builder().metadata(metadata).build(),
    )
}
