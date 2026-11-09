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
import com.embabel.agent.api.models.AnthropicModels
import com.embabel.agent.test.models.OptionsConverterTestSupport
import com.embabel.chat.MessageRole
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.OptionsConverter
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.ReasoningEffort
import com.embabel.common.ai.model.Thinking
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
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
            val keysForModel = ClaudeCapabilities.warnedIgnoredSampling.count { it.startsWith("$model|") }
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

    @Nested
    inner class ReasoningEffortTests {

        private fun convert(options: LlmOptions, model: String) =
            optionsConverter.convertOptions(options, model) as AnthropicChatOptions

        private fun assertEffortWithDefaultThinking(opts: AnthropicChatOptions, effort: ReasoningEffort, label: String) {
            assertNull(opts.thinking, "$label keeps the provider's thinking default")
            assertEquals(effort.value, opts.outputConfig?.effort()?.orElseThrow()?.asString(), label)
        }

        @Test
        fun `every level from low to max is sent as output_config effort with the provider's thinking default`() {
            for (effort in ReasoningEffort.entries - ReasoningEffort.NONE) {
                assertEffortWithDefaultThinking(convert(LlmOptions().withReasoningEffort(effort), AnthropicModels.CLAUDE_HAIKU_5_5), effort, "$effort")
            }
        }

        @Test
        fun `models before 4-7 that accept effort get it too`() {
            for (model in listOf("claude-opus-4-5", AnthropicModels.CLAUDE_OPUS_4_6, AnthropicModels.CLAUDE_SONNET_4_6)) {
                assertEffortWithDefaultThinking(convert(LlmOptions().withReasoningEffort(ReasoningEffort.LOW), model), ReasoningEffort.LOW, model)
            }
        }

        @Test
        fun `a model whose default is thinking off keeps it off and still gets the effort`() {
            assertEffortWithDefaultThinking(
                convert(LlmOptions().withReasoningEffort(ReasoningEffort.LOW), AnthropicModels.CLAUDE_OPUS_4_8),
                ReasoningEffort.LOW, AnthropicModels.CLAUDE_OPUS_4_8,
            )
        }

        @Test
        fun `model names without a parsable version get the effort and never disabled thinking`() {
            for (model in listOf("claude-fable-5", "claude-mythos-preview")) {
                assertEffortWithDefaultThinking(convert(LlmOptions().withReasoningEffort(ReasoningEffort.LOW), model), ReasoningEffort.LOW, model)
            }
        }

        @Test
        fun `the effort composes with a token budget on a model that thinks by budget`() {
            val opts = convert(
                LlmOptions().withThinking(Thinking.withTokenBudget(2048)).withReasoningEffort(ReasoningEffort.LOW),
                "claude-opus-4-5",
            )
            assertEquals(2048L, opts.thinking.asEnabled().budgetTokens())
            assertEquals(OutputConfig.Effort.LOW, opts.outputConfig?.effort()?.orElse(null))
        }

        @Test
        fun `the effort wins over the effort a token budget maps to`() {
            val options = LlmOptions()
                .withThinking(Thinking.withTokenBudget(32768)) // maps to MAX
                .withReasoningEffort(ReasoningEffort.LOW)
            val opts = convert(options, AnthropicModels.CLAUDE_SONNET_5_5)
            assertTrue(opts.thinking.isAdaptive())
            assertEquals(OutputConfig.Effort.LOW, opts.outputConfig?.effort()?.orElse(null))
        }

        @Test
        fun `none is unsupported on a model that declares no thinking capability`() {
            for (model in listOf("claude-fable-5", "claude-mythos-preview", AnthropicModels.CLAUDE_HAIKU_5_5)) {
                val error = assertThrows(UnsupportedOperationException::class.java) {
                    convert(LlmOptions().withReasoningEffort(ReasoningEffort.NONE), model)
                }
                assertTrue(error.message!!.contains(model), error.message)
            }
        }

        @Test
        fun `none turns thinking off where the catalog declares it can`() {
            val opts = AnthropicOptionsConverter.declaring(thinkingCanBeDisabled = true)
                .convertOptions(LlmOptions().withReasoningEffort(ReasoningEffort.NONE), AnthropicModels.CLAUDE_HAIKU_5_5) as AnthropicChatOptions
            assertTrue(opts.thinking.isDisabled())
            assertNull(opts.outputConfig)
        }

        @Test
        fun `none fails where the catalog declares thinking cannot be turned off`() {
            val error = assertThrows(UnsupportedOperationException::class.java) {
                AnthropicOptionsConverter.declaring(thinkingCanBeDisabled = false)
                    .convertOptions(LlmOptions().withReasoningEffort(ReasoningEffort.NONE), AnthropicModels.CLAUDE_OPUS_5_5)
            }
            assertTrue(error.message!!.contains(AnthropicModels.CLAUDE_OPUS_5_5), error.message)
        }

        @Test
        fun `a caller who turned thinking off is not overridden by a role's effort`() {
            val role = LlmOptions.withModel(AnthropicModels.CLAUDE_HAIKU_5_5).withReasoningEffort(ReasoningEffort.HIGH)
            val opts = convert(LlmOptions().withoutThinking().withDefaultsFrom(role), AnthropicModels.CLAUDE_HAIKU_5_5)
            assertTrue(opts.thinking.isDisabled())
            assertNull(opts.outputConfig)
        }

        @Test
        fun `a caller's thinking budget is not overridden by a role's none`() {
            val role = LlmOptions.withModel(AnthropicModels.CLAUDE_SONNET_4_6).withReasoningEffort(ReasoningEffort.NONE)
            val opts = convert(LlmOptions().withThinking(Thinking.withTokenBudget(8192)).withDefaultsFrom(role), AnthropicModels.CLAUDE_SONNET_4_6)
            assertEquals(8192L, opts.thinking.asEnabled().budgetTokens())
        }
    }

    /** What the effort puts on the wire, captured by a local server standing in for Anthropic. */
    @Nested
    inner class ReasoningEffortWireTests {

        private val mapper = jacksonObjectMapper()
        private val captured = CopyOnWriteArrayList<JsonNode>()
        private lateinit var server: HttpServer

        @BeforeEach
        fun startServer() {
            server = HttpServer.create(InetSocketAddress(0), 0)
            server.createContext("/v1/messages") { exchange ->
                captured.add(mapper.readTree(exchange.requestBody.readBytes()))
                val reply = """{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"text","text":"ok"}],
                    "model":"${AnthropicModels.CLAUDE_SONNET_5_5}","stop_reason":"end_turn","stop_sequence":null,
                    "usage":{"input_tokens":1,"output_tokens":1}}""".toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, reply.size.toLong())
                exchange.responseBody.use { it.write(reply) }
            }
            server.start()
        }

        @AfterEach
        fun stopServer() = server.stop(0)

        private fun send(model: String, options: LlmOptions, converter: OptionsConverter = AnthropicOptionsConverter): JsonNode {
            val llm = AnthropicModelFactory(apiKey = "test-key", baseUrl = "http://localhost:${server.address.port}")
                .build(model) as SpringAiLlmService
            llm.chatModel.call(Prompt(listOf(UserMessage("hi")), converter.convertOptions(options, model)))
            return captured.single()
        }

        @Test
        fun `claude-sonnet-5-5 sends the effort as output_config effort and leaves thinking to the model`() {
            val body = send(AnthropicModels.CLAUDE_SONNET_5_5, LlmOptions.withModel(AnthropicModels.CLAUDE_SONNET_5_5).withReasoningEffort(ReasoningEffort.LOW))
            assertEquals("low", body["output_config"]["effort"].asString())
            assertFalse(body.has("thinking"))
        }

        @Test
        fun `claude-haiku-5-5 sends the effort as output_config effort and leaves thinking to the model`() {
            val body = send(AnthropicModels.CLAUDE_HAIKU_5_5, LlmOptions.withModel(AnthropicModels.CLAUDE_HAIKU_5_5).withReasoningEffort(ReasoningEffort.LOW))
            assertEquals("low", body["output_config"]["effort"].asString())
            assertFalse(body.has("thinking"))
        }

        @Test
        fun `claude-haiku-5-5 sends none as disabled thinking without an effort where the catalog declares it`() {
            val body = send(
                AnthropicModels.CLAUDE_HAIKU_5_5,
                LlmOptions.withModel(AnthropicModels.CLAUDE_HAIKU_5_5).withReasoningEffort(ReasoningEffort.NONE),
                AnthropicOptionsConverter.declaring(thinkingCanBeDisabled = true),
            )
            assertEquals("disabled", body["thinking"]["type"].asString())
            assertFalse(body.has("output_config"))
        }
    }
}
