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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.ClassificationSpec
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.ChoiceQuestionSpec
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.PropositionQuestionSpec
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.Question
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.RatingQuestionSpec
import com.embabel.common.ai.decision.RatingResult
import com.embabel.common.ai.decision.UnsupportedDecisionException
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import java.util.concurrent.CancellationException

class AskTelemetryTest {

    private val sentinelInput = "SENTINEL-INPUT-5c21"
    private val sentinelInstruction = "SENTINEL-INSTRUCTION-e803"
    private val sentinelOption = "SENTINEL-OPTION-19fa"
    private val role = "support-triage"
    private val serviceName = "jev-latest"
    private val providerName = "TypeSafe"
    private val provenance = ModelProvenance("jev-latest", "TypeSafe")

    private val askKeys = setOf("operation", "outcome", "service", "provider", "question_count")
    private val providerKeys = setOf("operation", "outcome")

    // The meter handler adds its own `error` key to every timer: the error class name, or `none`.
    private val timerKeys = { keys: Set<String> -> keys + "error" }

    private val urgent: PropositionQuestionSpec =
        Questions.named("urgent").proposition("$sentinelInstruction Is this urgent?").build()

    private val department: ChoiceQuestionSpec = Questions.named("department")
        .choice("$sentinelInstruction Which department?")
        .option("billing", "$sentinelOption payments")
        .option("support", "$sentinelOption help")
        .build()

    private val anger: RatingQuestionSpec = Questions.named("anger")
        .rating("$sentinelInstruction How angry?")
        .level("calm", "$sentinelOption calm")
        .level("angry", "$sentinelOption angry")
        .build()

    private val questionNames = listOf("urgent", "department", "anger")
    private val departmentRequest = ClassificationRequest.of(sentinelInput, ClassificationSpec.of(department))
    private val spec = DecisionSpec.of(urgent, department, anger)
    private val request = DecisionRequest.of(sentinelInput, spec)

    private class Recorder : ObservationHandler<Observation.Context> {
        val stopped = mutableListOf<Observation.Context>()
        val errors = mutableListOf<Throwable>()

        // Each event with the ask's outcome at the moment the event arrived.
        val events = mutableListOf<Pair<Observation.Event, String?>>()

        override fun supportsContext(context: Observation.Context) = true
        override fun onError(context: Observation.Context) {
            errors += context.error!!
        }

        override fun onEvent(event: Observation.Event, context: Observation.Context) {
            events += event to context.getLowCardinalityKeyValue("outcome")?.value
        }

        override fun onStop(context: Observation.Context) {
            stopped += context
        }
    }

    private class Telemetry(vararg extra: ObservationHandler<Observation.Context>) {
        val recorder = Recorder()
        val meters = SimpleMeterRegistry()
        val registry: ObservationRegistry = ObservationRegistry.create().apply {
            extra.forEach { observationConfig().observationHandler(it) }
            observationConfig().observationHandler(recorder)
                .observationHandler(DefaultMeterObservationHandler(meters))
        }
        val observation = ServiceCallObservation(registry)

        fun timers(name: String): List<Meter> = meters.meters.filter { it.id.name == name && it.id.type == Meter.Type.TIMER }
    }

    private fun tags(context: Observation.Context): Map<String, String> =
        context.lowCardinalityKeyValues.associate { it.key to it.value }

    private fun complete(): DecisionResponse =
        DecisionResponse.builder(spec)
            .answer(urgent, PropositionResult.Answered(true, provenance))
            .answer(department, ClassificationResult.NoMatch(provenance))
            .answer(anger, RatingResult.Answered(provenance, selectedLevelId = "calm"))
            .build()

    private fun partial(): DecisionResponse =
        DecisionResponse.builder(spec)
            .answer(urgent, PropositionResult.Inconclusive(provenance))
            .answer(department, ClassificationResult.Selected("billing", provenance))
            .answer(anger, RatingResult.Failure(FailureReason.INVALID_RESPONSE))
            .build()

    private fun ask(telemetry: Telemetry, work: () -> DecisionResponse): DecisionResponse =
        telemetry.observation.ask(serviceName, providerName, request, work)

