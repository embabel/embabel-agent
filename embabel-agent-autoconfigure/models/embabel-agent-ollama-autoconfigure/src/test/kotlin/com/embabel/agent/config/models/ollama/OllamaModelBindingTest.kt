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
package com.embabel.agent.config.models.ollama

import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
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
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.ollama.api.OllamaChatOptions

/**
 * End-to-end regression that a native (non-OpenAI-compatible) provider reaches the wire with
 * the selected model. [OllamaOptionsConverter] never sets a model, so before generic binding
 * in [SpringAiLlmService] its [OllamaChatOptions] carried no model. This proves the model
 * configured on the [ChatModel] is bound onto request options while the concrete
 * [OllamaChatOptions] type and other fields survive.
 */
class OllamaModelBindingTest {

    @Test
    fun `sends the selected Ollama model rather than the converter default`() {
        // Prepare: bean configured with a specific Ollama model; converter sets no model.
        val configuredOptions = OllamaChatOptions.builder().model("llama3.2").build()
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
            name = "llama3.2",
            provider = "Ollama",
            chatModel = chatModel,
            optionsConverter = OllamaOptionsConverter,
        )

        // Execute
        service.createMessageSender(LlmOptions().withTopP(0.8))
            .call(messages = listOf(UserMessage("Hi")), tools = emptyList())

        // Verify: concrete Ollama options carry the selected model, topP preserved.
        val sentOptions = capturedPrompt.captured.options
        assertThat(sentOptions).isInstanceOf(OllamaChatOptions::class.java)
        assertThat(sentOptions.model).isEqualTo("llama3.2")
        assertThat(sentOptions.topP).isEqualTo(0.8)
    }
}
