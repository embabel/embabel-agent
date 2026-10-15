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

import com.embabel.common.ai.classification.Category
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

class QuestionTest {

    private val provenance = ModelProvenance("model", "provider")

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

    private fun assertRejected(vararg fragments: String, block: () -> Unit) {
        val error = assertThrows(IllegalArgumentException::class.java, Executable(block))
        for (fragment in fragments) {
            assertTrue(error.message!!.contains(fragment)) { "Expected '$fragment' in: ${error.message}" }
        }
    }

    @Nested
    inner class Kinds {

        @Test
        fun `each kind has a lower-case wire name`() {
            assertEquals("proposition", QuestionKind.PROPOSITION.wireName)
            assertEquals("choice", QuestionKind.CHOICE.wireName)
            assertEquals("rating", QuestionKind.RATING.wireName)
        }

        @Test
        fun `each spec reports its kind and is typed by its result`() {
            val urgent: Question<PropositionResult> = Questions.named("is_urgent").proposition("Is it urgent?").build()
            val choice: Question<ClassificationResult> = department()
            val rating: Question<RatingResult> = frustration()
            assertEquals(QuestionKind.PROPOSITION, urgent.kind)
            assertEquals(QuestionKind.CHOICE, choice.kind)
            assertEquals(QuestionKind.RATING, rating.kind)
        }
    }

    @Nested
    inner class PropositionBuilder {

        @Test
        fun `builds a proposition with its name and instructions`() {
            val question = Questions.named("is_urgent").proposition("Does this convey urgency?").build()
            assertEquals("is_urgent", question.name)
            assertEquals("Does this convey urgency?", question.instructions)
        }

        @Test
        fun `asking replaces earlier instructions`() {
            val question = Questions.named("is_urgent").proposition("First").asking("Second").build()
            assertEquals("Second", question.instructions)
        }

        @Test
        fun `a blank name is rejected when the question is named`() {
            for (name in listOf("", " ", "\t")) {
                assertRejected("name must not be blank") { Questions.named(name) }
                assertRejected("name must not be blank") { PropositionQuestionSpec.Builder.create(name) }
                assertRejected("name must not be blank") { ChoiceQuestionSpec.Builder.create(name) }
                assertRejected("name must not be blank") { RatingQuestionSpec.Builder.create(name) }
            }
        }

        @Test
        fun `missing instructions are rejected at build and the message names the question`() {
            assertRejected("'is_urgent'", "instructions") { PropositionQuestionSpec.Builder.create("is_urgent").build() }
        }

        @Test
        fun `blank instructions are rejected at build and the message names the question`() {
            val builder = Questions.named("is_urgent").proposition(" ")
            assertRejected("'is_urgent'", "instructions must not be blank") { builder.build() }
        }

        @Test
        fun `a builder made by the internal factory starts with no instructions`() {
            val question = PropositionQuestionSpec.Builder.create("is_urgent").asking("Is it urgent?").build()
            assertEquals(Questions.named("is_urgent").proposition("Is it urgent?").build(), question)
        }
    }

    @Nested
    inner class ChoiceBuilder {

        @Test
        fun `builds options in declared order`() {
            val question = department()
            assertEquals(
                listOf(
                    Category("billing", "Payments, invoicing, refunds"),
                    Category("technical", "Bugs, outages, integrations"),
                ),
                question.options,
            )
        }

        @Test
        fun `options cannot be modified through the returned list`() {
            @Suppress("UNCHECKED_CAST")
            val options = department().options as MutableList<Category>
            assertThrows(UnsupportedOperationException::class.java) { options.add(Category("sales", "Pricing")) }
        }

        @Test
        fun `a choice needs at least one option`() {
            val builder = Questions.named("department").choice("Which team?")
            assertRejected("'department'", "at least one option") { builder.build() }
        }

        @Test
        fun `option ids must be unique`() {
            val builder = Questions.named("department").choice("Which team?")
                .option("billing", "Payments")
                .option("billing", "Invoices")
            assertRejected("'department'", "unique", "billing") { builder.build() }
        }

        @Test
        fun `a blank option id is rejected at build and the message names the question`() {
            val builder = Questions.named("department").choice("Which team?").option(" ", "Payments")
            assertRejected("'department'", "option id must not be blank") { builder.build() }
        }

        @Test
        fun `missing instructions are rejected for a choice`() {
            val builder = ChoiceQuestionSpec.Builder.create("department").option("billing", "Payments")
            assertRejected("'department'", "instructions") { builder.build() }
        }
    }

