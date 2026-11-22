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
package com.embabel.agent.core

import com.embabel.common.ai.model.LlmMetadata
import com.embabel.common.ai.model.NativeUsage
import com.embabel.common.ai.model.PerTokenPricingModel
import com.embabel.common.ai.model.PricingModel
import com.embabel.common.ai.model.withAnthropicCacheRateDefaults
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class NativeUsageTest {

    private fun cost(pricing: PricingModel, usage: Usage): Double {
        val llm = mockk<LlmMetadata> { every { pricingModel } returns pricing }
        return LlmInvocation(llm, usage, timestamp = Instant.now(), runningTime = Duration.ZERO).cost()
    }

    private fun usage(native: NativeUsage) = Usage(native.promptTokens, native.outputTokens, null, billableUsage = native)

    @Test
    fun `plus sums native usage instead of dropping it`() {
        val a = NativeUsage(inputTokens = 100, outputTokens = 50, cacheReadInputTokens = 10,
            cacheWriteInputTokens = 5, cacheWriteLongInputTokens = 1000, thinkingTokens = 30)
        val b = NativeUsage(inputTokens = 1, outputTokens = 2, cacheReadInputTokens = 3,
            cacheWriteInputTokens = 4, cacheWriteLongInputTokens = 5, thinkingTokens = 6)

        assertEquals(NativeUsage(101, 52, 13, 9, 1005, 36), (usage(a) + usage(b)).billableUsage)
    }

    @Test
    fun `plus derives native usage for a side that has none`() {
        val withNative = usage(NativeUsage(inputTokens = 100, outputTokens = 50))
        val generic = Usage(promptTokens = 30, completionTokens = 7, nativeUsage = null, cacheReadInputTokens = 20)

        assertEquals(
            NativeUsage(inputTokens = 110, outputTokens = 57, cacheReadInputTokens = 20),
            (withNative + generic).billableUsage,
        )
        assertNull((generic + generic).billableUsage)
    }

    @Test
    fun `Anthropic 1h cache writes from two calls cost 2x input`() {
        // Issue #2260: two 1h writes of 1,000 tokens at $2/M input cost $0.008, not the 5m rate's $0.005.
        val pricing = PerTokenPricingModel(2.0, 10.0).withAnthropicCacheRateDefaults()
        val call = usage(NativeUsage(cacheWriteLongInputTokens = 1000))

        assertEquals(0.008, cost(pricing, call + call), 1e-12)
    }

    @Test
    fun `OpenAI cache writes cost the write rate and reads the read rate`() {
        val pricing = PerTokenPricingModel(1.0, 8.0, usdPer1mCacheReadInputTokens = 0.1, usdPer1mCacheWriteInputTokens = 1.25)
        val call = usage(NativeUsage(inputTokens = 1000, cacheReadInputTokens = 800, cacheWriteInputTokens = 10_000))

        assertEquals((1000 * 1.0 + 800 * 0.1 + 10_000 * 1.25) / 1_000_000, cost(pricing, call), 1e-12)
    }

    @Test
    fun `thinking tokens are priced as output`() {
        val pricing = PerTokenPricingModel(2.0, 10.0)
        // 20 visible plus 30 thinking output tokens.
        val call = usage(NativeUsage(inputTokens = 100, outputTokens = 50, thinkingTokens = 30))

        assertEquals((100 * 2.0 + 50 * 10.0) / 1_000_000, cost(pricing, call + call) / 2, 1e-12)
    }

    @Test
    fun `an unknown long-TTL write rate is never cheaper than 2_5x input`() {
        val pricing = PerTokenPricingModel(2.0, 10.0)

        assertEquals(1000 * 2.0 * 2.5 / 1_000_000, cost(pricing, usage(NativeUsage(cacheWriteLongInputTokens = 1000))), 1e-12)
    }
}
