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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.spi.DecisionContentCapture
import com.embabel.common.ai.decision.spi.DecisionExecution
import com.embabel.common.ai.decision.spi.QuestionSetExecution
import com.embabel.common.ai.decision.support.NoOpDecisionService
import com.embabel.common.ai.decision.support.StubDecisionService
import com.embabel.common.ai.model.observation.ObservedDecisionService
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DatabindException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.util.EnumSet

class StructuredInputTest {

    private val mapper = JsonMapper.builder().build()

    private val provenance = ModelProvenance("model-a", "provider-a")

    private val urgent = Questions.named("urgent").proposition("Is `intent.kind` urgent?").build()

    private val spec = DecisionSpec.of(urgent)

    // Deliberately not in alphabetical order, so a sorted rendering would show up.
    private val stateJson = """{"intent":{"kind":"COMPANIONS","subject":"Tomato"},"rule":"Declared only.","facts":[{"subject":"Tomato","object":"Basil"}]}"""

    private fun state(): ObjectNode = mapper.readTree(stateJson) as ObjectNode

    private val specJson = """{"questions":[{"kind":"proposition","name":"urgent","instructions":"Is `intent.kind` urgent?"}]}"""

    /** Answers the whole request in one call and keeps what it was given. */
    private class Recording : DecisionService, QuestionSetExecution {
        val requests = mutableListOf<DecisionRequest>()

        override val name = "recording"
        override val provider = "test"

        override fun capabilities(): DecisionCapabilities = DecisionCapabilities.of(EnumSet.allOf(QuestionKind::class.java))

        override fun classify(request: ClassificationRequest): ClassificationResult = error("not used")

        override fun assess(request: PropositionRequest): PropositionResult = error("not used")

        override fun askQuestionSet(request: DecisionRequest): DecisionResponse {
            requests += request
            return DecisionResponse.failed(request.spec, FailureReason.UNAVAILABLE)
        }
    }

    @Nested
    inner class Request {

        @Test
        fun `a structured request holds the state and its JSON text as the input`() {
            val request = DecisionRequest.of(state(), spec)
            assertEquals(state(), request.state)
            assertEquals(stateJson, request.input)
            assertEquals(spec, request.spec)
        }

        @Test
        fun `a structured request can be built from questions`() {
            assertEquals(DecisionRequest.of(state(), spec), DecisionRequest.of(state(), urgent))
        }

        @Test
        fun `an array is a valid state and keeps its element order`() {
            val array = mapper.readTree("""[{"b":1,"a":2},"second"]""")
            val request = DecisionRequest.of(array, spec)
            assertEquals(array, request.state)
            assertEquals("""[{"b":1,"a":2},"second"]""", request.input)
        }

        @Test
        fun `a text request has no state`() {
            assertNull(DecisionRequest.of("text", spec).state)
        }

        @Test
        fun `a state that is neither an object nor an array is rejected`() {
            for (scalar in listOf(mapper.readTree("\"text\""), mapper.readTree("3"), mapper.nullNode())) {
                val error = assertThrows(IllegalArgumentException::class.java) { DecisionRequest.of(scalar, spec) }
                assertTrue(error.message!!.contains("object or array")) { error.message }
            }
        }

        @Test
        fun `changing the node given to of does not change the request`() {
            val given = state()
            val request = DecisionRequest.of(given, spec)
            given.put("added", true)
            assertEquals(state(), request.state)
            assertEquals(stateJson, request.input)
        }

        @Test
        fun `changing the node a request returns does not change the request`() {
            val request = DecisionRequest.of(state(), spec)
            (request.state as ObjectNode).put("added", true)
            assertEquals(state(), request.state)
        }

        @Test
        fun `a binary leaf is captured as text, so later changes to the bytes do not reach the request`() {
            val bytes = "AB".toByteArray()
            val request = DecisionRequest.of(mapper.createObjectNode().put("blob", bytes), spec)
            bytes[0] = 'C'.code.toByte()
            assertEquals("""{"blob":"QUI="}""", request.input)
            assertEquals("""{"blob":"QUI="}""", request.state.toString())
            assertTrue(request.state!!.get("blob").isString)
        }

        @Test
        fun `an embedded object is captured as plain JSON`() {
            val held = linkedMapOf("b" to 1, "a" to 2)
            val request = DecisionRequest.of(mapper.createObjectNode().putPOJO("held", held), spec)
            held["c"] = 3
            assertEquals("""{"held":{"b":1,"a":2}}""", request.input)
            assertTrue(request.state!!.get("held").isObject)
        }

        @Test
        fun `a decimal keeps its digits`() {
            val request = DecisionRequest.of(mapper.createObjectNode().put("price", BigDecimal("1.10")), spec)
            assertEquals("""{"price":1.10}""", request.input)
            assertEquals("""{"price":1.10}""", request.state.toString())
        }

        @Test
        fun `a state that cannot be written as JSON is rejected without quoting it`() {
            class Leaky {
                @Suppress("unused")
                val value: String get() = error("SECRET-STATE")
            }
            val error = assertThrows(IllegalArgumentException::class.java) {
                DecisionRequest.of(mapper.createObjectNode().putPOJO("held", Leaky()), spec)
            }
            assertFalse(error.message!!.contains("SECRET-STATE")) { error.message }
            assertNull(error.cause)
        }

        @Test
        fun `structured requests with equal state and spec are equal`() {
            assertEquals(DecisionRequest.of(state(), spec), DecisionRequest.of(state(), spec))
            assertEquals(DecisionRequest.of(state(), spec).hashCode(), DecisionRequest.of(state(), spec).hashCode())
        }

        @Test
        fun `a structured request is not equal to a text request holding the same JSON`() {
            assertNotEquals(DecisionRequest.of(state(), spec), DecisionRequest.of(stateJson, spec))
            assertNotEquals(DecisionRequest.of(stateJson, spec), DecisionRequest.of(state(), spec))
        }

        @Test
        fun `toString omits the state`() {
            assertFalse(DecisionRequest.of(state(), spec).toString().contains("Tomato"))
        }
    }

