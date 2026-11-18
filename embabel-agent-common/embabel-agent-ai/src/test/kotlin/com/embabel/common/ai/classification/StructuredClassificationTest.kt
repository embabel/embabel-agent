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
package com.embabel.common.ai.classification

import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.model.observation.ObservedClassificationService
import com.embabel.common.ai.model.observation.ObservedDecisionService
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import tools.jackson.databind.DatabindException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

class StructuredClassificationTest {

    private val mapper = JsonMapper.builder().build()

    private val provenance = ModelProvenance("model-a", "provider-a")

    private val spec = classificationSpec {
        asking("Which team should handle `ticket.subject`?")
        category("billing", "Payments")
        category("support", "Help")
    }

    // Deliberately not in alphabetical order, so a sorted rendering would show up.
    private val stateJson = """{"ticket":{"subject":"Refund","body":"Charged twice"},"priority":2}"""

    private fun state(): ObjectNode = mapper.readTree(stateJson) as ObjectNode

    private val specJson = mapper.writeValueAsString(spec)

    private val structuredJson = """{"state":$stateJson,"spec":$specJson}"""

    private val selected = ClassificationResult.Selected("billing", provenance)

    /** Keeps the classification requests it was given. */
    private inner class Recording : DecisionService {
        val requests = mutableListOf<ClassificationRequest>()

        override val name = "recording"
        override val provider = "test"

        override fun classify(request: ClassificationRequest): ClassificationResult {
            requests += request
            return selected
        }

        override fun assess(request: PropositionRequest): PropositionResult = error("not used")
    }

    private class Stopped : ObservationHandler<Observation.Context> {
        val names = mutableListOf<String>()

        override fun supportsContext(context: Observation.Context) = true

        override fun onStop(context: Observation.Context) {
            names.add(context.name.orEmpty())
        }
    }

    @Nested
    inner class Request {

        @Test
        fun `a structured request holds the state and its JSON text as the input`() {
            val request = ClassificationRequest.of(state(), spec)
            assertEquals(state(), request.state)
            assertEquals(stateJson, request.input)
            assertEquals(spec, request.spec)
            assertEquals(spec.categories, request.categories)
        }

        @Test
        fun `a text request has no state`() {
            assertNull(ClassificationRequest.of("text", spec).state)
        }

        @Test
        fun `a state that is neither an object nor an array is rejected`() {
            for (scalar in listOf(mapper.readTree("\"text\""), mapper.readTree("3"), mapper.nullNode())) {
                val error = assertThrows(IllegalArgumentException::class.java) { ClassificationRequest.of(scalar, spec) }
                assertTrue(error.message!!.contains("object or array")) { error.message }
            }
        }

        @Test
        fun `changing the node given to of does not change the request`() {
            val given = state()
            val request = ClassificationRequest.of(given, spec)
            given.put("added", true)
            assertEquals(state(), request.state)
            assertEquals(stateJson, request.input)
        }

        @Test
        fun `a structured request equals the decision request for the same state and spec`() {
            assertEquals(ClassificationRequest.of(state(), spec), ClassificationRequest.of(state(), spec))
            assertEquals(DecisionRequest.of(state(), spec), ClassificationRequest.of(state(), spec))
            assertNotEquals(ClassificationRequest.of(stateJson, spec), ClassificationRequest.of(state(), spec))
        }

        @Test
        fun `toString omits the state`() {
            assertFalse(ClassificationRequest.of(state(), spec).toString().contains("Refund"))
        }
    }

