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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.embabel.agent.api.models.AnthropicModels
import com.embabel.agent.test.models.OptionsConverterTestSupport
import com.embabel.chat.MessageRole
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.Thinking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.ai.anthropic.AnthropicCacheStrategy
import org.springframework.ai.anthropic.AnthropicCacheTtl
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt

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

        private val sampling = LlmOptions().withTemperature(0.7).withTopP(0.9).withTopK(40)

        private fun convert(options: LlmOptions, model: String) =
            optionsConverter.convertOptions(options, model) as AnthropicChatOptions

        private fun assertNoSampling(opts: AnthropicChatOptions) {
            assertNull(opts.temperature, "temperature")
            assertNull(opts.topP, "topP")
            assertNull(opts.topK, "topK")
        }

        @Test
        fun `claude-opus-4-8 with thinking off drops temperature, topP and topK`() {
            assertNoSampling(convert(sampling.withoutThinking(), AnthropicModels.CLAUDE_OPUS_4_8))
        }

        @Test
        fun `claude-opus-4-8 with thinking omitted drops temperature, topP and topK`() {
            assertNoSampling(convert(sampling, AnthropicModels.CLAUDE_OPUS_4_8))
        }

        @Test
        fun `claude-opus-4-8 with thinking on drops temperature, topP and topK`() {
            assertNoSampling(convert(sampling.withThinking(Thinking.withTokenBudget(8000)), AnthropicModels.CLAUDE_OPUS_4_8))
        }

        @Test
        fun `claude-sonnet-5-5 with thinking off drops temperature, topP and topK`() {
            assertNoSampling(convert(sampling.withoutThinking(), AnthropicModels.CLAUDE_SONNET_5_5))
        }

        @Test
        fun `claude-sonnet-5-5 with thinking omitted drops temperature, topP and topK`() {
            assertNoSampling(convert(sampling, AnthropicModels.CLAUDE_SONNET_5_5))
        }

        @Test
        fun `claude-sonnet-5-5 with thinking on drops temperature, topP and topK`() {
            assertNoSampling(convert(sampling.withThinking(Thinking.withTokenBudget(8000)), AnthropicModels.CLAUDE_SONNET_5_5))
        }

        @Test
        fun `claude-sonnet-5-5 drops default temperature too`() {
            assertNull(convert(LlmOptions().withTemperature(1.0), AnthropicModels.CLAUDE_SONNET_5_5).temperature)
        }

        @Test
        fun `warn-once state stays bounded when temperature varies`() {
            val model = AnthropicModels.CLAUDE_OPUS_4_8
            (1..50).forEach { convert(LlmOptions().withTemperature(it / 100.0), model) }
            val keysForModel = AnthropicOptionsConverter.warnedIgnoredSampling.count { it.startsWith("$model|") }
            assertTrue(keysForModel <= 7, "expected at most one key per parameter-name subset, got $keysForModel")
        }

        @Test
        fun `claude-sonnet-4-5 passes temperature, topP and topK through`() {
            val opts = convert(sampling, AnthropicModels.CLAUDE_SONNET_4_5)
            assertEquals(0.7, opts.temperature)
            assertEquals(0.9, opts.topP)
            assertEquals(40, opts.topK)
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


    @Nested
    inner class ThinkingOptOutTests {

        private val haiku = "claude-haiku-5-5"

        private fun convert(options: LlmOptions, model: String) =
            optionsConverter.convertOptions(options, model) as AnthropicChatOptions

        @Test
        fun `claude-haiku-5-5 withoutThinking sends thinking disabled`() {
            val opts = convert(LlmOptions().withoutThinking(), haiku)
            assertTrue(opts.thinking?.isDisabled() == true, "expected thinking disabled, got ${opts.thinking}")
        }

        @Test
        fun `claude-haiku-5-5 withoutThinking sets no output config — caller and default output controls survive`() {
            assertNull(convert(LlmOptions().withoutThinking(), haiku).outputConfig)
        }

        /**
         * The legacy streaming path goes through ChatClient, which starts from the model's default options and
         * replaces outputConfig wholesale with any non-null request value. The opt-out must leave it null so a
         * default schema and an already-compatible effort survive.
         */
        @Test
        fun `claude-haiku-5-5 withoutThinking keeps a default output schema and low effort through ChatClient merge`() {
            val defaults = AnthropicChatOptions.builder()
                .model(haiku)
                .thinkingAdaptive(ThinkingConfigAdaptive.Display.SUMMARIZED)
                .effort(OutputConfig.Effort.LOW)
                .outputSchema("""{"type":"object","properties":{},"additionalProperties":false}""")
                .build()

            val merged = mergeThroughChatClient(defaults, convert(LlmOptions().withoutThinking(), haiku))

            assertTrue(merged.thinking?.isDisabled() == true, "expected thinking disabled, got ${merged.thinking}")
            assertNotNull(merged.outputSchema, "default output schema was dropped by the merge")
            assertEquals(OutputConfig.Effort.LOW, merged.outputConfig?.effort()?.orElse(null))
        }

        /**
         * Known limitation: a default effort of XHIGH/MAX (e.g. a Haiku catalog thinking budget ≥ 16384) survives
         * next to disabled, which Anthropic rejects with HTTP 400. Follow-up: clamp effort at the merge boundary
         * (https://github.com/embabel/embabel-agent/issues/2174). Flip this test when it lands.
         */
        @Test
        fun `claude-haiku-5-5 withoutThinking keeps an xhigh or max default effort through ChatClient merge — known limitation`() {
            listOf(OutputConfig.Effort.XHIGH, OutputConfig.Effort.MAX).forEach { defaultEffort ->
                val defaults = AnthropicChatOptions.builder()
                    .model(haiku)
                    .thinkingAdaptive(ThinkingConfigAdaptive.Display.SUMMARIZED)
                    .effort(defaultEffort)
                    .build()

                val merged = mergeThroughChatClient(defaults, convert(LlmOptions().withoutThinking(), haiku))

                assertTrue(merged.thinking?.isDisabled() == true, "expected thinking disabled, got ${merged.thinking}")
                assertEquals(defaultEffort, merged.outputConfig?.effort()?.orElse(null))
            }
        }

        private fun mergeThroughChatClient(defaults: AnthropicChatOptions, request: AnthropicChatOptions): AnthropicChatOptions {
            val model = CapturingChatModel(defaults)
            ChatClient.create(model).prompt(Prompt("test", request)).call().content()
            return model.captured!!.options as AnthropicChatOptions
        }

        @Test
        fun `older models withoutThinking set no effort`() {
            assertNull(convert(LlmOptions().withoutThinking(), AnthropicModels.CLAUDE_SONNET_4_5).outputConfig)
            assertNull(convert(LlmOptions().withoutThinking(), AnthropicModels.CLAUDE_OPUS_4_8).outputConfig)
        }

        @Test
        fun `claude-haiku-5-5 thinking not set omits thinking field`() {
            assertNull(convert(LlmOptions(), haiku).thinking)
        }

        @Test
        fun `claude-haiku-5-5 with extraction only omits thinking field — extraction is not an opt-out`() {
            assertNull(convert(LlmOptions().withThinking(Thinking.withExtraction()), haiku).thinking)
        }

        @Test
        fun `claude-haiku-5-5 with budget uses adaptive thinking and mapped effort`() {
            val opts = convert(LlmOptions().withThinking(Thinking.withTokenBudget(8000)), haiku)
            assertTrue(opts.thinking.isAdaptive())
            assertEquals(OutputConfig.Effort.HIGH, opts.outputConfig?.effort()?.orElse(null))
        }

        @Test
        fun `claude-sonnet-5-5 withoutThinking omits thinking and warns once`() {
            val model = AnthropicModels.CLAUDE_SONNET_5_5
            AnthropicOptionsConverter.warnedThinkingNotDisabled.remove(model)
            val logger = LoggerFactory.getLogger(AnthropicOptionsConverter::class.java) as Logger
            val logs = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(logs)
            try {
                assertNull(convert(LlmOptions().withoutThinking(), model).thinking)
                assertNull(convert(LlmOptions().withoutThinking(), model).thinking)
                val warnings = logs.list.filter { it.level == Level.WARN && it.formattedMessage.contains("cannot disable thinking") }
                assertEquals(1, warnings.size, "expected exactly one warning, got ${warnings.map { it.formattedMessage }}")
                assertTrue(warnings.single().formattedMessage.contains(model))
            } finally {
                logger.detachAppender(logs)
            }
        }

        @Test
        fun `claude-opus-5-5 withoutThinking omits thinking field`() {
            assertNull(convert(LlmOptions().withoutThinking(), AnthropicModels.CLAUDE_OPUS_5_5).thinking)
        }

        @Test
        fun `claude-sonnet-5-5 and opus-5-5 thinking not set omit thinking field`() {
            assertNull(convert(LlmOptions(), AnthropicModels.CLAUDE_SONNET_5_5).thinking)
            assertNull(convert(LlmOptions(), AnthropicModels.CLAUDE_OPUS_5_5).thinking)
        }

        @Test
        fun `claude-opus-5-5 with budget uses adaptive thinking and mapped effort`() {
            val opts = convert(LlmOptions().withThinking(Thinking.withTokenBudget(2000)), AnthropicModels.CLAUDE_OPUS_5_5)
            assertTrue(opts.thinking.isAdaptive())
            assertEquals(OutputConfig.Effort.MEDIUM, opts.outputConfig?.effort()?.orElse(null))
        }

        @Test
        fun `claude-sonnet-4-5 not set and withoutThinking both send disabled`() {
            assertTrue(convert(LlmOptions(), AnthropicModels.CLAUDE_SONNET_4_5).thinking.isDisabled())
            assertTrue(convert(LlmOptions().withoutThinking(), AnthropicModels.CLAUDE_SONNET_4_5).thinking.isDisabled())
        }

        @Test
        fun `claude-opus-4-8 not set and withoutThinking both send disabled`() {
            assertTrue(convert(LlmOptions(), AnthropicModels.CLAUDE_OPUS_4_8).thinking.isDisabled())
            assertTrue(convert(LlmOptions().withoutThinking(), AnthropicModels.CLAUDE_OPUS_4_8).thinking.isDisabled())
        }

        @Test
        fun `claude-opus-5-5 withoutThinking sets no effort`() {
            assertNull(convert(LlmOptions().withoutThinking(), AnthropicModels.CLAUDE_OPUS_5_5).outputConfig)
        }

        @Test
        fun `claude-opus-4-8 with budget uses adaptive thinking`() {
            val opts = convert(LlmOptions().withThinking(Thinking.withTokenBudget(8000)), AnthropicModels.CLAUDE_OPUS_4_8)
            assertTrue(opts.thinking.isAdaptive())
            assertEquals(OutputConfig.Effort.HIGH, opts.outputConfig?.effort()?.orElse(null))
        }
    }

    private class CapturingChatModel(private val defaults: AnthropicChatOptions) : ChatModel {
        var captured: Prompt? = null
        override fun getOptions(): ChatOptions = defaults
        override fun call(prompt: Prompt): ChatResponse {
            captured = prompt
            return ChatResponse(listOf(Generation(AssistantMessage("ok"))))
        }
    }

}
