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
import kotlin.test.assertTrue

class FilterEnhancerTest {

    private fun chunk(id: String, score: Double) = SimpleSimilaritySearchResult(
        match = Chunk(id = id, text = "text-$id", parentId = "doc", metadata = emptyMap()),
        score = score,
    )

    @Nested
    inner class Metadata {

        @Test
        fun `reports filtering metadata without masquerading as deduplication`() {
            assertEquals("filter", FilterEnhancer.name)
            assertEquals(EnhancementType.CUSTOM, FilterEnhancer.enhancementType)
        }

        @Test
        fun `estimateImpact always recommends apply`() {
            val response = RagResponse(
                request = RagRequest("q"),
                service = "test",
                results = emptyList(),
            )
            val estimate = FilterEnhancer.estimateImpact(response)

            assertEquals(1.0, estimate.expectedQualityGain)
            assertEquals(0L, estimate.estimatedLatencyMs)
            assertEquals(0, estimate.estimatedTokenCost)
            assertEquals(EnhancementRecommendation.APPLY, estimate.recommendation)
        }
    }

    @Nested
    inner class Enhance {

        @Test
        fun `returns same instance when all results already pass threshold and topK`() {
            val request = RagRequest("q", similarityThreshold = 0.5, topK = 10)
            val response = RagResponse(
                request = request,
                service = "test",
                results = listOf(chunk("a", 0.9), chunk("b", 0.8)),
            )

            assertSame(response, FilterEnhancer.enhance(response))
        }

        @Test
        fun `filters scores below similarity threshold`() {
            val request = RagRequest("q", similarityThreshold = 0.8, topK = 10)
            val response = RagResponse(
                request = request,
                service = "test",
                results = listOf(chunk("keep", 0.9), chunk("drop", 0.7)),
            )

            val enhanced = FilterEnhancer.enhance(response)

            assertEquals(1, enhanced.results.size)
            assertEquals("keep", enhanced.results.single().match.id)
        }

        @Test
        fun `sorts all passing results even when result count is unchanged`() {
            val request = RagRequest("q", similarityThreshold = 0.0, topK = 10)
            val response = RagResponse(
                request = request,
                service = "test",
                results = listOf(chunk("low", 0.1), chunk("high", 0.9), chunk("mid", 0.5)),
            )

            val enhanced = FilterEnhancer.enhance(response)

            assertNotSame(response, enhanced)
            assertEquals(listOf("high", "mid", "low"), enhanced.results.map { it.match.id })
        }

        @Test
        fun `sorts by score descending then applies topK`() {
            val request = RagRequest("q", similarityThreshold = 0.0, topK = 2)
            val response = RagResponse(
                request = request,
                service = "test",
                results = listOf(chunk("low", 0.1), chunk("high", 0.9), chunk("mid", 0.5)),
            )

            val enhanced = FilterEnhancer.enhance(response)

            assertEquals(listOf("high", "mid"), enhanced.results.map { it.match.id })
        }

        @Test
        fun `keeps scores equal to threshold`() {
            val request = RagRequest("q", similarityThreshold = 0.8, topK = 5)
            val response = RagResponse(
                request = request,
                service = "test",
                results = listOf(chunk("edge", 0.8)),
            )

            val enhanced = FilterEnhancer.enhance(response)

            assertEquals(1, enhanced.results.size)
        }

        @Test
        fun `empty results stay empty`() {
            val response = RagResponse(
                request = RagRequest("q"),
                service = "test",
                results = emptyList(),
            )

            assertSame(response, FilterEnhancer.enhance(response))
        }

        @Test
        fun `topK zero returns no results`() {
            val response = RagResponse(
                request = RagRequest("q", similarityThreshold = 0.0, topK = 0),
                service = "test",
                results = listOf(chunk("a", 0.9)),
            )

            val enhanced = FilterEnhancer.enhance(response)

            assertTrue(enhanced.results.isEmpty())
        }
    }
}
