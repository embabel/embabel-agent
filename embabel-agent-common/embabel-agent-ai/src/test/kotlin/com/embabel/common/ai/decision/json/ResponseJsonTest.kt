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
package com.embabel.common.ai.decision.json

import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.ChoiceQuestionSpec
import com.embabel.common.ai.decision.DecisionAnswer
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.ExecutionMode
import com.embabel.common.ai.decision.LevelProbability
import com.embabel.common.ai.decision.PropositionQuestionSpec
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.RatingQuestionSpec
import com.embabel.common.ai.decision.RatingResult
import com.embabel.common.ai.decision.RatingScore
import com.embabel.common.ai.decision.RatingStatistic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import tools.jackson.databind.DatabindException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.json.JsonMapper

class ResponseJsonTest {

    private val mapper: JsonMapper = JsonMapper.builder().build()

    // Adds the Kotlin module, which jackson-module-kotlin lists for ServiceLoader.
    private val discovered: JsonMapper = JsonMapper.builder().findAndAddModules().build()

    private val jev = ModelProvenance("jev-latest", "typesafe")
    private val jevFull = ModelProvenance("jev-latest", "typesafe", "2026-09", "req-42")

    private val jevJson = """{"modelName":"jev-latest","provider":"typesafe"}"""
    private val jevFullJson = """{"modelName":"jev-latest","provider":"typesafe","version":"2026-09","requestId":"req-42"}"""

    private val urgent: PropositionQuestionSpec = Questions.named("is_urgent").proposition("Does this convey urgency?").build()

    private val department: ChoiceQuestionSpec = Questions.named("department")
        .choice("Which team should handle this?")
        .option("billing", "Payments, invoicing, refunds")
        .option("technical", "Bugs, outages, integrations")
        .option("sales", "Pricing, upgrades, new accounts")
        .build()

    private val frustration: RatingQuestionSpec = Questions.named("frustration")
        .rating("How frustrated is the customer?")
        .level("Calm")
        .level("Frustrated")
        .level("Very angry")
        .build()

    private val triage: DecisionSpec = DecisionSpec.of(urgent, department, frustration)

    private val anger = RatingResult.Answered(
        jevFull,
        selectedLevelId = "Frustrated",
        distribution = listOf(
            LevelProbability("Calm", 0.2),
            LevelProbability("Frustrated", 0.7),
            LevelProbability("Very angry", 0.1),
        ),
        score = RatingScore(0.9, RatingStatistic.EXPECTED_LEVEL_INDEX),
        confidence = 0.6,
    )

    private fun answered(): DecisionResponse = DecisionResponse.builder(triage, ExecutionMode.NATIVE)
        .answer(urgent, PropositionResult.Answered(true, jev, 0.93))
        .answer(department, ClassificationResult.Selected("billing", jev, 0.91))
        .answer(frustration, anger)
        .build()

    private val optionsJson = """"options":[{"id":"billing","description":"Payments, invoicing, refunds"},""" +
        """{"id":"technical","description":"Bugs, outages, integrations"},""" +
        """{"id":"sales","description":"Pricing, upgrades, new accounts"}]"""

    private val levelsJson = """"levels":[{"id":"Calm","description":"Calm"},{"id":"Frustrated","description":"Frustrated"},""" +
        """{"id":"Very angry","description":"Very angry"}]"""

    private val urgentOutcome = """{"status":"answered","answer":true,"pTrue":0.93,"provenance":$jevJson}"""
    private val departmentOutcome = """{"status":"selected","categoryId":"billing","confidence":0.91,"provenance":$jevJson}"""
    private val angerOutcome = """{"status":"answered","selectedLevelId":"Frustrated",""" +
        """"distribution":[{"levelId":"Calm","probability":0.2},{"levelId":"Frustrated","probability":0.7},""" +
        """{"levelId":"Very angry","probability":0.1}],""" +
        """"score":{"value":0.9,"statistic":"expected_level_index"},"confidence":0.6,"provenance":$jevFullJson}"""

