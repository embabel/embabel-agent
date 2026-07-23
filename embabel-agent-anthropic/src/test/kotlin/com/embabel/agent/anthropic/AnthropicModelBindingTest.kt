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

import com.embabel.agent.api.models.AnthropicModels
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.chat.messages.AssistantMessage as SpringAiAssistantMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt

/**
 * End-to-end regression for the Anthropic half of the Spring AI 2.0 model-binding bug
 * (see #1815 for the OpenAI/Gemini equivalent).
 *
 * [AnthropicOptionsConverter] never sets a model, so its [AnthropicChatOptions] carry the
 * SDK's baked-in `DEFAULT_MODEL` (`claude-haiku-4-5`). Because Embabel always passes these
 * per-request options and Spring AI 2.0 no longer merges the model bean's configured model,
 * every call would silently target Haiku. [SpringAiLlmService] now binds the configured
 * model generically; this test proves the selected model reaches the wire while the concrete
 * [AnthropicChatOptions] type and other fields are preserved.
 */
class AnthropicModelBindingTest {

    @Test
    fun `sends the selected Anthropic model rather than the converter default`() {
        // Prepare: bean configured with Sonnet; converter (unchanged) bakes in Haiku default.
        val configuredOptions = AnthropicChatOptions.builder().model("claude-sonnet-4-5").build()
        val capturedPrompt = slot<Prompt>()
        val generation = Generation(SpringAiAssistantMessage("done"))
        val chatResponse = mockk<ChatResponse> {
            every { result } returns generation
            every { results } returns listOf(generation)
            every { metadata } returns mockk<ChatResponseMetadata>(relaxed = true) { every { usage } returns null }
        }
        val chatModel = mockk<ChatModel> {
            every { options } returns configuredOptions
            every { call(capture(capturedPrompt)) } returns chatResponse
        }
        val service = SpringAiLlmService(
            name = "claude-sonnet-4-5",
            provider = AnthropicModels.PROVIDER,
            chatModel = chatModel,
            optionsConverter = AnthropicOptionsConverter,
            thinkingSupported = true,
        )

        // Execute
        service.createMessageSender(LlmOptions().withMaxTokens(500))
            .call(messages = listOf(UserMessage("Hi")), tools = emptyList())

        // Verify: concrete Anthropic options carry the selected model, maxTokens preserved.
        val sentOptions = capturedPrompt.captured.options
        assertThat(sentOptions).isInstanceOf(AnthropicChatOptions::class.java)
        assertThat(sentOptions.model).isEqualTo("claude-sonnet-4-5")
        assertThat(sentOptions.maxTokens).isEqualTo(500)
    }
}
