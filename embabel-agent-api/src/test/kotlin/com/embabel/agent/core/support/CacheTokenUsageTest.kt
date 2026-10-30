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
package com.embabel.agent.core.support

import com.embabel.agent.core.EarlyTerminationPolicy
import com.embabel.agent.core.LlmInvocation
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.Usage
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.agent.spi.support.springai.toEmbabelUsage
import com.embabel.agent.support.SimpleTestAgent
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmMetadata
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.PerTokenPricingModel
import com.embabel.common.ai.model.PricingModel
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.metadata.Usage as SpringAiUsage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import java.time.Duration
import java.time.Instant

/**
 * Prompt-cache tokens must reach Embabel usage, cost and budgets.
 * Anthropic reports cache reads and writes in addition to promptTokens;
 * OpenAI reports cached reads as part of promptTokens.
 */
class CacheTokenUsageTest {

    private val anthropicStyleUsage = DefaultUsage(100, 50, 150, null, 18_000L, 2_000L)

    private val cachePricing = PerTokenPricingModel(
        usdPer1mInputTokens = 2.0,
        usdPer1mOutputTokens = 10.0,
        usdPer1mCacheReadInputTokens = 0.2,
        usdPer1mCacheWriteInputTokens = 2.5,
    )

    private fun llm(pricingModel: PricingModel) = LlmMetadata.create("model", "provider", null, pricingModel)

    private fun invocation(usage: Usage, pricingModel: PricingModel = cachePricing) = LlmInvocation(
        llmMetadata = llm(pricingModel),
        usage = usage,
        timestamp = Instant.now(),
        runningTime = Duration.ZERO,
    )

    private fun process() = SimpleAgentProcess(
        id = "p1",
        parentId = null,
        agent = SimpleTestAgent,
        processOptions = ProcessOptions(),
        blackboard = InMemoryBlackboard(),
        platformServices = dummyPlatformServices(),
        plannerFactory = DefaultPlannerFactory,
    )

    @Nested
    inner class AnthropicStyle {

        @Test
        fun `cache reads and writes count as input tokens`() {
            val usage = anthropicStyleUsage.toEmbabelUsage(promptTokensExcludeCache = true)
            assertEquals(20_100, usage.promptTokens)
            assertEquals(18_000, usage.cacheReadInputTokens)
            assertEquals(2_000, usage.cacheWriteInputTokens)
            assertEquals(20_150, usage.totalTokens)
        }

        @Test
        fun `cost prices cache reads and writes at their own rates`() {
            val usage = anthropicStyleUsage.toEmbabelUsage(promptTokensExcludeCache = true)
            assertEquals(0.0093, invocation(usage).cost(), 1e-12)
        }

        @Test
        fun `cache tokens without cache rates are priced as ordinary input`() {
            val usage = anthropicStyleUsage.toEmbabelUsage(promptTokensExcludeCache = true)
            val cost = invocation(usage, PricingModel.usdPer1MTokens(2.0, 10.0)).cost()
            assertEquals((20_100 * 2.0 + 50 * 10.0) / 1_000_000, cost, 1e-12)
        }

        @Test
        fun `cache tokens survive process aggregation`() {
            val process = process()
            val usage = anthropicStyleUsage.toEmbabelUsage(promptTokensExcludeCache = true)
            process.recordLlmInvocation(invocation(usage))
            process.recordLlmInvocation(invocation(usage))
            val total = process.totalUsage()
            assertEquals(40_200, total.promptTokens)
            assertEquals(36_000, total.cacheReadInputTokens)
            assertEquals(4_000, total.cacheWriteInputTokens)
            assertEquals(36_000, process.usage().cacheReadInputTokens)
            assertEquals(0.0186, process.totalCost(), 1e-12)
        }

        @Test
        fun `cost budget between ordinary-only and cache-inclusive cost trips`() {
            val process = process()
            process.recordLlmInvocation(invocation(anthropicStyleUsage.toEmbabelUsage(promptTokensExcludeCache = true)))
            assertNotNull(EarlyTerminationPolicy.hardBudgetLimit(0.005).shouldTerminate(process))
        }

        @Test
        fun `token budget counts cache tokens`() {
            val process = process()
            process.recordLlmInvocation(invocation(anthropicStyleUsage.toEmbabelUsage(promptTokensExcludeCache = true)))
            assertNotNull(EarlyTerminationPolicy.maxTokens(20_000).shouldTerminate(process))
        }

        @Test
        fun `message sender of a cache-excluding service converts usage accordingly`() {
            val chatModel = mockk<ChatModel>()
            every { chatModel.call(any<org.springframework.ai.chat.prompt.Prompt>()) } returns ChatResponse(
                listOf(Generation(AssistantMessage("done"))),
                ChatResponseMetadata.builder().usage(anthropicStyleUsage).build(),
            )
            val service = SpringAiLlmService(
                name = "claude",
                provider = "provider",
                chatModel = chatModel,
                promptTokensExcludeCache = true,
            )
            val usage = service.createMessageSender(LlmOptions()).call(listOf(UserMessage("hi")), emptyList()).usage
            assertEquals(20_100, usage?.promptTokens)
            assertEquals(18_000, usage?.cacheReadInputTokens)
        }
    }

    @Nested
    inner class Unchanged {

        @Test
        fun `no-cache usage keeps tokens and cost`() {
            val usage = DefaultUsage(100, 50).toEmbabelUsage()
            assertEquals(100, usage.promptTokens)
            assertNull(usage.cacheReadInputTokens)
            assertNull(usage.cacheWriteInputTokens)
            assertEquals(0.0007, invocation(usage).cost(), 1e-12)
        }

        @Test
        fun `cached reads already inside promptTokens are not double counted`() {
            val usage = DefaultUsage(20_100, 50, 20_150, null, 18_000L, null).toEmbabelUsage()
            assertEquals(20_100, usage.promptTokens)
            assertEquals(18_000, usage.cacheReadInputTokens)
            val cost = invocation(usage, PricingModel.usdPer1MTokens(2.0, 10.0)).cost()
            assertEquals((20_100 * 2.0 + 50 * 10.0) / 1_000_000, cost, 1e-12)
        }

        @Test
        fun `custom costOf usage override is honoured`() {
            val overriding = object : PricingModel {
                override fun usdPerInputToken() = 2.0 / 1_000_000
                override fun usdPerOutputToken() = 10.0 / 1_000_000
                override fun costOf(usage: SpringAiUsage) = 0.123
            }
            assertEquals(0.123, invocation(Usage(100, 50, null), overriding).cost(), 1e-12)
        }

        @Test
        fun `ordinary-only budget does not trip`() {
            val process = process()
            process.recordLlmInvocation(invocation(DefaultUsage(100, 50).toEmbabelUsage()))
            assertNull(EarlyTerminationPolicy.hardBudgetLimit(0.005).shouldTerminate(process))
        }
    }
}
