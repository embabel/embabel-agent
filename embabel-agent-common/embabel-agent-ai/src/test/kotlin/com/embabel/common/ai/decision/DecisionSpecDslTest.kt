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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DecisionSpecDslTest {

    // The same triage spec, built with the customizer builder, to compare against the DSL result.
    private fun javaTriage(): DecisionSpec = DecisionSpec.builder()
        .proposition("is_urgent") { it.asking("Does this convey urgency?") }
        .choice("department") {
            it.asking("Which team should handle this?")
                .option("billing", "Payments, invoicing, refunds")
                .option("technical", "Bugs, outages, integrations")
                .option("sales", "Pricing, upgrades, new accounts")
        }
        .rating("frustration") {
            it.asking("How frustrated is the customer?")
                .level("Calm")
                .level("Frustrated")
                .level("Very angry")
        }
        .build()

    @Test
    fun `the DSL example builds a spec equal to the Java triage spec`() {
        // tag::dsl[]
        val triage = decisionSpec {
            proposition("is_urgent") {
                asking("Does this convey urgency?")
            }
            choice("department") {
                asking("Which team should handle this?")
                option("billing", "Payments, invoicing, refunds")
                option("technical", "Bugs, outages, integrations")
                option("sales", "Pricing, upgrades, new accounts")
            }
            rating("frustration") {
                asking("How frustrated is the customer?")
                level("Calm")
                level("Frustrated")
                level("Very angry")
            }
        }
        // end::dsl[]

        val expected = javaTriage()
        assertEquals(expected, triage)
    }

    @Nested
    inner class Blocks {

        @Test
        fun `each block runs exactly once`() {
            var propositionRuns = 0
            var choiceRuns = 0
            var ratingRuns = 0
            decisionSpec {
                proposition("is_urgent") {
                    propositionRuns++
                    asking("Does this convey urgency?")
                }
                choice("department") {
                    choiceRuns++
                    asking("Which team should handle this?").option("billing", "Payments")
                }
                rating("frustration") {
                    ratingRuns++
                    asking("How frustrated is the customer?").level("Calm").level("Angry")
                }
            }
            assertEquals(1, propositionRuns)
            assertEquals(1, choiceRuns)
            assertEquals(1, ratingRuns)
        }

        @Test
        fun `a throwing block propagates unchanged and no spec is produced`() {
            val thrown = IllegalStateException("boom")
            var built = false
            val error = assertThrows(IllegalStateException::class.java) {
                decisionSpec {
                    proposition("is_urgent") { asking("Does this convey urgency?") }
                    choice("department") {
                        asking("Which team should handle this?").option("billing", "Payments")
                        throw thrown
                    }
                }
                built = true
            }
            assertSame(thrown, error)
            assertFalse(built)
        }

        @Test
        fun `an invalid definition is rejected and no spec is produced`() {
            var built = false
            val error = assertThrows(IllegalArgumentException::class.java) {
                decisionSpec {
                    proposition("is_urgent") { asking("Does this convey urgency?") }
                    choice("department") { }
                }
                built = true
            }
            assertTrue(error.message!!.contains("'department'"))
            assertFalse(built)
        }

        @Test
        fun `a duplicate name is rejected and no spec is produced`() {
            var built = false
            val error = assertThrows(IllegalArgumentException::class.java) {
                decisionSpec {
                    proposition("is_urgent") { asking("Does this convey urgency?") }
                    proposition("is_urgent") { asking("Again?") }
                }
                built = true
            }
            assertTrue(error.message!!.contains("'is_urgent'"))
            assertFalse(built)
        }
    }

    @Nested
    inner class NamedQuestions {

        @Test
        fun `question adds a question that was built earlier`() {
            val department = Questions.named("department")
                .choice("Which team should handle this?")
                .option("billing", "Payments, invoicing, refunds")
                .build()
            val spec = decisionSpec {
                proposition("is_urgent") { asking("Does this convey urgency?") }
                question(department)
            }
            assertEquals(listOf("is_urgent", "department"), spec.questions.map { it.name })
            assertSame(department, spec.questions[1])
        }
    }

    @Nested
    inner class EmptySpec {

        @Test
        fun `an empty block cannot build a spec`() {
            val error = assertThrows(IllegalArgumentException::class.java) { decisionSpec { } }
            assertTrue(error.message!!.contains("at least one question"))
        }
    }
}