    @Nested
    inner class RatingBuilder {

        @Test
        fun `a level given one label uses it as id and description`() {
            val question = frustration()
            assertEquals("Calm", question.levels[0].id)
            assertEquals("Calm", question.levels[0].description)
        }

        @Test
        fun `a level can have a separate id and description`() {
            val question = Questions.named("mood").rating("How is the mood?")
                .level("low", "Quiet and withdrawn")
                .level("high", "Cheerful and talkative")
                .build()
            assertEquals(listOf(RatingLevel("low", "Quiet and withdrawn"), RatingLevel("high", "Cheerful and talkative")), question.levels)
        }

        @Test
        fun `levels cannot be modified through the returned list`() {
            @Suppress("UNCHECKED_CAST")
            val levels = frustration().levels as MutableList<RatingLevel>
            assertThrows(UnsupportedOperationException::class.java) { levels.clear() }
        }

        @Test
        fun `a rating needs at least two levels`() {
            assertRejected("'mood'", "at least two levels") { Questions.named("mood").rating("How?").build() }
            assertRejected("'mood'", "at least two levels") { Questions.named("mood").rating("How?").level("Calm").build() }
        }

        @Test
        fun `level ids must be unique`() {
            val builder = Questions.named("mood").rating("How?").level("Calm").level("Calm", "Relaxed")
            assertRejected("'mood'", "unique", "Calm") { builder.build() }
        }

        @Test
        fun `a blank level id is rejected at build and the message names the question`() {
            val builder = Questions.named("mood").rating("How?").level("Calm").level("")
            assertRejected("'mood'", "level id must not be blank") { builder.build() }
        }

        @Test
        fun `missing instructions are rejected for a rating`() {
            val builder = RatingQuestionSpec.Builder.create("mood").level("Calm").level("Angry")
            assertRejected("'mood'", "instructions") { builder.build() }
        }
    }

    @Nested
    inner class EqualityAndSnapshots {

        @Test
        fun `the same definition built twice gives equal questions`() {
            assertEquals(department(), department())
            assertEquals(department().hashCode(), department().hashCode())
            assertEquals(frustration(), frustration())
            assertEquals(frustration().hashCode(), frustration().hashCode())
            val first = Questions.named("x").proposition("Which?").build()
            assertEquals(first, Questions.named("x").proposition("Which?").build())
        }

        @Test
        fun `questions with different definitions are not equal`() {
            fun choice(name: String, instructions: String, vararg options: Pair<String, String>) =
                options.fold(Questions.named(name).choice(instructions)) { builder, (id, description) ->
                    builder.option(id, description)
                }.build()
            val billing = "billing" to "Payments, invoicing, refunds"
            val technical = "technical" to "Bugs, outages, integrations"
            val base = choice("department", "Which team should handle this?", billing, technical)
            assertEquals(base, department())
            assertNotEquals(base, choice("team", "Which team should handle this?", billing, technical))
            assertNotEquals(base, choice("department", "Other?", billing, technical))
            assertNotEquals(base, choice("department", "Which team should handle this?", technical, billing))
            assertNotEquals(base, choice("department", "Which team should handle this?", billing, "technical" to "Bugs"))
            val rating = Questions.named("q").rating("Q?").level("a").level("b").build()
            val choice = Questions.named("q").choice("Q?").option("a", "a").option("b", "b").build()
            assertFalse(rating.equals(choice))
            assertFalse(choice.equals(rating))
        }

        @Test
        fun `rating and proposition equality covers instructions, level order and level descriptions`() {
            assertNotEquals(
                Questions.named("p").proposition("A").build(),
                Questions.named("p").proposition("B").build(),
            )
            val reordered = Questions.named("frustration").rating("How frustrated is the customer?")
                .level("Very angry")
                .level("Frustrated")
                .level("Calm")
                .build()
            val described = Questions.named("frustration").rating("How frustrated is the customer?")
                .level("Calm", "Relaxed")
                .level("Frustrated")
                .level("Very angry")
                .build()
            assertNotEquals(frustration(), reordered)
            assertNotEquals(frustration(), described)
        }

        @Test
        fun `toString shows only the name and kind`() {
            val text = department().toString()
            assertTrue(text.contains("department"))
            assertTrue(text.contains("CHOICE"))
            assertFalse(text.contains("Which team"))
            assertFalse(text.contains("billing"))
        }

        @Test
        fun `a builder can be reused and earlier questions do not change`() {
            val builder = Questions.named("department").choice("Which team?").option("billing", "Payments")
            val first = builder.build()
            builder.option("technical", "Bugs").asking("Which team now?")
            val second = builder.build()
            assertEquals(listOf(Category("billing", "Payments")), first.options)
            assertEquals("Which team?", first.instructions)
            assertEquals(2, second.options.size)
            assertEquals("Which team now?", second.instructions)
            assertEquals(Questions.named("department").choice("Which team?").option("billing", "Payments").build(), first)
        }

        @Test
        fun `a rating builder can be reused and earlier questions do not change`() {
            val builder = Questions.named("mood").rating("How?").level("Calm").level("Angry")
            val first = builder.build()
            builder.level("Furious")
            assertEquals(2, first.levels.size)
            assertEquals(3, builder.build().levels.size)
            assertEquals(builder.build(), builder.build())
        }

        @Test
        fun `a failed build leaves the builder usable`() {
            val builder = Questions.named("department").choice("Which team?")
            assertThrows(IllegalArgumentException::class.java) { builder.build() }
            assertEquals(1, builder.option("billing", "Payments").build().options.size)
        }
    }

