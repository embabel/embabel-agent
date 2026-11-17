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
package com.embabel.common.ai.model.observation

import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.DecisionCall
import com.embabel.common.ai.decision.DecisionCapabilities
import com.embabel.common.ai.decision.DecisionOption
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.decision.DecisionServiceMetadata
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.PropositionQuestionSpec
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.TextDecisionInput
import com.embabel.common.ai.decision.UnsupportedDecisionException
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.EnumSet

class ObservedDecisionCallTest {

    private val provenance = ModelProvenance("model-a", "provider-a")

    private val urgent: PropositionQuestionSpec = Questions.named("urgent").proposition("Is it urgent?").build()

    private val flag: DecisionOption<String> = DecisionOption.of("test", "flag", String::class.java)

    private val recorder = Recorder()

    private val registry: ObservationRegistry = ObservationRegistry.create().apply {
        observationConfig().observationHandler(recorder)
    }

    private class Recorder : ObservationHandler<Observation.Context> {
        val stopped = mutableListOf<Observation.Context>()

        override fun supportsContext(context: Observation.Context) = true

        override fun onStop(context: Observation.Context) {
            stopped += context
        }

        fun named(name: String) = stopped.filter { it.name == name }

        fun operations(name: String) = named(name).map { it.lowCardinalityKeyValues.getValue("operation") }

        private fun io.micrometer.common.KeyValues.getValue(key: String): String =
            first { it.key == key }.value
    }

    /** Remembers each call it gets and answers every question true. */
    private inner class Executor {
        val calls = mutableListOf<DecisionCall>()

        fun service(): DecisionService = DecisionService.from(
            DecisionServiceMetadata.create("one-function", "test"),
            DecisionCapabilities.of(EnumSet.of(QuestionKind.PROPOSITION)).acceptingOption(flag),
        ) { call ->
            calls += call
            DecisionResponse.builder(call.spec).answer(urgent, PropositionResult.Answered(true, provenance)).build()
        }
    }

    /** A service written against the base API only, with a log of provider calls. */
    private class Legacy : DecisionService {
        override val name = "legacy"
        override val provider = "test"
        val calls = mutableListOf<String>()

        override fun classify(request: ClassificationRequest): ClassificationResult {
            calls += "classify"
            return ClassificationResult.NoMatch(ModelProvenance("legacy", "test"))
        }

        override fun assess(request: PropositionRequest): PropositionResult {
            calls += "assess"
            return PropositionResult.Answered(true, ModelProvenance("legacy", "test"))
        }
    }

    private fun callWithFlag(): DecisionCall = DecisionCall(DecisionRequest.of("text", urgent)) { option(flag, "on") }

    @Test
    fun `an option reaches the executor through the observed decorator and the ask is observed`() {
        val executor = Executor()
        val observed = ObservedDecisionService(executor.service(), registry)

        val response = observed.ask(callWithFlag())

        assertEquals(PropositionResult.Answered(true, provenance), response.answer(urgent))
        assertEquals(1, executor.calls.size)
        assertEquals("on", executor.calls.single().option(flag))
        assertEquals(listOf("ask"), recorder.operations("embabel.ai.ask"))
        assertEquals(listOf("ask_question_set"), recorder.operations("embabel.ai.decision"))
    }

    @Test
    fun `a nested observed decorator records one ask observation for a call`() {
        val executor = Executor()
        val observed = ObservedDecisionService(ObservedDecisionService(executor.service(), registry), registry)

        observed.ask(callWithFlag())

        assertEquals(1, executor.calls.size)
        assertEquals("on", executor.calls.single().option(flag))
        assertEquals(1, recorder.named("embabel.ai.ask").size)
    }

    @Test
    fun `a legacy delegate asked with an option is refused before any provider call`() {
        val legacy = Legacy()
        val observed = ObservedDecisionService(legacy, registry)

        assertThrows<UnsupportedDecisionException> { observed.ask(callWithFlag()) }

        assertTrue(legacy.calls.isEmpty())
        assertTrue(recorder.named("embabel.ai.decision").isEmpty())
    }

    @Test
    fun `a legacy delegate still answers a text call without options`() {
        val legacy = Legacy()
        val observed = ObservedDecisionService(legacy, registry)

        val response = observed.ask(DecisionCall.of(TextDecisionInput("text"), DecisionSpec.of(urgent)))

        assertEquals(PropositionResult.Answered(true, ModelProvenance("legacy", "test")), response.answer(urgent))
        assertEquals(listOf("assess"), legacy.calls)
        assertEquals(1, recorder.named("embabel.ai.ask").size)
    }
}
