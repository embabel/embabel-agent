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

class DecisionResponseTest {

    private val jev = ModelProvenance("jev-latest", "typesafe")

    private fun urgent(instructions: String = "Does this convey urgency?"): PropositionQuestionSpec =
        Questions.named("is_urgent").proposition(instructions).build()

    private fun department(billing: String = "Payments, invoicing, refunds"): ChoiceQuestionSpec =
        Questions.named("department")
            .choice("Which team should handle this?")
            .option("billing", billing)
            .option("technical", "Bugs, outages, integrations")
            .build()

    private fun frustration(): RatingQuestionSpec = Questions.named("frustration")
        .rating("How frustrated is the customer?")
        .level("Calm")
        .level("Frustrated")
        .level("Very angry")
        .build()

    private fun triage(): DecisionSpec = DecisionSpec.of(urgent(), department(), frustration())

    private val urgentYes = PropositionResult.Answered(true, jev, 0.93)
    private val billing = ClassificationResult.Selected("billing", jev, 0.91)
    private val frustrated = RatingResult.Answered(
        jev,
        selectedLevelId = "Frustrated",
        distribution = listOf(
            LevelProbability("Calm", 0.2),
            LevelProbability("Frustrated", 0.7),
            LevelProbability("Very angry", 0.1),
        ),
        score = RatingScore(0.9, RatingStatistic.EXPECTED_LEVEL_INDEX),
    )

    private fun answered(mode: ExecutionMode = ExecutionMode.NATIVE): DecisionResponse =
        DecisionResponse.builder(triage(), mode)
            .answer(urgent(), urgentYes)
            .answer(department(), billing)
            .answer(frustration(), frustrated)
            .build()

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
        fun `a built response carries the spec id, the mode and one answer per question in spec order`() {
            val spec = triage()
            val response = DecisionResponse.builder(spec, ExecutionMode.SEQUENTIAL)
                .answer(frustration(), frustrated)
                .answer(urgent(), urgentYes)
                .answer(department(), billing)
                .build()
            assertEquals(spec.definitionId, response.definitionId)
            assertEquals(ExecutionMode.SEQUENTIAL, response.executionMode)
            assertNull(response.requestFailure)
            assertEquals(listOf("is_urgent", "department", "frustration"), response.answers.map { it.name })
            assertEquals(spec.questions.map { it.definitionId }, response.answers.map { it.definitionId })
            assertEquals(spec.questions.map { it.kind }, response.answers.map { it.kind })
        }

        @Test
        fun `each answer holds its outcome and the public definition of its question`() {
            val response = answered()
            val proposition = assertInstanceOf(DecisionAnswer.Proposition::class.java, response.answer("is_urgent"))
            assertSame(urgentYes, proposition.outcome)
            assertEquals(QuestionKind.PROPOSITION, proposition.kind)

            val choice = assertInstanceOf(DecisionAnswer.Choice::class.java, response.answer("department"))
            assertSame(billing, choice.outcome)
            assertEquals(department().options, choice.options)
            assertEquals(QuestionKind.CHOICE, choice.kind)

            val rating = assertInstanceOf(DecisionAnswer.Rating::class.java, response.answer("frustration"))
            assertSame(frustrated, rating.outcome)
            assertEquals(frustration().levels, rating.levels)
            assertEquals(QuestionKind.RATING, rating.kind)
        }

        @Test
        fun `outcomes without a selection are accepted`() {
            val response = DecisionResponse.builder(triage(), ExecutionMode.NATIVE)
                .answer(urgent(), PropositionResult.Inconclusive(jev))
                .answer(department(), ClassificationResult.NoMatch(jev))
                .answer(frustration(), RatingResult.Inconclusive(jev))
                .build()
            assertEquals(ClassificationResult.NoMatch(jev), response.answer(department()))
        }

        @Test
        fun `a question outside the spec is rejected`() {
            val other = Questions.named("tone").proposition("Is the tone polite?").build()
            assertRejected("'tone'") {
                DecisionResponse.builder(triage(), ExecutionMode.NATIVE).answer(other, urgentYes)
            }
        }

        @Test
        fun `a question with the spec's name and a changed definition is rejected`() {
            assertRejected("'is_urgent'", "different definition") {
                DecisionResponse.builder(triage(), ExecutionMode.NATIVE)
                    .answer(urgent("Is this urgent?"), urgentYes)
            }
        }

