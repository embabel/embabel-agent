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

import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.LlmOptions
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.anthropic.AnthropicChatOptions

/**
 * Regression for the Spring AI 2.0 model-binding bug affecting Anthropic:
 * [AnthropicOptionsConverter] never calls .model(), so its [AnthropicChatOptions] carry
 * DEFAULT_MODEL ("claude-haiku-4-5"). Spring AI 2.0 no longer merges the ChatModel bean's
 * configured model into per-request options, so haiku would go on the wire silently.
 * [SpringAiLlmService.buildChatOptions] now stamps the service name explicitly.
 */
class AnthropicModelBindingTest {

    @Test
    fun `buildChatOptions stamps configured model not AnthropicChatOptions haiku default`() {
        val service = SpringAiLlmService(
            name = "claude-sonnet-4-5",
            provider = "Anthropic",
            chatModel = mockk(relaxed = true),
            optionsConverter = AnthropicOptionsConverter,
        )

        val result = service.buildChatOptions(LlmOptions())

        assertThat(result).isInstanceOf(AnthropicChatOptions::class.java)
        assertThat(result.model).isEqualTo("claude-sonnet-4-5")
        assertThat(result.model).isNotEqualTo(AnthropicChatOptions.DEFAULT_MODEL)
    }

    @Test
    fun `buildChatOptions preserves AnthropicChatOptions fields alongside model`() {
        val service = SpringAiLlmService(
            name = "claude-sonnet-4-5",
            provider = "Anthropic",
            chatModel = mockk(relaxed = true),
            optionsConverter = AnthropicOptionsConverter,
        )

        val result = service.buildChatOptions(LlmOptions().withMaxTokens(500))

        assertThat(result).isInstanceOf(AnthropicChatOptions::class.java)
        assertThat(result.model).isEqualTo("claude-sonnet-4-5")
        assertThat(result.maxTokens).isEqualTo(500)
    }

    @Test
    fun `buildChatOptions result type is AnthropicChatOptions not generic fallback`() {
        val service = SpringAiLlmService(
            name = "claude-haiku-4-5",
            provider = "Anthropic",
            chatModel = mockk(relaxed = true),
            optionsConverter = AnthropicOptionsConverter,
        )

        val result = service.buildChatOptions(LlmOptions())

        assertThat(result).isInstanceOf(AnthropicChatOptions::class.java)
        assertThat(result.model).isEqualTo("claude-haiku-4-5")
    }
}
