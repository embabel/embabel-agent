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
import org.junit.jupiter.api.function.Executable
import java.io.IOException

class DecisionSpecTest {

    private fun urgent(): PropositionQuestionSpec =
        Questions.named("is_urgent").proposition("Does this convey urgency?").build()

    private fun department(): ChoiceQuestionSpec = Questions.named("department")
        .choice("Which team should handle this?")
        .option("billing", "Payments, invoicing, refunds")
        .option("technical", "Bugs, outages, integrations")
        .build()

    private fun frustration(): RatingQuestionSpec = Questions.named("frustration")
        .rating("How frustrated is the customer?")
        .level("Calm")
        .level("Frustrated")
        .level("Very angry")
        .build()

    // A builder holding only the urgent question, declared through its customizer.
    private fun builderWithUrgent(): DecisionSpec.Builder =
        DecisionSpec.builder().proposition("is_urgent") { it.asking("Does this convey urgency?") }

    private fun assertRejected(vararg fragments: String, block: () -> Unit): IllegalArgumentException {
        val error = assertThrows(IllegalArgumentException::class.java, Executable(block))
        for (fragment in fragments) {
            assertTrue(error.message!!.contains(fragment)) { "Expected '$fragment' in: ${error.message}" }
        }
        return error
    }

    @Nested
    inner class Building {

        @Test
        fun `the builder keeps questions in declared order`() {
            val spec = DecisionSpec.builder()
                .proposition("is_urgent") { it.asking("Does this convey urgency?") }
                .choice("department") {
                    it.asking("Which team should handle this?")
                        .option("billing", "Payments, invoicing, refunds")
                        .option("technical", "Bugs, outages, integrations")
                }
                .rating("frustration") {
                    it.asking("How frustrated is the customer?")
                        .level("Calm")
                        .level("Frustrated")
                        .level("Very angry")
                }
                .build()
            assertEquals(listOf("is_urgent", "department", "frustration"), spec.questions.map { it.name })
            assertEquals(listOf(urgent(), department(), frustration()), spec.questions)
        }

        @Test
        fun `a declared question equals the same question built with Questions named`() {
            val spec = builderWithUrgent().build()
            assertEquals(urgent(), spec.questions.single())
        }

        @Test
        fun `a question object can be added directly`() {
            val department = department()
            val spec = builderWithUrgent().question(department).build()
            assertSame(department, spec.questions[1])
        }

        @Test
        fun `question finds a question by name`() {
            val spec = DecisionSpec.of(urgent(), department())
            assertEquals(department(), spec.question("department"))
            assertNull(spec.question("missing"))
        }

        @Test
        fun `the questions list cannot be modified`() {
            @Suppress("UNCHECKED_CAST")
            val questions = DecisionSpec.of(urgent()).questions as MutableList<Question<*>>
            assertThrows(UnsupportedOperationException::class.java) { questions.add(department()) }
        }

        @Test
        fun `an empty builder cannot build`() {
            assertRejected("at least one question") { DecisionSpec.builder().build() }
        }
    }

    @Nested
    inner class Customizers {

        @Test
        fun `each customizer runs exactly once and before the declaring call returns`() {
            var propositionRuns = 0
            var choiceRuns = 0
            var ratingRuns = 0
            val builder = DecisionSpec.builder()
            builder.proposition("is_urgent") {
                propositionRuns++
                it.asking("Does this convey urgency?")
            }
            assertEquals(1, propositionRuns)
            builder.choice("department") {
                choiceRuns++
                it.asking("Which team should handle this?").option("billing", "Payments")
            }
            assertEquals(1, choiceRuns)
            builder.rating("frustration") {
                ratingRuns++
                it.asking("How frustrated is the customer?").level("Calm").level("Angry")
            }
            assertEquals(1, ratingRuns)

            builder.build()
            builder.build()
            assertEquals(listOf(1, 1, 1), listOf(propositionRuns, choiceRuns, ratingRuns))
        }

        @Test
        fun `each customizer receives a fresh builder carrying the declared name`() {
            val seen = mutableListOf<PropositionQuestionSpec.Builder>()
            val spec = DecisionSpec.builder()
                .proposition("first") { seen += it.asking("One?") }
                .proposition("second") { seen += it.asking("Two?") }
                .build()
            assertEquals(2, seen.size)
            assertNotSame(seen[0], seen[1])
            assertEquals(listOf("first", "second"), spec.questions.map { it.name })
        }

        @Test
        fun `changing a leaked question builder later does not change the built spec`() {
            lateinit var leaked: ChoiceQuestionSpec.Builder
            val builder = DecisionSpec.builder().choice("department") {
                leaked = it.asking("Which team?").option("billing", "Payments")
            }
            val before = builder.build()
            leaked.asking("Changed?").option("sales", "Pricing")
            assertEquals(before, builder.build())
            assertEquals(1, (builder.build().questions.single() as ChoiceQuestionSpec).options.size)
        }
    }

