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
package com.embabel.agent.rag.tools

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class TopKInflationStrategyTest {

    @Nested
    inner class DefaultStrategyTests {

        @Test
        fun `default strategy multiplies by 3`() {
            assertEquals(30, TopKInflationStrategy.DEFAULT.inflate(10))
        }

        @Test
        fun `default strategy caps at 1000`() {
            assertEquals(1000, TopKInflationStrategy.DEFAULT.inflate(500))
        }
    }

    @Nested
    inner class MultiplierStrategyTests {

        @Test
        fun `multiplier strategy applies multiplier`() {
            val strategy = TopKInflationStrategy.multiplier(5)
            assertEquals(50, strategy.inflate(10))
        }

        @Test
        fun `multiplier strategy respects max cap`() {
            val strategy = TopKInflationStrategy.multiplier(5, maxTopK = 100)
            assertEquals(100, strategy.inflate(50))
        }

        @Test
        fun `multiplier strategy returns exact value when under cap`() {
            val strategy = TopKInflationStrategy.multiplier(2, maxTopK = 100)
            assertEquals(40, strategy.inflate(20))
        }
    }

    @Nested
    inner class OffsetStrategyTests {

        @Test
        fun `offset strategy adds fixed amount`() {
            val strategy = TopKInflationStrategy.offset(50)
            assertEquals(60, strategy.inflate(10))
        }

        @Test
        fun `offset strategy respects max cap`() {
            val strategy = TopKInflationStrategy.offset(100, maxTopK = 50)
            assertEquals(50, strategy.inflate(10))
        }

        @Test
        fun `offset strategy works with zero offset`() {
            val strategy = TopKInflationStrategy.offset(0)
            assertEquals(10, strategy.inflate(10))
        }
    }

    @Nested
    inner class ExpectedPassRateStrategyTests {

        @Test
        fun `pass rate of 50 percent doubles topK`() {
            val strategy = TopKInflationStrategy.expectedPassRate(0.5)
            assertEquals(20, strategy.inflate(10))
        }

        @Test
        fun `pass rate of 100 percent returns same topK`() {
            val strategy = TopKInflationStrategy.expectedPassRate(1.0)
            assertEquals(10, strategy.inflate(10))
        }

        @Test
        fun `pass rate of 10 percent inflates by 10x`() {
            val strategy = TopKInflationStrategy.expectedPassRate(0.1)
            assertEquals(100, strategy.inflate(10))
        }

        @Test
        fun `pass rate respects max cap`() {
            val strategy = TopKInflationStrategy.expectedPassRate(0.1, maxTopK = 50)
            assertEquals(50, strategy.inflate(10))
        }

        @Test
        fun `pass rate rejects zero`() {
            assertThrows<IllegalArgumentException> {
                TopKInflationStrategy.expectedPassRate(0.0)
            }
        }

        @Test
        fun `pass rate rejects greater than 1`() {
            assertThrows<IllegalArgumentException> {
                TopKInflationStrategy.expectedPassRate(1.1)
            }
        }
    }

    @Nested
    inner class CustomStrategyTests {

        @Test
        fun `can create custom strategy with lambda`() {
            val strategy = TopKInflationStrategy { topK -> topK * topK }
            assertEquals(100, strategy.inflate(10))
        }
    }
}
