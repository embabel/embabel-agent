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
package com.embabel.agent.rag.pipeline

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.service.EnhancementRecommendation
import com.embabel.agent.rag.service.EnhancementType
import com.embabel.agent.rag.service.RagRequest
import com.embabel.agent.rag.service.RagResponse
import com.embabel.common.core.types.SimpleSimilaritySearchResult
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class DeduplicatingEnhancerTest {

    private fun chunk(id: String, score: Double = 0.9) = SimpleSimilaritySearchResult(
        match = Chunk(id = id, text = "text-$id", parentId = "doc", metadata = emptyMap()),
        score = score,
    )

    @Nested
    inner class Metadata {

        @Test
        fun `reports deduplication enhancement type`() {
            assertEquals("dedupe", DeduplicatingEnhancer.name)
            assertEquals(EnhancementType.DEDUPLICATION, DeduplicatingEnhancer.enhancementType)
        }

        @Test
        fun `estimateImpact always recommends apply`() {
            val response = RagResponse(request = RagRequest("q"), service = "test", results = emptyList())
            val estimate = DeduplicatingEnhancer.estimateImpact(response)

            assertEquals(1.0, estimate.expectedQualityGain)
            assertEquals(EnhancementRecommendation.APPLY, estimate.recommendation)
            assertEquals(0L, estimate.estimatedLatencyMs)
            assertEquals(0, estimate.estimatedTokenCost)
        }
    }

    @Nested
    inner class Enhance {

        @Test
        fun `returns same instance when all ids are unique`() {
            val response = RagResponse(
                request = RagRequest("q"),
                service = "test",
                results = listOf(chunk("a"), chunk("b")),
            )

            assertSame(response, DeduplicatingEnhancer.enhance(response))
        }

        @Test
        fun `keeps first occurrence and encounter order for duplicate ids`() {
            val response = RagResponse(
                request = RagRequest("q"),
                service = "test",
                results = listOf(
                    chunk("a", 0.9),
                    chunk("b", 0.8),
                    chunk("a", 0.5),
                    chunk("b", 0.4),
                    chunk("c", 0.7),
                ),
            )

            val enhanced = DeduplicatingEnhancer.enhance(response)

            assertNotSame(response, enhanced)
            assertEquals(listOf("a", "b", "c"), enhanced.results.map { it.match.id })
            assertEquals(listOf(0.9, 0.8, 0.7), enhanced.results.map { it.score })
        }

        @Test
        fun `empty results stay empty`() {
            val response = RagResponse(request = RagRequest("q"), service = "test", results = emptyList())

            assertSame(response, DeduplicatingEnhancer.enhance(response))
        }
    }
}