    // Throws a checked exception from a Kotlin lambda, as a Java provider can.
    private fun interrupted(): Nothing = throw InterruptedException("interrupted")

    @Nested
    inner class AskTags {

        private fun assertAsk(telemetry: Telemetry, outcome: String) {
            val context = telemetry.recorder.stopped.single { it.name == "embabel.ai.ask" }
            assertEquals(
                mapOf(
                    "operation" to "ask",
                    "outcome" to outcome,
                    "service" to serviceName,
                    "provider" to providerName,
                    "question_count" to "2-4",
                ),
                tags(context),
            )
            assertTrue(context.highCardinalityKeyValues.none())
            val keyValues = tags(context).flatMap { listOf(it.key, it.value) }.toTypedArray()
            assertEquals(1L, telemetry.meters.get("embabel.ai.ask").tags(*keyValues).timer().count())
            telemetry.timers("embabel.ai.ask").forEach { meter ->
                assertEquals(timerKeys(askKeys), meter.id.tags.map { it.key }.toSet())
            }
            assertNull(telemetry.registry.currentObservation)
        }

        @Test
        fun `complete response records complete`() {
            val telemetry = Telemetry()
            val response = complete()
            assertSame(response, ask(telemetry) { response })
            assertAsk(telemetry, "complete")
            assertTrue(telemetry.recorder.errors.isEmpty())
        }

        @Test
        fun `a failed answer makes the response partial`() {
            val telemetry = Telemetry()
            val response = partial()
            assertSame(response, ask(telemetry) { response })
            assertAsk(telemetry, "partial")
            assertTrue(telemetry.recorder.errors.isEmpty())
        }

        @Test
        fun `request failure records the outcome and a stackless error marker`() {
            val telemetry = Telemetry()
            val response = DecisionResponse.failed(spec, FailureReason.UNAVAILABLE)
            assertSame(response, ask(telemetry) { response })
            assertAsk(telemetry, "request_failure")
            val error = telemetry.recorder.errors.single()
            assertEquals("request_failure", error.message)
            assertNull(error.cause)
            assertTrue(error.stackTrace.isEmpty())
        }

        @Test
        fun `unsupported request records unsupported`() {
            val telemetry = Telemetry()
            val failure = UnsupportedDecisionException("Decision service 'jev-latest' cannot run the request")
            assertSame(failure, assertThrows<UnsupportedDecisionException> { ask(telemetry) { throw failure } })
            assertAsk(telemetry, "unsupported")
            assertEquals("unsupported", telemetry.recorder.errors.single().message)
        }

        @Test
        fun `other exceptions keep the exception label`() {
            val telemetry = Telemetry()
            val failure = IllegalStateException(sentinelInput)
            assertSame(failure, assertThrows<IllegalStateException> { ask(telemetry) { throw failure } })
            assertAsk(telemetry, "exception")
            assertFalse(telemetry.recorder.errors.single().toString().contains(sentinelInput))
        }

        @Test
        fun `interruption records interrupted and leaves the interrupt flag alone`() {
            val telemetry = Telemetry()
            assertThrows<InterruptedException> { ask(telemetry) { interrupted() } }
            assertFalse(Thread.currentThread().isInterrupted)
            assertAsk(telemetry, "interrupted")
        }

        @Test
        fun `cancellation records cancelled`() {
            val telemetry = Telemetry()
            assertThrows<CancellationException> { ask(telemetry) { throw CancellationException("stop") } }
            assertAsk(telemetry, "cancelled")
        }

        @Test
        fun `a one-question ask records the question count 1 and no execution_mode tag`() {
            val telemetry = Telemetry()
            val single = DecisionSpec.of(urgent)
            val response = DecisionResponse.builder(single)
                .answer(urgent, PropositionResult.Answered(false, provenance)).build()
            telemetry.observation.ask(serviceName, providerName, DecisionRequest.of(sentinelInput, single)) { response }
            val askTags = tags(telemetry.recorder.stopped.single())
            assertEquals("1", askTags["question_count"])
            assertFalse("execution_mode" in askTags)
        }

        @Test
        fun `question count buckets`() {
            val expected = mapOf(1 to "1", 2 to "2-4", 4 to "2-4", 5 to "5-16", 16 to "5-16", 17 to "17+")
            expected.forEach { (count, bucket) ->
                val telemetry = Telemetry()
                val questions: List<Question<*>> = (1..count).map { Questions.named("q$it").proposition("Is $it true?").build() }
                val many = DecisionSpec.of(questions)
                telemetry.observation.ask(serviceName, providerName, DecisionRequest.of(sentinelInput, many)) {
                    DecisionResponse.failed(many, FailureReason.UNAVAILABLE)
                }
                assertEquals(bucket, tags(telemetry.recorder.stopped.single())["question_count"]) { "count $count" }
            }
        }
    }

