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

import com.embabel.agent.event.RagEvent
import com.embabel.agent.event.RagEventListener
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.pipeline.event.EnhancementCompletedRagPipelineEvent
import com.embabel.agent.rag.pipeline.event.EnhancementStartingRagPipelineEvent
import com.embabel.agent.rag.service.DesiredMaxLatency
import com.embabel.agent.rag.service.EnhancementEstimate
import com.embabel.agent.rag.service.EnhancementRecommendation
import com.embabel.agent.rag.service.EnhancementType
import com.embabel.agent.rag.service.QualityMetrics
import com.embabel.agent.rag.service.RagRequest
import com.embabel.agent.rag.service.RagResponse
import com.embabel.agent.rag.service.RagResponseEnhancer
import com.embabel.common.core.types.SimpleSimilaritySearchResult
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Verifies adaptive and sequential enhancer execution, including skips, failures, and lifecycle events. */
class AdaptivePipelineRagResponseEnhancerTest {

    private fun response(
        results: List<SimpleSimilaritySearchResult<Chunk>> = emptyList(),
        request: RagRequest = RagRequest("q"),
        quality: QualityMetrics? = null,
    ) = RagResponse(
        request = request,
        service = "test",
        results = results,
        qualityMetrics = quality,
    )

    private fun chunk(id: String) = SimpleSimilaritySearchResult(
        match = Chunk(id = id, text = id, parentId = "doc", metadata = emptyMap()),
        score = 0.9,
    )

    @Nested
    inner class NameAndType {

        @Test
        fun `name joins enhancer names`() {
            val pipeline = AdaptivePipelineRagResponseEnhancer(
                enhancers = listOf(FixedEnhancer("a"), FixedEnhancer("b")),
                listener = RecordingRagListener(),
            )

            assertEquals("a->b", pipeline.name)
            assertEquals(EnhancementType.CUSTOM, pipeline.enhancementType)
        }

        @Test
        fun `empty pipeline has empty name and custom type`() {
            val pipeline = AdaptivePipelineRagResponseEnhancer(
                enhancers = emptyList(),
                listener = RecordingRagListener(),
            )

            assertEquals("", pipeline.name)
            assertEquals(EnhancementType.CUSTOM, pipeline.enhancementType)
        }
    }

    @Nested
    inner class NonAdaptiveExecution {

        @Test
        fun `applies enhancers in order when adaptiveExecution is false`() {
            val listener = RecordingRagListener()
            val pipeline = AdaptivePipelineRagResponseEnhancer(
                enhancers = listOf(AppendingEnhancer("x"), AppendingEnhancer("y")),
                adaptiveExecution = false,
                listener = listener,
            )

            val original = response(listOf(chunk("base")))

            val enhanced = pipeline.enhance(original)

            assertEquals(listOf("base", "x", "y"), enhanced.results.map { it.match.id })
            assertEquals(listOf("x", "y"), listener.starting.map { it.enhancerName })
            assertEquals(listOf("x", "y"), listener.completed.map { it.enhancerName })
            val enhancement = assertNotNull(enhanced.enhancement)
            assertEquals("y", enhancement.enhancer.name)
            assertEquals(listOf("base", "x"), enhancement.basis.results.map { it.match.id })
            assertEquals(EnhancementType.CUSTOM, enhancement.enhancementType)
            assertTrue(enhancement.processingTimeMs >= 0)
        }

        @Test
        fun `non adaptive execution ignores skip recommendation`() {
            val listener = RecordingRagListener()
            val enhancer = FixedEnhancer(
                name = "forced",
                estimate = EnhancementEstimate(0.0, 0, 0, EnhancementRecommendation.SKIP),
            )
            val pipeline = AdaptivePipelineRagResponseEnhancer(
                enhancers = listOf(enhancer),
                adaptiveExecution = false,
                listener = listener,
            )

            pipeline.enhance(response())

            assertEquals(1, enhancer.enhancementCalls)
            assertEquals(listOf("forced"), listener.starting.map { it.enhancerName })
            assertEquals(listOf("forced"), listener.completed.map { it.enhancerName })
        }

        @Test
        fun `empty pipeline returns original response without events`() {
            val listener = RecordingRagListener()
            val pipeline = AdaptivePipelineRagResponseEnhancer(
                enhancers = emptyList(),
                adaptiveExecution = false,
                listener = listener,
            )
            val original = response(listOf(chunk("base")))

            val enhanced = pipeline.enhance(original)

            assertSame(original, enhanced)
            assertTrue(listener.starting.isEmpty())
            assertTrue(listener.completed.isEmpty())
        }
    }

