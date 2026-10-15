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
import com.embabel.common.ai.decision.DecisionCapabilities
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.LevelProbability
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.RatingResult
import com.embabel.common.ai.decision.RatingScore
import com.embabel.common.ai.decision.RatingStatistic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/**
 * Pins the exact JSON bytes of the decision types. The files under `decision/golden` hold the
 * compact JSON the writer produces for a spec with every question kind, a request, options,
 * capabilities, a response with every outcome kind, and a response with a request failure.
 * Each value must write those bytes and read back equal, through a plain mapper and through a
 * mapper with the Kotlin module.
 */
class DecisionJsonGoldenTest {

    private val mappers: List<JsonMapper> = listOf(
        JsonMapper.builder().build(),
        JsonMapper.builder().findAndAddModules().build(),
    )

    private val jev = ModelProvenance("jev-latest", "typesafe")
    private val jevFull = ModelProvenance("jev-latest", "typesafe", "2026-09", "req-42")

    private val urgent = Questions.named("is_urgent").proposition("Does this convey urgency?").build()
    private val department = Questions.named("department")
        .choice("Which team should handle this?")
        .option("billing", "Payments, invoicing, refunds")
        .option("technical", "Bugs, outages, integrations")
        .build()
    private val frustration = Questions.named("frustration")
        .rating("How frustrated is the customer?")
        .level("calm", "Calm and patient")
        .level("frustrated", "Frustrated")
        .level("angry", "Very angry")
        .build()

    private val triage = DecisionSpec.of(urgent, department, frustration)

    private fun proposition(name: String) = Questions.named(name).proposition("Is it $name?").build()

    private fun choice(name: String) = Questions.named(name).choice("Which $name?").option("a", "A").option("b", "B").build()

    private fun rating(name: String) = Questions.named(name).rating("How $name?").level("low").level("mid").level("high").build()

    private val pAnswered = proposition("p_answered")
    private val pAnsweredBare = proposition("p_answered_bare")
    private val pInconclusive = proposition("p_inconclusive")
    private val pFailure = proposition("p_failure")
    private val cSelected = choice("c_selected")
    private val cSelectedBare = choice("c_selected_bare")
    private val cNoMatch = choice("c_no_match")
    private val cInconclusive = choice("c_inconclusive")
    private val cFailure = choice("c_failure")
    private val rFull = rating("r_full")
    private val rSelected = rating("r_selected")
    private val rDistribution = rating("r_distribution")
    private val rScore = rating("r_score")
    private val rInconclusive = rating("r_inconclusive")
    private val rFailure = rating("r_failure")

    private val everyOutcome = DecisionSpec.of(
        pAnswered, pAnsweredBare, pInconclusive, pFailure,
        cSelected, cSelectedBare, cNoMatch, cInconclusive, cFailure,
        rFull, rSelected, rDistribution, rScore, rInconclusive, rFailure,
    )

    private val distribution = listOf(
        LevelProbability("low", 0.25),
        LevelProbability("mid", 0.5),
        LevelProbability("high", 0.25),
    )

    private val response: DecisionResponse = DecisionResponse.builder(everyOutcome)
        .answer(pAnswered, PropositionResult.Answered(true, jev, 0.93))
        .answer(pAnsweredBare, PropositionResult.Answered(false, jevFull))
        .answer(pInconclusive, PropositionResult.Inconclusive(jev))
        .answer(pFailure, PropositionResult.Failure(FailureReason.INVALID_RESPONSE))
        .answer(cSelected, ClassificationResult.Selected("b", jevFull, 0.91))
        .answer(cSelectedBare, ClassificationResult.Selected("a", jev))
        .answer(cNoMatch, ClassificationResult.NoMatch(jev))
        .answer(cInconclusive, ClassificationResult.Inconclusive(jev))
        .answer(cFailure, ClassificationResult.Failure(FailureReason.UNAVAILABLE))
        .answer(
            rFull,
            RatingResult.Answered(
                jevFull,
                selectedLevelId = "mid",
                distribution = distribution,
                score = RatingScore(1.0, RatingStatistic.EXPECTED_LEVEL_INDEX),
                confidence = 0.6,
            ),
        )
        .answer(rSelected, RatingResult.Answered(jev, selectedLevelId = "high"))
        .answer(rDistribution, RatingResult.Answered(jev, distribution = distribution))
        .answer(rScore, RatingResult.Answered(jev, score = RatingScore(1.75, RatingStatistic.EXPECTED_LEVEL_INDEX)))
        .answer(rInconclusive, RatingResult.Inconclusive(jev))
        .answer(rFailure, RatingResult.Failure(FailureReason.INVALID_RESPONSE))
        .build()

    private val failed = DecisionResponse.failed(triage, FailureReason.UNAVAILABLE)

    private val request = DecisionRequest.of("My invoice is wrong and nobody answers.", triage)

    private val capabilities = DecisionCapabilities.of(QuestionKind.entries.toSet())

    private fun golden(name: String): String {
        val resource = requireNotNull(javaClass.getResourceAsStream("/decision/golden/$name.json")) { "No golden file $name" }
        return resource.use { String(it.readAllBytes(), Charsets.UTF_8) }
    }

    private fun <T : Any> assertGolden(name: String, value: T, type: Class<T>) {
        val expected = golden(name)
        for (mapper in mappers) {
            assertEquals(expected, mapper.writeValueAsString(value)) { "Written bytes differ from $name.json" }
            assertEquals(value, mapper.readValue(expected, type)) { "$name.json does not read back equal" }
        }
    }

    @Test
    fun `a spec with every question kind`() = assertGolden("spec", triage, DecisionSpec::class.java)

    @Test
    fun `a request`() = assertGolden("request", request, DecisionRequest::class.java)

    @Test
    fun `capabilities with both limits`() = assertGolden("capabilities", capabilities, DecisionCapabilities::class.java)

    @Test
    fun `a response with every outcome kind`() = assertGolden("response", response, DecisionResponse::class.java)

    @Test
    fun `a response with a request failure`() = assertGolden("failed-response", failed, DecisionResponse::class.java)
}
