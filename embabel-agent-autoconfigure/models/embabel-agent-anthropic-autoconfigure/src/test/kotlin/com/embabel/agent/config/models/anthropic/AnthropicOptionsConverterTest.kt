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
package com.embabel.agent.config.models.anthropic

import com.embabel.agent.test.models.OptionsConverterTestSupport
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.Thinking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.anthropic.api.AnthropicApi
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy

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

    @Test
    fun `should default to no caching`() {
        val options = optionsConverter.convertOptions(LlmOptions())
        // Spring AI returns NONE strategy instead of null when no caching configured
        assertEquals(AnthropicCacheStrategy.NONE, options.cacheOptions?.strategy)
    }

    @Test
    fun `should set system-only caching`() {
        val options = optionsConverter.convertOptions(
            LlmOptions().withAnthropicCaching(systemPrompt = true)
        )
        assertEquals(AnthropicCacheStrategy.SYSTEM_ONLY, options.cacheOptions?.strategy)
    }

    @Test
    fun `should set tools-only caching`() {
        val options = optionsConverter.convertOptions(
            LlmOptions().withAnthropicCaching(tools = true)
        )
        assertEquals(AnthropicCacheStrategy.TOOLS_ONLY, options.cacheOptions?.strategy)
    }

    @Test
    fun `should set system and tools caching`() {
        val options = optionsConverter.convertOptions(
            LlmOptions().withAnthropicCaching(systemPrompt = true, tools = true)
        )
        assertEquals(AnthropicCacheStrategy.SYSTEM_AND_TOOLS, options.cacheOptions?.strategy)
    }

    @Test
    fun `should set conversation history caching`() {
        val options = optionsConverter.convertOptions(
            LlmOptions().withAnthropicCaching(conversationHistory = true)
        )
        assertEquals(AnthropicCacheStrategy.CONVERSATION_HISTORY, options.cacheOptions?.strategy)
    }

    @Test
    fun `conversation history caching takes precedence`() {
        val options = optionsConverter.convertOptions(
            LlmOptions().withAnthropicCaching(
                systemPrompt = true,
                tools = true,
                conversationHistory = true
            )
        )
        assertEquals(AnthropicCacheStrategy.CONVERSATION_HISTORY, options.cacheOptions?.strategy)
    }

    @Test
    fun `should preserve caching when chaining other options`() {
        val options = optionsConverter.convertOptions(
            LlmOptions()
                .withAnthropicCaching(systemPrompt = true)
                .withTemperature(0.7)
                .withMaxTokens(1000)
        )
        assertEquals(AnthropicCacheStrategy.SYSTEM_ONLY, options.cacheOptions?.strategy)
        assertEquals(0.7, options.temperature)
        assertEquals(1000, options.maxTokens)
    }

}