    private fun urgentAnswer(outcome: String = urgentOutcome) =
        """{"name":"is_urgent","kind":"proposition","definitionId":"${urgent.definitionId}","outcome":$outcome}"""

    private fun departmentAnswer(outcome: String = departmentOutcome) =
        """{"name":"department","kind":"choice","definitionId":"${department.definitionId}",$optionsJson,"outcome":$outcome}"""

    private fun frustrationAnswer(outcome: String = angerOutcome) =
        """{"name":"frustration","kind":"rating","definitionId":"${frustration.definitionId}",$levelsJson,"outcome":$outcome}"""

    private fun response(vararg answers: String, extra: String = ""): String =
        """{"definitionId":"${triage.definitionId}","executionMode":"native",$extra"answers":[""" +
            answers.joinToString(",") + "]}"

    private val answeredJson = response(
        urgentAnswer(),
        departmentAnswer(),
        frustrationAnswer(),
    )

    private fun assertRejects(
        json: String,
        type: Class<*>,
        vararg fragments: String,
        using: JsonMapper = mapper,
    ): DatabindException {
        val error = assertThrows(DatabindException::class.java) { using.readValue(json, type) }
        fragments.forEach { fragment ->
            assertTrue(error.message!!.contains(fragment)) { "Expected '$fragment' in: ${error.message}" }
        }
        return error
    }