    @Nested
    inner class AtomicFailure {

        @Test
        fun `a throwing customizer propagates its exception unchanged and adds nothing`() {
            val builder = builderWithUrgent()
            val before = builder.build()
            val thrown = IllegalStateException("boom")
            val error = assertThrows(IllegalStateException::class.java) {
                builder.choice("department") {
                    it.asking("Which team?").option("billing", "Payments")
                    throw thrown
                }
            }
            assertSame(thrown, error)
            assertEquals(before, builder.build())
        }

        @Test
        fun `a checked exception from a customizer propagates unchanged`() {
            val builder = builderWithUrgent()
            val before = builder.build()
            val thrown = IOException("disk")
            val error = assertThrows(IOException::class.java) {
                builder.rating("frustration") { throw thrown }
            }
            assertSame(thrown, error)
            assertEquals(before, builder.build())
        }

        @Test
        fun `an error thrown by a customizer propagates unchanged and adds nothing`() {
            val builder = builderWithUrgent()
            val before = builder.build()
            val thrown = AssertionError("fatal")
            val error = assertThrows(AssertionError::class.java) {
                builder.proposition("other") { throw thrown }
            }
            assertSame(thrown, error)
            assertEquals(before, builder.build())
        }

        @Test
        fun `a definition missing asking is rejected by name and adds nothing`() {
            val builder = builderWithUrgent()
            val before = builder.build()
            assertRejected("'department'", "instructions") {
                builder.choice("department") { it.option("billing", "Payments") }
            }
            assertEquals(before, builder.build())
        }

        @Test
        fun `each kind's invalid definition is rejected and adds nothing`() {
            val builder = builderWithUrgent()
            val before = builder.build()
            assertRejected("'other'", "instructions") { builder.proposition("other") { } }
            assertRejected("'department'", "at least one option") {
                builder.choice("department") { it.asking("Which team?") }
            }
            assertRejected("'frustration'", "at least two levels") {
                builder.rating("frustration") { it.asking("How frustrated?").level("Calm") }
            }
            assertEquals(before, builder.build())
        }

        @Test
        fun `a duplicate name is rejected by name and adds nothing`() {
            val builder = builderWithUrgent().question(department())
            val before = builder.build()
            assertRejected("'is_urgent'") { builder.proposition("is_urgent") { it.asking("Something else?") } }
            assertRejected("'department'") { builder.choice("department") { it.asking("Who?").option("a", "A") } }
            assertRejected("'is_urgent'") { builder.rating("is_urgent") { it.asking("How?").level("a").level("b") } }
            assertEquals(before, builder.build())
        }

        @Test
        fun `a repeated identical declaration is a duplicate`() {
            val builder = builderWithUrgent()
            val before = builder.build()
            assertRejected("'is_urgent'", "unique") {
                builder.proposition("is_urgent") { it.asking("Does this convey urgency?") }
            }
            assertRejected("'is_urgent'", "unique") { builder.question(urgent()) }
            assertEquals(before, builder.build())
        }

        @Test
        fun `a duplicate name is rejected before its customizer runs`() {
            val builder = builderWithUrgent()
            var runs = 0
            assertRejected("'is_urgent'") {
                builder.proposition("is_urgent") {
                    runs++
                    it.asking("Again?")
                }
            }
            assertEquals(0, runs)
        }

        @Test
        fun `a blank name is rejected before its customizer runs and adds nothing`() {
            val builder = builderWithUrgent()
            val before = builder.build()
            var runs = 0
            for (name in listOf("", " ")) {
                assertRejected("name must not be blank") { builder.proposition(name) { runs++ } }
                assertRejected("name must not be blank") { builder.choice(name) { runs++ } }
                assertRejected("name must not be blank") { builder.rating(name) { runs++ } }
            }
            assertEquals(0, runs)
            assertEquals(before, builder.build())
        }

        @Test
        fun `a failure on an empty builder leaves it empty`() {
            val builder = DecisionSpec.builder()
            assertThrows(IllegalStateException::class.java) {
                builder.proposition("is_urgent") { throw IllegalStateException("boom") }
            }
            assertRejected("at least one question") { builder.build() }
            assertEquals(DecisionSpec.of(urgent()), builder.question(urgent()).build())
        }

        @Test
        fun `the builder keeps working after a failure`() {
            val builder = builderWithUrgent()
            assertRejected("'is_urgent'") { builder.question(urgent()) }
            assertThrows(IllegalStateException::class.java) {
                builder.choice("department") { throw IllegalStateException("boom") }
            }
            builder.question(department())
            assertEquals(DecisionSpec.of(urgent(), department()), builder.build())
        }

        @Test
        fun `a customizer cannot add to the builder that is running it`() {
            val builder = builderWithUrgent()
            val before = builder.build()
            assertThrows(IllegalStateException::class.java) {
                builder.choice("department") {
                    builder.question(frustration())
                    it.asking("Which team?").option("billing", "Payments")
                }
            }
            assertEquals(before, builder.build())
        }

        @Test
        fun `a customizer that swallows the nested rejection still adds only its own question`() {
            val builder = builderWithUrgent()
            var nested: IllegalStateException? = null
            builder.proposition("other") {
                nested = assertThrows(IllegalStateException::class.java) {
                    builder.proposition("inner") { q -> q.asking("Inner?") }
                }
                it.asking("Other?")
            }
            assertTrue(nested!!.message!!.contains("customizer"))
            assertEquals(listOf("is_urgent", "other"), builder.build().questions.map { it.name })
            builder.question(department())
            assertEquals(listOf("is_urgent", "other", "department"), builder.build().questions.map { it.name })
        }
    }

