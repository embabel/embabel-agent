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
import com.embabel.agent.test.unit.FakeOperationContext
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.DecisionAnswer
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.RatingResult
import com.embabel.common.ai.decision.decisionSpec
import com.embabel.common.ai.decision.support.StubDecisionService
import com.embabel.common.ai.model.DecisionServiceRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A revision example shaped like a proposition store: it classifies a new proposition against
 * scoped candidates, stores the proposals and rechecks candidate versions before applying one.
 */
class DiceRevisionKotlinExampleTest {

    private val model = ModelProvenance("revision-model", "stub")

    private val newProposition = "prop-new" to "The Riverside branch opens at 9am on Saturdays."

    private val hours = Candidate("c-hours", version = 3, text = "The Riverside branch opens at 8am on Saturdays.")
    private val parking = Candidate("c-parking", version = 5, text = "Riverside has free parking.")
    private val address = Candidate("c-address", version = 1, text = "Riverside is on Mill Street.", pinned = true)

    // tag::revision[]
    data class Candidate(val id: String, val version: Long, val text: String, val pinned: Boolean = false)

    data class Proposal(
        val propositionId: String,
        val candidateId: String,
        val candidateVersion: Long,
        val relationId: String,
        val evidenceStrength: String?,
        val confidence: Double?,
        val model: ModelProvenance,
    )

    sealed interface Classification {
        data class Proposed(val proposals: List<Proposal>) : Classification
        data class NeedsReview(val reasons: List<String>) : Classification

        // Every candidate was answered as unrelated.
        data object New : Classification
    }

    sealed interface Application {
        data class Applied(val proposal: Proposal) : Application
        data class Invalidated(val proposal: Proposal, val reason: String) : Application
        data class Rejected(val proposal: Proposal, val reason: String) : Application
    }

    fun relationQuestion(candidate: Candidate) = "relation-${candidate.id}"

    fun revisionSpec(candidates: List<Candidate>): DecisionSpec = decisionSpec {
        candidates.forEach { candidate ->
            choice(relationQuestion(candidate)) {
                asking("How does the new proposition relate to candidate ${candidate.id}?")
                option("duplicate", "States the same fact")
                option("reinforces", "Adds support for the same fact")
                option("contradicts", "Cannot be true at the same time")
                option("unrelated", "Concerns a different fact")
            }
        }
        rating("evidence-strength") {
            asking("How strong is the evidence in the new proposition?")
            level("weak")
            level("moderate")
            level("strong")
        }
    }

    fun render(proposition: String, candidates: List<Candidate>): String =
        "New proposition: $proposition\n" + candidates.joinToString("\n") { "Candidate ${it.id}: ${it.text}" }

    val proposalStore = mutableListOf<Proposal>()
    val appliedRevisions = mutableListOf<Proposal>()

    fun classify(propositionId: String, candidates: List<Candidate>, response: DecisionResponse): Classification {
        val strength = response.answers
            .filterIsInstance<DecisionAnswer.Rating>()
            .firstOrNull { it.name == "evidence-strength" }
            ?.let { (it.outcome as? RatingResult.Answered)?.selectedLevelId }
        val proposals = mutableListOf<Proposal>()
        val reasons = mutableListOf<String>()
        for (candidate in candidates) {
            val answer = response.answers
                .filterIsInstance<DecisionAnswer.Choice>()
                .firstOrNull { it.name == relationQuestion(candidate) }
            when (val outcome = answer?.outcome) {
                null -> reasons += "${candidate.id}: no answer"
                is ClassificationResult.Selected -> if (outcome.categoryId != "unrelated") {
                    proposals += Proposal(
                        propositionId, candidate.id, candidate.version, outcome.categoryId,
                        strength, outcome.confidence, outcome.provenance,
                    )
                }
                is ClassificationResult.NoMatch -> reasons += "${candidate.id}: no relation fits"
                is ClassificationResult.Inconclusive -> reasons += "${candidate.id}: inconclusive"
                is ClassificationResult.Failure -> reasons += "${candidate.id}: failed with ${outcome.reason}"
            }
        }
        return when {
            reasons.isNotEmpty() -> Classification.NeedsReview(reasons)
            proposals.isEmpty() -> Classification.New
            else -> Classification.Proposed(proposals).also { proposalStore += proposals }
        }
    }

    fun classifyNew(ai: Ai, propositionId: String, text: String, candidates: List<Candidate>): Classification {
        val response = ai.decisions().byRole("dice-revision").ask(render(text, candidates), revisionSpec(candidates))
        return classify(propositionId, candidates, response)
    }

    // Runs in the application's transaction against the current candidate state.
    fun apply(proposal: Proposal, current: Map<String, Candidate>): Application {
        val candidate = current[proposal.candidateId]
            ?: return Application.Invalidated(proposal, "candidate is no longer in scope")
        if (candidate.version != proposal.candidateVersion) {
            return Application.Invalidated(
                proposal,
                "candidate changed from version ${proposal.candidateVersion} to ${candidate.version}",
            )
        }
        if (proposal.relationId == "contradicts" && candidate.pinned) {
            return Application.Rejected(proposal, "a pinned candidate is not demoted")
        }
        if (proposal.relationId == "contradicts" && proposal.evidenceStrength != "strong") {
            return Application.Rejected(proposal, "demotion needs strong evidence")
        }
        appliedRevisions += proposal
        return Application.Applied(proposal)
    }
    // end::revision[]

