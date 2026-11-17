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
package com.embabel.agent.spi.support.decision

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
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.UnsupportedDecisionException
import com.embabel.common.ai.model.DecisionServiceRegistry
import com.embabel.common.ai.model.observation.ObservedDecisionService
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class OperationBoundDecisionCallTest {

    private val provenance = ModelProvenance("model-a", "provider-a")

    private val urgent = Questions.named("urgent").proposition("Is it urgent?").build()

    private val flag: DecisionOption<String> = DecisionOption.of("test", "flag", String::class.java)

    private val handler = RecordingHandler()

    private val observations: ObservationRegistry = ObservationRegistry.create().also {
        it.observationConfig().observationHandler(handler)
    }

    /** Records the name and parent of every observation started on the registry. */
    private class RecordingHandler : ObservationHandler<Observation.Context> {
        val started = CopyOnWriteArrayList<String>()
        val parents = ConcurrentHashMap<String, String>()

        override fun onStart(context: Observation.Context) {
            started += context.name
            context.parentObservation?.contextView?.name?.let { parents[context.name] = it }
        }

        override fun supportsContext(context: Observation.Context): Boolean = true
    }

    /** Remembers each call it gets, and the observation current when it ran. */
    private inner class Executor {
        val calls = mutableListOf<DecisionCall>()
        val currentNames = mutableListOf<String?>()

        fun service(): DecisionService = DecisionService.from(
            DecisionServiceMetadata.create("one-function", "test"),
            DecisionCapabilities.of(EnumSet.of(QuestionKind.PROPOSITION)).acceptingOption(flag),
        ) { call ->
            calls += call
            currentNames += observations.currentObservation?.context?.name
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

    private fun registryOf(vararg services: Pair<String, DecisionService>): DecisionServiceRegistry {
        val builder = DecisionServiceRegistry.builder().observationRegistry(observations)
        services.forEach { (name, service) -> builder.register(name, service) }
        return builder.build()
    }

    /** Runs [block] with a started observation named [name] in scope. */
    private fun <R> inObservation(name: String, block: () -> R): R {
        val observation = Observation.start(name, observations)
        try {
            return observation.openScope().use { block() }
        } finally {
            observation.stop()
        }
    }

    @Test
    fun `an option reaches the executor through the binding alone and runs under the bound parent`() {
        val executor = Executor()
        val registry = registryOf("exec" to executor.service())
        val bound = inObservation("action") { OperationBoundServices.decisions(registry).named("exec") }

        bound.ask(callWithFlag())

        assertEquals(1, executor.calls.size)
        assertEquals("on", executor.calls.single().option(flag))
        assertEquals(listOf("action"), executor.currentNames)
    }

    @Test
    fun `a service passed to using gets the option and its ask observation has the bound parent`() {
        val executor = Executor()
        val bound = inObservation("action") { OperationBoundServices.decisions(registryOf()).using(executor.service()) }

        bound.ask(callWithFlag())

        assertEquals(1, executor.calls.size)
        assertEquals("on", executor.calls.single().option(flag))
        assertEquals(listOf("action", "embabel.ai.ask", "embabel.ai.decision"), handler.started)
        assertEquals("action", handler.parents["embabel.ai.ask"])
        assertEquals("embabel.ai.ask", handler.parents["embabel.ai.decision"])
    }

    @Test
    fun `an observed service inside a binding runs the executor once with the option`() {
        val executor = Executor()
        val observed = ObservedDecisionService(executor.service(), observations)
        inObservation("action") {
            val bound = OperationBoundServices.decisions(registryOf()).using(observed)
            assertSame(observed, (bound as OperationBoundDecisionService).delegate)
            bound.ask(callWithFlag())
        }

        assertEquals(1, executor.calls.size)
        assertEquals("on", executor.calls.single().option(flag))
        assertEquals(1, handler.started.count { it == "embabel.ai.ask" })
    }

    @Test
    fun `a bound legacy service asked with an option is refused before any provider call`() {
        val legacy = Legacy()
        val bound = inObservation("action") { OperationBoundServices.decisions(registryOf("legacy" to legacy)).named("legacy") }

        assertThrows<UnsupportedDecisionException> { bound.ask(callWithFlag()) }

        assertTrue(legacy.calls.isEmpty())
    }
}
