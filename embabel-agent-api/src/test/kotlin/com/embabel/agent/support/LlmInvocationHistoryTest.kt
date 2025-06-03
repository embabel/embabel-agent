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
package com.embabel.agent.support

import com.embabel.agent.core.LlmInvocation
import com.embabel.agent.core.LlmInvocationHistory
import com.embabel.common.ai.model.Llm
import com.embabel.common.ai.model.PricingModel
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.metadata.DefaultUsage
import java.time.Duration
import java.time.Instant

class LlmInvocationHistoryImpl(
    override val llmInvocations: MutableList<LlmInvocation> = mutableListOf(),
) : LlmInvocationHistory

class LlmInvocationHistoryTest {

    @Test
    fun `empty history`() {
        val llmih = LlmInvocationHistoryImpl()
        assertEquals(0.0, llmih.cost(), "No cost yet")
        assertTrue(llmih.modelsUsed().isEmpty(), "No models used yet")
        assertEquals(0, llmih.usage().promptTokens, "No prompt tokens yet")
        assertEquals(0, llmih.usage().completionTokens, "No completion tokens yet")
        assertEquals(
            "LLMs: []; prompt tokens: 0; completion tokens: 0; cost: $${"%.4f".format(0.0)}",
            llmih.costInfoString(false),
            "No cost info yet"
        )
    }

    @Test
    fun `one call`() {
        val llmih = LlmInvocationHistoryImpl()
        val mockLlm = mockk<Llm>()
        every { mockLlm.name } returns "Mock LLM"
        every { mockLlm.pricingModel } returns PricingModel.ALL_YOU_CAN_EAT
        val usage = DefaultUsage(100, 200)
        llmih.llmInvocations += LlmInvocation(
            llm = mockLlm,
            timestamp = Instant.now(),
            runningTime = Duration.ofMillis(100),
            usage = usage,
        )
        assertEquals(0.0, llmih.cost(), "No cost as it's an all you can eat model")
        assertEquals(setOf(mockLlm), llmih.modelsUsed().toSet(), "Model used")
        assertEquals(100, llmih.usage().promptTokens, "Correct prompt tokens")
        assertEquals(200, llmih.usage().completionTokens, "Correct completion tokens")
        assertEquals(
            "LLMs: [Mock LLM]; prompt tokens: ${usage.promptTokens}; completion tokens: ${usage.completionTokens}; cost: $${"%.4f".format(0.0)}",
            llmih.costInfoString(false),
            "No cost info yet"
        )
    }
}