    @Nested
    inner class Json {

        @Test
        fun `a structured request writes its state in place of the input and reads back equal`() {
            val request = ClassificationRequest.of(state(), spec)
            assertEquals(structuredJson, mapper.writeValueAsString(request))
            val read = mapper.readValue(structuredJson, ClassificationRequest::class.java)
            assertEquals(request, read)
            assertEquals(spec, read.spec)
        }

        @Test
        fun `a text request still writes only its input and reads back equal`() {
            val request = ClassificationRequest.of("text", spec)
            val json = """{"input":"text","spec":$specJson}"""
            assertEquals(json, mapper.writeValueAsString(request))
            assertEquals(request, mapper.readValue(json, ClassificationRequest::class.java))
        }

        @Test
        fun `a structured request is written by a mapper that may not open private members`() {
            val closed = JsonMapper.builder().disable(MapperFeature.CAN_OVERRIDE_ACCESS_MODIFIERS).build()
            assertEquals(structuredJson, closed.writeValueAsString(ClassificationRequest.of(state(), spec)))
        }

        @Test
        fun `strict creator mappers read a text request and a structured one`() {
            val strict = listOf(
                JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).build(),
                JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES).build(),
            )
            for (m in strict) {
                assertEquals(
                    ClassificationRequest.of("text", spec),
                    m.readValue("""{"input":"text","spec":$specJson}""", ClassificationRequest::class.java),
                )
                assertEquals(ClassificationRequest.of(state(), spec), m.readValue(structuredJson, ClassificationRequest::class.java))
            }
        }

        @Test
        fun `a request with both an input and a state is rejected`() {
            val error = assertThrows(DatabindException::class.java) {
                mapper.readValue("""{"input":"text","state":$stateJson,"spec":$specJson}""", ClassificationRequest::class.java)
            }
            assertTrue(error.message!!.contains("'input' or 'state'")) { error.message }
        }

        @Test
        fun `a spec that is not one choice question is rejected`() {
            val twoQuestions = mapper.writeValueAsString(
                DecisionSpec.of(spec.question, Questions.named("urgent").proposition("Is it urgent?").build()),
            )
            assertThrows(DatabindException::class.java) {
                mapper.readValue("""{"state":$stateJson,"spec":$twoQuestions}""", ClassificationRequest::class.java)
            }
        }

        @Test
        fun `an unknown member is rejected`() {
            assertThrows(DatabindException::class.java) {
                mapper.readValue("""{"state":$stateJson,"spec":$specJson,"extra":1}""", ClassificationRequest::class.java)
            }
        }
    }

    @Nested
    inner class Classifying {

        @Test
        fun `a structured request reaches the service unchanged`() {
            val service = Recording()
            assertEquals(selected, service.classify(ClassificationRequest.of(state(), spec)))
            assertEquals(listOf(ClassificationRequest.of(state(), spec)), service.requests)
        }

        @Test
        fun `an observed decision service observes a structured classification`() {
            val stopped = Stopped()
            val registry = ObservationRegistry.create().apply { observationConfig().observationHandler(stopped) }
            val delegate = Recording()
            assertEquals(selected, ObservedDecisionService(delegate, registry).classify(ClassificationRequest.of(state(), spec)))
            assertEquals(listOf("embabel.ai.classification"), stopped.names)
            assertEquals(state(), delegate.requests.single().state)
        }

        @Test
        fun `an observed classification service observes a structured classification`() {
            val stopped = Stopped()
            val registry = ObservationRegistry.create().apply { observationConfig().observationHandler(stopped) }
            val delegate = Recording()
            assertEquals(selected, ObservedClassificationService(delegate, registry).classify(ClassificationRequest.of(state(), spec)))
            assertEquals(listOf("embabel.ai.classification"), stopped.names)
            assertEquals(state(), delegate.requests.single().state)
        }

        @Test
        fun `a structured ask gives a per-question service the state for a choice question`() {
            val service = Recording()
            val response = service.ask(DecisionRequest.of(state(), DecisionSpec.of(spec.question)))
            assertEquals(selected, response.answer(spec.question))
            assertEquals(state(), service.requests.single().state)
            assertEquals(stateJson, service.requests.single().input)
        }

        @Test
        fun `a text ask still gives a per-question service a text request`() {
            val service = Recording()
            service.ask("text", DecisionSpec.of(spec.question))
            assertNull(service.requests.single().state)
            assertEquals("text", service.requests.single().input)
        }
    }
}
