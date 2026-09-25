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

class PropositionTest {
    private val provenance = ModelProvenance("model", "provider", "v1", "request-1")

    @Test
    fun `false is an answer with optional independent probability of truth`() {
        val answer = PropositionResult.Answered(false, provenance)
        assertFalse(answer.answer)
        assertNull(answer.pTrue)
        assertSame(provenance, answer.provenance)
        assertEquals(0.2, PropositionResult.Answered(false, provenance, 0.2).pTrue)
        assertNotEquals(answer, PropositionResult.Inconclusive(provenance))
        assertNotEquals(answer, PropositionResult.Failure(FailureReason.INVALID_RESPONSE))
    }

    @Test
    fun `truth probability rejects malformed scores and retains endpoints`() {
        for (score in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1, 1.1)) {
            assertThrows(IllegalArgumentException::class.java) { PropositionResult.Answered(true, provenance, score) }
        }
        for (score in listOf(0.0, 1.0)) assertEquals(score, PropositionResult.Answered(true, provenance, score).pTrue)
    }
}
