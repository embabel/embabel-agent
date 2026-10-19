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
package com.embabel.agent.cloudflare

import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.RatingResult
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Runs a support triage spec against the decision models on Workers AI. It runs only when
 * `CLOUDFLARE_SMOKE_TEST=true`, `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID` are set. Jev also
 * needs `CLOUDFLARE_SMOKE_JEV=true`, because Cloudflare bills third-party models through AI Gateway
 * credits or a stored provider key. The token reaches the factory through its supplier and is never
 * printed. Printed lines hold outcome kinds and confidence values only.
 */
@EnabledIfEnvironmentVariable(named = "CLOUDFLARE_SMOKE_TEST", matches = "true")
@EnabledIfEnvironmentVariable(named = "CLOUDFLARE_API_TOKEN", matches = ".+")
@EnabledIfEnvironmentVariable(named = "CLOUDFLARE_ACCOUNT_ID", matches = ".+")
class CloudflareClefSmokeTest {

    private val urgent = Questions.named("urgent").proposition("Does the customer need a response today?").build()
    private val department = Questions.named("department").choice("Which team should handle this message?")
        .option("billing", "Payments, charges, invoices and refunds")
        .option("technical", "Bugs, outages and integrations")
        .build()
    private val frustration = Questions.named("frustration").rating("How frustrated is the customer?")
        .level("calm", "Calm").level("frustrated", "Frustrated").level("very_angry", "Very angry")
        .build()

    @Test
    fun `each clef model answers every question kind in one call`() {
        listOf(CloudflareModelFactory.CLEF, CloudflareModelFactory.CLEF_FLASH, CloudflareModelFactory.CLEF_OMNI).forEach(::triage)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CLOUDFLARE_SMOKE_JEV", matches = "true")
    fun `jev answers every question kind in one call`() {
        triage(CloudflareModelFactory.JEV)
    }

    private fun triage(model: String) {
        val factory = CloudflareModelFactory(System.getenv("CLOUDFLARE_ACCOUNT_ID"), { System.getenv("CLOUDFLARE_API_TOKEN") })
        val response = factory.build(model).ask(
            DecisionRequest.of(
                "You charged my card twice for the same invoice. Refund it today or I cancel. I am furious.",
                DecisionSpec.of(urgent, department, frustration),
            ),
        )
        assertNull(response.requestFailure, model)
        val proposition = response.answer(urgent)
        val choice = response.answer(department)
        val rating = response.answer(frustration)
        println("Cloudflare smoke $model: ${kind(proposition)} ${kind(choice)} ${kind(rating)}")
        assertTrue(proposition is PropositionResult.Answered || proposition is PropositionResult.Inconclusive, model)
        assertTrue(choice is ClassificationResult.Selected || choice is ClassificationResult.Inconclusive, model)
        assertTrue(rating is RatingResult.Answered, model)
    }

    @Test
    fun `the account lists clef and validates the token`() {
        val factory = CloudflareModelFactory(System.getenv("CLOUDFLARE_ACCOUNT_ID"), { System.getenv("CLOUDFLARE_API_TOKEN") })
        val names = factory.modelNames()
        println("Cloudflare smoke models: $names")
        assertTrue(CloudflareModelFactory.CLEF in names, names.toString())
        factory.buildValidated()
    }

    private fun kind(outcome: Any): String = when (outcome) {
        is PropositionResult.Answered -> "Answered pTrue=${outcome.pTrue}"
        is ClassificationResult.Selected -> "Selected confidence=${outcome.confidence}"
        is RatingResult.Answered -> "Answered confidence=${outcome.confidence}"
        else -> outcome.javaClass.simpleName
    }
}