    @Nested
    inner class RoundTrip {

        @Test
        fun `an answered response writes the golden JSON and reads back equal through both mappers`() {
            for (m in listOf(mapper, discovered)) {
                assertEquals(answeredJson, m.writeValueAsString(answered()))
                assertEquals(answered(), m.readValue(answeredJson, DecisionResponse::class.java))
            }
        }

        @Test
        fun `every outcome variant writes the golden JSON and reads back equal`() {
            val p1 = Questions.named("p_answered").proposition("Is it?").build()
            val p2 = Questions.named("p_inconclusive").proposition("Is it?").build()
            val p3 = Questions.named("p_failure").proposition("Is it?").build()
            fun choice(name: String) = Questions.named(name).choice("Which?").option("a", "A").option("b", "B").build()
            val c1 = choice("c_selected")
            val c2 = choice("c_no_match")
            val c3 = choice("c_inconclusive")
            val c4 = choice("c_failure")
            fun rating(name: String) = Questions.named(name).rating("How much?").level("low").level("high").build()
            val r1 = rating("r_answered")
            val r2 = rating("r_inconclusive")
            val r3 = rating("r_failure")
            val spec = DecisionSpec.of(p1, p2, p3, c1, c2, c3, c4, r1, r2, r3)
            val response = DecisionResponse.builder(spec, ExecutionMode.SEQUENTIAL)
                .answer(p1, PropositionResult.Answered(false, jevFull))
                .answer(p2, PropositionResult.Inconclusive(jev))
                .answer(p3, PropositionResult.Failure(FailureReason.INVALID_RESPONSE))
                .answer(c1, ClassificationResult.Selected("b", jev))
                .answer(c2, ClassificationResult.NoMatch(jev))
                .answer(c3, ClassificationResult.Inconclusive(jev))
                .answer(c4, ClassificationResult.Failure(FailureReason.UNAVAILABLE))
                .answer(r1, RatingResult.Answered(jev, score = RatingScore(1.0, RatingStatistic.EXPECTED_LEVEL_INDEX)))
                .answer(r2, RatingResult.Inconclusive(jev))
                .answer(r3, RatingResult.Failure(FailureReason.INVALID_RESPONSE))
                .build()

            fun p(q: PropositionQuestionSpec, outcome: String) =
                """{"name":"${q.name}","kind":"proposition","definitionId":"${q.definitionId}","outcome":$outcome}"""
            val ab = """"options":[{"id":"a","description":"A"},{"id":"b","description":"B"}]"""
            fun c(q: ChoiceQuestionSpec, outcome: String) =
                """{"name":"${q.name}","kind":"choice","definitionId":"${q.definitionId}",$ab,"outcome":$outcome}"""
            val lh = """"levels":[{"id":"low","description":"low"},{"id":"high","description":"high"}]"""
            fun r(q: RatingQuestionSpec, outcome: String) =
                """{"name":"${q.name}","kind":"rating","definitionId":"${q.definitionId}",$lh,"outcome":$outcome}"""
            val expected = """{"definitionId":"${spec.definitionId}","executionMode":"sequential","answers":[""" + listOf(
                p(p1, """{"status":"answered","answer":false,"provenance":$jevFullJson}"""),
                p(p2, """{"status":"inconclusive","provenance":$jevJson}"""),
                p(p3, """{"status":"failure","reason":"invalid_response"}"""),
                c(c1, """{"status":"selected","categoryId":"b","provenance":$jevJson}"""),
                c(c2, """{"status":"no_match","provenance":$jevJson}"""),
                c(c3, """{"status":"inconclusive","provenance":$jevJson}"""),
                c(c4, """{"status":"failure","reason":"unavailable"}"""),
                r(r1, """{"status":"answered","score":{"value":1.0,"statistic":"expected_level_index"},"provenance":$jevJson}"""),
                r(r2, """{"status":"inconclusive","provenance":$jevJson}"""),
                r(r3, """{"status":"failure","reason":"invalid_response"}"""),
            ).joinToString(",") + "]}"

            assertEquals(expected, mapper.writeValueAsString(response))
            assertEquals(response, mapper.readValue(expected, DecisionResponse::class.java))
            assertEquals(response, discovered.readValue(expected, DecisionResponse::class.java))
        }

        @Test
        fun `a failed response writes requestFailure and reads back equal`() {
            val failed = DecisionResponse.failed(triage, ExecutionMode.SINGLE_QUESTION, FailureReason.UNAVAILABLE)
            val failure = """{"status":"failure","reason":"unavailable"}"""
            val json = """{"definitionId":"${triage.definitionId}","executionMode":"single_question",""" +
                """"requestFailure":"unavailable","answers":[${urgentAnswer(failure)},""" +
                """${departmentAnswer(failure)},${frustrationAnswer(failure)}]}"""
            assertEquals(json, mapper.writeValueAsString(failed))
            assertEquals(failed, mapper.readValue(json, DecisionResponse::class.java))
        }

        @Test
        fun `the JSON holds no input, no instructions and no class names`() {
            val json = mapper.writeValueAsString(answered())
            assertThat(json).doesNotContain(
                "input", "instructions", "Does this convey urgency?", "com.embabel", "Answered", "Selected",
                "DecisionAnswer", "PropositionResult", "ClassificationResult", "RatingResult", "ModelProvenance", "@class",
            )
        }

        @Test
        fun `a reader that never held the questions reads the response and rebuilt questions look up typed outcomes`() {
            val fresh = JsonMapper.builder().findAndAddModules().build()
            val read = fresh.readValue(answeredJson, DecisionResponse::class.java)
            val rebuiltDepartment = Questions.named("department")
                .choice("Which team should handle this?")
                .option("billing", "Payments, invoicing, refunds")
                .option("technical", "Bugs, outages, integrations")
                .option("sales", "Pricing, upgrades, new accounts")
                .build()
            val rebuiltUrgent = Questions.named("is_urgent").proposition("Does this convey urgency?").build()
            val rebuiltFrustration = Questions.named("frustration")
                .rating("How frustrated is the customer?").level("Calm").level("Frustrated").level("Very angry").build()

            val team: ClassificationResult = read.answer(rebuiltDepartment)
            assertEquals("billing", assertInstanceOf(ClassificationResult.Selected::class.java, team).categoryId)
            val urgency: PropositionResult = read.answer(rebuiltUrgent)
            assertEquals(PropositionResult.Answered(true, jev, 0.93), urgency)
            val rating: RatingResult = read.answer(rebuiltFrustration)
            assertEquals(anger, rating)
            val choice = assertInstanceOf(DecisionAnswer.Choice::class.java, read.answer("department"))
            assertEquals(department.options, choice.options)
        }

        @Test
        fun `an answer written alone carries its name and reads back through DecisionAnswer and its own class`() {
            val choice = answered().answer("department")
            // The same object appears as an element of a response's answers array.
            val json = departmentAnswer()
            assertEquals(json, mapper.writeValueAsString(choice))
            assertEquals(choice, mapper.readValue(json, DecisionAnswer::class.java))
            assertEquals(choice, mapper.readValue(json, DecisionAnswer.Choice::class.java))

            val proposition = answered().answer("is_urgent")
            val propositionJson = mapper.writeValueAsString(proposition)
            assertEquals(proposition, mapper.readValue(propositionJson, DecisionAnswer.Proposition::class.java))
            val rating = answered().answer("frustration")
            assertEquals(rating, mapper.readValue(mapper.writeValueAsString(rating), DecisionAnswer.Rating::class.java))
        }

        @Test
        fun `a rating result writes the golden JSON and reads back through RatingResult and its own class`() {
            assertEquals(angerOutcome, mapper.writeValueAsString(anger))
            assertEquals(anger, mapper.readValue(angerOutcome, RatingResult::class.java))
            assertEquals(anger, mapper.readValue(angerOutcome, RatingResult.Answered::class.java))
            val inconclusive = RatingResult.Inconclusive(jev)
            val json = """{"status":"inconclusive","provenance":$jevJson}"""
            assertEquals(json, mapper.writeValueAsString(inconclusive))
            assertEquals(inconclusive, mapper.readValue(json, RatingResult.Inconclusive::class.java))
            val failure = RatingResult.Failure(FailureReason.UNAVAILABLE)
            assertEquals(failure, mapper.readValue(mapper.writeValueAsString(failure), RatingResult.Failure::class.java))
        }

        @Test
        fun `members may come in any order, including the outcome before the kind`() {
            val reordered = """{"answers":[{"outcome":{"provenance":$jevJson,"pTrue":0.93,"answer":true,""" +
                """"status":"answered"},"definitionId":"${urgent.definitionId}","kind":"proposition","name":"is_urgent"},""" +
                """{"outcome":$departmentOutcome,$optionsJson,"name":"department","kind":"choice",""" +
                """"definitionId":"${department.definitionId}"},${frustrationAnswer()}],""" +
                """"executionMode":"native","definitionId":"${triage.definitionId}"}"""
            assertEquals(answered(), mapper.readValue(reordered, DecisionResponse::class.java))
        }

        @Test
        fun `explicit nulls for optional members read as absent`() {
            val outcome = """{"status":"answered","answer":true,"pTrue":null,""" +
                """"provenance":{"modelName":"jev-latest","provider":"typesafe","version":null,"requestId":null}}"""
            val read = mapper.readValue(response(
                urgentAnswer(outcome),
                departmentAnswer(),
                frustrationAnswer(),
                extra = """"requestFailure":null,""",
            ), DecisionResponse::class.java)
            assertEquals(PropositionResult.Answered(true, jev), read.answer(urgent))
        }

        @Test
        fun `the mapper's naming strategy changes neither the written nor the accepted names`() {
            val renaming = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build()
            assertEquals(answeredJson, renaming.writeValueAsString(answered()))
            assertEquals(answered(), renaming.readValue(answeredJson, DecisionResponse::class.java))
        }

        @Test
        fun `a tree keeps answer order and reads back equal`() {
            val tree = mapper.readTree(answeredJson)
            assertEquals(answered(), mapper.treeToValue(tree, DecisionResponse::class.java))
        }
    }