        @Test
        fun `a second answer for a question is rejected and the first one stays`() {
            val builder = DecisionResponse.builder(triage(), ExecutionMode.NATIVE).answer(urgent(), urgentYes)
            assertRejected("'is_urgent'", "already has an answer") {
                builder.answer(urgent(), PropositionResult.Answered(false, jev))
            }
            val response = builder.answer(department(), billing).answer(frustration(), frustrated).build()
            assertSame(urgentYes, response.answer(urgent()))
        }

        @Test
        fun `build requires an answer for every question`() {
            val builder = DecisionResponse.builder(triage(), ExecutionMode.NATIVE).answer(department(), billing)
            assertRejected("Missing", "'is_urgent'", "'frustration'") { builder.build() }
        }

        @Test
        fun `the builder has one answer method per question class`() {
            val firstParameters = DecisionResponse.Builder::class.java.methods
                .filter { it.name == "answer" }
                .map { it.parameterTypes.toList() }
                .toSet()
            assertEquals(
                setOf(
                    listOf(PropositionQuestionSpec::class.java, PropositionResult::class.java),
                    listOf(ChoiceQuestionSpec::class.java, ClassificationResult::class.java),
                    listOf(RatingQuestionSpec::class.java, RatingResult::class.java),
                ),
                firstParameters,
            )
        }

        @Test
        fun `a holder of any question dispatches to the typed answer methods with a sealed when`() {
            val spec = triage()
            val outcomes: Map<String, Any> = mapOf("is_urgent" to urgentYes, "department" to billing, "frustration" to frustrated)
            val builder = DecisionResponse.builder(spec, ExecutionMode.NATIVE)
            for (question: Question<*> in spec.questions) {
                val outcome = outcomes.getValue(question.name)
                when (question) {
                    is PropositionQuestionSpec -> builder.answer(question, outcome as PropositionResult)
                    is ChoiceQuestionSpec -> builder.answer(question, outcome as ClassificationResult)
                    is RatingQuestionSpec -> builder.answer(question, outcome as RatingResult)
                }
            }
            assertEquals(answered(), builder.build())
        }

        @Test
        fun `a choice outcome with an unknown option id is rejected`() {
            assertRejected("'department'", "not one of its options") {
                DecisionResponse.builder(triage(), ExecutionMode.NATIVE)
                    .answer(department(), ClassificationResult.Selected("sales", jev))
            }
        }

        @Test
        fun `a rating level outside the scale is rejected`() {
            assertRejected("'frustration'", "not one of its levels") {
                DecisionResponse.builder(triage(), ExecutionMode.NATIVE)
                    .answer(frustration(), RatingResult.Answered(jev, selectedLevelId = "Furious"))
            }
        }

        @Test
        fun `a score above the top level is rejected`() {
            assertRejected("'frustration'", "2.5", "last level index 2") {
                DecisionResponse.builder(triage(), ExecutionMode.NATIVE).answer(
                    frustration(),
                    RatingResult.Answered(jev, score = RatingScore(2.5, RatingStatistic.EXPECTED_LEVEL_INDEX)),
                )
            }
        }

        @Test
        fun `a score at the top level is accepted`() {
            val top = RatingResult.Answered(jev, score = RatingScore(2.0, RatingStatistic.EXPECTED_LEVEL_INDEX))
            val response = DecisionResponse.builder(DecisionSpec.of(frustration()), ExecutionMode.NATIVE)
                .answer(frustration(), top)
                .build()
            assertSame(top, response.answer(frustration()))
        }

        @Test
        fun `a distribution missing a level is rejected`() {
            assertRejected("'frustration'", "distribution") {
                DecisionResponse.builder(triage(), ExecutionMode.NATIVE).answer(
                    frustration(),
                    RatingResult.Answered(
                        jev,
                        distribution = listOf(LevelProbability("Calm", 0.5), LevelProbability("Frustrated", 0.5)),
                    ),
                )
            }
        }

        @Test
        fun `the answers list cannot be modified`() {
            @Suppress("UNCHECKED_CAST")
            val answers = answered().answers as MutableList<DecisionAnswer>
            assertThrows(UnsupportedOperationException::class.java) { answers.removeAt(0) }
        }

