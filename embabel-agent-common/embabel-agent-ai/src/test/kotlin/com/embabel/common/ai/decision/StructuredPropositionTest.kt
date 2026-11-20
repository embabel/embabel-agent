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
package com.embabel.common.ai.decision

import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.spi.PropositionAssessment
import com.embabel.common.ai.decision.support.NoOpDecisionService
import com.embabel.common.ai.decision.support.StubDecisionService
import com.embabel.common.ai.model.observation.ObservedDecisionService
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

class StructuredPropositionTest {

    private val mapper = JsonMapper.builder().build()

    private val provenance = ModelProvenance("model-a", "provider-a")

    private val proposition = "Is `ticket.subject` about a refund?"

    // Deliberately not in alphabetical order, so a sorted rendering would show up.
    private val stateJson = """{"ticket":{"subject":"Refund","body":"Charged twice"},"priority":2}"""

    private fun state(): ObjectNode = mapper.readTree(stateJson) as ObjectNode

    private val answered = PropositionResult.Answered(true, provenance)

    /** Keeps the proposition requests it was given. */
    private open inner class Recording : DecisionService {
        val requests = mutableListOf<PropositionRequest>()

        override val name = "recording"
        override val provider = "test"

        override fun classify(request: ClassificationRequest): ClassificationResult = error("not used")

        override fun assess(request: PropositionRequest): PropositionResult {
            requests += request
            return answered
        }
    }

    @Nested
    inner class Request {

        @Test
        fun `a structured request holds the state and its JSON text as the input`() {
            val request = PropositionRequest(state(), proposition)
            assertEquals(state(), request.state)
            assertEquals(stateJson, request.input)
            assertEquals(proposition, request.proposition)
        }

        @Test
        fun `a text request has no state`() {
            assertNull(PropositionRequest("text", proposition).state)
        }

        @Test
        fun `a state that is neither an object nor an array is rejected`() {
            for (scalar in listOf(mapper.readTree("\"text\""), mapper.readTree("3"), mapper.nullNode())) {
                val error = assertThrows(IllegalArgumentException::class.java) { PropositionRequest(scalar, proposition) }
                assertTrue(error.message!!.contains("object or array")) { error.message }
            }
        }

        @Test
        fun `a blank proposition is rejected for a structured request`() {
            assertThrows(IllegalArgumentException::class.java) { PropositionRequest(state(), " ") }
        }

        @Test
        fun `changing the node given to the request does not change the request`() {
            val given = state()
            val request = PropositionRequest(given, proposition)
            given.put("added", true)
            assertEquals(state(), request.state)
            assertEquals(stateJson, request.input)
        }

        @Test
        fun `changing the node a request returns does not change the request`() {
            val request = PropositionRequest(state(), proposition)
            (request.state as ObjectNode).put("added", true)
            assertEquals(state(), request.state)
        }

        @Test
        fun `a binary leaf is captured as text, so later changes to the bytes do not reach the request`() {
            val bytes = "AB".toByteArray()
            val request = PropositionRequest(mapper.createObjectNode().put("blob", bytes), proposition)
            bytes[0] = 'C'.code.toByte()
            assertEquals("""{"blob":"QUI="}""", request.input)
        }

        @Test
        fun `a state that cannot be written as JSON is rejected without quoting it`() {
            class Leaky {
                @Suppress("unused")
                val value: String get() = error("SECRET-STATE")
            }
            val error = assertThrows(IllegalArgumentException::class.java) {
                PropositionRequest(mapper.createObjectNode().putPOJO("held", Leaky()), proposition)
            }
            assertFalse(error.message!!.contains("SECRET-STATE")) { error.message }
            assertNull(error.cause)
        }

        @Test
        fun `toString omits the state`() {
            assertFalse(PropositionRequest(state(), proposition).toString().contains("Refund"))
        }
    }

    @Nested
    inner class Assessing {

        private val question = Questions.named("refund").proposition(proposition).build()

        @Test
        fun `a structured ask gives a per-question service the state for a proposition question`() {
            val service = Recording()
            val response = service.ask(DecisionRequest.of(state(), DecisionSpec.of(question)))
            assertEquals(answered, response.answer(question))
            assertEquals(state(), service.requests.single().state)
            assertEquals(stateJson, service.requests.single().input)
            assertEquals(proposition, service.requests.single().proposition)
        }

        @Test
        fun `a text ask still gives a per-question service a text request`() {
            val service = Recording()
            service.ask("text", DecisionSpec.of(question))
            assertNull(service.requests.single().state)
            assertEquals("text", service.requests.single().input)
        }

        @Test
        fun `a service with the proposition hook gets the state as JSON text`() {
            val inputs = mutableListOf<String>()
            val service = object : Recording(), PropositionAssessment {
                override fun assess(input: String, question: PropositionQuestionSpec): PropositionResult {
                    inputs += input
                    return answered
                }
            }
            service.ask(DecisionRequest.of(state(), DecisionSpec.of(question)))
            assertEquals(listOf(stateJson), inputs)
        }

        @Test
        fun `an observed service observes a structured assessment and passes the state on`() {
            val stopped = mutableListOf<String>()
            val handler = object : ObservationHandler<Observation.Context> {
                override fun supportsContext(context: Observation.Context) = true

                override fun onStop(context: Observation.Context) {
                    stopped.add(context.name.orEmpty())
                }
            }
            val registry = ObservationRegistry.create().apply { observationConfig().observationHandler(handler) }
            val delegate = Recording()
            assertEquals(answered, ObservedDecisionService(delegate, registry).assess(PropositionRequest(state(), proposition)))
            assertEquals(listOf("embabel.ai.decision"), stopped)
            assertEquals(state(), delegate.requests.single().state)
        }

        @Test
        fun `a stub answers a structured assessment from its script`() {
            val stub = StubDecisionService.builder("stub").assessing(proposition, answered).build()
            assertEquals(answered, stub.assess(PropositionRequest(state(), proposition)))
        }

        @Test
        fun `the no-op service fails a structured assessment as unavailable`() {
            assertEquals(
                PropositionResult.Failure(FailureReason.UNAVAILABLE),
                NoOpDecisionService().assess(PropositionRequest(state(), proposition)),
            )
        }
    }
}