    @Nested
    inner class Rejections {

        @Test
        fun `a duplicate answer name is rejected`() {
            val json = response(
                urgentAnswer(),
                urgentAnswer(),
                departmentAnswer(),
                frustrationAnswer(),
            )
            assertRejects(
                json, DecisionResponse::class.java,
                "Answer names must be unique within a decision response. Repeated: 'is_urgent'",
            )
        }

        @Test
        fun `a dropped answer is rejected by the spec id check`() {
            val json = response(urgentAnswer(), departmentAnswer())
            assertRejects(
                json, DecisionResponse::class.java,
                "The answers do not match the response's spec", "Expected spec id '${triage.definitionId}'",
            )
        }

        @Test
        fun `an extra answer is rejected by the spec id check`() {
            val extra = Questions.named("tone").proposition("Is it polite?").build()
            val json = response(
                urgentAnswer(),
                departmentAnswer(),
                frustrationAnswer(),
                """{"name":"tone","kind":"proposition","definitionId":"${extra.definitionId}","outcome":$urgentOutcome}""",
            )
            assertRejects(json, DecisionResponse::class.java, "The answers do not match the response's spec")
        }

        @Test
        fun `swapped answers are rejected by the spec id check`() {
            val json = response(
                departmentAnswer(),
                urgentAnswer(),
                frustrationAnswer(),
            )
            val error = assertRejects(json, DecisionResponse::class.java, "The answers do not match the response's spec")
            assertInstanceOf(IllegalArgumentException::class.java, error.cause)
        }

        @Test
        fun `an unknown status is rejected`() {
            val json = response(
                urgentAnswer("""{"status":"yes","answer":true,"provenance":$jevJson}"""),
                departmentAnswer(),
                frustrationAnswer(),
            )
            assertRejects(
                json, DecisionResponse::class.java,
                "'yes'",
            )
        }

        @Test
        fun `a status that belongs to another kind is rejected`() {
            val json = response(
                urgentAnswer("""{"status":"no_match","provenance":$jevJson}"""),
                departmentAnswer(),
                frustrationAnswer(),
            )
            assertRejects(json, DecisionResponse::class.java, "'no_match'")
        }

        @Test
        fun `a selection outside the embedded options is rejected`() {
            val json = response(
                urgentAnswer(),
                departmentAnswer("""{"status":"selected","categoryId":"legal","provenance":$jevJson}"""),
                frustrationAnswer(),
            )
            assertRejects(json, DecisionResponse::class.java, "Question 'department': the selected category id is not one of its options")
        }

        @Test
        fun `a rating distribution missing a level is rejected`() {
            val outcome = """{"status":"answered","distribution":[{"levelId":"Calm","probability":0.3},""" +
                """{"levelId":"Frustrated","probability":0.7}],"provenance":$jevJson}"""
            val json = response(
                urgentAnswer(),
                departmentAnswer(),
                frustrationAnswer(outcome),
            )
            assertRejects(json, DecisionResponse::class.java, "Question 'frustration': the distribution must cover exactly its levels")
        }

        @Test
        fun `a rating score above the top level is rejected`() {
            val outcome = """{"status":"answered","score":{"value":2.5,"statistic":"expected_level_index"},"provenance":$jevJson}"""
            val json = response(
                urgentAnswer(),
                departmentAnswer(),
                frustrationAnswer(outcome),
            )
            assertRejects(json, DecisionResponse::class.java, "Question 'frustration': the score 2.5 is above the last level index 2")
        }

        @Test
        fun `a failure without a reason is rejected`() {
            val json = response(
                urgentAnswer("""{"status":"failure"}"""),
                departmentAnswer(),
                frustrationAnswer(),
            )
            assertRejects(json, DecisionResponse::class.java, "'reason'")
        }

        @Test
        fun `an unknown failure reason is rejected`() {
            val json = response(
                urgentAnswer(),
                departmentAnswer("""{"status":"failure","reason":"timeout"}"""),
                frustrationAnswer(),
            )
            assertRejects(
                json, DecisionResponse::class.java,
                "\"timeout\"",
            )
        }

        @Test
        fun `a member of another status is rejected`() {
            val json = response(
                urgentAnswer("""{"status":"answered","answer":true,"reason":"unavailable","provenance":$jevJson}"""),
                departmentAnswer(),
                frustrationAnswer(),
            )
            assertRejects(json, DecisionResponse::class.java, "Unknown member 'reason' in PropositionResult")
        }

        @Test
        fun `requestFailure alongside a non-failure outcome is rejected`() {
            val json = response(
                urgentAnswer(),
                departmentAnswer("""{"status":"failure","reason":"unavailable"}"""),
                frustrationAnswer("""{"status":"failure","reason":"unavailable"}"""),
                extra = """"requestFailure":"unavailable",""",
            )
            assertRejects(
                json, DecisionResponse::class.java,
                "can only hold failure outcomes with that reason", "'is_urgent' (not a failure)",
            )
        }

        @Test
        fun `an unknown member in an outcome is rejected`() {
            val json = response(
                urgentAnswer(),
                departmentAnswer("""{"status":"no_match","why":"none fit","provenance":$jevJson}"""),
                frustrationAnswer(),
            )
            assertRejects(json, DecisionResponse::class.java, "Unknown member 'why' in ClassificationResult")
        }

        @Test
        fun `unknown members are rejected on a plain mapper and on one that ignores unknown properties`() {
            val lenient = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()
            val withInstructions = """{"name":"is_urgent","kind":"proposition","instructions":"Is it?",""" +
                """"definitionId":"${urgent.definitionId}","outcome":$urgentOutcome}"""
            val withLevels = """{"name":"is_urgent","kind":"proposition","definitionId":"${urgent.definitionId}",$levelsJson,"outcome":$urgentOutcome}"""
            val provenance = """{"status":"inconclusive","provenance":{"modelName":"a","provider":"p","region":"eu"}}"""
            val distribution = """{"status":"answered","distribution":[{"levelId":"Calm","probability":1.0,"rank":0},""" +
                """{"levelId":"Frustrated","probability":0.0},{"levelId":"Very angry","probability":0.0}],"provenance":$jevJson}"""
            for (m in listOf(mapper, lenient)) {
                assertRejects(
                    response(urgentAnswer(), departmentAnswer(), frustrationAnswer(), extra = """"spec":{},"""),
                    DecisionResponse::class.java,
                    "Unknown member 'spec' in DecisionResponse",
                    using = m,
                )
                assertRejects(
                    response(withInstructions, departmentAnswer(), frustrationAnswer()),
                    DecisionResponse::class.java,
                    "Unknown member 'instructions' in DecisionAnswer",
                    using = m,
                )
                assertRejects(
                    response(withLevels, departmentAnswer(), frustrationAnswer()),
                    DecisionResponse::class.java,
                    "Unknown member 'levels' in DecisionAnswer",
                    using = m,
                )
                assertRejects(provenance, RatingResult::class.java, "Unknown member 'region' in ModelProvenance", using = m)
                assertRejects(
                    response(urgentAnswer(), departmentAnswer(), frustrationAnswer(distribution)),
                    DecisionResponse::class.java,
                    "Unknown member 'rank' in LevelProbability",
                    using = m,
                )
            }
        }

        @Test
        fun `a missing required member is rejected`() {
            assertRejects(
                """{"definitionId":"${triage.definitionId}","answers":[]}""",
                DecisionResponse::class.java,
                "'executionMode'",
            )
            val noOptions = """{"name":"department","kind":"choice","definitionId":"${department.definitionId}","outcome":$departmentOutcome}"""
            assertRejects(
                response(urgentAnswer(), noOptions, frustrationAnswer()),
                DecisionResponse::class.java,
                "'options'",
            )
            assertRejects(
                """{"status":"inconclusive"}""",
                RatingResult::class.java,
                "'provenance'",
            )
            assertRejects(
                """{"kind":"proposition","definitionId":"${urgent.definitionId}","outcome":$urgentOutcome}""",
                DecisionAnswer::class.java,
                "'name'",
            )
        }

        @Test
        fun `an unknown kind or execution mode is rejected`() {
            val poll = """{"name":"is_urgent","kind":"poll","definitionId":"${urgent.definitionId}","outcome":$urgentOutcome}"""
            assertRejects(
                response(poll, departmentAnswer(), frustrationAnswer()),
                DecisionResponse::class.java,
                "'poll'",
            )
            assertRejects(
                answeredJson.replace("\"executionMode\":\"native\"", "\"executionMode\":\"NATIVE\""),
                DecisionResponse::class.java,
                "\"NATIVE\"",
            )
        }

        @Test
        fun `reading an answer as the wrong class is rejected`() {
            val json = mapper.writeValueAsString(answered().answer("department"))
            assertRejects(json, DecisionAnswer.Rating::class.java, "'choice'")
            val inconclusive = """{"status":"inconclusive","provenance":$jevJson}"""
            assertRejects(inconclusive, RatingResult.Answered::class.java, "'inconclusive'")
        }

        @Test
        fun `a value the result constructors refuse is rejected with the constructor message as the cause`() {
            val json = response(
                urgentAnswer("""{"status":"answered","answer":true,"pTrue":1.5,"provenance":$jevJson}"""),
                departmentAnswer(),
                frustrationAnswer(),
            )
            val error = assertRejects(json, DecisionResponse::class.java, "Probability of truth must be finite and between 0 and 1")
            assertInstanceOf(IllegalArgumentException::class.java, error.cause)
            assertRejects(
                """{"status":"answered","provenance":$jevJson}""",
                RatingResult::class.java,
                "Answered rating must report a selected level, a distribution or a score",
            )
            assertRejects(
                """{"status":"inconclusive","provenance":{"modelName":" ","provider":"p"}}""",
                RatingResult::class.java,
                "Model name must not be blank",
            )
        }

        @Test
        fun `an explicit null in a required boolean or number is rejected`() {
            assertRejects(
                response(urgentAnswer("""{"status":"answered","answer":null,"provenance":$jevJson}"""), departmentAnswer(), frustrationAnswer()),
                DecisionResponse::class.java,
                "Cannot map `null`",
            )
            assertRejects(
                """{"status":"answered","score":{"value":null,"statistic":"expected_level_index"},"provenance":$jevJson}""",
                RatingResult::class.java,
                "Cannot map `null`",
            )
            assertRejects(
                """{"status":"answered","distribution":[{"levelId":"a","probability":null},{"levelId":"b","probability":1.0}],"provenance":$jevJson}""",
                RatingResult::class.java,
                "Cannot map `null`",
            )
        }

        @Test
        fun `a malformed answer definition id is rejected`() {
            val json = response(
                urgentAnswer().replace(urgent.definitionId, "d1-short"),
                departmentAnswer(),
                frustrationAnswer(),
            )
            assertRejects(json, DecisionResponse::class.java, "definition id 'd1-short' is not a question id")
        }

        @Test
        fun `a response must be an object`() {
            assertRejects("[]", DecisionResponse::class.java, "from Array value")
        }

        @Test
        fun `a tree with swapped answers is rejected`() {
            val swapped = response(
                departmentAnswer(),
                urgentAnswer(),
                frustrationAnswer(),
            )
            val error = assertThrows(DatabindException::class.java) {
                mapper.treeToValue(mapper.readTree(swapped), DecisionResponse::class.java)
            }
            assertTrue(error.message!!.contains("The answers do not match the response's spec"))
        }
    }

