/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.config.models.anthropic

import com.embabel.agent.test.models.OptionsConverterTestSupport
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.Thinking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.anthropic.api.AnthropicApi

class AnthropicOptionsConverterTest : OptionsConverterTestSupport<AnthropicChatOptions>(
    optionsConverter = AnthropicOptionsConverter
) {

    @Test
    fun `should default to no thinking`() {
        val options = optionsConverter.convertOptions(LlmOptions())
        assertEquals(AnthropicApi.ThinkingType.DISABLED, options.thinking.type)
    }

    @Test
    fun `should set thinking`() {
        val options = optionsConverter.convertOptions(LlmOptions().withThinking(Thinking.withTokenBudget(2000)))
        assertEquals(AnthropicApi.ThinkingType.ENABLED, options.thinking.type)
        assertEquals(2000, options.thinking.budgetTokens())
    }

    @Test
    fun `should set high maxTokens default`() {
        val options = optionsConverter.convertOptions(LlmOptions())
        assertEquals(AnthropicOptionsConverter.DEFAULT_MAX_TOKENS, options.maxTokens)
    }

    @Test
    fun `should set override maxTokens default`() {
        val options = optionsConverter.convertOptions(LlmOptions().withMaxTokens(200))
        assertEquals(200, options.maxTokens)
    }

}
