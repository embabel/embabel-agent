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
package com.embabel.agent.systemone

import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.ClassificationSpec
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.RatingResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.net.URI
import java.time.Duration

/**
 * Runs a support triage spec against a local System One endpoint, such as CLEF Flash in Ollama:
 *
 * ```
 * ollama pull clef-flash
 * SYSTEMONE_SMOKE_URL=http://localhost:11434 SYSTEMONE_SMOKE_MODEL=clef-flash mvn test -pl embabel-agent-systemone -Dtest=SystemOneLocalSmokeTest
 * ```
 *
 * It runs only when `SYSTEMONE_SMOKE_URL` is set. The model defaults to `clef-flash`. Printed lines
 * hold outcome kinds and confidence values only.
 */
@EnabledIfEnvironmentVariable(named = "SYSTEMONE_SMOKE_URL", matches = ".+")
class SystemOneLocalSmokeTest {

    private val model = System.getenv("SYSTEMONE_SMOKE_MODEL")?.takeIf { it.isNotBlank() } ?: "clef-flash"

    // A local model can take a while to load on its first call.
    private val factory = SystemOneModelFactory(
        SystemOneEndpoint("Local", URI.create(System.getenv("SYSTEMONE_SMOKE_URL")), readTimeout = Duration.ofMinutes(5)),
        model,
        { null },
    )

    private val urgent = Questions.named("urgent").proposition("Does the customer need a response today?").build()
    private val department = Questions.named("department").choice("Which team should handle this message?")
        .option("billing", "Payments, charges, invoices and refunds")
        .option("technical", "Bugs, outages and integrations")
        .option("sales", "New purchases, upgrades and pricing questions")
        .build()
    private val frustration = Questions.named("frustration").rating("How frustrated is the customer?")
        .level("calm", "Calm").level("frustrated", "Frustrated").level("very_angry", "Very angry")
        .build()

    private val input =
        "This is the third month in a row you charged my card twice for the same invoice. " +
            "Refund the duplicate today or I am cancelling my account. I am furious that nobody answers my emails."

    @Test
    fun `one call answers every question kind`() {
        val response = factory.build().ask(DecisionRequest.of(input, DecisionSpec.of(urgent, department, frustration)))

        assertNull(response.requestFailure)
        val proposition = response.answer(urgent)
        val choice = response.answer(department)
        val rating = response.answer(frustration)
        println("System One smoke $model: ${kind(proposition)} | ${kind(choice)} | ${kind(rating)}")
        assertTrue(proposition is PropositionResult.Answered && proposition.answer)
        assertTrue(choice is ClassificationResult.Selected && choice.categoryId == "billing")
        assertEquals(listOf("billing", "technical", "sales"), (choice as ClassificationResult.Selected).distribution.map { it.categoryId })
        assertTrue(rating is RatingResult.Answered && rating.distribution.size == 3 && rating.score != null)
        assertEquals("Local", (proposition as PropositionResult.Answered).provenance.provider)
    }

    @Test
    fun `the endpoint lists the model`() {
        assertTrue(factory.modelNames().any { it == model || it.startsWith("$model:") }, factory.modelNames().toString())
    }

    @Test
    fun `classify and assess each make one call`() {
        val spec = ClassificationSpec.builder().asking("Which team should handle this message?")
            .category("billing", "Payments, charges, invoices and refunds")
            .category("technical", "Bugs, outages and integrations")
            .build()
        val service = factory.build()

        val classified = service.classify(ClassificationRequest.of(input, spec))
        val assessed = service.assess(PropositionRequest(input, "The customer is satisfied"))

        println("System One smoke $model: classify ${kind(classified)} | assess ${kind(assessed)}")
        assertTrue(classified is ClassificationResult.Selected && classified.categoryId == "billing")
        assertTrue(assessed is PropositionResult.Answered && !assessed.answer)
    }

    private fun kind(outcome: Any): String = when (outcome) {
        is PropositionResult.Answered -> "answered ${outcome.answer} pTrue=${"%.3f".format(outcome.pTrue)}"
        is ClassificationResult.Selected -> "selected confidence=${"%.3f".format(outcome.confidence)}"
        is RatingResult.Answered -> "rated score=${"%.3f".format(outcome.score?.value)} confidence=${"%.3f".format(outcome.confidence)}"
        else -> outcome.javaClass.simpleName
    }
}