    @Nested
    inner class AnswersArray {

        @Test
        fun `answers are written as an array in spec order with the name first in each element`() {
            val tree = mapper.readTree(mapper.writeValueAsString(answered()))
            val answers = tree.get("answers")
            assertTrue(answers.isArray)
            // JsonNode has its own map, which maps the node itself, so go through the elements explicitly.
            val elements = answers.iterator().asSequence().toList()
            assertEquals(listOf("is_urgent", "department", "frustration"), elements.map { it.get("name").asString() })
            elements.forEach { assertEquals("name", it.propertyNames().first()) }
        }

        @Test
        fun `answers written as an object keyed by name are rejected, even when single values may read as arrays`() {
            val keyed = """{"definitionId":"${triage.definitionId}","executionMode":"native","answers":{""" +
                """"is_urgent":{"kind":"proposition","definitionId":"${urgent.definitionId}","outcome":$urgentOutcome}}}"""
            val singleAsArray = JsonMapper.builder().enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY).build()
            for (m in listOf(mapper, singleAsArray)) {
                assertRejects(keyed, DecisionResponse::class.java, "from Object value", using = m)
            }
        }

        @Test
        fun `an element without a name is rejected`() {
            val unnamed = """{"kind":"proposition","definitionId":"${urgent.definitionId}","outcome":$urgentOutcome}"""
            assertRejects(
                response(unnamed, departmentAnswer(), frustrationAnswer()),
                DecisionResponse::class.java,
                "'name'",
            )
        }

