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
package com.embabel.common.ai.decision.support

import com.embabel.common.ai.classification.Category
import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.ClassificationSpec
import com.embabel.common.ai.classification.classificationSpec
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.ChoiceQuestionSpec
import com.embabel.common.ai.decision.DecisionCapabilities
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.RatingQuestionSpec
import com.embabel.common.ai.decision.RatingResult
import com.embabel.common.ai.decision.UnsupportedDecisionException
import com.embabel.common.ai.decision.spi.NativeQuestionSetExecution
import com.embabel.common.ai.model.observation.ObservedDecisionService
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.EnumSet

class StubDecisionServiceTest {

    private val provenance = ModelProvenance("stub-model", "stub")

    private val urgent = Questions.named("urgent").proposition("Is it urgent?").build()

    private val team: ChoiceQuestionSpec = Questions.named("team")
        .choice("Which team?")
        .option("billing", "Payments")
        .option("support", "Help")
        .build()

    private val anger: RatingQuestionSpec = Questions.named("anger")
        .rating("How angry?")
        .level("calm")
        .level("angry")
        .build()

    private val urgentAnswer = PropositionResult.Answered(false, provenance)
    private val teamAnswer = ClassificationResult.Selected("support", provenance)
    private val angerAnswer = RatingResult.Answered(provenance, selectedLevelId = "angry")

    private fun classifying(question: ChoiceQuestionSpec): ClassificationRequest =
        ClassificationRequest.of("text", ClassificationSpec.of(question))

    private fun scripted(): StubDecisionService.Builder = StubDecisionService.builder("triage-stub")
        .proposition("urgent", urgentAnswer)
        .choice("team", teamAnswer)
        .rating("anger", angerAnswer)

    @Test
    fun `identity and default capabilities`() {
        val stub = scripted().build()
        assertEquals("triage-stub", stub.name)
        assertEquals("stub", stub.provider)
        assertEquals(
            DecisionCapabilities.of(EnumSet.allOf(QuestionKind::class.java)),
            stub.capabilities(),
        )
    }

    @Test
    fun `a native ask returns the scripted outcomes exactly`() {
        val stub = scripted().build()
        val response = stub.ask(DecisionRequest.of("text", urgent, team, anger))
        assertEquals(urgentAnswer, response.answer(urgent))
        assertEquals(teamAnswer, response.answer(team))
        assertEquals(angerAnswer, response.answer(anger))
        assertEquals(listOf("askNative"), stub.calls())
    }

    @Test
    fun `assess, classify and rate return their scripted outcomes`() {
        val noMatch = ClassificationResult.NoMatch(provenance)
        val stub = scripted()
            .assessing("Is it urgent?", urgentAnswer)
            .classifying(setOf("a", "b"), noMatch)
            .build()
        assertEquals(urgentAnswer, stub.assess(PropositionRequest("text", "Is it urgent?")))
        assertEquals(noMatch, stub.classify(ClassificationRequest.of("text", classificationSpec { asking("Which category fits?"); category("b", "B"); category("a", "A") })))
        assertEquals(teamAnswer, stub.classify(classifying(team)))
        assertEquals(angerAnswer, stub.rate("text", anger))
        assertEquals(listOf("assess", "classify", "classify", "rate"), stub.calls())
    }

    @Test
    fun `an unscripted question throws naming it`() {
        val stub = StubDecisionService.builder("triage-stub").proposition("urgent", urgentAnswer).build()
        val error = assertThrows(IllegalStateException::class.java) {
            stub.ask(DecisionRequest.of("text", urgent, team))
        }
        assertTrue(error.message!!.contains("'team'")) { error.message }
        val classifyError = assertThrows(IllegalStateException::class.java) { stub.classify(classifying(team)) }
        assertTrue(classifyError.message!!.contains("'team'")) { classifyError.message }
        val rateError = assertThrows(IllegalStateException::class.java) { stub.rate("text", anger) }
        assertTrue(rateError.message!!.contains("'anger'"))
    }

    @Test
    fun `an unscripted assessment or classification throws`() {
        val stub = StubDecisionService.builder("triage-stub").build()
        assertThrows(IllegalStateException::class.java) { stub.assess(PropositionRequest("text", "Is it urgent?")) }
        val error = assertThrows(IllegalStateException::class.java) {
            stub.classify(ClassificationRequest.of("text", classificationSpec { asking("Which category fits?"); category("a", "A") }))
        }
        assertTrue(error.message!!.contains("a"))
    }