    private fun aiWith(stub: StubDecisionService): Ai = FakeOperationContext.withDecisionServices(
        DecisionServiceRegistry.builder()
            .register("revision", stub)
            .decisionRole("dice-revision", "revision")
            .build(),
    ).ai()

    private fun stub(vararg relations: Pair<Candidate, ClassificationResult>, strength: RatingResult) =
        StubDecisionService.builder("revision")
            .apply { relations.forEach { (candidate, outcome) -> choice(relationQuestion(candidate), outcome) } }
            .rating("evidence-strength", strength)
            .build()

    private val strong = RatingResult.Answered(model, selectedLevelId = "strong")

    @Test
    fun `selected relations become stored proposals with every field`() {
        val stub = stub(
            hours to ClassificationResult.Selected("contradicts", model),
            parking to ClassificationResult.Selected("unrelated", model, 0.8),
            strength = strong,
        )

        val result = classifyNew(aiWith(stub), newProposition.first, newProposition.second, listOf(hours, parking))

        val expected = Proposal(
            propositionId = "prop-new",
            candidateId = "c-hours",
            candidateVersion = 3,
            relationId = "contradicts",
            evidenceStrength = "strong",
            confidence = null,
            model = model,
        )
        assertEquals(Classification.Proposed(listOf(expected)), result)
        assertNull(expected.confidence)
        assertEquals(listOf(expected), proposalStore)
        assertEquals(listOf<Proposal>(), appliedRevisions)

        assertEquals(Application.Applied(expected), apply(expected, mapOf(hours.id to hours)))
        assertEquals(listOf(expected), appliedRevisions)
    }

    @Test
    fun `a changed candidate version invalidates the proposal`() {
        val stub = stub(hours to ClassificationResult.Selected("reinforces", model, 0.99), strength = strong)
        val result = classifyNew(aiWith(stub), newProposition.first, newProposition.second, listOf(hours))
        val proposal = (result as Classification.Proposed).proposals.single()

        val edited = hours.copy(version = 4, text = "The Riverside branch opens at 9am on Saturdays and Sundays.")
        val application = apply(proposal, mapOf(hours.id to edited))

        assertEquals(Application.Invalidated(proposal, "candidate changed from version 3 to 4"), application)
        assertEquals(Application.Invalidated(proposal, "candidate is no longer in scope"), apply(proposal, emptyMap()))
        assertEquals(listOf<Proposal>(), appliedRevisions)
    }

    @Test
    fun `an inconclusive answer needs review and is not New`() {
        val stub = stub(
            hours to ClassificationResult.Inconclusive(model),
            parking to ClassificationResult.Selected("unrelated", model),
            strength = RatingResult.Inconclusive(model),
        )

        val result = classifyNew(aiWith(stub), newProposition.first, newProposition.second, listOf(hours, parking))

        assertEquals(Classification.NeedsReview(listOf("c-hours: inconclusive")), result)
        assertEquals(listOf<Proposal>(), proposalStore)
    }

    @Test
    fun `a candidate with no answer needs review and is not New`() {
        val stub = stub(hours to ClassificationResult.Selected("unrelated", model), strength = strong)
        val response = aiWith(stub).decisions().byRole("dice-revision")
            .ask(render(newProposition.second, listOf(hours)), revisionSpec(listOf(hours)))

        // The parking candidate entered scope after the request was asked.
        val result = classify(newProposition.first, listOf(hours, parking), response)

        assertEquals(Classification.NeedsReview(listOf("c-parking: no answer")), result)
    }

    @Test
    fun `only answers of unrelated for every candidate give New`() {
        val stub = stub(
            hours to ClassificationResult.Selected("unrelated", model),
            parking to ClassificationResult.Selected("unrelated", model),
            strength = strong,
        )

        val result = classifyNew(aiWith(stub), newProposition.first, newProposition.second, listOf(hours, parking))

        assertEquals(Classification.New, result)
        assertEquals(listOf<Proposal>(), proposalStore)
    }

    @Test
    fun `a high-confidence answer that fails a policy check is not applied`() {
        val stub = stub(address to ClassificationResult.Selected("contradicts", model, 0.99), strength = strong)
        val result = classifyNew(aiWith(stub), newProposition.first, "Riverside is on Station Road.", listOf(address))
        val proposal = (result as Classification.Proposed).proposals.single()
        assertEquals(0.99, proposal.confidence)

        val application = apply(proposal, mapOf(address.id to address))

        assertEquals(Application.Rejected(proposal, "a pinned candidate is not demoted"), application)
        assertEquals(listOf<Proposal>(), appliedRevisions)

        val weak = proposal.copy(candidateId = hours.id, candidateVersion = hours.version, evidenceStrength = "weak")
        val weakApplication = apply(weak, mapOf(hours.id to hours))
        assertTrue(weakApplication is Application.Rejected)
        assertEquals(listOf<Proposal>(), appliedRevisions)
    }
}