    @Nested
    inner class Snapshots {

        @Test
        fun `later declarations do not change a spec already built`() {
            val builder = builderWithUrgent()
            val first = builder.build()
            builder.question(department())
            val second = builder.build()
            assertEquals(listOf("is_urgent"), first.questions.map { it.name })
            assertEquals(listOf("is_urgent", "department"), second.questions.map { it.name })
            assertNotEquals(first, second)
        }

        @Test
        fun `building twice gives equal independent specs`() {
            val builder = builderWithUrgent()
            val first = builder.build()
            val second = builder.build()
            assertEquals(first, second)
            assertNotSame(first, second)
        }

        @Test
        fun `changing the list given to of does not change the spec`() {
            val questions = mutableListOf<Question<*>>(urgent())
            val spec = DecisionSpec.of(questions)
            questions += department()
            assertEquals(listOf("is_urgent"), spec.questions.map { it.name })
        }

        @Test
        fun `changing the array given to of does not change the spec`() {
            val questions = arrayOf<Question<*>>(urgent(), department())
            val spec = DecisionSpec.of(*questions)
            questions[1] = frustration()
            assertEquals(listOf("is_urgent", "department"), spec.questions.map { it.name })
        }
    }

    @Nested
    inner class Factories {

        @Test
        fun `of and the builder give equal specs`() {
            val built = DecisionSpec.builder()
                .proposition("is_urgent") { it.asking("Does this convey urgency?") }
                .choice("department") {
                    it.asking("Which team should handle this?")
                        .option("billing", "Payments, invoicing, refunds")
                        .option("technical", "Bugs, outages, integrations")
                }
                .build()
            val listed = DecisionSpec.of(urgent(), department())
            assertEquals(listed, built)
            assertEquals(listed.hashCode(), built.hashCode())
            assertEquals(listed, DecisionSpec.of(listOf(urgent(), department())))
        }

        @Test
        fun `of needs at least one question`() {
            assertRejected("at least one question") { DecisionSpec.of() }
            assertRejected("at least one question") { DecisionSpec.of(emptyList()) }
        }

        @Test
        fun `of rejects repeated names and names them`() {
            val other = Questions.named("department").proposition("Is it for a department?").build()
            assertRejected("'department'") { DecisionSpec.of(department(), urgent(), other) }
            assertRejected("'is_urgent'") { DecisionSpec.of(listOf(urgent(), urgent())) }
        }
    }

    @Nested
    inner class Equality {

        @Test
        fun `specs with the same questions in the same order are equal`() {
            assertEquals(DecisionSpec.of(urgent(), department()), DecisionSpec.of(urgent(), department()))
            assertEquals(
                DecisionSpec.of(urgent(), department()).hashCode(),
                DecisionSpec.of(urgent(), department()).hashCode(),
            )
        }

        @Test
        fun `question order matters`() {
            val forward = DecisionSpec.of(urgent(), department())
            val backward = DecisionSpec.of(department(), urgent())
            assertNotEquals(forward, backward)
        }

        @Test
        fun `a changed question makes a different spec`() {
            val changed = Questions.named("is_urgent").proposition("Is this urgent?").build()
            assertNotEquals(DecisionSpec.of(urgent()), DecisionSpec.of(changed))
        }

        @Test
        fun `toString lists the question names`() {
            assertEquals("DecisionSpec(questions=[is_urgent, department])", DecisionSpec.of(urgent(), department()).toString())
        }
    }
}