    @Nested
    inner class AdaptiveSkipping {

        @Test
        fun `skips enhancer that recommends SKIP`() {
            val skipper = FixedEnhancer(
                name = "skip-me",
                estimate = EnhancementEstimate(
                    expectedQualityGain = 0.0,
                    estimatedLatencyMs = 10,
                    estimatedTokenCost = 0,
                    recommendation = EnhancementRecommendation.SKIP,
                ),
            )
            val pipeline = AdaptivePipelineRagResponseEnhancer(
                enhancers = listOf(skipper, AppendingEnhancer("kept")),
                adaptiveExecution = true,
                listener = RecordingRagListener(),
            )

            val enhanced = pipeline.enhance(response(listOf(chunk("base"))))

            assertEquals(listOf("base", "kept"), enhanced.results.map { it.match.id })
            assertEquals(0, skipper.enhancementCalls)
        }

        @Test
        fun `skips expensive enhancer when quality already high`() {
            val expensive = FixedEnhancer(
                name = "expensive",
                estimate = EnhancementEstimate(
                    expectedQualityGain = 0.1,
                    estimatedLatencyMs = 1500,
                    estimatedTokenCost = 0,
                    recommendation = EnhancementRecommendation.APPLY,
                ),
            )
            val highQuality = QualityMetrics(
                faithfulness = 0.9,
                answerRelevancy = 0.9,
                contextPrecision = 0.9,
                contextRecall = 0.9,
                contextRelevancy = 0.9,
                overallScore = 0.95,
            )
            val pipeline = AdaptivePipelineRagResponseEnhancer(
                enhancers = listOf(expensive, AppendingEnhancer("cheap")),
                adaptiveExecution = true,
                qualityThreshold = 0.7,
                listener = RecordingRagListener(),
            )

            val enhanced = pipeline.enhance(
                response(listOf(chunk("base")), quality = highQuality),
            )

            assertEquals(listOf("base", "cheap"), enhanced.results.map { it.match.id })
            assertEquals(0, expensive.enhancementCalls)
        }

        @Test
        fun `applies enhancer with absent impact estimate even when quality is high`() {
            val enhancer = FixedEnhancer(name = "unknown", estimate = null)
            val listener = RecordingRagListener()
            val pipeline = AdaptivePipelineRagResponseEnhancer(
                enhancers = listOf(enhancer),
                adaptiveExecution = true,
                qualityThreshold = 0.7,
                listener = listener,
            )
            val highQuality = QualityMetrics(
                faithfulness = 0.9,
                answerRelevancy = 0.9,
                contextPrecision = 0.9,
                contextRecall = 0.9,
                contextRelevancy = 0.9,
                overallScore = 0.95,
            )

            pipeline.enhance(response(quality = highQuality))

            assertEquals(1, enhancer.enhancementCalls)
            assertEquals(listOf("unknown"), listener.completed.map { it.enhancerName })
        }

        @Test
        fun `quality and latency boundaries remain inclusive`() {
            val qualityBoundary = FixedEnhancer(
                name = "quality-boundary",
                estimate = EnhancementEstimate(0.1, 1501, 0, EnhancementRecommendation.APPLY),
            )
            val latencyBoundary = FixedEnhancer(
                name = "latency-boundary",
                estimate = EnhancementEstimate(0.1, 1000, 0, EnhancementRecommendation.APPLY),
            )
            val qualityAtThreshold = QualityMetrics(
                faithfulness = 0.7,
                answerRelevancy = 0.7,
                contextPrecision = 0.7,
                contextRecall = 0.7,
                contextRelevancy = 0.7,
                overallScore = 0.7,
            )
            val highQuality = qualityAtThreshold.copy(overallScore = 0.95)

            AdaptivePipelineRagResponseEnhancer(
                enhancers = listOf(qualityBoundary),
                adaptiveExecution = true,
                qualityThreshold = 0.7,
                listener = RecordingRagListener(),
            ).enhance(response(quality = qualityAtThreshold))
            AdaptivePipelineRagResponseEnhancer(
                enhancers = listOf(latencyBoundary),
                adaptiveExecution = true,
                qualityThreshold = 0.7,
                listener = RecordingRagListener(),
            ).enhance(response(quality = highQuality))

            assertEquals(1, qualityBoundary.enhancementCalls)
            assertEquals(1, latencyBoundary.enhancementCalls)
        }

        @Test
        fun `skips enhancers when latency budget is exhausted`() {
            val listener = RecordingRagListener()
            val skipped = FixedEnhancer(name = "never")
            val pipeline = AdaptivePipelineRagResponseEnhancer(
                enhancers = listOf(skipped),
                adaptiveExecution = true,
                listener = listener,
            )
            val request = RagRequest("q").withHint(
                DesiredMaxLatency(duration = Duration.ofMillis(-1)),
            )
            val original = response(results = listOf(chunk("base")), request = request)

            val enhanced = pipeline.enhance(original)

            assertSame(original, enhanced)
            assertEquals(0, skipped.enhancementCalls)
            assertTrue(listener.starting.isEmpty())
            assertTrue(listener.completed.isEmpty())
        }

        @Test
        fun `enhancer failure propagates after starting event without completed event`() {
            val listener = RecordingRagListener()
            val pipeline = AdaptivePipelineRagResponseEnhancer(
                enhancers = listOf(ThrowingEnhancer),
                adaptiveExecution = true,
                listener = listener,
            )

            val failure = assertFailsWith<IllegalStateException> {
                pipeline.enhance(response())
            }

            assertEquals("enhancement failed", failure.message)
            assertEquals(listOf("throwing"), listener.starting.map { it.enhancerName })
            assertTrue(listener.completed.isEmpty())
        }
    }

