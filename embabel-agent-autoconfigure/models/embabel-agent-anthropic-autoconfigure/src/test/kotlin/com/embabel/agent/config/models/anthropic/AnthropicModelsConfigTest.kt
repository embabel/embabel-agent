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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.embabel.agent.anthropic.ClaudeCapabilities
import com.embabel.agent.api.dsl.agent
import com.embabel.agent.core.EarlyTerminationPolicy
import com.embabel.agent.core.LlmInvocation
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.SimpleAgentProcess
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.agent.spi.support.springai.toEmbabelUsage
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import com.embabel.common.ai.autoconfig.LlmAutoConfigMetadataLoader
import com.embabel.common.ai.model.ONE_MILLION
import com.embabel.common.ai.model.PerTokenPricingModel
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.slf4j.LoggerFactory
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.web.client.RestClient
import java.time.Duration
import java.time.Instant

class AnthropicModelsConfigTest {

    private class Built(val llm: SpringAiLlmService, val options: AnthropicChatOptions, val warnings: List<String>)

    private fun build(modelDef: AnthropicModelDefinition): Built {
        val logger = LoggerFactory.getLogger(ClaudeCapabilities::class.java) as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        try {
            val beanFactory = DefaultListableBeanFactory()
            AnthropicModelsConfig(
                envBaseUrl = null,
                envApiKey = "test-key",
                properties = AnthropicProperties(),
                observationRegistry = beanFactory.getBeanProvider(ObservationRegistry::class.java),
                restClientBuilder = beanFactory.getBeanProvider(RestClient.Builder::class.java),
                configurableBeanFactory = beanFactory,
                modelLoader = object : LlmAutoConfigMetadataLoader<AnthropicModelDefinitions> {
                    override fun loadAutoConfigMetadata() = AnthropicModelDefinitions(models = listOf(modelDef))
                },
            ).anthropicModelsInitializer()
            val llm = beanFactory.getBean(modelDef.name) as SpringAiLlmService
            val options = llm.chatModel.defaultOptions as AnthropicChatOptions
            return Built(llm, options, logs.list.filter { it.level == Level.WARN }.map { it.formattedMessage })
        } finally {
            logger.detachAppender(logs)
        }
    }

    @Test
    fun `restricted model drops configured sampling with one warning naming model and values`() {
        val built = build(
            AnthropicModelDefinition(
                name = "sonnet55", modelId = "claude-sonnet-5-5", temperature = 0.3, topP = 0.9, topK = 40,
            )
        )
        assertNull(built.options.temperature)
        assertNull(built.options.topP)
        assertNull(built.options.topK)
        assertEquals(1, built.warnings.size, "${built.warnings}")
        val warning = built.warnings.single()
        assertTrue(
            warning.contains("claude-sonnet-5-5") && warning.contains("temperature=0.3") &&
                warning.contains("topP=0.9") && warning.contains("topK=40")
        ) { warning }
    }

    @Test
    fun `restricted model with default temperature drops it without warning`() {
        val built = build(AnthropicModelDefinition(name = "sonnet55", modelId = "claude-sonnet-5-5", temperature = 1.0))
        assertNull(built.options.temperature)
        assertTrue(built.warnings.isEmpty()) { "${built.warnings}" }
    }

    @Test
    fun `restricted model without sampling configured does not warn`() {
        val built = build(AnthropicModelDefinition(name = "sonnet55", modelId = "claude-sonnet-5-5"))
        assertTrue(built.warnings.isEmpty()) { "${built.warnings}" }
    }

    @Test
    fun `older model passes sampling through without warning`() {
        val built = build(
            AnthropicModelDefinition(
                name = "sonnet45", modelId = "claude-sonnet-4-5", temperature = 0.3, topP = 0.9, topK = 40,
            )
        )
        assertEquals(0.3, built.options.temperature)
        assertEquals(0.9, built.options.topP)
        assertEquals(40, built.options.topK)
        assertTrue(built.warnings.isEmpty()) { "${built.warnings}" }
    }

    @Test
    fun `cache tokens are counted on top of prompt tokens`() {
        val llm = build(AnthropicModelDefinition(name = "sonnet55", modelId = "claude-sonnet-5-5")).llm
        assertTrue(llm.promptTokensExcludeCache)
    }