    @Nested
    inner class ProviderCalls {

        private fun outcomes(telemetry: Telemetry, operation: String): List<String> =
            telemetry.recorder.stopped.filter { tags(it)["operation"] == operation }.map { tags(it).getValue("outcome") }

        @Test
        fun `assess and ask_question_set share one key set under embabel ai decision`() {
            val telemetry = Telemetry()
            telemetry.observation.assess { PropositionResult.Answered(true, provenance) }
            telemetry.observation.questionSet { complete() }
            telemetry.observation.questionSet { partial() }
            telemetry.observation.questionSet { DecisionResponse.failed(spec, FailureReason.UNAVAILABLE) }
            assertThrows<UnsupportedDecisionException> {
                telemetry.observation.questionSet { throw UnsupportedDecisionException("no") }
            }
            assertThrows<InterruptedException> { telemetry.observation.questionSet { interrupted() } }
            telemetry.observation.rate { RatingResult.Inconclusive(provenance) }

            assertEquals(listOf("complete", "partial", "request_failure", "exception", "interrupted"),
                outcomes(telemetry, "ask_question_set"))
            val decisionTimers = telemetry.timers("embabel.ai.decision")
            assertEquals(setOf("assess", "ask_question_set", "rate"),
                decisionTimers.map { it.id.getTag("operation") }.toSet())
            decisionTimers.forEach { assertEquals(timerKeys(providerKeys), it.id.tags.map { tag -> tag.key }.toSet()) }
            telemetry.recorder.stopped.forEach {
                assertEquals("embabel.ai.decision", it.name)
                assertEquals(providerKeys, tags(it).keys)
            }
        }

        @Test
        fun `classify of a choice question validates the selection against its options`() {
            val telemetry = Telemetry()
            listOf(
                ClassificationResult.Selected("billing", provenance),
                ClassificationResult.NoMatch(provenance),
                ClassificationResult.Inconclusive(provenance),
                ClassificationResult.Failure(FailureReason.UNAVAILABLE),
            ).forEach { result -> assertSame(result, telemetry.observation.classify(departmentRequest) { result }) }
            assertThrows<IllegalArgumentException> {
                telemetry.observation.classify(departmentRequest) { ClassificationResult.Selected("SENTINEL-ID", provenance) }
            }
            assertEquals(listOf("selected", "no_match", "inconclusive", "failure", "invalid_response"),
                outcomes(telemetry, "classify"))
            assertTrue(telemetry.recorder.errors.none { it.toString().contains("SENTINEL-ID") })
        }

        @Test
        fun `rate uses answered, inconclusive and failure`() {
            val telemetry = Telemetry()
            listOf(
                RatingResult.Answered(provenance, selectedLevelId = "angry"),
                RatingResult.Inconclusive(provenance),
                RatingResult.Failure(FailureReason.UNAVAILABLE),
            ).forEach { result -> assertSame(result, telemetry.observation.rate { result }) }
            assertEquals(listOf("answered", "inconclusive", "failure"), outcomes(telemetry, "rate"))
        }

        @Test
        fun `provider calls inside an ask are children of the ask`() {
            val telemetry = Telemetry()
            ask(telemetry) {
                telemetry.observation.questionSet { complete() }
            }
            val child = telemetry.recorder.stopped.single { it.name == "embabel.ai.decision" }
            val parent = telemetry.recorder.stopped.single { it.name == "embabel.ai.ask" }
            assertSame(parent, child.parentObservation!!.contextView)
        }
    }