        @Test
        fun `responses have no public constructor`() {
            for (type in listOf(
                DecisionResponse::class.java,
                DecisionResponse.Builder::class.java,
                DecisionAnswer.Proposition::class.java,
                DecisionAnswer.Choice::class.java,
                DecisionAnswer.Rating::class.java,
            )) {
                assertTrue(type.constructors.none { !it.isSynthetic }) { "$type has a public constructor" }
            }
        }
    }

    @Nested
    inner class NameLookup {

        @Test
        fun `an unknown name is rejected with the name in the message`() {
            assertRejected("'tone'") { answered().answer("tone") }
        }
    }

    @Nested
    inner class TypedLookup {

        @Test
        fun `lookup returns the outcome for the question that built the response`() {
            val department = department()
            val response = DecisionResponse.builder(DecisionSpec.of(department), ExecutionMode.NATIVE)
                .answer(department, billing)
                .build()
            val team: ClassificationResult = response.answer(department)
            assertSame(billing, team)
        }

        @Test
        fun `lookup returns the outcome for an equivalent reconstructed question`() {
            val response = answered()
            val urgency: PropositionResult = response.answer(urgent())
            val team: ClassificationResult = response.answer(department())
            val anger: RatingResult = response.answer(frustration())
            assertSame(urgentYes, urgency)
            assertSame(billing, team)
            assertSame(frustrated, anger)
        }

        @Test
        fun `changed instructions are rejected as definition drift`() {
            val drifted = urgent("Is this urgent?")
            val error = assertRejected("'is_urgent'", "different definition") { answered().answer(drifted) }
            assertTrue(error.message!!.contains(urgent().definitionId))
            assertTrue(error.message!!.contains(drifted.definitionId))
        }

        @Test
        fun `a changed option description is rejected as definition drift`() {
            assertRejected("'department'", "different definition") {
                answered().answer(department(billing = "Money matters"))
            }
        }

        @Test
        fun `a question of another kind is rejected`() {
            val wrongKind = Questions.named("department").proposition("Is this for a department?").build()
            assertRejected("'department'", "choice", "proposition") { answered().answer(wrongKind) }
        }

        @Test
        fun `an unknown name is rejected`() {
            val tone = Questions.named("tone").proposition("Is the tone polite?").build()
            assertRejected("'tone'") { answered().answer(tone) }
        }
    }

    @Nested
    inner class Failed {

        @Test
        fun `failed gives every question a failure of its kind and records the request failure`() {
            val spec = triage()
            val response = DecisionResponse.failed(spec, ExecutionMode.NATIVE, FailureReason.UNAVAILABLE)
            assertEquals(spec.definitionId, response.definitionId)
            assertEquals(ExecutionMode.NATIVE, response.executionMode)
            assertEquals(FailureReason.UNAVAILABLE, response.requestFailure)
            assertEquals(listOf("is_urgent", "department", "frustration"), response.answers.map { it.name })
            assertEquals(PropositionResult.Failure(FailureReason.UNAVAILABLE), response.answer(urgent()))
            assertEquals(ClassificationResult.Failure(FailureReason.UNAVAILABLE), response.answer(department()))
            assertEquals(RatingResult.Failure(FailureReason.UNAVAILABLE), response.answer(frustration()))
            val choice = assertInstanceOf(DecisionAnswer.Choice::class.java, response.answer("department"))
            assertEquals(department().options, choice.options)
        }
    }

    @Nested
    inner class Standalone {

        private fun rebuild(
            answers: List<DecisionAnswer>,
            definitionId: String = triage().definitionId,
            requestFailure: FailureReason? = null,
        ): DecisionResponse = DecisionResponse.create(definitionId, ExecutionMode.NATIVE, requestFailure, answers)

        @Test
        fun `the internal factory rebuilds an equal response from its parts`() {
            val original = answered()
            val parts = original.answers.map {
                when (it) {
                    is DecisionAnswer.Proposition -> DecisionAnswer.Proposition.create(it.name, it.definitionId, it.outcome)
                    is DecisionAnswer.Choice -> DecisionAnswer.Choice.create(it.name, it.definitionId, it.options, it.outcome)
                    is DecisionAnswer.Rating -> DecisionAnswer.Rating.create(it.name, it.definitionId, it.levels, it.outcome)
                }
            }
            val rebuilt = rebuild(parts)
            assertEquals(original, rebuilt)
            assertEquals(original.hashCode(), rebuilt.hashCode())
            assertSame(billing, rebuilt.answer(department()))
        }

        @Test
        fun `the typed lookup rejects embedded options that differ from the question's under the same definition id`() {
            val answers = answered().answers
            val tampered = DecisionAnswer.Choice.create(
                "department",
                department().definitionId,
                listOf(Category("billing", "Payments, invoicing, refunds"), Category("hacked", "Bugs, outages, integrations")),
                ClassificationResult.Selected("hacked", jev),
            )
            val response = rebuild(listOf(answers[0], tampered, answers[2]))
            assertSame(tampered, response.answer("department"))
            assertRejected("'department'", "options differ from the question's") { response.answer(department()) }
            assertSame(urgentYes, response.answer(urgent()))
        }

        @Test
        fun `the typed lookup rejects embedded levels that differ from the question's under the same definition id`() {
            val answers = answered().answers
            val tampered = DecisionAnswer.Rating.create(
                "frustration",
                frustration().definitionId,
                listOf(RatingLevel("Calm"), RatingLevel("Frustrated"), RatingLevel("Furious")),
                RatingResult.Answered(jev, selectedLevelId = "Furious"),
            )
            val response = rebuild(listOf(answers[0], answers[1], tampered))
            assertRejected("'frustration'", "levels differ from the question's") { response.answer(frustration()) }
            assertSame(billing, response.answer(department()))
        }

        @Test
        fun `a dropped answer fails the spec id check with the expected and actual ids`() {
            val kept = answered().answers.drop(1)
            val actual = DefinitionIds.spec(kept.map { it.definitionId })
            assertRejected(triage().definitionId, actual) { rebuild(kept) }
        }

        @Test
        fun `a swapped pair fails the spec id check`() {
            val (first, second, third) = answered().answers
            assertRejected(triage().definitionId) { rebuild(listOf(second, first, third)) }
        }

        @Test
        fun `repeated answer names are rejected`() {
            val answers = answered().answers
            assertRejected("'is_urgent'") { rebuild(listOf(answers[0], answers[0])) }
        }

        @Test
        fun `a response needs at least one answer`() {
            assertRejected("at least one answer") { rebuild(emptyList(), DefinitionIds.spec(emptyList())) }
        }

        @Test
        fun `a request failure with a non-failure outcome is rejected`() {
            val failed = DecisionResponse.failed(triage(), ExecutionMode.NATIVE, FailureReason.UNAVAILABLE).answers
            val mixed = listOf(failed[0], answered().answers[1], failed[2])
            assertRejected("request failure", "'department'") {
                rebuild(mixed, requestFailure = FailureReason.UNAVAILABLE)
            }
        }

        @Test
        fun `a request failure with a failure of another reason is rejected`() {
            val unavailable = DecisionResponse.failed(triage(), ExecutionMode.NATIVE, FailureReason.UNAVAILABLE).answers
            val invalid = DecisionResponse.failed(triage(), ExecutionMode.NATIVE, FailureReason.INVALID_RESPONSE).answers
            val mixed = listOf(unavailable[0], invalid[1], unavailable[2])
            val error = assertRejected("'department'", "INVALID_RESPONSE", "UNAVAILABLE") {
                rebuild(mixed, requestFailure = FailureReason.UNAVAILABLE)
            }
            assertFalse(error.message!!.contains("'is_urgent'"), error.message)
            assertFalse(error.message!!.contains("'frustration'"), error.message)
        }

        @Test
        fun `failure outcomes without a request failure are allowed`() {
            val failed = DecisionResponse.failed(triage(), ExecutionMode.NATIVE, FailureReason.INVALID_RESPONSE)
            val rebuilt = rebuild(failed.answers)
            assertNull(rebuilt.requestFailure)
        }

        @Test
        fun `a choice answer rejects a selection outside its embedded options`() {
            assertRejected("'department'", "not one of its options") {
                DecisionAnswer.Choice.create(
                    "department",
                    department().definitionId,
                    department().options,
                    ClassificationResult.Selected("sales", jev),
                )
            }
        }

        @Test
        fun `a choice answer needs unique options`() {
            assertRejected("'department'", "at least one option") {
                DecisionAnswer.Choice.create("department", department().definitionId, emptyList(), billing)
            }
            val repeated = listOf(Category("billing", "One"), Category("billing", "Two"))
            assertRejected("'department'", "'billing'") {
                DecisionAnswer.Choice.create("department", department().definitionId, repeated, billing)
            }
        }

        @Test
        fun `a blank option or level id cannot reach an answer`() {
            // Category and RatingLevel reject a blank id when they are made, before an answer sees them.
            assertRejected("must not be blank") {
                DecisionAnswer.Choice.create(
                    "department",
                    department().definitionId,
                    listOf(Category(" ", "Unnamed"), Category("billing", "Payments")),
                    ClassificationResult.NoMatch(jev),
                )
            }
            assertRejected("must not be blank") {
                DecisionAnswer.Rating.create(
                    "frustration",
                    frustration().definitionId,
                    listOf(RatingLevel("", "Unnamed"), RatingLevel("Calm")),
                    RatingResult.Inconclusive(jev),
                )
            }
        }

        @Test
        fun `a rating answer rejects a level outside its embedded levels`() {
            assertRejected("'frustration'", "not one of its levels") {
                DecisionAnswer.Rating.create(
                    "frustration",
                    frustration().definitionId,
                    frustration().levels,
                    RatingResult.Answered(jev, selectedLevelId = "Furious"),
                )
            }
        }

        @Test
        fun `a rating answer rejects a score above its top level`() {
            assertRejected("'frustration'", "last level index 1") {
                DecisionAnswer.Rating.create(
                    "frustration",
                    frustration().definitionId,
                    listOf(RatingLevel("Calm"), RatingLevel("Angry")),
                    RatingResult.Answered(jev, score = RatingScore(1.5, RatingStatistic.EXPECTED_LEVEL_INDEX)),
                )
            }
        }

        @Test
        fun `a rating answer needs at least two unique levels`() {
            assertRejected("'frustration'", "at least two levels") {
                DecisionAnswer.Rating.create(
                    "frustration", frustration().definitionId, listOf(RatingLevel("Calm")), frustrated,
                )
            }
            assertRejected("'frustration'", "'Calm'") {
                DecisionAnswer.Rating.create(
                    "frustration",
                    frustration().definitionId,
                    listOf(RatingLevel("Calm"), RatingLevel("Calm", "Relaxed")),
                    RatingResult.Inconclusive(jev),
                )
            }
        }

        @Test
        fun `an answer needs a name and a definition id`() {
            assertRejected("name") { DecisionAnswer.Proposition.create(" ", urgent().definitionId, urgentYes) }
            assertRejected("'is_urgent'", "definition id") { DecisionAnswer.Proposition.create("is_urgent", "", urgentYes) }
        }

        @Test
        fun `an answer definition id must have the question id shape`() {
            for (id in listOf("garbage", "d1-short", triage().definitionId, urgent().definitionId + "x")) {
                assertRejected("'is_urgent'", "not a question id") {
                    DecisionAnswer.Proposition.create("is_urgent", id, urgentYes)
                }
            }
        }

        @Test
        fun `a response definition id must have the spec id shape`() {
            for (id in listOf("garbage", urgent().definitionId)) {
                assertRejected("'$id'", "not a spec id") { rebuild(answered().answers, definitionId = id) }
            }
        }
    }

    @Nested
    inner class Equality {

        @Test
        fun `responses with equal parts are equal`() {
            assertEquals(answered(), answered())
            assertEquals(answered().hashCode(), answered().hashCode())
            assertEquals(answered().answers, answered().answers)
        }

        @Test
        fun `responses differ by mode, outcome and request failure`() {
            assertNotEquals(answered(ExecutionMode.NATIVE), answered(ExecutionMode.SEQUENTIAL))
            val other = DecisionResponse.builder(triage(), ExecutionMode.NATIVE)
                .answer(urgent(), PropositionResult.Answered(false, jev))
                .answer(department(), billing)
                .answer(frustration(), frustrated)
                .build()
            assertNotEquals(answered(), other)
            val unavailable = DecisionResponse.failed(triage(), ExecutionMode.NATIVE, FailureReason.UNAVAILABLE)
            assertNotEquals(unavailable, DecisionResponse.create(
                unavailable.definitionId, ExecutionMode.NATIVE, null, unavailable.answers,
            ))
        }

        @Test
        fun `toString names the spec id, the mode, the request failure and the answers`() {
            val text = answered().toString()
            assertTrue(text.contains("is_urgent") && text.contains("department") && text.contains("frustration"), text)
            assertTrue(text.contains(triage().definitionId), text)
            assertTrue(text.contains("NATIVE") && text.contains("requestFailure=null"), text)
        }
    }
}