    @Test
    fun `model without configured cache rates never under-charges cache reads`() {
        val llm = build(
            AnthropicModelDefinition(
                name = "custom",
                modelId = "claude-sonnet-4-5",
                pricingModel = PerTokenPricingModel(2.0, 10.0),
            )
        ).llm
        // reads at the input rate, writes at the 5-minute TTL rate of 1.25x input
        assertEquals((100 * 2.0 + 50 * 10.0 + 18_000 * 2.0 + 2_000 * 2.5) / 1_000_000,
            llm.pricingModel!!.costOf(100, 50, 18_000, 2_000), 1e-12)
    }

    @Test
    fun `configured cache rates win over the fallback`() {
        val llm = build(
            AnthropicModelDefinition(
                name = "sonnet55",
                modelId = "claude-sonnet-5-5",
                pricingModel = PerTokenPricingModel(2.0, 10.0, 0.0, 0.0),
            )
        ).llm
        assertEquals(0.0007, llm.pricingModel!!.costOf(100, 50, 18_000, 2_000), 1e-12)
    }

    @Nested
    inner class BundledModels {

        private val beanFactory = DefaultListableBeanFactory().also {
            AnthropicModelsConfig(
                envBaseUrl = null,
                envApiKey = "test-key",
                properties = AnthropicProperties(),
                observationRegistry = it.getBeanProvider(ObservationRegistry::class.java),
                restClientBuilder = it.getBeanProvider(RestClient.Builder::class.java),
                configurableBeanFactory = it,
                modelLoader = AnthropicModelLoader(),
            ).anthropicModelsInitializer()
        }

        private fun llm(name: String) = beanFactory.getBean(name) as SpringAiLlmService

        /**
         * Anthropic's published rates, platform.claude.com/docs/en/about-claude/pricing,
         * checked 2026-10-29: cache hits, then 5-minute cache writes, in USD per 1M tokens.
         */
        @ParameterizedTest
        @CsvSource(
            "claude_fable_51, 0.25, 12.5",
            "claude_opus_55, 0.20, 5.0",
            "claude_sonnet_55, 0.10, 2.5",
            "claude_haiku_55, 0.01, 0.125",
            "claude_opus_48, 0.50, 6.25",
            "claude_opus_46, 0.50, 6.25",
            "claude_sonnet_46, 0.30, 3.75",
            "claude_sonnet_45, 0.30, 3.75",
            "claude_haiku_45, 0.10, 1.25",
        )
        fun `carry Anthropic's published cache rates`(name: String, read: Double, write: Double) {
            val pricing = llm(name).pricingModel!!
            assertEquals(read, pricing.usdPerCacheReadInputToken() * ONE_MILLION, 1e-9)
            assertEquals(write, pricing.usdPerCacheWriteInputToken() * ONE_MILLION, 1e-9)
        }

        @Test
        fun `every bundled model is covered by the published rates`() {
            assertEquals(9, AnthropicModelLoader().loadAutoConfigMetadata().models.size)
        }

        @Test
        fun `Sonnet 5_5 cache invocation costs its published price and stays within a budget above it`() {
            val sonnet = llm("claude_sonnet_55")
            val process = SimpleAgentProcess(
                id = "p1",
                parentId = null,
                agent = agent("CacheBudget", description = "cache budget") {
                    transformation<UserInput, UserInput>(name = "echo") { it.input }
                    goal(name = "done", description = "done", satisfiedBy = UserInput::class)
                },
                processOptions = ProcessOptions(),
                blackboard = InMemoryBlackboard(),
                platformServices = dummyPlatformServices(),
                plannerFactory = DefaultPlannerFactory,
            )
            process.recordLlmInvocation(
                LlmInvocation(
                    llmMetadata = sonnet,
                    usage = DefaultUsage(100, 50, 150, null, 18_000L, 2_000L)
                        .toEmbabelUsage(promptTokensExcludeCache = sonnet.promptTokensExcludeCache),
                    timestamp = Instant.now(),
                    runningTime = Duration.ZERO,
                )
            )
            assertEquals(0.0075, process.totalCost(), 1e-12)
            assertNull(EarlyTerminationPolicy.hardBudgetLimit(0.008).shouldTerminate(process))
            assertNotNull(EarlyTerminationPolicy.hardBudgetLimit(0.007).shouldTerminate(process))
        }
    }
}
