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
package com.embabel.agent.decision.example

import com.embabel.agent.decision.DecisionModel
import com.embabel.agent.decision.DecisionOption
import com.embabel.agent.decision.DecisionOutcome
import com.embabel.agent.decision.DecisionRequest
import com.embabel.agent.decision.KeyOutcome
import com.embabel.agent.decision.typesafe.TypeSafeDecisionModel
import java.time.Duration
import java.util.function.Supplier

enum class KotlinRoute { ACCEPT, REVIEW, REJECT }
enum class KotlinUrgency { LOW, MEDIUM, HIGH }

data class KotlinDecisionEvidence(
    val eligible: Boolean,
    val route: KotlinRoute,
    val urgency: KotlinUrgency,
    val routeDistribution: Map<KotlinRoute, Double>,
    val provenance: com.embabel.agent.decision.DecisionProvenance,
)

// tag::kotlin-consumer[]
fun runKotlinDecision(model: DecisionModel): KotlinDecisionEvidence {
    val builder = DecisionRequest.builder()
        .state(mapOf("subject" to "synthetic public example"))
        .timeout(Duration.ofSeconds(20))
    val eligible = builder.yesNo("eligible", "Is this item eligible?")
    val route = builder.choice(
        "route", "Which route should the host consider?", KotlinRoute.entries.map {
            DecisionOption.of(it.name.lowercase(), it, it.name.lowercase())
        },
    )
    val urgency = builder.rating(
        "urgency", "How urgent is review?", KotlinUrgency.entries.map {
            DecisionOption.of(it.name.lowercase(), it, it.name.lowercase())
        },
    )
    return when (val outcome = model.ask(builder.build())) {
        is DecisionOutcome.Failure -> error("Decision failed safely: ${outcome.safeCode}")
        is DecisionOutcome.Success -> {
            val yes = outcome.answer(eligible)
            val routeAnswer = outcome.answer(route)
            val urgencyAnswer = outcome.answer(urgency)
            if (yes is KeyOutcome.Failure) error("Eligibility evidence failed safely: ${yes.safeCode}")
            if (routeAnswer is KeyOutcome.Failure) error("Route evidence failed safely: ${routeAnswer.safeCode}")
            if (urgencyAnswer is KeyOutcome.Failure) error("Urgency evidence failed safely: ${urgencyAnswer.safeCode}")
            yes as KeyOutcome.Success
            routeAnswer as KeyOutcome.Success
            urgencyAnswer as KeyOutcome.Success
            KotlinDecisionEvidence(yes.value, routeAnswer.value, urgencyAnswer.value, routeAnswer.distribution, outcome.provenance)
        }
        else -> error("Unknown decision outcome")
    }
}
// end::kotlin-consumer[]

fun main() {
    val key = System.getenv("TYPESAFE_API_KEY")?.takeIf(String::isNotBlank)
        ?: error("TYPESAFE_API_KEY is required; no network request was made")
    val requestedModel = System.getenv("TYPESAFE_MODEL")?.takeIf(String::isNotBlank)
        ?: error("TYPESAFE_MODEL is required; no network request was made")
    val model = TypeSafeDecisionModel.create(Supplier { key }, requestedModel)
        .withDefaults(Duration.ofSeconds(20), com.embabel.agent.decision.DecisionRecordPolicy.metadata())
    val evidence = runKotlinDecision(model)
    println("Decision completed with ${evidence.provenance.evidenceKind} evidence from ${evidence.provenance.resolvedModel}")
}
