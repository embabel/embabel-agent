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
package com.embabel.common.ai.decision

import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RatingResultTest {
    private val provenance = ModelProvenance("model", "provider", "v1", "request-1")

    @Test
    fun `rating level defaults its description to its id`() {
        val level = RatingLevel("Calm")
        assertEquals("Calm", level.id)
        assertEquals("Calm", level.description)
    }

    @Test
    fun `rating level rejects a blank id`() {
        for (id in listOf("", " ", "\t")) {
            assertThrows(IllegalArgumentException::class.java) { RatingLevel(id) }
        }
    }

    @Test
    fun `rating score rejects malformed values but accepts zero`() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1)) {
            assertThrows(IllegalArgumentException::class.java) { RatingScore(value, RatingStatistic.EXPECTED_LEVEL_INDEX) }
        }
        assertEquals(0.0, RatingScore(0.0, RatingStatistic.EXPECTED_LEVEL_INDEX).value)
    }

    @Test
    fun `level probability rejects a blank id or an out of range probability`() {
        assertThrows(IllegalArgumentException::class.java) { LevelProbability(" ", 0.5) }
        for (probability in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1)) {
            assertThrows(IllegalArgumentException::class.java) { LevelProbability("Calm", probability) }
        }
        assertEquals(0.0, LevelProbability("Calm", 0.0).probability)
        assertEquals(1.0, LevelProbability("Calm", 1.0).probability)
    }

    @Test
    fun `a score alone is valid answered evidence`() {
        val answer = RatingResult.Answered(
            provenance = provenance,
            score = RatingScore(1.4, RatingStatistic.EXPECTED_LEVEL_INDEX),
        )
        assertNull(answer.selectedLevelId)
        assertTrue(answer.distribution.isEmpty())
        assertEquals(1.4, answer.score?.value)
    }

    @Test
    fun `a selected level alone is valid answered evidence`() {
        val answer = RatingResult.Answered(provenance = provenance, selectedLevelId = "Calm")
        assertEquals("Calm", answer.selectedLevelId)
        assertNull(answer.score)
    }

    @Test
    fun `a normalized distribution alone is valid answered evidence`() {
        val answer = RatingResult.Answered(
            provenance = provenance,
            distribution = listOf(LevelProbability("Calm", 0.5), LevelProbability("Anxious", 0.5)),
        )
        assertEquals(2, answer.distribution.size)
    }

    @Test
    fun `answered evidence requires at least one of selection, distribution or score`() {
        assertThrows(IllegalArgumentException::class.java) { RatingResult.Answered(provenance = provenance) }
    }

    @Test
    fun `answered rejects a blank selected level id`() {
        assertThrows(IllegalArgumentException::class.java) {
            RatingResult.Answered(provenance = provenance, selectedLevelId = " ")
        }
    }

    @Test
    fun `answered rejects a distribution that does not sum to one`() {
        assertThrows(IllegalArgumentException::class.java) {
            RatingResult.Answered(
                provenance = provenance,
                distribution = listOf(LevelProbability("Calm", 0.48), LevelProbability("Anxious", 0.5)),
            )
        }
    }

    @Test
    fun `answered accepts a distribution within the rounding tolerance`() {
        val answer = RatingResult.Answered(
            provenance = provenance,
            distribution = listOf(LevelProbability("Calm", 0.5000004), LevelProbability("Anxious", 0.5)),
        )
        assertEquals(2, answer.distribution.size)
    }

    @Test
    fun `answered rejects duplicate level ids in the distribution`() {
        assertThrows(IllegalArgumentException::class.java) {
            RatingResult.Answered(
                provenance = provenance,
                distribution = listOf(LevelProbability("Calm", 0.5), LevelProbability("Calm", 0.5)),
            )
        }
    }

    @Test
    fun `answered rejects a non finite score`() {
        assertThrows(IllegalArgumentException::class.java) { RatingScore(Double.NaN, RatingStatistic.EXPECTED_LEVEL_INDEX) }
    }

    @Test
    fun `answered rejects a malformed confidence`() {
        for (confidence in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1)) {
            assertThrows(IllegalArgumentException::class.java) {
                RatingResult.Answered(provenance = provenance, selectedLevelId = "Calm", confidence = confidence)
            }
        }
        val answer = RatingResult.Answered(provenance = provenance, selectedLevelId = "Calm", confidence = 0.9)
        assertEquals(0.9, answer.confidence)
    }

    @Test
    fun `distribution is unmodifiable and immune to later changes in the caller's list`() {
        val mutable = mutableListOf(LevelProbability("Calm", 0.5), LevelProbability("Anxious", 0.5))
        val answer = RatingResult.Answered(provenance = provenance, distribution = mutable)
        assertThrows(UnsupportedOperationException::class.java) {
            (answer.distribution as MutableList<LevelProbability>).add(LevelProbability("Furious", 0.0))
        }
        mutable.add(LevelProbability("Furious", 0.0))
        assertEquals(2, answer.distribution.size)
    }

    @Test
    fun `inconclusive and failure carry no answer payload`() {
        val inconclusive = RatingResult.Inconclusive(provenance)
        assertSame(provenance, inconclusive.provenance)
        val failure = RatingResult.Failure(FailureReason.UNAVAILABLE)
        assertEquals(FailureReason.UNAVAILABLE, failure.reason)
    }
}