    @Nested
    inner class Json {

        private val structuredJson = """{"state":$stateJson,"spec":$specJson}"""

        @Test
        fun `a structured request writes its state in place of the input and reads back equal`() {
            val request = DecisionRequest.of(state(), spec)
            assertEquals(structuredJson, mapper.writeValueAsString(request))
            assertEquals(request, mapper.readValue(structuredJson, DecisionRequest::class.java))
        }

        @Test
        fun `a text request still writes only its input`() {
            assertEquals("""{"input":"text","spec":$specJson}""", mapper.writeValueAsString(DecisionRequest.of("text", spec)))
        }

        @Test
        fun `requests write the same JSON on a mapper that may not open private members`() {
            val closed = JsonMapper.builder().disable(MapperFeature.CAN_OVERRIDE_ACCESS_MODIFIERS).build()
            assertEquals("""{"input":"text","spec":$specJson}""", closed.writeValueAsString(DecisionRequest.of("text", spec)))
            assertEquals(structuredJson, closed.writeValueAsString(DecisionRequest.of(state(), spec)))
        }

        // Mappers that refuse a creator property that is missing or null. Both are off by default.
        private val strict = listOf(
            JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).build(),
            JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES).build(),
        )

        @Test
        fun `strict creator mappers still read a text request and a structured one`() {
            for (m in strict) {
                assertEquals(
                    DecisionRequest.of("text", spec),
                    m.readValue("""{"input":"text","spec":$specJson}""", DecisionRequest::class.java),
                )
                assertEquals(DecisionRequest.of(state(), spec), m.readValue(structuredJson, DecisionRequest::class.java))
            }
        }

        @Test
        fun `strict creator mappers still read a classification request`() {
            val json = """{"input":"text","spec":{"questions":[{"kind":"choice","name":"team","instructions":"Which?","options":[{"id":"a","description":"A"}]}]}}"""
            for (m in strict) {
                assertEquals("text", m.readValue(json, ClassificationRequest::class.java).input)
            }
        }

        @Test
        fun `an explicit null state reads as a text request`() {
            assertEquals(
                DecisionRequest.of("text", spec),
                mapper.readValue("""{"input":"text","state":null,"spec":$specJson}""", DecisionRequest::class.java),
            )
        }

        @Test
        fun `a request with both an input and a state is rejected`() {
            val error = assertThrows(DatabindException::class.java) {
                mapper.readValue("""{"input":"text","state":$stateJson,"spec":$specJson}""", DecisionRequest::class.java)
            }
            assertTrue(error.message!!.contains("'input' or 'state'")) { error.message }
        }

        @Test
        fun `a scalar state is rejected`() {
            assertThrows(DatabindException::class.java) {
                mapper.readValue("""{"state":"text","spec":$specJson}""", DecisionRequest::class.java)
            }
        }

        @Test
        fun `decimals in a state read from JSON keep their digits on a default mapper`() {
            val json = """{"state":{"x":0.12345678901234567890123456789,"big":1E+400},"spec":$specJson}"""
            val request = mapper.readValue(json, DecisionRequest::class.java)
            val state = request.state!!
            assertEquals(BigDecimal("0.12345678901234567890123456789"), state.get("x").decimalValue())
            assertEquals(BigDecimal("1E+400"), state.get("big").decimalValue())
        }

        @Test
        fun `exact decimals survive a write then read on a default mapper`() {
            val json = """{"state":{"x":0.12345678901234567890123456789,"big":1E+400},"spec":$specJson}"""
            val request = mapper.readValue(json, DecisionRequest::class.java)
            assertEquals(request, mapper.readValue(mapper.writeValueAsString(request), DecisionRequest::class.java))
        }

        @Test
        fun `a request inside another object reads, and so does the property after it`() {
            val holder = mapper.readValue("""{"request":$structuredJson,"count":2}""", RequestHolder::class.java)
            assertEquals(DecisionRequest.of(state(), spec), holder.request)
            assertEquals(2, holder.count)
        }

        @Test
        fun `decimals keep their digits in a request inside another object`() {
            val json = """{"request":{"state":{"x":0.12345678901234567890123456789,"big":1E+400},"spec":$specJson},"count":2}"""
            val holder = mapper.readValue(json, RequestHolder::class.java)
            val state = holder.request!!.state!!
            assertEquals(BigDecimal("0.12345678901234567890123456789"), state.get("x").decimalValue())
            assertEquals(BigDecimal("1E+400"), state.get("big").decimalValue())
            assertEquals(2, holder.count)
        }

        @Test
        fun `a request read from a parser already past its opening brace reads every member`() {
            mapper.createParser(structuredJson).use { parser ->
                parser.nextToken()
                parser.nextToken()
                assertEquals(DecisionRequest.of(state(), spec), mapper.readValue(parser, DecisionRequest::class.java))
            }
        }

        // Refuses a JSON object that names the same member twice. Off by default.
        private val noDuplicates = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).build()

        @Test
        fun `a duplicate state is rejected when the mapper refuses duplicate keys`() {
            assertThrows(DatabindException::class.java) {
                noDuplicates.readValue("""{"state":{"x":1},"state":{"x":2},"spec":$specJson}""", DecisionRequest::class.java)
            }
        }

        @Test
        fun `a duplicate key inside the state is rejected when the mapper refuses duplicate keys`() {
            assertThrows(DatabindException::class.java) {
                noDuplicates.readValue("""{"state":{"x":1,"x":2},"spec":$specJson}""", DecisionRequest::class.java)
            }
        }

        @Test
        fun `a duplicate key inside the state is rejected by a parser that checks for duplicates`() {
            val strictParser = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
            assertThrows(JacksonException::class.java) {
                strictParser.readValue("""{"state":{"x":1,"x":2},"spec":$specJson}""", DecisionRequest::class.java)
            }
        }
    }

    @Nested
    inner class Asking {

        @Test
        fun `ask with a state hands a question-set service the structured request`() {
            val service = Recording()
            service.ask(DecisionRequest.of(state(), spec))
            assertEquals(listOf(DecisionRequest.of(state(), spec)), service.requests)
        }

        @Test
        fun `an observed service passes the state through to its delegate`() {
            val delegate = Recording()
            ObservedDecisionService(delegate, ObservationRegistry.create()).ask(DecisionRequest.of(state(), spec))
            assertEquals(state(), delegate.requests.single().state)
        }

        @Test
        fun `a per-question service gets the state as JSON text`() {
            val asked = mutableListOf<PropositionRequest>()
            val service = object : DecisionService {
                override val name = "per-question"
                override val provider = "test"

                override fun classify(request: ClassificationRequest): ClassificationResult = error("not used")

                override fun assess(request: PropositionRequest): PropositionResult {
                    asked += request
                    return PropositionResult.Answered(true, provenance)
                }
            }
            val response = service.ask(DecisionRequest.of(state(), spec))
            assertEquals(stateJson, asked.single().input)
            assertEquals(PropositionResult.Answered(true, provenance), response.answer(urgent))
        }

        @Test
        fun `a stub answers a structured request from its script`() {
            val answer = PropositionResult.Answered(false, provenance)
            val stub = StubDecisionService.builder("stub").proposition("urgent", answer).build()
            assertEquals(answer, stub.ask(DecisionRequest.of(state(), spec)).answer(urgent))
        }

        @Test
        fun `the no-op service fails a structured request as unavailable`() {
            assertEquals(FailureReason.UNAVAILABLE, NoOpDecisionService().ask(DecisionRequest.of(state(), spec)).requestFailure)
        }
    }

    @Nested
    inner class Logging {

        private fun trace(contentCapture: Boolean, block: () -> Unit): List<String> {
            val logger = LoggerFactory.getLogger(DecisionExecution::class.java) as Logger
            val previous = logger.level
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            logger.level = Level.TRACE
            if (contentCapture) DecisionContentCapture.enable() else DecisionContentCapture.disable()
            try {
                block()
                return appender.list.map { it.formattedMessage }
            } finally {
                DecisionContentCapture.disable()
                logger.level = previous
                logger.detachAppender(appender)
                appender.stop()
            }
        }

        @Test
        fun `content capture logs the state as JSON text`() {
            val lines = trace(contentCapture = true) { Recording().ask(DecisionRequest.of(state(), spec)) }
            assertTrue(lines.any { it.contains("input=$stateJson") }) { "Lines: $lines" }
        }

        @Test
        fun `nothing of the state is logged with content capture off`() {
            val lines = trace(contentCapture = false) { Recording().ask(DecisionRequest.of(state(), spec)) }
            assertTrue(lines.none { it.contains("Tomato") }) { "Lines: $lines" }
        }
    }
}

/** An object that holds a request with another property after it, for reading a request nested in JSON. */
private class RequestHolder {
    var request: DecisionRequest? = null
    var count: Int = 0
}
