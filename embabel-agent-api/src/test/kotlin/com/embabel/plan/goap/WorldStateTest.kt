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
package com.embabel.plan.goap

import com.embabel.plan.common.condition.ConditionDetermination
import com.embabel.plan.common.condition.ConditionWorldState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class WorldStateTest {

    @Nested
    inner class Constructor {
        @Test
        fun `test constructor with empty map`() {
            val worldState = ConditionWorldState()
            assertTrue(worldState.state.isEmpty())
        }

        @Test
        fun `test constructor with populated map`() {
            val state = mapOf(
                "condition1" to ConditionDetermination.TRUE,
                "condition2" to ConditionDetermination.FALSE
            )
            val worldState = ConditionWorldState(state)
            assertEquals(state, worldState.state)
        }
    }

    @Nested
    inner class UnknownConditions {

        @Test
        fun `test unknownConditions with empty state`() {
            val worldState = ConditionWorldState()
            assertTrue(worldState.unknownConditions().isEmpty())
        }

        @Test
        fun `test unknownConditions with no unknowns`() {
            val state = mapOf(
                "condition1" to ConditionDetermination.TRUE,
                "condition2" to ConditionDetermination.FALSE
            )
            val worldState = ConditionWorldState(state)
            assertTrue(worldState.unknownConditions().isEmpty())
        }

        @Test
        fun `test unknownConditions with unknowns`() {
            val state = mapOf(
                "condition1" to ConditionDetermination.TRUE,
                "condition2" to ConditionDetermination.UNKNOWN,
                "condition3" to ConditionDetermination.FALSE,
                "condition4" to ConditionDetermination.UNKNOWN
            )
            val worldState = ConditionWorldState(state)
            val unknowns = worldState.unknownConditions()
            assertEquals(2, unknowns.size)
            assertTrue(unknowns.contains("condition2"))
            assertTrue(unknowns.contains("condition4"))
        }
    }

    @Nested
    inner class Variants {

        @Test
        fun `test variants with an unknown condition`() {
            val state = mapOf(
                "condition1" to ConditionDetermination.TRUE,
                "condition2" to ConditionDetermination.UNKNOWN
            )
            val worldState = ConditionWorldState(state)
            val variants = worldState.variants("condition2")

            assertEquals(2, variants.size)

            val trueVariant = variants.find { it.state["condition2"] == ConditionDetermination.TRUE }
            val falseVariant = variants.find { it.state["condition2"] == ConditionDetermination.FALSE }

            assertNotNull(trueVariant)
            assertNotNull(falseVariant)
            assertEquals(ConditionDetermination.TRUE, trueVariant!!.state["condition1"])
            assertEquals(ConditionDetermination.TRUE, falseVariant!!.state["condition1"])
        }
    }

    @Nested
    inner class Plus {

        @Test
        fun `test plus operator`() {
            val state = mapOf(
                "condition1" to ConditionDetermination.TRUE,
                "condition2" to ConditionDetermination.FALSE
            )
            val worldState = ConditionWorldState(state)
            val newWorldState = worldState + ("condition3" to ConditionDetermination.UNKNOWN)

            assertEquals(3, newWorldState.state.size)
            assertEquals(ConditionDetermination.TRUE, newWorldState.state["condition1"])
            assertEquals(ConditionDetermination.FALSE, newWorldState.state["condition2"])
            assertEquals(ConditionDetermination.UNKNOWN, newWorldState.state["condition3"])
        }

        @Test
        fun `test plus operator overwriting existing condition`() {
            val state = mapOf(
                "condition1" to ConditionDetermination.TRUE,
                "condition2" to ConditionDetermination.FALSE
            )
            val worldState = ConditionWorldState(state)
            val newWorldState = worldState + ("condition1" to ConditionDetermination.UNKNOWN)

            assertEquals(2, newWorldState.state.size)
            assertEquals(ConditionDetermination.UNKNOWN, newWorldState.state["condition1"])
            assertEquals(ConditionDetermination.FALSE, newWorldState.state["condition2"])
        }
    }

    @Nested
    inner class WithOneChange {

        @Test
        fun `test withOneChange with empty state`() {
            val worldState = ConditionWorldState()
            val changes = worldState.withOneChange()
            assertTrue(changes.isEmpty())
        }

        @Test
        fun `test withOneChange with single TRUE condition`() {
            val state = mapOf("condition1" to ConditionDetermination.TRUE)
            val worldState = ConditionWorldState(state)
            val changes = worldState.withOneChange()

            assertEquals(2, changes.size)

            val falseVariant = changes.find { it.state["condition1"] == ConditionDetermination.FALSE }
            val unknownVariant = changes.find { it.state["condition1"] == ConditionDetermination.UNKNOWN }

            assertNotNull(falseVariant)
            assertNotNull(unknownVariant)
        }

        @Test
        fun `test withOneChange with single FALSE condition`() {
            val state = mapOf("condition1" to ConditionDetermination.FALSE)
            val worldState = ConditionWorldState(state)
            val changes = worldState.withOneChange()

            assertEquals(2, changes.size)

            val trueVariant = changes.find { it.state["condition1"] == ConditionDetermination.TRUE }
            val unknownVariant = changes.find { it.state["condition1"] == ConditionDetermination.UNKNOWN }

            assertNotNull(trueVariant)
            assertNotNull(unknownVariant)
        }

        @Test
        fun `test withOneChange with single UNKNOWN condition`() {
            val state = mapOf("condition1" to ConditionDetermination.UNKNOWN)
            val worldState = ConditionWorldState(state)
            val changes = worldState.withOneChange()

            assertEquals(2, changes.size)

            val trueVariant = changes.find { it.state["condition1"] == ConditionDetermination.TRUE }
            val falseVariant = changes.find { it.state["condition1"] == ConditionDetermination.FALSE }

            assertNotNull(trueVariant)
            assertNotNull(falseVariant)
        }


        @Test
        fun `test withOneChange with 10 conditions`() {
            val states = oneChangeWithNConditions(10)
            assertEquals(20, states.size) // 10 conditions, each with 2 alternate values

        }

        @Test
        fun `test withOneChange with 40 conditions`() {
            val states = oneChangeWithNConditions(40)
            assertEquals(80, states.size) // 10 conditions, each with 2 alternate values

        }

        private fun oneChangeWithNConditions(n: Int): Collection<ConditionWorldState> {
            val state = mutableMapOf<String, ConditionDetermination>()
            for (i in 1..n) {
                state["condition$i"] = ConditionDetermination.entries.toTypedArray().random()
            }
            val worldState = ConditionWorldState(state)
            val changes = worldState.withOneChange()

            return changes
        }

        @Test
        fun `test withOneChange with multiple conditions`() {
            val state = mapOf(
                "condition1" to ConditionDetermination.TRUE,
                "condition2" to ConditionDetermination.FALSE,
                "condition3" to ConditionDetermination.UNKNOWN
            )
            val worldState = ConditionWorldState(state)
            val changes = worldState.withOneChange()

            assertEquals(6, changes.size)  // 3 conditions, each with 2 alternate values

            // Verify condition1 variants (TRUE can be changed to FALSE or UNKNOWN)
            val condition1ToFalse = changes.find {
                it.state["condition1"] == ConditionDetermination.FALSE &&
                        it.state["condition2"] == ConditionDetermination.FALSE &&
                        it.state["condition3"] == ConditionDetermination.UNKNOWN
            }
            val condition1ToUnknown = changes.find {
                it.state["condition1"] == ConditionDetermination.UNKNOWN &&
                        it.state["condition2"] == ConditionDetermination.FALSE &&
                        it.state["condition3"] == ConditionDetermination.UNKNOWN
            }

            // Verify condition2 variants (FALSE can be changed to TRUE or UNKNOWN)
            val condition2ToTrue = changes.find {
                it.state["condition1"] == ConditionDetermination.TRUE &&
                        it.state["condition2"] == ConditionDetermination.TRUE &&
                        it.state["condition3"] == ConditionDetermination.UNKNOWN
            }
            val condition2ToUnknown = changes.find {
                it.state["condition1"] == ConditionDetermination.TRUE &&
                        it.state["condition2"] == ConditionDetermination.UNKNOWN &&
                        it.state["condition3"] == ConditionDetermination.UNKNOWN
            }

            // Verify condition3 variants (UNKNOWN can be changed to TRUE or FALSE)
            val condition3ToTrue = changes.find {
                it.state["condition1"] == ConditionDetermination.TRUE &&
                        it.state["condition2"] == ConditionDetermination.FALSE &&
                        it.state["condition3"] == ConditionDetermination.TRUE
            }
            val condition3ToFalse = changes.find {
                it.state["condition1"] == ConditionDetermination.TRUE &&
                        it.state["condition2"] == ConditionDetermination.FALSE &&
                        it.state["condition3"] == ConditionDetermination.FALSE
            }

            assertNotNull(condition1ToFalse)
            assertNotNull(condition1ToUnknown)
            assertNotNull(condition2ToTrue)
            assertNotNull(condition2ToUnknown)
            assertNotNull(condition3ToTrue)
            assertNotNull(condition3ToFalse)
        }
    }
}