        @Test
        fun `an element that is not an object is rejected`() {
            assertRejects(
                response("\"is_urgent\"", departmentAnswer(), frustrationAnswer()),
                DecisionResponse::class.java,
                "DecisionAnswer",
            )
        }

        @Test
        fun `a repeated answer name is rejected`() {
            assertRejects(
                response(urgentAnswer(), departmentAnswer(), departmentAnswer(), frustrationAnswer()),
                DecisionResponse::class.java,
                "Answer names must be unique within a decision response. Repeated: 'department'",
            )
        }

        @Test
        fun `an array in another order fails the spec id check`() {
            val reordered = response(frustrationAnswer(), urgentAnswer(), departmentAnswer())
            assertRejects(
                reordered, DecisionResponse::class.java,
                "The answers do not match the response's spec", "Expected spec id '${triage.definitionId}'",
            )
        }
    }

    @Nested
    inner class TamperedDefinitions {

        // Each edit keeps every definition id, so the response still reads. Only the typed lookup
        // can tell that the embedded options or levels no longer match the question.
        private fun readTampered(vararg edits: Pair<String, String>): DecisionResponse {
            var json = answeredJson
            for ((from, to) in edits) {
                assertTrue(json.contains(from)) { "Expected '$from' in the written JSON" }
                json = json.replace(from, to)
            }
            return mapper.readValue(json, DecisionResponse::class.java)
        }

        @Test
        fun `a renamed option with a matching selection reads but fails the typed lookup`() {
            val read = readTampered(
                "\"id\":\"technical\"" to "\"id\":\"hacked\"",
                "\"categoryId\":\"billing\"" to "\"categoryId\":\"hacked\"",
            )
            val choice = assertInstanceOf(DecisionAnswer.Choice::class.java, read.answer("department"))
            assertEquals(department.definitionId, choice.definitionId)
            val error = assertThrows(IllegalArgumentException::class.java) { read.answer(department) }
            assertTrue(error.message!!.contains("'department'")) { error.message }
            assertTrue(error.message!!.contains("options differ from the question's")) { error.message }
        }

        @Test
        fun `a changed option description reads but fails the typed lookup`() {
            val read = readTampered("Bugs, outages, integrations" to "Anything at all")
            val error = assertThrows(IllegalArgumentException::class.java) { read.answer(department) }
            assertTrue(error.message!!.contains("options differ from the question's")) { error.message }
        }

        @Test
        fun `a renamed level with a matching selection and distribution reads but fails the typed lookup`() {
            val read = readTampered(
                "\"Very angry\"" to "\"Furious\"",
                "\"selectedLevelId\":\"Frustrated\"" to "\"selectedLevelId\":\"Furious\"",
            )
            val rating = assertInstanceOf(DecisionAnswer.Rating::class.java, read.answer("frustration"))
            assertEquals(frustration.definitionId, rating.definitionId)
            val error = assertThrows(IllegalArgumentException::class.java) { read.answer(frustration) }
            assertTrue(error.message!!.contains("'frustration'")) { error.message }
            assertTrue(error.message!!.contains("levels differ from the question's")) { error.message }
        }

        @Test
        fun `a changed level description reads but fails the typed lookup`() {
            val read = readTampered("{\"id\":\"Calm\",\"description\":\"Calm\"}" to "{\"id\":\"Calm\",\"description\":\"Relaxed\"}")
            val error = assertThrows(IllegalArgumentException::class.java) { read.answer(frustration) }
            assertTrue(error.message!!.contains("levels differ from the question's")) { error.message }
        }

        @Test
        fun `an untouched response still passes the typed lookup for every question`() {
            val read = readTampered()
            assertEquals(ClassificationResult.Selected("billing", jev, 0.91), read.answer(department))
            assertEquals(anger, read.answer(frustration))
        }
    }
}
