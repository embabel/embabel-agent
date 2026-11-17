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
package com.embabel.agent.openai.decision

import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.ChoiceQuestionSpec
import com.embabel.common.ai.decision.ConfidenceMeasure
import com.embabel.common.ai.decision.DecisionArity
import com.embabel.common.ai.decision.DecisionCall
import com.embabel.common.ai.decision.DecisionCapabilities
import com.embabel.common.ai.decision.DecisionEvidenceKind
import com.embabel.common.ai.decision.DecisionInput
import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.decision.DecisionServiceMetadata
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.DecisionUsage
import com.embabel.common.ai.decision.JsonDecisionInput
import com.embabel.common.ai.decision.PropositionQuestionSpec
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.TextDecisionInput
import com.embabel.common.ai.decision.UnsupportedDecisionException
import com.embabel.common.ai.decision.spi.DecisionExecutor
import com.embabel.common.ai.decision.spi.DecisionResponseAssembler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/**
 * The Kotlin examples on the decision providers reference page. Each tagged region is included in
 * the page as it stands, and the tests around it check that it does what the page says.
 */
class DecisionProviderExamplesTest {

    private val refund = Questions.named("refund").proposition("Does the customer ask for a refund?").build()

    private val route = Questions.named("route").choice("Which team should handle this ticket?")
        .option("billing", "Payment problems")
        .option("support", "Other problems")
        .build()

    private fun rulesService(): DecisionService {
        // tag::rules-provider[]
        val rules = DecisionExecutor { call ->
            val text = (call.input as TextDecisionInput).value
            val question = call.spec.questions.single()
            val provenance = ModelProvenance("refund-rules-v1", "in-process")
            val answers = DecisionResponseAssembler.forSpec(call.spec, "refund-rules")
            when (question) {
                is PropositionQuestionSpec ->
                    answers.proposition(question.name, PropositionResult.Answered("refund" in text, provenance))
                is ChoiceQuestionSpec -> {
                    val team = if ("charged" in text) "billing" else "support"
                    answers.choice(question.name, ClassificationResult.Selected(team, provenance))
                }
                else -> answers.unreadable(question.name)
            }
            answers.build()
        }
        val capabilities = DecisionCapabilities(setOf(QuestionKind.PROPOSITION, QuestionKind.CHOICE)) {
            maxQuestions = 1
        }
        val service = DecisionService.from(
            DecisionServiceMetadata.create("refund-rules", "in-process"),
            capabilities,
            rules,
        )
        // end::rules-provider[]
        return service
    }

    private fun modelCapabilities(): DecisionCapabilities {
        // tag::provider-capabilities[]
        val capabilities = DecisionCapabilities(setOf(QuestionKind.PROPOSITION, QuestionKind.CHOICE)) {
            input(DecisionInput.JSON)
            maxQuestions = 400
            option(OpenAiDecisionOptions.PROMPT_CACHE_KEY)
            option(OpenAiDecisionOptions.SAFETY_IDENTIFIER)
            arity(QuestionKind.CHOICE, 2, 50)
            reports(QuestionKind.PROPOSITION, DecisionEvidenceKind.TRUE_PROBABILITY, DecisionEvidenceKind.REFUSAL)
            reports(
                QuestionKind.CHOICE,
                DecisionEvidenceKind.CATEGORY_DISTRIBUTION,
                DecisionEvidenceKind.CONFIDENCE,
                DecisionEvidenceKind.REFUSAL,
            )
            confidence(QuestionKind.CHOICE, ConfidenceMeasure("example.top-category-probability"))
        }
        // end::provider-capabilities[]
        return capabilities
    }

    private val modelProvenance = ModelProvenance("ticket-model", "example")

    /** Calls the executor of the ticket service received, so a test can check what reached it. */
    private val seenCalls = mutableListOf<DecisionCall>()

    private fun ticketService(): DecisionService {
        // tag::refusing-provider[]
        val executor = DecisionExecutor { call ->
            DecisionResponseAssembler.forSpec(call.spec, "ticket-model")
                .proposition("duplicate", PropositionResult.Answered(true, modelProvenance, 0.93))
                .refused("team", modelProvenance)
                .usage(DecisionUsage(inputTokens = 812, outputTokens = 9, cacheReadInputTokens = 768))
                .build()
        }
        // end::refusing-provider[]
        return DecisionService.from(
            DecisionServiceMetadata.create("ticket-model", "example"),
            modelCapabilities(),
        ) { call ->
            seenCalls += call
            executor.execute(call)
        }
    }

    @Test
    fun `rules provider answers one proposition from text`() {
        val response = rulesService().ask("I was charged twice and want a refund.", DecisionSpec.of(refund))

        assertEquals(
            PropositionResult.Answered(true, ModelProvenance("refund-rules-v1", "in-process")),
            response.answer(refund),
        )
    }