    private class RecordingRagListener : RagEventListener {
        val starting = CopyOnWriteArrayList<EnhancementStartingRagPipelineEvent>()
        val completed = CopyOnWriteArrayList<EnhancementCompletedRagPipelineEvent>()

        override fun onRagEvent(event: RagEvent) {
            when (event) {
                is EnhancementStartingRagPipelineEvent -> starting += event
                is EnhancementCompletedRagPipelineEvent -> completed += event
            }
        }
    }

    private class FixedEnhancer(
        override val name: String,
        private val estimate: EnhancementEstimate? = EnhancementEstimate(
            expectedQualityGain = 0.1,
            estimatedLatencyMs = 0,
            estimatedTokenCost = 0,
            recommendation = EnhancementRecommendation.APPLY,
        ),
    ) : RagResponseEnhancer {
        var enhancementCalls: Int = 0
            private set

        override val enhancementType: EnhancementType = EnhancementType.CUSTOM
        override fun enhance(response: RagResponse): RagResponse {
            enhancementCalls++
            return response
        }

        override fun estimateImpact(response: RagResponse): EnhancementEstimate? = estimate
    }

    private class AppendingEnhancer(private val id: String) : RagResponseEnhancer {
        override val name: String = id
        override val enhancementType: EnhancementType = EnhancementType.CUSTOM
        override fun enhance(response: RagResponse): RagResponse =
            response.copy(
                results = response.results + SimpleSimilaritySearchResult(
                    match = Chunk(id = id, text = id, parentId = "doc", metadata = emptyMap()),
                    score = 0.5,
                ),
            )

        override fun estimateImpact(response: RagResponse): EnhancementEstimate =
            EnhancementEstimate(0.1, 0, 0, EnhancementRecommendation.APPLY)
    }

    private object ThrowingEnhancer : RagResponseEnhancer {
        override val name: String = "throwing"
        override val enhancementType: EnhancementType = EnhancementType.CUSTOM

        override fun enhance(response: RagResponse): RagResponse =
            throw IllegalStateException("enhancement failed")

        override fun estimateImpact(response: RagResponse): EnhancementEstimate =
            EnhancementEstimate(0.0, 0, 0, EnhancementRecommendation.APPLY)
    }
}