    @Test
    fun `a scripted choice outside the options fails the native ask`() {
        val stub = scripted().choice("team", ClassificationResult.Selected("elsewhere", provenance)).build()
        val error = assertThrows(IllegalStateException::class.java) {
            stub.ask(DecisionRequest.of("text", urgent, team))
        }
        assertTrue(error.message!!.contains("'team'"))
    }

    @Test
    fun `propositions-only capabilities reject a choice with no calls`() {
        val stub = scripted().capabilities(DecisionCapabilities.of(EnumSet.of(QuestionKind.PROPOSITION))).build()
        assertThrows(UnsupportedDecisionException::class.java) {
            stub.ask(DecisionRequest.of("text", urgent, team))
        }
        assertThrows(UnsupportedDecisionException::class.java) {
            stub.ask("text", DecisionSpec.of(team))
        }
        assertEquals(emptyList<String>(), stub.calls())
    }

    @Test
    fun `a per-question stub calls assess, classify and rate in spec order`() {
        val stub = scripted().perQuestion().build()
        val response = stub.ask(DecisionRequest.of("text", urgent, team, anger))
        assertEquals(listOf("assess", "classify", "rate"), stub.calls())
        assertEquals(urgentAnswer, response.answer(urgent))
        assertEquals(teamAnswer, response.answer(team))
        assertEquals(angerAnswer, response.answer(anger))
    }

    @Test
    fun `a classification request asked on a native stub answers its question`() {
        val spec = ClassificationSpec.of(team)
        val stub = scripted().build()
        val response = stub.ask(ClassificationRequest.of("text", spec))
        assertEquals(teamAnswer, response.answer(spec.question))
        assertEquals(listOf("askNative"), stub.calls())
    }

    @Test
    fun `a classification request asked on a per-question stub goes through classify`() {
        val spec = classificationSpec { asking("Which category fits?"); category("a", "A"); category("b", "B") }
        val noMatch = ClassificationResult.NoMatch(provenance)
        val stub = StubDecisionService.builder("triage-stub").classifying(setOf("a", "b"), noMatch).perQuestion().build()
        val response = stub.ask(ClassificationRequest.of("text", spec))
        assertEquals(noMatch, response.answer(spec.question))
        assertEquals(listOf("classify"), stub.calls())
    }

    @Test
    fun `a per-question stub is no native service and a default stub is one`() {
        assertFalse(scripted().perQuestion().build() is NativeQuestionSetExecution)
        assertTrue(scripted().build() is NativeQuestionSetExecution)
    }

    @Test
    fun `a proposition scripted by name answers a question assess call`() {
        val stub = scripted().build()
        assertEquals(urgentAnswer, stub.assess("text", urgent))
        assertEquals(listOf("assess"), stub.calls())
    }

    @Test
    fun `a proposition scripted by name answers a per-question ask through a decorator`() {
        val stub = scripted().perQuestion().build()
        val observed = ObservedDecisionService(stub, ObservationRegistry.NOOP)
        val response = observed.ask(DecisionRequest.of("text", urgent, team))
        assertEquals(urgentAnswer, response.answer(urgent))
        assertEquals(listOf("assess", "classify"), stub.calls())
    }

    @Test
    fun `an assessing script answers direct assess calls and leaves question asks to the name`() {
        val other = PropositionResult.Answered(true, provenance)
        val stub = scripted().assessing("Is it urgent?", other).perQuestion().build()
        assertEquals(urgentAnswer, stub.ask("text", DecisionSpec.of(urgent)).answer(urgent))
        assertEquals(other, stub.assess(PropositionRequest("text", "Is it urgent?")))
    }

    @Test
    fun `an unscripted proposition in a per-question ask names the question`() {
        val stub = StubDecisionService.builder("triage-stub").perQuestion().build()
        val error = assertThrows(IllegalStateException::class.java) { stub.ask("text", DecisionSpec.of(urgent)) }
        assertTrue(error.message!!.contains("proposition(\"urgent\", result)")) { error.message }
    }

    @Test
    fun `the call log is in call order and cannot be modified`() {
        val stub = scripted().assessing("Is it urgent?", urgentAnswer).build()
        stub.rate("text", anger)
        stub.classify(classifying(team))
        stub.assess(PropositionRequest("text", "Is it urgent?"))
        val calls = stub.calls()
        assertEquals(listOf("rate", "classify", "assess"), calls)
        assertThrows(UnsupportedOperationException::class.java) { (calls as MutableList<String>).add("x") }
    }
}
