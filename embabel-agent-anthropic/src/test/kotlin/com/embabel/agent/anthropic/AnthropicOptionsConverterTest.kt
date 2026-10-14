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

import com.anthropic.models.messages.OutputConfig
import com.embabel.agent.api.models.AnthropicModels
import com.embabel.agent.test.models.OptionsConverterTestSupport
import com.embabel.chat.MessageRole
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.Thinking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.anthropic.AnthropicCacheStrategy
import org.springframework.ai.anthropic.AnthropicCacheTtl
import org.springframework.ai.anthropic.AnthropicChatOptions

class AnthropicOptionsConverterTest : OptionsConverterTestSupport(
    optionsConverter = AnthropicOptionsConverter
) {

    @Test
    fun `should default to no thinking`() {
        val options = (optionsConverter.convertOptions(LlmOptions(), "test-model") as AnthropicChatOptions)
        // Spring AI 2.0 replaced AnthropicApi.ThinkingType with anthropic-java's
        // ThinkingConfigParam (a sealed union of enabled/disabled/adaptive).
        // isDisabled() / isEnabled() are functions (not Kotlin properties), so call them.
        assertTrue(options.thinking.isDisabled(), "expected thinking to be disabled")
    }

    @Test
    fun `should set thinking`() {
        val options = (optionsConverter.convertOptions(LlmOptions().withThinking(Thinking.withTokenBudget(2000)), "test-model") as AnthropicChatOptions)
        assertTrue(options.thinking.isEnabled(), "expected thinking to be enabled")
        assertEquals(2000L, options.thinking.asEnabled().budgetTokens())
    }

    @Test
    fun `should set high maxTokens default`() {
        val options = (optionsConverter.convertOptions(LlmOptions(), "test-model") as AnthropicChatOptions)
        assertEquals(AnthropicOptionsConverter.DEFAULT_MAX_TOKENS, options.maxTokens)
    }

    @Test
    fun `should set override maxTokens default`() {
        val options = (optionsConverter.convertOptions(LlmOptions().withMaxTokens(200), "test-model") as AnthropicChatOptions)
        assertEquals(200, options.maxTokens)
    }

    @Nested
    inner class CachingTests {

        @Test
        fun `should default to no caching`() {
            val options = (optionsConverter.convertOptions(LlmOptions(), "test-model") as AnthropicChatOptions)
            // Spring AI returns NONE strategy instead of null when no caching configured
            assertEquals(AnthropicCacheStrategy.NONE, options.cacheOptions?.strategy)
        }

        @Test
        fun `should set system-only caching`() {
            val options = (optionsConverter.convertOptions(
                LlmOptions().withAnthropicCaching(systemPrompt = true)
            , "test-model") as AnthropicChatOptions)
            assertEquals(AnthropicCacheStrategy.SYSTEM_ONLY, options.cacheOptions?.strategy)
        }

        @Test
        fun `should set tools-only caching`() {
            val options = (optionsConverter.convertOptions(
                LlmOptions().withAnthropicCaching(tools = true)
            , "test-model") as AnthropicChatOptions)
            assertEquals(AnthropicCacheStrategy.TOOLS_ONLY, options.cacheOptions?.strategy)
        }

        @Test
        fun `should set system and tools caching`() {
            val options = (optionsConverter.convertOptions(
                LlmOptions().withAnthropicCaching(systemPrompt = true, tools = true)
            , "test-model") as AnthropicChatOptions)
            assertEquals(AnthropicCacheStrategy.SYSTEM_AND_TOOLS, options.cacheOptions?.strategy)
        }

        @Test
        fun `should set conversation history caching`() {
            val options = (optionsConverter.convertOptions(
                LlmOptions().withAnthropicCaching(conversationHistory = true)
            , "test-model") as AnthropicChatOptions)
            assertEquals(AnthropicCacheStrategy.CONVERSATION_HISTORY, options.cacheOptions?.strategy)
        }

        @Test
        fun `conversation history caching takes precedence`() {
            val options = (optionsConverter.convertOptions(
                LlmOptions().withAnthropicCaching(
                    systemPrompt = true,
                    tools = true,
                    conversationHistory = true
                )
            , "test-model") as AnthropicChatOptions)
            assertEquals(AnthropicCacheStrategy.CONVERSATION_HISTORY, options.cacheOptions?.strategy)
        }

        @Test
        fun `should preserve caching when chaining other options`() {
            val options = (optionsConverter.convertOptions(
                LlmOptions()
                    .withAnthropicCaching(systemPrompt = true)
                    .withTemperature(0.7)
                    .withMaxTokens(1000)
            , "test-model") as AnthropicChatOptions)
            assertEquals(AnthropicCacheStrategy.SYSTEM_ONLY, options.cacheOptions?.strategy)
            assertEquals(0.7, options.temperature)
            assertEquals(1000, options.maxTokens)
        }

        @Test
        fun `should set message type minimum content length`() {
            val config = AnthropicCachingConfig(systemPrompt = true)
                .messageTypeMinContentLength(MessageRole.SYSTEM, 1024)
                .messageTypeMinContentLength(MessageRole.USER, 512)
                .messageTypeMinContentLength(MessageRole.ASSISTANT, 512)

            assertEquals(1024, config.messageTypeMinContentLengths[MessageRole.SYSTEM])
            assertEquals(512, config.messageTypeMinContentLengths[MessageRole.USER])
            assertEquals(512, config.messageTypeMinContentLengths[MessageRole.ASSISTANT])
        }

        @Test
        fun `should set message type TTL`() {
            val config = AnthropicCachingConfig(systemPrompt = true)
                .messageTypeTtl(MessageRole.SYSTEM, AnthropicCacheTtl.ONE_HOUR)
                .messageTypeTtl(MessageRole.USER, AnthropicCacheTtl.FIVE_MINUTES)

            assertEquals(AnthropicCacheTtl.ONE_HOUR, config.messageTypeTtls[MessageRole.SYSTEM])
            assertEquals(AnthropicCacheTtl.FIVE_MINUTES, config.messageTypeTtls[MessageRole.USER])
        }

        @Test
        fun `should configure caching with message type controls`() {
            val llmOptions = LlmOptions().withAnthropicCaching(
                AnthropicCachingConfig(
                    systemPrompt = true,
                    conversationHistory = true
                )
                    .messageTypeMinContentLength(MessageRole.SYSTEM, 1024)
                    .messageTypeMinContentLength(MessageRole.USER, 512)
                    .messageTypeTtl(MessageRole.SYSTEM, AnthropicCacheTtl.ONE_HOUR)
            )

            val cachingConfig = llmOptions.getAnthropicCaching()
            assertEquals(true, cachingConfig?.systemPrompt)
            assertEquals(true, cachingConfig?.conversationHistory)
            assertEquals(1024, cachingConfig?.messageTypeMinContentLengths?.get(MessageRole.SYSTEM))
            assertEquals(512, cachingConfig?.messageTypeMinContentLengths?.get(MessageRole.USER))
            assertEquals(AnthropicCacheTtl.ONE_HOUR, cachingConfig?.messageTypeTtls?.get(MessageRole.SYSTEM))
        }

        @Test
        fun `should apply messageTypeMinContentLength to Spring AI options`() {
            val llmOptions = LlmOptions().withAnthropicCaching(
                AnthropicCachingConfig(systemPrompt = true)
                    .messageTypeMinContentLength(MessageRole.SYSTEM, 1024)
                    .messageTypeMinContentLength(MessageRole.USER, 512)
            )

            val options = (optionsConverter.convertOptions(llmOptions, "test-model") as AnthropicChatOptions)
            val cacheOptions = options.cacheOptions

            assertEquals(AnthropicCacheStrategy.SYSTEM_ONLY, cacheOptions?.strategy)
        }

        @Test
        fun `should apply messageTypeTtl to Spring AI options`() {
            val llmOptions = LlmOptions().withAnthropicCaching(
                AnthropicCachingConfig(systemPrompt = true)
                    .messageTypeTtl(MessageRole.SYSTEM, AnthropicCacheTtl.ONE_HOUR)
                    .messageTypeTtl(MessageRole.USER, AnthropicCacheTtl.FIVE_MINUTES)
            )

            val options = (optionsConverter.convertOptions(llmOptions, "test-model") as AnthropicChatOptions)
            val cacheOptions = options.cacheOptions

            assertEquals(AnthropicCacheStrategy.SYSTEM_ONLY, cacheOptions?.strategy)
        }

        @Test
        fun `should apply both messageTypeMinContentLength and messageTypeTtl`() {
            val llmOptions = LlmOptions().withAnthropicCaching(
                AnthropicCachingConfig(systemPrompt = true, tools = true)
                    .messageTypeMinContentLength(MessageRole.SYSTEM, 2048)
                    .messageTypeTtl(MessageRole.SYSTEM, AnthropicCacheTtl.ONE_HOUR)
            )

            val options = (optionsConverter.convertOptions(llmOptions, "test-model") as AnthropicChatOptions)
            val cacheOptions = options.cacheOptions

            assertEquals(AnthropicCacheStrategy.SYSTEM_AND_TOOLS, cacheOptions?.strategy)
        }

        @Test
        fun `getAnthropicCaching should return null when not configured`() {
            val llmOptions = LlmOptions()
            assertNull(llmOptions.getAnthropicCaching())
        }

        @Test
        fun `getAnthropicCaching should return config when configured`() {
            val config = AnthropicCachingConfig(systemPrompt = true)
            val llmOptions = LlmOptions().withAnthropicCaching(config)

            val retrieved = llmOptions.getAnthropicCaching()
            assertEquals(true, retrieved?.systemPrompt)
        }
    }

    @Nested
    inner class SamplingParameterTests {

        @Test
        fun `claude-opus-4-8 without thinking — temperature passed through`() {
            // ThinkingMode Disabled on ≥4.7: temperature must not be suppressed
            val opts = optionsConverter.convertOptions(
                LlmOptions().withTemperature(0.7),
                AnthropicModels.CLAUDE_OPUS_4_8,
            ) as AnthropicChatOptions
            assertEquals(0.7, opts.temperature)
        }

        @Test
        fun `claude-opus-4-8 without thinking — topP passed through`() {
            val opts = optionsConverter.convertOptions(
                LlmOptions().withTopP(0.9),
                AnthropicModels.CLAUDE_OPUS_4_8,
            ) as AnthropicChatOptions
            assertEquals(0.9, opts.topP)
        }

        @Test
        fun `claude-sonnet-5-5 without thinking — temperature passed through`() {
            // ThinkingMode Omit on 5.x: temperature must not be suppressed
            val opts = optionsConverter.convertOptions(
                LlmOptions().withTemperature(0.5),
                AnthropicModels.CLAUDE_SONNET_5_5,
            ) as AnthropicChatOptions
            assertEquals(0.5, opts.temperature)
        }

        @Test
        fun `claude-sonnet-5-5 with thinking — temperature suppressed`() {
            // ThinkingMode Adaptive: temperature conflicts with active thinking
            val opts = optionsConverter.convertOptions(
                LlmOptions().withThinking(Thinking.withTokenBudget(8000)).withTemperature(0.7),
                AnthropicModels.CLAUDE_SONNET_5_5,
            ) as AnthropicChatOptions
            assertNull(opts.temperature)
        }

        @Test
        fun `claude-opus-4-8 with thinking — temperature suppressed`() {
            // ThinkingMode Adaptive (opus-4-8 ≥4.7 with budget): temperature must be suppressed
            val opts = optionsConverter.convertOptions(
                LlmOptions().withThinking(Thinking.withTokenBudget(8000)).withTemperature(0.7),
                AnthropicModels.CLAUDE_OPUS_4_8,
            ) as AnthropicChatOptions
            assertNull(opts.temperature)
        }
    }

    @Nested
    inner class Claude5ThinkingTests {

        @Test
        fun `claude-sonnet-5-5 with thinking uses adaptive mode — thinkingEnabled rejected with HTTP 400`() {
            val opts = optionsConverter.convertOptions(
                LlmOptions().withThinking(Thinking.withTokenBudget(8000)),
                AnthropicModels.CLAUDE_SONNET_5_5,
            ) as AnthropicChatOptions
            assertTrue(opts.thinking.isAdaptive(), "claude-sonnet-5-5 must use adaptive thinking")
        }

        @Test
        fun `claude-sonnet-5-5 with thinking maps budget 8000 to HIGH effort`() {
            val opts = optionsConverter.convertOptions(
                LlmOptions().withThinking(Thinking.withTokenBudget(8000)),
                AnthropicModels.CLAUDE_SONNET_5_5,
            ) as AnthropicChatOptions
            assertEquals(OutputConfig.Effort.HIGH, opts.outputConfig?.effort()?.orElse(null))
        }

        @Test
        fun `claude-sonnet-5-5 without thinking does not send disabled`() {
            val opts = optionsConverter.convertOptions(
                LlmOptions(),
                AnthropicModels.CLAUDE_SONNET_5_5,
            ) as AnthropicChatOptions
            // null thinking = field omitted entirely from request, which is correct for Claude 5.1+
            assertFalse(opts.thinking?.isDisabled() == true, "claude-sonnet-5-5 must not send thinking.type=disabled")
        }

        @Test
        fun `claude-fable-5-1 without thinking does not send disabled`() {
            val opts = optionsConverter.convertOptions(
                LlmOptions(),
                AnthropicModels.CLAUDE_FABLE_5_1,
            ) as AnthropicChatOptions
            // null thinking = field omitted entirely from request, which is correct for Claude 5.1+
            assertFalse(opts.thinking?.isDisabled() == true, "claude-fable-5-1 must not send thinking.type=disabled")
        }

        @Test
        fun `claude-sonnet-4-5 with thinking uses enabled mode with budget`() {
            val opts = optionsConverter.convertOptions(
                LlmOptions().withThinking(Thinking.withTokenBudget(2000)),
                AnthropicModels.CLAUDE_SONNET_4_5,
            ) as AnthropicChatOptions
            assertTrue(opts.thinking.isEnabled(), "expected legacy enabled thinking for claude-sonnet-4-5")
            assertEquals(2000L, opts.thinking.asEnabled().budgetTokens())
        }
    }

}
