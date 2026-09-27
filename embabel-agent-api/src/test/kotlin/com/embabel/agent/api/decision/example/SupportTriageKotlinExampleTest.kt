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
package com.embabel.agent.api.decision.example

import com.embabel.agent.api.common.Ai
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.DecisionAnswer
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.RatingResult
import com.embabel.common.ai.decision.decisionSpec
import com.embabel.common.ai.decision.support.StubDecisionService
import com.embabel.common.ai.model.DecisionServiceRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The support-triage example in Kotlin, with the spec built through the Kotlin DSL.
 */
class SupportTriageKotlinExampleTest {

    private val ticket = "The export button fails with an error and I have a board meeting in an hour."
    private val model = ModelProvenance("triage-model", "stub")

    private fun aiWith(stub: StubDecisionService): Ai = ExampleOperations.withRegistry(
        DecisionServiceRegistry.builder()
            .register("triage-stub", stub)
            .decisionRole("support-triage", "triage-stub")
            .build(),
    ).ai()

    // tag::dsl[]
    data class Route(val queue: String, val sameDay: Boolean, val frustrationConfidence: Double?)

    val triage = decisionSpec {
        proposition("urgent") { asking("Does the customer need help today?") }
        choice("department") {
            asking("Which team should handle the ticket?")
            option("billing", "Payments, invoices and refunds")
            option("technical", "Errors, outages and integrations")
        }
        rating("frustration") {
            asking("How frustrated is the customer?")
            level("calm")
            level("frustrated")
            level("very-angry")
        }
    }

    fun route(response: DecisionResponse): Route {
        val urgent = response.answer("urgent") as DecisionAnswer.Proposition
        val department = response.answer("department") as DecisionAnswer.Choice
        val frustration = response.answer("frustration") as DecisionAnswer.Rating
        val sameDay = when (val outcome = urgent.outcome) {
            is PropositionResult.Answered -> outcome.answer
            is PropositionResult.Inconclusive, is PropositionResult.Failure -> true
        }
        val queue = when (val outcome = department.outcome) {
            is ClassificationResult.Selected -> outcome.categoryId
            is ClassificationResult.NoMatch -> "general"
            is ClassificationResult.Inconclusive -> "human-review"
            is ClassificationResult.Failure -> "retry-later"
        }
        // Confidence is optional. It is shown to the agent and does not change the route.
        val confidence = (frustration.outcome as? RatingResult.Answered)?.confidence
        return Route(queue, sameDay, confidence)
    }

    fun triageTicket(ai: Ai, ticket: String): Route =
        route(ai.decisions().byRole("support-triage").ask(ticket, triage))
    // end::dsl[]

    @Test
    fun `a native answer routes to the selected team`() {
        val stub = StubDecisionService.builder("triage-stub")
            .proposition("urgent", PropositionResult.Answered(true, model))
            .choice("department", ClassificationResult.Selected("technical", model, 0.1))
            .rating("frustration", RatingResult.Answered(model, selectedLevelId = "frustrated"))
            .build()

        assertEquals(Route("technical", sameDay = true, frustrationConfidence = null), triageTicket(aiWith(stub), ticket))
        assertEquals(listOf("askNative"), stub.calls())
    }

    @Test
    fun `reported confidence is carried and does not change the route`() {
        val stub = StubDecisionService.builder("triage-stub")
            .proposition("urgent", PropositionResult.Answered(false, model))
            .choice("department", ClassificationResult.NoMatch(model))
            .rating("frustration", RatingResult.Answered(model, selectedLevelId = "calm", confidence = 0.95))
            .build()

        assertEquals(Route("general", sameDay = false, frustrationConfidence = 0.95), triageTicket(aiWith(stub), ticket))
    }

    @Test
    fun `inconclusive and failed answers route to people`() {
        val stub = StubDecisionService.builder("triage-stub")
            .proposition("urgent", PropositionResult.Inconclusive(model))
            .choice("department", ClassificationResult.Inconclusive(model))
            .rating("frustration", RatingResult.Inconclusive(model))
            .build()

        val route = triageTicket(aiWith(stub), ticket)
        assertEquals(Route("human-review", sameDay = true, frustrationConfidence = null), route)
        assertNull(route.frustrationConfidence)

        val failed = route(DecisionResponse.failed(triage, FailureReason.UNAVAILABLE))
        assertEquals(Route("retry-later", sameDay = true, frustrationConfidence = null), failed)
    }
}
