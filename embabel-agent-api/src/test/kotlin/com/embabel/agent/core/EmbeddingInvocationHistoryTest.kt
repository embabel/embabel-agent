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

import com.embabel.common.ai.model.EmbeddingServiceMetadata
import com.embabel.common.ai.model.PricingModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class EmbeddingInvocationHistoryImpl(
    override val embeddingInvocations: MutableList<EmbeddingInvocation> = mutableListOf(),
) : EmbeddingInvocationHistory

class EmbeddingInvocationHistoryTest {

    private val pricedMetadata = EmbeddingServiceMetadata(
        name = "text-embedding-3-small",
        provider = "OpenAI",
        pricingModel = PricingModel.usdPer1MTokens(0.02, 0.0),
    )

    private val freeMetadata = EmbeddingServiceMetadata(
        name = "all-MiniLM-L6-v2",
        provider = "ONNX",
    )

    private fun anInvocation(
        metadata: EmbeddingServiceMetadata,
        promptTokens: Int,
    ) = EmbeddingInvocation(
        embeddingMetadata = metadata,
        usage = Usage(promptTokens = promptTokens, completionTokens = 0, nativeUsage = null),
        agentName = null,
        timestamp = Instant.now(),
        runningTime = Duration.ofMillis(10),
    )

    @Test
    fun `empty history`() {
        val h = EmbeddingInvocationHistoryImpl()
        assertEquals(0.0, h.embeddingCost(), 1e-9)
        assertTrue(h.embeddingModelsUsed().isEmpty())
        assertEquals(0, h.embeddingUsage().promptTokens)
        assertEquals(
            "Embeddings: [] across 0 calls; prompt tokens: 0; cost: $${"%.4f".format(0.0)}",
            h.embeddingCostInfoString(false),
        )
    }

    @Test
    fun `one priced invocation`() {
        val h = EmbeddingInvocationHistoryImpl()
        h.embeddingInvocations as MutableList<EmbeddingInvocation> += anInvocation(pricedMetadata, 1_000_000)
        assertEquals(0.02, h.embeddingCost(), 1e-9)
        assertEquals(setOf(pricedMetadata), h.embeddingModelsUsed().toSet())
        assertEquals(1_000_000, h.embeddingUsage().promptTokens)
    }

    @Test
    fun `free model contributes zero to cost`() {
        val h = EmbeddingInvocationHistoryImpl()
        h.embeddingInvocations as MutableList<EmbeddingInvocation> += anInvocation(freeMetadata, 5_000_000)
        assertEquals(0.0, h.embeddingCost(), 1e-9)
        assertEquals(5_000_000, h.embeddingUsage().promptTokens)
    }

    @Test
    fun `multiple invocations sum tokens and cost`() {
        val h = EmbeddingInvocationHistoryImpl()
        h.embeddingInvocations as MutableList<EmbeddingInvocation> += anInvocation(pricedMetadata, 500_000)
        h.embeddingInvocations as MutableList<EmbeddingInvocation> += anInvocation(pricedMetadata, 1_500_000)
        assertEquals(0.04, h.embeddingCost(), 1e-9)
        assertEquals(2_000_000, h.embeddingUsage().promptTokens)
    }

    @Test
    fun `models used distinct and sorted by name`() {
        val h = EmbeddingInvocationHistoryImpl()
        h.embeddingInvocations as MutableList<EmbeddingInvocation> += anInvocation(pricedMetadata, 100)
        h.embeddingInvocations as MutableList<EmbeddingInvocation> += anInvocation(freeMetadata, 100)
        h.embeddingInvocations as MutableList<EmbeddingInvocation> += anInvocation(pricedMetadata, 100)
        val names = h.embeddingModelsUsed().map { it.name }
        assertEquals(listOf("all-MiniLM-L6-v2", "text-embedding-3-small"), names)
    }

    @Test
    fun `same model used multiple times deduplicates to one entry`() {
        val h = EmbeddingInvocationHistoryImpl()
        h.embeddingInvocations as MutableList<EmbeddingInvocation> += anInvocation(pricedMetadata, 100)
        h.embeddingInvocations as MutableList<EmbeddingInvocation> += anInvocation(pricedMetadata, 200)
        h.embeddingInvocations as MutableList<EmbeddingInvocation> += anInvocation(pricedMetadata, 300)
        assertEquals(listOf(pricedMetadata), h.embeddingModelsUsed())
    }

    @Test
    fun `multiple priced models contribute to combined cost`() {
        val largeModel = EmbeddingServiceMetadata(
            name = "text-embedding-3-large",
            provider = "OpenAI",
            pricingModel = PricingModel.usdPer1MTokens(0.13, 0.0),
        )
        val h = EmbeddingInvocationHistoryImpl()
        h.embeddingInvocations as MutableList<EmbeddingInvocation> += anInvocation(pricedMetadata, 1_000_000)
        h.embeddingInvocations as MutableList<EmbeddingInvocation> += anInvocation(largeModel, 1_000_000)
        assertEquals(0.15, h.embeddingCost(), 1e-9)
        assertEquals(2_000_000, h.embeddingUsage().promptTokens)
        assertEquals(setOf(pricedMetadata, largeModel), h.embeddingModelsUsed().toSet())
    }

    @Test
    fun `costInfoString non-verbose with data`() {
        val h = EmbeddingInvocationHistoryImpl()
        h.embeddingInvocations as MutableList<EmbeddingInvocation> += anInvocation(pricedMetadata, 1_000_000)
        assertEquals(
            "Embeddings: [text-embedding-3-small] across 1 calls; prompt tokens: 1,000,000; cost: \$0.0200",
            h.embeddingCostInfoString(false),
        )
    }

    @Test
    fun `costInfoString verbose with data`() {
        val h = EmbeddingInvocationHistoryImpl()
        h.embeddingInvocations as MutableList<EmbeddingInvocation> += anInvocation(pricedMetadata, 1_000_000)
        val expected = "Embeddings used: [text-embedding-3-small] across 1 calls\n" +
                "Prompt tokens: 1,000,000\n" +
                "Cost: \$0.0200\n"
        assertEquals(expected, h.embeddingCostInfoString(true))
    }
}
