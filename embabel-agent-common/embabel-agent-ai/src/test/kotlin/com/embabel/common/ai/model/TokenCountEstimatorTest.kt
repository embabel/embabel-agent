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

class TokenCountEstimatorTest {

    data class SimpleMessage(val role: String, val content: String)

    @Nested
    inner class Noop {

        @Test
        fun `NOOP returns 0 for any input`() {
            assertEquals(0, TokenCountEstimator.NOOP.estimate("hello world"))
        }
    }

    @Nested
    inner class HeuristicFactory {

        @Test
        fun `heuristic returns default CharacterHeuristicTokenCountEstimator`() {
            assertSame(CharacterHeuristicTokenCountEstimator.DEFAULT, TokenCountEstimator.heuristic())
        }
    }

    @Nested
    inner class LambdaCreation {

        @Test
        fun `fun interface supports lambda creation`() {
            val estimator: TokenCountEstimator<String> = TokenCountEstimator { it.length / 3 }
            assertEquals(3, estimator.estimate("123456789"))
        }
    }

    @Nested
    inner class GenericTypeParameter {

        @Test
        fun `supports non-String content types`() {
            val estimator: TokenCountEstimator<SimpleMessage> = TokenCountEstimator { msg ->
                msg.content.length / 4
            }
            assertEquals(2, estimator.estimate(SimpleMessage("user", "abcdefgh")))
        }

        @Test
        fun `message estimator can compose with string estimator`() {
            val textEstimator = TokenCountEstimator.heuristic()
            val messageEstimator: TokenCountEstimator<SimpleMessage> = TokenCountEstimator { msg ->
                textEstimator.estimate(msg.content)
            }
            assertEquals(2, messageEstimator.estimate(SimpleMessage("user", "abcdefgh")))
        }
    }
}
