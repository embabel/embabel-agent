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
package com.embabel.agent.api.common

import com.embabel.agent.test.unit.FakeOperationContext
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.ChoiceQuestionSpec
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.support.StubDecisionService
import com.embabel.common.ai.model.DecisionServiceRegistry
import com.embabel.common.ai.model.ModelType
import com.embabel.common.ai.model.ServiceSelectionException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiDecisionSelectorsTest {

    private val provenance = ModelProvenance("stub-model", "stub")

    private val urgent = Questions.named("urgent").proposition("Is the customer asking for urgent help?").build()

    private val team: ChoiceQuestionSpec = Questions.named("team")
        .choice("Which team should handle this?")
        .option("billing", "Payments and invoices")
        .option("support", "Product help")
        .build()

    private val triage = DecisionSpec.of(urgent, team)

    private val urgentAnswer = PropositionResult.Answered(true, provenance)
    private val teamAnswer = ClassificationResult.Selected("billing", provenance)

    private val stub = StubDecisionService.builder("triage-stub")
        .proposition("urgent", urgentAnswer)
        .choice("team", teamAnswer)
        .build()

    private val registry = DecisionServiceRegistry.builder()
        .register("triage-stub", stub)
        .decisionRole("support-triage", "triage-stub")
        .build()

    private val feedback = "I was charged twice and need this fixed today."

    @Test
    fun `byRole selects the registered service and returns its outcomes`() {
        val response = FakeOperationContext.withDecisionServices(registry).ai().decisions().byRole("support-triage").ask(feedback, triage)
        assertEquals(urgentAnswer, response.answer(urgent))
        assertEquals(teamAnswer, response.answer(team))
        assertEquals(listOf("askNative"), stub.calls())
    }

    @Test
    fun `named, defaultService and using select the service`() {
        val ai = FakeOperationContext.withDecisionServices(registry).ai()
        listOf(
            ai.decisions().named("triage-stub"),
            ai.decisions().defaultService(),
            ai.decisions().using(stub),
        ).forEach { service ->
            assertEquals("triage-stub", service.name)
            assertEquals(stub.capabilities(), service.capabilities())
            assertEquals(teamAnswer, service.ask(feedback, triage).answer(team))
        }
        assertEquals(listOf("askNative", "askNative", "askNative"), stub.calls())
    }

    @Test
    fun `classifications select a decision service that keeps its family`() {
        val bound = DecisionServiceRegistry.builder()
            .register("triage-stub", stub)
            .classificationRole("routing", "triage-stub")
            .build()
        val service = FakeOperationContext.withDecisionServices(bound).ai().classifications().byRole("routing")
        assertEquals(ModelType.DECISION, service.type)
    }

    @Test
    fun `a missing role throws a selection error naming the role`() {
        val error = assertThrows(ServiceSelectionException::class.java) {
            FakeOperationContext.withDecisionServices(registry).ai().decisions().byRole("billing-review")
        }
        assertEquals(ServiceSelectionException.Reason.UNKNOWN_ROLE, error.reason)
        assertTrue(error.message!!.contains("billing-review"), error.message)
    }

    @Test
    fun `the platform default registry is empty and supports using only`() {
        val ai = FakeOperationContext().ai()
        assertEquals(DecisionServiceRegistry.empty(), FakeOperationContext().processContext.platformServices.decisionServices())
        assertEquals("triage-stub", ai.decisions().using(stub).name)
        assertThrows(ServiceSelectionException::class.java) { ai.decisions().defaultService() }
        assertThrows(ServiceSelectionException::class.java) { ai.classifications().named("triage-stub") }
    }
}