    @Test
    fun `rules provider answers one choice from text`() {
        val outcome = rulesService().ask("I was charged twice.", DecisionSpec.of(route)).answer(route)

        assertEquals("billing", assertInstanceOf(ClassificationResult.Selected::class.java, outcome).categoryId)
    }

    @Test
    fun `a spec over the question limit is rejected whole`() {
        assertThrows(UnsupportedDecisionException::class.java) {
            rulesService().ask("I was charged twice.", DecisionSpec.of(refund, route))
        }
    }

    @Test
    fun `an option the capabilities do not list is rejected before the provider runs`() {
        var executorRan = false
        val service = DecisionService.from(
            DecisionServiceMetadata.create("refund-rules", "in-process"),
            DecisionCapabilities.of(setOf(QuestionKind.PROPOSITION)),
        ) { call ->
            executorRan = true
            DecisionResponseAssembler.forSpec(call.spec, "refund-rules").build()
        }
        val call = DecisionCall(TextDecisionInput("refund please"), DecisionSpec.of(refund)) {
            openAi { promptCacheKey = "support-triage-v1" }
        }

        assertThrows(UnsupportedDecisionException::class.java) { service.ask(call) }
        assertFalse(executorRan)
    }

    @Test
    fun `provider capabilities state input, limit, options and evidence`() {
        val capabilities = modelCapabilities()

        assertEquals(setOf(DecisionInput.JSON), capabilities.nativeInputKinds)
        assertEquals(400, capabilities.maxQuestions)
        assertEquals(
            setOf(OpenAiDecisionOptions.PROMPT_CACHE_KEY.id, OpenAiDecisionOptions.SAFETY_IDENTIFIER.id),
            capabilities.supportedCallOptionIds,
        )
        assertEquals(DecisionArity(2, 50), capabilities.arity(QuestionKind.CHOICE))
        assertEquals(
            setOf(
                DecisionEvidenceKind.CATEGORY_DISTRIBUTION,
                DecisionEvidenceKind.CONFIDENCE,
                DecisionEvidenceKind.REFUSAL,
            ),
            capabilities.evidence(QuestionKind.CHOICE),
        )
        assertEquals(
            ConfidenceMeasure("example.top-category-probability"),
            capabilities.confidenceMeasure(QuestionKind.CHOICE),
        )
    }

    @Test
    fun `a per-kind declaration needs the kind to be accepted`() {
        assertThrows(IllegalArgumentException::class.java) {
            DecisionCapabilities(setOf(QuestionKind.PROPOSITION)) {
                confidence(QuestionKind.CHOICE, ConfidenceMeasure("example.top-category-probability"))
            }
        }
    }

    @Test
    fun `structured call reaches the provider as JSON with its option, and answers are read back`() {
        val service = ticketService()

        // tag::structured-call[]
        val ticket = JsonMapper.builder().build().readTree(
            """{"order":{"id":"A-17","charges":[19.99,19.99]},"message":"I was charged twice."}""",
        )
        val duplicate = Questions.named("duplicate").proposition("Was the order charged twice?").build()
        val team = Questions.named("team").choice("Which team should handle this ticket?")
            .option("billing", "Payment problems")
            .option("support", "Other problems")
            .build()
        val call = DecisionCall(JsonDecisionInput(ticket), DecisionSpec.of(duplicate, team)) {
            requireNativeJson()
            openAi { promptCacheKey = "support-triage-v1" }
        }
        val response = service.ask(call)
        // end::structured-call[]

        // tag::reading-answers[]
        val charged = response.answer(duplicate)
        val owner = when (val outcome = response.answer(team)) {
            is ClassificationResult.Selected -> outcome.categoryId
            else -> if (response.wasRefused(team)) "human-review" else "support"
        }
        val inputTokens = response.usage?.inputTokens
        // end::reading-answers[]

        val seen = seenCalls.single()
        assertEquals(JsonDecisionInput(ticket), seen.input)
        assertTrue(seen.nativeJsonRequired)
        assertEquals("support-triage-v1", seen.option(OpenAiDecisionOptions.PROMPT_CACHE_KEY))

        assertEquals(PropositionResult.Answered(true, modelProvenance, 0.93), charged)
        assertInstanceOf(ClassificationResult.Inconclusive::class.java, response.answer(team))
        assertTrue(response.wasRefused(team))
        assertFalse(response.wasRefused(duplicate))
        assertEquals(setOf("team"), response.refusedQuestions)
        assertEquals("human-review", owner)
        assertEquals(812L, inputTokens)
        assertEquals(768L, response.usage?.cacheReadInputTokens)
    }

    @Test
    fun `a call shows option ids and leaves out option values and input`() {
        val call = DecisionCall(TextDecisionInput("refund please"), DecisionSpec.of(refund)) {
            openAi { safetyIdentifier = "user-42" }
        }

        assertTrue(call.toString().contains(OpenAiDecisionOptions.SAFETY_IDENTIFIER.id))
        assertFalse(call.toString().contains("user-42"))
        assertFalse(call.toString().contains("refund please"))
    }
}
