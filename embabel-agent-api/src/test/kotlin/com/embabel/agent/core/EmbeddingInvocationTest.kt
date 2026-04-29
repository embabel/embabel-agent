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
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class EmbeddingInvocationTest {

    private val timestamp: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val runningTime: Duration = Duration.ofMillis(50)

    @Test
    fun `cost is computed from pricing model when present`() {
        val metadata = EmbeddingServiceMetadata(
            name = "text-embedding-3-small",
            provider = "OpenAI",
            pricingModel = PricingModel.usdPer1MTokens(0.02, 0.0),
        )
        val invocation = EmbeddingInvocation(
            embeddingMetadata = metadata,
            usage = Usage(promptTokens = 1_000_000, completionTokens = 0, nativeUsage = null),
            agentName = "test",
            timestamp = timestamp,
            runningTime = runningTime,
        )
        assertEquals(0.02, invocation.cost(), 1e-9)
    }

    @Test
    fun `cost is zero when pricing model is null`() {
        val metadata = EmbeddingServiceMetadata(
            name = "all-MiniLM-L6-v2",
            provider = "ONNX",
        )
        val invocation = EmbeddingInvocation(
            embeddingMetadata = metadata,
            usage = Usage(promptTokens = 50_000, completionTokens = 0, nativeUsage = null),
            agentName = "test",
            timestamp = timestamp,
            runningTime = runningTime,
        )
        assertEquals(0.0, invocation.cost(), 1e-9)
    }

    @Test
    fun `cost is zero when promptTokens is null`() {
        val metadata = EmbeddingServiceMetadata(
            name = "text-embedding-3-small",
            provider = "OpenAI",
            pricingModel = PricingModel.usdPer1MTokens(0.02, 0.0),
        )
        val invocation = EmbeddingInvocation(
            embeddingMetadata = metadata,
            usage = Usage(promptTokens = null, completionTokens = null, nativeUsage = null),
            agentName = null,
            timestamp = timestamp,
            runningTime = runningTime,
        )
        assertEquals(0.0, invocation.cost(), 1e-9)
    }

    @Test
    fun `cost ignores completionTokens since embeddings have none`() {
        val metadata = EmbeddingServiceMetadata(
            name = "text-embedding-3-small",
            provider = "OpenAI",
            pricingModel = PricingModel.usdPer1MTokens(0.02, 100.0),
        )
        val invocation = EmbeddingInvocation(
            embeddingMetadata = metadata,
            usage = Usage(promptTokens = 1_000_000, completionTokens = 999, nativeUsage = null),
            agentName = null,
            timestamp = timestamp,
            runningTime = runningTime,
        )
        // Even with a completion price set, embeddings should not bill completion tokens.
        assertEquals(0.02, invocation.cost(), 1e-9)
    }
}
