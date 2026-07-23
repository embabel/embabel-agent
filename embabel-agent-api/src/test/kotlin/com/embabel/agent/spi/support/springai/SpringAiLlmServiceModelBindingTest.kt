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

import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.OptionsConverter
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage as SpringAiAssistantMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import reactor.core.publisher.Flux

/**
 * Regression tests for the generic model-binding performed by [SpringAiLlmService].
 *
 * Spring AI 2.0 stopped merging a [ChatModel]'s configured options into a prompt that
 * already carries per-request options (see OpenAiChatModel/AnthropicChatModel
 * `buildRequestPrompt`). Embabel always supplies per-request options via its
 * [OptionsConverter], and those options carry the provider's baked-in default model
 * (e.g. `gpt-5-mini`, `claude-haiku-4-5`). Without re-binding, every call would ignore
 * the selected model. [SpringAiLlmService] closes this generically by binding the model
 * configured on the underlying [ChatModel] onto the converted request options.
 */
class SpringAiLlmServiceModelBindingTest {

    private fun chatResponseStub(): ChatResponse {
        val generation = Generation(SpringAiAssistantMessage("done"))
        return mockk {
            every { result } returns generation
            every { results } returns listOf(generation)
            every { metadata } returns mockk<ChatResponseMetadata>(relaxed = true) { every { usage } returns null }
        }
    }

    @Test
    fun `createMessageSender binds the chat model's configured model onto the request`() {
        // Prepare: the bean is configured with the selected model, but the converter
        // returns options carrying a different (provider-default) model.
        val configuredOptions = ChatOptions.builder().model("gpt-4.1").build()
        val converterOutput = ChatOptions.builder()
            .model("gpt-5-mini")
            .temperature(0.5)
            .build()
        val capturedPrompt = slot<Prompt>()
        val chatResponse = chatResponseStub()
        val chatModel = mockk<ChatModel> {
            every { options } returns configuredOptions
            every { call(capture(capturedPrompt)) } returns chatResponse
        }
        val service = SpringAiLlmService(
            name = "gpt-4.1",
            provider = "Test",
            chatModel = chatModel,
            optionsConverter = OptionsConverter { converterOutput },
        )

        // Execute
        service.createMessageSender(LlmOptions())
            .call(messages = listOf(UserMessage("Hi")), tools = emptyList())

        // Verify: the request carries the configured model, other options preserved.
        val sentOptions = capturedPrompt.captured.options
        assertThat(sentOptions.model).isEqualTo("gpt-4.1")
        assertThat(sentOptions.temperature).isEqualTo(0.5)
    }

    @Test
    fun `binds model onto a generic ToolCallingChatOptions converter (Bedrock-shape)`() {
        // Bedrock's converter returns a generic ToolCallingChatOptions carrying no model; the
        // model lives only on the ChatModel. Verify binding sets it while preserving the type.
        val configuredOptions = ToolCallingChatOptions.builder().model("anthropic.claude-sonnet").build()
        val converterOutput = ToolCallingChatOptions.builder().build()
        val capturedPrompt = slot<Prompt>()
        val chatResponse = chatResponseStub()
        val chatModel = mockk<ChatModel> {
            every { options } returns configuredOptions
            every { call(capture(capturedPrompt)) } returns chatResponse
        }
        val service = SpringAiLlmService(
            name = "anthropic.claude-sonnet",
            provider = "Bedrock",
            chatModel = chatModel,
            optionsConverter = OptionsConverter { converterOutput },
        )

        // Execute
        service.createMessageSender(LlmOptions())
            .call(messages = listOf(UserMessage("Hi")), tools = emptyList())

        // Verify: concrete generic type preserved and the configured model bound.
        val sentOptions = capturedPrompt.captured.options
        assertThat(sentOptions).isInstanceOf(ToolCallingChatOptions::class.java)
        assertThat(sentOptions.model).isEqualTo("anthropic.claude-sonnet")
    }

    @Test
    fun `createMessageStreamer binds the configured model onto the streamed request`() {
        // Prepare
        val configuredOptions = ChatOptions.builder().model("gpt-4.1").build()
        val converterOutput = ChatOptions.builder().model("gpt-5-mini").build()
        val capturedPrompt = slot<Prompt>()
        // Use a real ChatResponse: the ChatClient streaming aggregator reads metadata the mock
        // stub does not provide.
        val chatModel = mockk<ChatModel> {
            every { options } returns configuredOptions
            every { stream(capture(capturedPrompt)) } returns
                Flux.just(ChatResponse(listOf(Generation(SpringAiAssistantMessage("chunk")))))
        }
        val service = SpringAiLlmService(
            name = "gpt-4.1",
            provider = "Test",
            chatModel = chatModel,
            optionsConverter = OptionsConverter { converterOutput },
        )

        // Execute
        service.createMessageStreamer(LlmOptions())
            .stream(messages = listOf(UserMessage("Hi")), tools = emptyList(), toolCallInspectors = emptyList())
            .blockLast()

        // Verify
        assertThat(capturedPrompt.captured.options.model).isEqualTo("gpt-4.1")
    }

    @Test
    fun `bindModel overrides the converted model and preserves other fields`() {
        // Prepare
        val converted = ChatOptions.builder()
            .model("gpt-5-mini")
            .temperature(0.5)
            .maxTokens(1000)
            .topP(0.9)
            .build()

        // Execute
        val bound = bindModel(converted, "gpt-4.1")

        // Verify
        assertThat(bound.model).isEqualTo("gpt-4.1")
        assertThat(bound.temperature).isEqualTo(0.5)
        assertThat(bound.maxTokens).isEqualTo(1000)
        assertThat(bound.topP).isEqualTo(0.9)
    }

    @Test
    fun `bindModel passes options through unchanged when configured model is null`() {
        // Prepare
        val converted = ChatOptions.builder().model("converter-model").build()

        // Execute
        val bound = bindModel(converted, null)

        // Verify
        assertThat(bound).isSameAs(converted)
        assertThat(bound.model).isEqualTo("converter-model")
    }

    @Test
    fun `bindModel passes options through unchanged when configured model is blank`() {
        // Prepare
        val converted = ChatOptions.builder().model("converter-model").build()

        // Execute
        val bound = bindModel(converted, " ")

        // Verify
        assertThat(bound).isSameAs(converted)
        assertThat(bound.model).isEqualTo("converter-model")
    }
}
