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
package com.embabel.common.ai.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EmbeddingServiceMetadataTest {

    @Test
    fun `factory invoke stores pricingModel`() {
        val pricing = PricingModel.usdPer1MTokens(0.02, 0.0)
        val metadata = EmbeddingServiceMetadata(
            name = "text-embedding-3-small",
            provider = "OpenAI",
            pricingModel = pricing,
        )
        assertNotNull(metadata.pricingModel)
        assertEquals(pricing, metadata.pricingModel)
    }

    @Test
    fun `factory invoke without pricing leaves pricingModel null`() {
        val metadata = EmbeddingServiceMetadata(
            name = "all-MiniLM-L6-v2",
            provider = "ONNX",
        )
        assertNull(metadata.pricingModel)
    }

    @Test
    fun `static create stores pricingModel`() {
        val pricing = PricingModel.usdPer1MTokens(0.13, 0.0)
        val metadata = EmbeddingServiceMetadata.create(
            "text-embedding-3-large",
            "OpenAI",
            pricing,
        )
        assertEquals(pricing, metadata.pricingModel)
    }

    @Test
    fun `pricingModel computes input-only cost for embeddings`() {
        val metadata = EmbeddingServiceMetadata(
            name = "text-embedding-3-small",
            provider = "OpenAI",
            pricingModel = PricingModel.usdPer1MTokens(0.02, 0.0),
        )
        val cost = metadata.pricingModel!!.costOf(1_000_000, 0)
        assertEquals(0.02, cost, 1e-9)
    }

    @Test
    fun `type is EMBEDDING`() {
        val metadata = EmbeddingServiceMetadata(
            name = "text-embedding-3-small",
            provider = "OpenAI",
        )
        assertEquals(ModelType.EMBEDDING, metadata.type)
    }

    @Test
    fun `name and provider are stored`() {
        val metadata = EmbeddingServiceMetadata(
            name = "text-embedding-3-small",
            provider = "OpenAI",
        )
        assertEquals("text-embedding-3-small", metadata.name)
        assertEquals("OpenAI", metadata.provider)
    }

    @Test
    fun `static create without pricing leaves pricingModel null`() {
        val metadata = EmbeddingServiceMetadata.create(
            "all-MiniLM-L6-v2",
            "ONNX",
        )
        assertNull(metadata.pricingModel)
        assertEquals("all-MiniLM-L6-v2", metadata.name)
        assertEquals("ONNX", metadata.provider)
    }
}