    @Nested
    inner class ChoiceValidation {

        @Test
        fun `a selection among the options is returned unchanged`() {
            val selected = ClassificationResult.Selected("billing", provenance, 0.9)
            assertSame(selected, department().validate(selected))
        }

        @Test
        fun `a selection outside the options is rejected and names the question`() {
            assertRejected("'department'") { department().validate(ClassificationResult.Selected("sales", provenance)) }
        }

        @Test
        fun `results without a selection pass through`() {
            val results = listOf(
                ClassificationResult.NoMatch(provenance),
                ClassificationResult.Inconclusive(provenance),
                ClassificationResult.Failure(FailureReason.UNAVAILABLE),
            )
            for (result in results) assertSame(result, department().validate(result))
        }
    }

    @Nested
    inner class RatingValidation {

        @Test
        fun `a selection on the scale is returned unchanged`() {
            val answered = RatingResult.Answered(provenance, selectedLevelId = "Frustrated")
            assertSame(answered, frustration().validate(answered))
        }

        @Test
        fun `a selection off the scale is rejected and names the question`() {
            assertRejected("'frustration'") {
                frustration().validate(RatingResult.Answered(provenance, selectedLevelId = "Livid"))
            }
        }

        @Test
        fun `a distribution covering exactly the levels in any order is accepted`() {
            val answered = RatingResult.Answered(
                provenance,
                distribution = listOf(
                    LevelProbability("Very angry", 0.2),
                    LevelProbability("Calm", 0.5),
                    LevelProbability("Frustrated", 0.3),
                ),
            )
            assertSame(answered, frustration().validate(answered))
        }

        @Test
        fun `a distribution missing a level is rejected`() {
            val answered = RatingResult.Answered(
                provenance,
                distribution = listOf(LevelProbability("Calm", 0.5), LevelProbability("Frustrated", 0.5)),
            )
            assertRejected("'frustration'", "distribution") { frustration().validate(answered) }
        }

        @Test
        fun `a distribution with a level off the scale is rejected`() {
            val answered = RatingResult.Answered(
                provenance,
                distribution = listOf(
                    LevelProbability("Calm", 0.25),
                    LevelProbability("Frustrated", 0.25),
                    LevelProbability("Very angry", 0.25),
                    LevelProbability("Livid", 0.25),
                ),
            )
            assertRejected("'frustration'", "distribution") { frustration().validate(answered) }
        }

        @Test
        fun `a score up to the last level index is accepted`() {
            for (value in listOf(0.0, 1.3, 2.0)) {
                val answered = RatingResult.Answered(provenance, score = RatingScore(value, RatingStatistic.EXPECTED_LEVEL_INDEX))
                assertSame(answered, frustration().validate(answered))
            }
        }

        @Test
        fun `a score beyond the last level index is rejected`() {
            val answered = RatingResult.Answered(provenance, score = RatingScore(2.0001, RatingStatistic.EXPECTED_LEVEL_INDEX))
            assertRejected("'frustration'", "score") { frustration().validate(answered) }
        }

        @Test
        fun `results without evidence pass through`() {
            val results = listOf(RatingResult.Inconclusive(provenance), RatingResult.Failure(FailureReason.INVALID_RESPONSE))
            for (result in results) assertSame(result, frustration().validate(result))
        }
    }
}
