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
package com.embabel.agent.rag.service.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The properties that the discarded `score / maxScore` normalization violated.
 */
class Bm25NormalizationTest {

    @Nested
    inner class Bounds {

        @Test
        fun `maps into 0 to 1 exclusive of 1`() {
            listOf(0.0, 0.1, 1.0, 3.0, 25.0, 1_000.0, 1e9).forEach { raw ->
                val score = Bm25Normalization.normalize(raw)
                assertTrue(score >= 0.0 && score < 1.0, "normalize($raw) = $score outside [0, 1)")
            }
        }

        @Test
        fun `no score reaches 1 across the realistic range`() {
            // The old normalization pinned the best hit to exactly 1.0, so a
            // similarity floor could never exclude a weak best-match. (At absurd
            // magnitudes — around 1e16 and up — `score + k == score` in double
            // arithmetic and the ratio rounds to 1.0. BM25 does not go there.)
            assertTrue(Bm25Normalization.normalize(1e12) < 1.0)
        }

        @Test
        fun `negative scores floor at zero`() {
            assertEquals(0.0, Bm25Normalization.normalize(-5.0))
        }

        @Test
        fun `k is the score that maps to one half`() {
            assertEquals(0.5, Bm25Normalization.normalize(Bm25Normalization.DEFAULT_K))
            assertEquals(0.5, Bm25Normalization.normalize(10.0, k = 10.0))
        }

        @Test
        fun `rejects a non-positive k`() {
            assertThrows<IllegalArgumentException> { Bm25Normalization.normalize(1.0, k = 0.0) }
            assertThrows<IllegalArgumentException> { Bm25Normalization.normalize(1.0, k = -1.0) }
        }
    }

    @Nested
    inner class RankingPreserved {

        @Test
        fun `is strictly monotonic in the raw score`() {
            val raw = listOf(0.0, 0.5, 1.0, 2.0, 3.0, 8.0, 20.0, 100.0)
            val normalized = raw.map { Bm25Normalization.normalize(it) }
            normalized.zipWithNext { a, b ->
                assertTrue(a < b, "expected strictly increasing, got $a then $b")
            }
        }

        @Test
        fun `preserves the original ordering`() {
            val raw = listOf(4.0, 19.0, 0.25, 7.5, 2.0)
            assertEquals(
                raw.sortedDescending().map { Bm25Normalization.normalize(it) },
                raw.map { Bm25Normalization.normalize(it) }.sortedDescending(),
            )
        }
    }

    @Nested
    inner class ResultSetIndependence {

        /**
         * The core defect: under `score / maxScore` the same document scored
         * differently depending on what else happened to match, so a threshold
         * meant "half as good as today's best hit" rather than anything stable,
         * and scores from two searches could not be compared or merged.
         */
        @Test
        fun `a document scores the same regardless of what else matched`() {
            val document = 4.0

            val amongWeakCompetition = listOf(document, 1.0, 0.5)
            val amongStrongCompetition = listOf(document, 40.0, 35.0)

            val scoreWithWeak = amongWeakCompetition.map { Bm25Normalization.normalize(it) }.first()
            val scoreWithStrong = amongStrongCompetition.map { Bm25Normalization.normalize(it) }.first()

            assertEquals(scoreWithWeak, scoreWithStrong)
        }

        @Test
        fun `a weak best match does not become a perfect match`() {
            val onlyResultIsWeak = listOf(0.3)
            val score = onlyResultIsWeak.map { Bm25Normalization.normalize(it) }.first()

            assertTrue(score < 0.2, "a weak sole result scored $score; a floor must still exclude it")
        }
    }
}
