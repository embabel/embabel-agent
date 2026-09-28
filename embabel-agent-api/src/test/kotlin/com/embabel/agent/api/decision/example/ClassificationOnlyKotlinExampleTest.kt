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
import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.classification.classificationSpec
import com.embabel.common.ai.decision.support.NoOpDecisionService
import com.embabel.common.ai.model.ClassificationService
import com.embabel.common.ai.model.DecisionServiceRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The classification-only example in Kotlin, with the spec built through the Kotlin DSL.
 */
class ClassificationOnlyKotlinExampleTest {

    private val model = ModelProvenance("keyword-model", "example")

    // tag::route[]
    val departments = classificationSpec {
        asking("Which team should handle this?")
        category("billing", "Payments, invoicing, refunds")
        category("technical", "Bugs, outages, integrations")
    }

    fun queueFor(ai: Ai, ticketText: String): String {
        val classifier = ai.classifications().byRole("ticket-routing")
        return when (val result = classifier.classify(ticketText, departments)) {
            is ClassificationResult.Selected -> result.categoryId
            is ClassificationResult.NoMatch -> "general"
            is ClassificationResult.Inconclusive, is ClassificationResult.Failure -> "triage"
        }
    }
    // end::route[]

    /**
     * Builds an `Ai` whose `ticket-routing` classification role is the given service.
     *
     * @param service the service that classifies tickets
     * @return an `Ai` from a workflow operation over that registry
     */
    private fun routingTo(service: ClassificationService): Ai = ExampleOperations.withRegistry(
        DecisionServiceRegistry.builder()
            .register("ticket-classifier", service)
            .classificationRole("ticket-routing", "ticket-classifier")
            .build(),
    ).ai()

    @Test
    fun `each outcome routes to a queue`() {
        val ai = routingTo(KeywordClassifier(model))

        assertEquals("billing", queueFor(ai, "The billing page charged me twice."))
        assertEquals("technical", queueFor(ai, "Technical fault: the export button crashes."))
        assertEquals("general", queueFor(ai, "Can I change my username?"))
        assertEquals("triage", queueFor(ai, " "))
        assertEquals("triage", queueFor(routingTo(NoOpDecisionService("disabled")), "The billing page charged me twice."))
    }

    /**
     * Stands in for a model. It selects the first category whose id appears in the ticket, finds no
     * match when none does, and is inconclusive for blank text.
     */
    private class KeywordClassifier(private val model: ModelProvenance) : ClassificationService {
        override val name = "keyword-classifier"
        override val provider = "example"

        override fun classify(request: ClassificationRequest): ClassificationResult {
            val text = request.input.lowercase()
            if (text.isBlank()) return ClassificationResult.Inconclusive(model)
            val match = request.categories.firstOrNull { text.contains(it.id.lowercase()) }
                ?: return ClassificationResult.NoMatch(model)
            return request.spec.selected(match.id, model)
        }
    }
}
