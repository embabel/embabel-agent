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
package com.embabel.agent.config.models.googlegenai

import com.embabel.agent.core.LlmInvocation
import com.embabel.agent.spi.support.springai.toEmbabelUsage
import com.embabel.common.ai.model.LlmMetadata
import com.embabel.common.ai.model.NativeUsage
import com.embabel.common.ai.model.PerTokenPricingModel
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.ai.google.genai.metadata.GoogleGenAiUsage
import java.time.Duration
import java.time.Instant

class GoogleGenAiNativeUsageReaderTest {

    private fun call(prompt: Int, candidates: Int, thoughts: Int, toolUsePrompt: Int?, cached: Int?) =
        GoogleGenAiUsage(prompt, candidates, prompt + candidates + thoughts + (toolUsePrompt ?: 0), thoughts, cached,
            toolUsePrompt, null, null, null, null, null, null)
            .toEmbabelUsage(false, GoogleGenAiNativeUsageReader)

    @Test
    fun `two calls keep every thinking token in output and price tool-use prompt tokens as input`() {
        // Issue #2260: merging two calls of 100 prompt, 20 candidates and 30 thoughts lost all 60 thoughts.
        val usage = call(100, 20, 30, toolUsePrompt = 40, cached = 10) + call(100, 20, 30, toolUsePrompt = null, cached = null)

        assertEquals(
            NativeUsage(inputTokens = 230, outputTokens = 100, cacheReadInputTokens = 10, thinkingTokens = 60),
            usage.billableUsage,
        )
        assertEquals(100, usage.completionTokens)
        assertEquals(240, usage.promptTokens)
        val llm = mockk<LlmMetadata> { every { pricingModel } returns PerTokenPricingModel(2.0, 10.0, 0.5) }
        val cost = LlmInvocation(llm, usage, timestamp = Instant.now(), runningTime = Duration.ZERO).cost()
        assertEquals((230 * 2.0 + 10 * 0.5 + 100 * 10.0) / 1_000_000, cost, 1e-12)
    }
}
