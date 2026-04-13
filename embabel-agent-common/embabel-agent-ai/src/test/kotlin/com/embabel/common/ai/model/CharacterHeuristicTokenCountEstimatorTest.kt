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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CharacterHeuristicTokenCountEstimatorTest {

    private val estimator = CharacterHeuristicTokenCountEstimator.DEFAULT

    @Nested
    inner class EmptyAndBlankInput {

        @Test
        fun `returns 0 for empty string`() {
            assertEquals(0, estimator.estimate(""))
        }

        @Test
        fun `returns 0 for blank string`() {
            assertEquals(0, estimator.estimate("   "))
        }

        @Test
        fun `returns 0 for whitespace only`() {
            assertEquals(0, estimator.estimate("\t\n "))
        }
    }

    @Nested
    inner class DefaultHeuristic {

        @Test
        fun `divides character count by 4`() {
            assertEquals(1, estimator.estimate("abcd"))
            assertEquals(2, estimator.estimate("abcdefgh"))
        }

        @Test
        fun `rounds up result for non-divisible lengths`() {
            assertEquals(2, estimator.estimate("abcde"))
            assertEquals(2, estimator.estimate("abcdef"))
            assertEquals(2, estimator.estimate("abcdefg"))
        }

        @Test
        fun `single character returns 1`() {
            assertEquals(1, estimator.estimate("a"))
        }

        @Test
        fun `three characters returns 1`() {
            assertEquals(1, estimator.estimate("abc"))
        }

        @Test
        fun `handles long text`() {
            val text = "a".repeat(400)
            assertEquals(100, estimator.estimate(text))
        }
    }

    @Nested
    inner class ConfigurableRatio {

        @Test
        fun `uses custom chars per token`() {
            val cjk = CharacterHeuristicTokenCountEstimator(charsPerToken = 2)
            assertEquals(4, cjk.estimate("abcdefgh"))
        }

        @Test
        fun `ratio of 1 returns character count`() {
            val exact = CharacterHeuristicTokenCountEstimator(charsPerToken = 1)
            assertEquals(5, exact.estimate("hello"))
        }

        @Test
        fun `rejects non-positive ratio`() {
            assertThrows(IllegalArgumentException::class.java) {
                CharacterHeuristicTokenCountEstimator(charsPerToken = 0)
            }
            assertThrows(IllegalArgumentException::class.java) {
                CharacterHeuristicTokenCountEstimator(charsPerToken = -1)
            }
        }

        @Test
        fun `default uses 4 chars per token`() {
            assertEquals(
                CharacterHeuristicTokenCountEstimator.DEFAULT_CHARS_PER_TOKEN,
                CharacterHeuristicTokenCountEstimator.DEFAULT.charsPerToken,
            )
        }
    }

    @Nested
    inner class Factory {

        @Test
        fun `default instance is returned by heuristic factory`() {
            assertSame(CharacterHeuristicTokenCountEstimator.DEFAULT, TokenCountEstimator.heuristic())
        }
    }
}