    @Nested
    inner class AnswerEvents {

        @Test
        fun `each answer becomes a counter with the ask's tags and a span event naming the question`() {
            val telemetry = Telemetry()
            ask(telemetry) { partial() }
            val askTags = tags(telemetry.recorder.stopped.single())
            val events = telemetry.recorder.events
            assertEquals(
                listOf("answer.proposition.inconclusive", "answer.choice.selected", "answer.rating.failure"),
                events.map { it.first.name },
            )
            assertEquals(
                listOf("urgent proposition inconclusive", "department choice selected", "anger rating failure"),
                events.map { it.first.contextualName },
            )
            // The ask's tags were final when each event arrived.
            events.forEach { assertEquals("partial", it.second) }
            listOf("proposition.inconclusive", "choice.selected", "rating.failure").forEach { suffix ->
                val counter = telemetry.meters.get("embabel.ai.ask.answer.$suffix").counter()
                assertEquals(1.0, counter.count())
                assertEquals(askTags, counter.id.tags.associate { it.key to it.value })
            }
        }

        @Test
        fun `choice no match counter carries the ask's tags`() {
            val telemetry = Telemetry()
            ask(telemetry) { complete() }
            val counter = telemetry.meters.get("embabel.ai.ask.answer.choice.no_match").counter()
            assertEquals(1.0, counter.count())
            assertEquals(tags(telemetry.recorder.stopped.single()), counter.id.tags.associate { it.key to it.value })
            assertNotNull(telemetry.meters.find("embabel.ai.ask.answer.proposition.answered").counter())
            assertNotNull(telemetry.meters.find("embabel.ai.ask.answer.rating.answered").counter())
        }

        @Test
        fun `exceptions record no answer events`() {
            val telemetry = Telemetry()
            assertThrows<IllegalStateException> { ask(telemetry) { throw IllegalStateException("boom") } }
            assertTrue(telemetry.recorder.events.isEmpty())
        }

        @Test
        fun `a handler that throws on an event does not change the result`() {
            val throwing = object : ObservationHandler<Observation.Context> {
                override fun supportsContext(context: Observation.Context) = true
                override fun onEvent(event: Observation.Event, context: Observation.Context) {
                    throw IllegalStateException(sentinelInput)
                }
            }
            val telemetry = Telemetry(throwing)
            val logger = LoggerFactory.getLogger(ServiceCallObservation::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val previous = logger.level
            logger.addAppender(appender)
            logger.level = Level.DEBUG
            try {
                val response = complete()
                assertSame(response, ask(telemetry) { response })
                val context = telemetry.recorder.stopped.single()
                assertEquals("complete", tags(context)["outcome"])
                assertNull(telemetry.registry.currentObservation)
                val warnings = appender.list.filter { it.level == Level.WARN }
                assertEquals(List(3) { "AI ask observation failed during record_event" }, warnings.map { it.formattedMessage })
                appender.list.forEach { event ->
                    assertNull(event.throwableProxy)
                    (questionNames + sentinelInput + sentinelInstruction).forEach {
                        assertFalse(event.formattedMessage.contains(it)) { event.formattedMessage }
                    }
                }
            } finally {
                logger.level = previous
                logger.detachAppender(appender)
                appender.stop()
            }
        }
    }

    @Nested
    inner class BoundedValues {

        @Test
        fun `no tag value holds a question name, role, input or instruction`() {
            val telemetry = Telemetry()
            ask(telemetry) {
                telemetry.observation.classify(departmentRequest) { ClassificationResult.Selected("billing", provenance) }
                telemetry.observation.rate { RatingResult.Answered(provenance, selectedLevelId = "calm") }
                partial()
            }
            assertThrows<IllegalStateException> { ask(telemetry) { throw IllegalStateException(sentinelInput) } }
            val forbidden = questionNames + role + sentinelInput + sentinelInstruction + sentinelOption
            val values = telemetry.meters.meters.flatMap { it.id.tags.map { tag -> tag.value } } +
                telemetry.recorder.stopped.flatMap { context ->
                    (context.lowCardinalityKeyValues + context.highCardinalityKeyValues).map { it.value }
                }
            assertTrue(values.isNotEmpty())
            values.forEach { value ->
                forbidden.forEach { assertFalse(value.contains(it)) { "tag value '$value' holds '$it'" } }
            }
        }
    }
}
