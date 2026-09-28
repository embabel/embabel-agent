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
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.ChoiceQuestionSpec
import com.embabel.common.ai.decision.DecisionAnswer
import com.embabel.common.ai.decision.DecisionCapabilities
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.PropositionQuestionSpec
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.RatingQuestionSpec
import com.embabel.common.ai.decision.RatingResult
import com.embabel.common.ai.decision.UnsupportedDecisionException
import com.embabel.common.ai.decision.spi.DecisionContentCapture
import com.embabel.common.ai.decision.spi.NativeQuestionSetExecution
import com.embabel.common.ai.decision.spi.PropositionAssessment
import com.embabel.common.ai.decision.spi.RatingAssessment
import com.embabel.common.ai.model.DecisionService
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.ResourceLock
import org.slf4j.LoggerFactory
import java.util.EnumSet

@ResourceLock("DecisionContentCapture")
class AskObservationTest {

    private val sentinelInput = "SENTINEL-INPUT-7a42"
    private val sentinelInstruction = "SENTINEL-INSTRUCTION-c9d1"
    private val sentinelOption = "SENTINEL-OPTION-3e6b"
    private val sentinels = listOf(sentinelInput, sentinelInstruction, sentinelOption)

    private val provenance = ModelProvenance("model-a", "provider-a")

    private val urgent: PropositionQuestionSpec =
        Questions.named("urgent").proposition("$sentinelInstruction Is it urgent?").build()

    private val team: ChoiceQuestionSpec = Questions.named("team")
        .choice("$sentinelInstruction Which team?")
        .option("billing", "$sentinelOption payments")
        .option("support", "$sentinelOption help")
        .build()

    private val anger: RatingQuestionSpec = Questions.named("anger")
        .rating("$sentinelInstruction How angry?")
        .level("calm", "$sentinelOption calm")
        .level("angry", "$sentinelOption angry")
        .build()

    private val allThree = DecisionRequest.of(sentinelInput, urgent, team, anger)
    private val oneProposition = DecisionRequest.of(sentinelInput, urgent)
    private val oneRating = DecisionRequest.of(sentinelInput, anger)

    private val allKinds: Set<QuestionKind> = EnumSet.allOf(QuestionKind::class.java)

    private class Recorder : ObservationHandler<Observation.Context> {
        val stopped = mutableListOf<Observation.Context>()
        val events = mutableListOf<Observation.Event>()

        override fun supportsContext(context: Observation.Context) = true

        override fun onEvent(event: Observation.Event, context: Observation.Context) {
            events += event
        }

        override fun onStop(context: Observation.Context) {
            stopped += context
        }

        fun named(name: String) = stopped.filter { it.name == name }

        fun ask() = named("embabel.ai.ask").single()

        fun providerCalls() = stopped.filter { it.name != "embabel.ai.ask" }
    }

    private class Telemetry {
        val recorder = Recorder()
        val registry: ObservationRegistry = ObservationRegistry.create().apply {
            observationConfig().observationHandler(recorder)
        }
    }

    private fun tags(context: Observation.Context): Map<String, String> =
        context.lowCardinalityKeyValues.associate { it.key to it.value }

    // Legacy delegate: only the base API, with a call log.
    private open class Legacy(override val name: String = "legacy-service") : DecisionService {
        override val provider = "legacy-provider"
        val calls = mutableListOf<String>()
        var onAssess: () -> PropositionResult = { PropositionResult.Answered(true, ModelProvenance("m", "p")) }

        override fun classify(request: ClassificationRequest): ClassificationResult {
            calls += "classify"
            return ClassificationResult.NoMatch(ModelProvenance("m", "p"))
        }

        override fun assess(request: PropositionRequest): PropositionResult {
            calls += "assess"
            return onAssess()
        }
    }

    private class Hooked : Legacy("hooked-service"), RatingAssessment {
        var onClassify: () -> ClassificationResult = { ClassificationResult.Selected("billing", ModelProvenance("m", "p")) }
        var onRate: () -> RatingResult = { RatingResult.Answered(ModelProvenance("m", "p"), selectedLevelId = "calm") }
        val questions = mutableListOf<String>()

        override fun classify(request: ClassificationRequest): ClassificationResult {
            calls += "classify"
            questions += request.spec.question.name
            return onClassify()
        }

        override fun rate(input: String, question: RatingQuestionSpec): RatingResult {
            calls += "rate"
            questions += question.name
            return onRate()
        }
    }

    private inner class Native : Legacy("native-service"), NativeQuestionSetExecution {
        var onNative: (DecisionRequest) -> DecisionResponse = { request ->
            DecisionResponse.builder(request.spec)
                .answer(urgent, PropositionResult.Answered(true, provenance))
                .answer(team, ClassificationResult.Selected("support", provenance))
                .answer(anger, RatingResult.Answered(provenance, selectedLevelId = "angry"))
                .build()
        }

        override fun capabilities(): DecisionCapabilities = DecisionCapabilities.of(allKinds)

        override fun askNative(request: DecisionRequest): DecisionResponse {
            calls += "askNative"
            return onNative(request)
        }
    }

    // Claims capabilities whose hooks it does not implement.
    private class Lying(private val claimed: DecisionCapabilities) : Legacy("lying-service") {
        override fun capabilities(): DecisionCapabilities = claimed
    }

    private class OverridingAsk : Legacy("overriding-service") {
        var overrideCalls = 0

        override fun ask(request: DecisionRequest): DecisionResponse {
            overrideCalls++
            return DecisionResponse.failed(request.spec, FailureReason.UNAVAILABLE)
        }
    }

    // Answers proposition questions through the question hook.
    private class QuestionAssessing : Legacy("question-service"), PropositionAssessment {
        val questions = mutableListOf<String>()

        override fun assess(input: String, question: PropositionQuestionSpec): PropositionResult {
            calls += "assessQuestion"
            questions += question.name
            return PropositionResult.Answered(false, ModelProvenance("m", "p"))
        }
    }

    // Throws a checked exception from a Kotlin lambda, as a Java provider can.
    private fun interrupted(): Nothing = throw InterruptedException("interrupted")

    @Nested
    inner class Counts {

        @Test
        fun `native ask records one ask observation with one ask_native child`() {
            val telemetry = Telemetry()
            val delegate = Native()
            val response = ObservedDecisionService(delegate, telemetry.registry).ask(allThree)
            assertEquals(listOf("askNative"), delegate.calls)
            val ask = telemetry.recorder.ask()
            assertEquals("complete", tags(ask)["outcome"])
            assertFalse("execution_mode" in tags(ask))
            val native = telemetry.recorder.providerCalls().single()
            assertEquals("embabel.ai.decision", native.name)
            assertEquals(mapOf("operation" to "ask_native", "outcome" to "complete"), tags(native))
            assertSame(ask, native.parentObservation?.contextView)
            assertEquals(3, telemetry.recorder.events.size)
            assertNull(telemetry.registry.currentObservation)
        }

        @Test
        fun `per-question ask of three questions records one ask and three provider observations`() {
            val telemetry = Telemetry()
            val delegate = Hooked()
            val response = ObservedDecisionService(delegate, telemetry.registry).ask(allThree)
            assertEquals(listOf("assess", "classify", "rate"), delegate.calls)
            val ask = telemetry.recorder.ask()
            assertEquals("complete", tags(ask)["outcome"])
            val providers = telemetry.recorder.providerCalls()
            assertEquals(listOf("assess", "classify", "rate"), providers.map { tags(it)["operation"] })
            assertEquals(
                listOf("embabel.ai.decision", "embabel.ai.classification", "embabel.ai.decision"),
                providers.map { it.name },
            )
            providers.forEach { assertSame(ask, it.parentObservation?.contextView) }
            assertEquals(
                listOf("answer.proposition.answered", "answer.choice.selected", "answer.rating.answered"),
                telemetry.recorder.events.map { it.name },
            )
        }

        @Test
        fun `unsupported ask records one ask with outcome unsupported and no provider observation`() {
            val telemetry = Telemetry()
            val delegate = Legacy()

            assertThrows<UnsupportedDecisionException> {
                ObservedDecisionService(delegate, telemetry.registry).ask(oneRating)
            }

            assertEquals("unsupported", tags(telemetry.recorder.ask())["outcome"])
            assertTrue(telemetry.recorder.providerCalls().isEmpty())
            assertTrue(delegate.calls.isEmpty())
        }

        @Test
        fun `every ask overload goes through the decorator`() {
            val telemetry = Telemetry()
            val delegate = Legacy()
            val observed = ObservedDecisionService(delegate, telemetry.registry)

            observed.ask(sentinelInput, DecisionSpec.of(urgent))
            observed.ask(oneProposition)

            assertEquals(2, telemetry.recorder.named("embabel.ai.ask").size)
            assertEquals(2, telemetry.recorder.named("embabel.ai.decision").size)
            assertEquals(listOf("assess", "assess"), delegate.calls)
        }

        @Test
        fun `a delegate's own ask override is not called through the decorator`() {
            val telemetry = Telemetry()
            val delegate = OverridingAsk()
            val observed = ObservedDecisionService(delegate, telemetry.registry)

            observed.ask(sentinelInput, DecisionSpec.of(urgent))
            val response = observed.ask(oneProposition)

            assertEquals(0, delegate.overrideCalls)
            assertNull(response.requestFailure)
            assertEquals(listOf("assess", "assess"), delegate.calls)
        }

        @Test
        fun `a proposition hook delegate is called per question inside assess observations`() {
            val telemetry = Telemetry()
            val delegate = QuestionAssessing()
            val second = Questions.named("second").proposition("$sentinelInstruction Is it second?").build()

            ObservedDecisionService(delegate, telemetry.registry).ask(DecisionRequest.of(sentinelInput, urgent, second))

            assertEquals(listOf("assessQuestion", "assessQuestion"), delegate.calls)
            assertEquals(listOf("urgent", "second"), delegate.questions)
            val providers = telemetry.recorder.providerCalls()
            assertEquals(listOf("assess", "assess"), providers.map { tags(it)["operation"] })
            providers.forEach { assertSame(telemetry.recorder.ask(), it.parentObservation?.contextView) }
        }
    }

    @Nested
    inner class Capabilities {

        @Test
        fun `capabilities are the delegate's`() {
            val legacy = Legacy()
            val hooked = Hooked()
            val native = Native()
            assertEquals(legacy.capabilities(), ObservedDecisionService(legacy).capabilities())
            assertEquals(hooked.capabilities(), ObservedDecisionService(hooked).capabilities())
            assertEquals(native.capabilities(), ObservedDecisionService(native).capabilities())
            assertEquals(
                setOf(QuestionKind.PROPOSITION, QuestionKind.CHOICE, QuestionKind.RATING),
                ObservedDecisionService(hooked).capabilities().questionKinds,
            )
        }
    }

    @Nested
    inner class Guards {

        @Test
        fun `a delegate claiming every kind without the rating hook throws before any provider call`() {
            val telemetry = Telemetry()
            val delegate = Lying(DecisionCapabilities.of(allKinds))

            val thrown = assertThrows<IllegalStateException> {
                ObservedDecisionService(delegate, telemetry.registry).ask(allThree)
            }

            assertTrue(thrown.message!!.contains("RatingAssessment"))
            assertTrue(thrown.message!!.contains("lying-service"))
            assertEquals("exception", tags(telemetry.recorder.ask())["outcome"])
            assertTrue(telemetry.recorder.providerCalls().isEmpty())
            assertTrue(delegate.calls.isEmpty())
        }

        @Test
        fun `a delegate claiming RATING without the hook throws before any provider call`() {
            val telemetry = Telemetry()
            val delegate = Lying(DecisionCapabilities.of(EnumSet.of(QuestionKind.PROPOSITION, QuestionKind.RATING)))

            val thrown = assertThrows<IllegalStateException> {
                ObservedDecisionService(delegate, telemetry.registry).ask(DecisionRequest.of(sentinelInput, urgent, anger))
            }

            assertTrue(thrown.message!!.contains("RatingAssessment"))
            assertTrue(telemetry.recorder.providerCalls().isEmpty())
            assertTrue(delegate.calls.isEmpty())
        }

        @Test
        fun `stacked decorators over a lying delegate throw before any provider observation`() {
            val telemetry = Telemetry()
            val delegate = Lying(DecisionCapabilities.of(allKinds))
            val stacked = ObservedDecisionService(ObservedDecisionService(delegate, telemetry.registry), telemetry.registry)

            val thrown = assertThrows<IllegalStateException> { stacked.ask(allThree) }

            assertTrue(thrown.message!!.contains("lying-service"))
            assertTrue(telemetry.recorder.providerCalls().isEmpty())
            assertTrue(delegate.calls.isEmpty())
        }

        @Test
        fun `hook methods called directly on a decorator of a delegate without the hook throw before any observation`() {
            val telemetry = Telemetry()
            val delegate = Legacy()
            val stacked = ObservedDecisionService(ObservedDecisionService(delegate, telemetry.registry), telemetry.registry)

            for (observed in listOf(ObservedDecisionService(delegate, telemetry.registry), stacked)) {
                val native = assertThrows<IllegalStateException> { observed.askNative(allThree) }
                assertTrue(native.message!!.contains("NativeQuestionSetExecution"))
                assertTrue(native.message!!.contains("legacy-service"))
                val rating = assertThrows<IllegalStateException> { observed.rate(sentinelInput, anger) }
                assertTrue(rating.message!!.contains("RatingAssessment"))
                val proposition = assertThrows<IllegalStateException> { observed.assess(sentinelInput, urgent) }
                assertTrue(proposition.message!!.contains("PropositionAssessment"))
            }
            assertTrue(telemetry.recorder.stopped.isEmpty())
            assertTrue(delegate.calls.isEmpty())
        }
    }

    @Nested
    inner class Failures {

        @Test
        fun `a choice outside the options fails only that question`() {
            val telemetry = Telemetry()
            val delegate = Hooked().apply { onClassify = { ClassificationResult.Selected("marketing", provenance) } }

            val response = ObservedDecisionService(delegate, telemetry.registry).ask(allThree)

            assertEquals(listOf("assess", "classify", "rate"), delegate.calls)
            val choice = response.answers.filterIsInstance<DecisionAnswer.Choice>().single()
            assertEquals(ClassificationResult.Failure(FailureReason.INVALID_RESPONSE), choice.outcome)
            assertInstanceOf(PropositionResult.Answered::class.java, response.answers.filterIsInstance<DecisionAnswer.Proposition>().single().outcome)
            assertInstanceOf(RatingResult.Answered::class.java, response.answers.filterIsInstance<DecisionAnswer.Rating>().single().outcome)
            assertEquals("partial", tags(telemetry.recorder.ask())["outcome"])
            val classify = telemetry.recorder.named("embabel.ai.classification").single()
            assertEquals(mapOf("operation" to "classify", "outcome" to "invalid_response"), tags(classify))
        }

        @Test
        fun `an IllegalArgumentException from rate fails only that question`() {
            val telemetry = Telemetry()
            val delegate = Hooked().apply { onRate = { throw IllegalArgumentException("level out of range") } }

            val response = ObservedDecisionService(delegate, telemetry.registry).ask(allThree)

            val rating = response.answers.filterIsInstance<DecisionAnswer.Rating>().single()
            assertEquals(RatingResult.Failure(FailureReason.INVALID_RESPONSE), rating.outcome)
            assertInstanceOf(ClassificationResult.Selected::class.java, response.answers.filterIsInstance<DecisionAnswer.Choice>().single().outcome)
            assertEquals("partial", tags(telemetry.recorder.ask())["outcome"])
        }

        @Test
        fun `interruption records interrupted and rethrows with the flag set`() {
            val telemetry = Telemetry()
            val delegate = Hooked().apply {
                onClassify = {
                    Thread.currentThread().interrupt()
                    interrupted()
                }
            }

            try {
                assertThrows<InterruptedException> {
                    ObservedDecisionService(delegate, telemetry.registry).ask(allThree)
                }
                assertTrue(Thread.currentThread().isInterrupted)
            } finally {
                Thread.interrupted()
            }

            assertEquals(listOf("assess", "classify"), delegate.calls)
            assertEquals("interrupted", tags(telemetry.recorder.ask())["outcome"])
            assertEquals("interrupted", tags(telemetry.recorder.named("embabel.ai.classification").single())["outcome"])
        }
    }

    @Nested
    inner class Diagnostics {

        private val loggerNames = listOf(
            "com.embabel.common.ai.decision.spi.DecisionExecution",
            "com.embabel.common.ai.model.observation.ServiceCallObservation",
        )

        private fun capture(level: Level, block: () -> Unit): List<ILoggingEvent> {
            val loggers = loggerNames.map { LoggerFactory.getLogger(it) as Logger }
            val previous = loggers.map { it.level }
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            loggers.forEach {
                it.addAppender(appender)
                it.level = level
            }
            DecisionContentCapture.disable()
            try {
                block()
                return appender.list.toList()
            } finally {
                loggers.zip(previous).forEach { (logger, level) ->
                    logger.level = level
                    logger.detachAppender(appender)
                }
                appender.stop()
            }
        }

        private fun List<ILoggingEvent>.at(level: Level) = filter { it.level == level }.map { it.formattedMessage }

        private fun assertContains(line: String, vararg fragments: String) {
            for (fragment in fragments) {
                assertTrue(line.contains(fragment)) { "Expected '$fragment' in: $line" }
            }
        }

        private fun assertNoPayload(events: List<ILoggingEvent>, telemetry: Telemetry) {
            val texts = events.map { it.formattedMessage } +
                telemetry.recorder.stopped.flatMap { context ->
                    context.lowCardinalityKeyValues.map { it.value } +
                        context.highCardinalityKeyValues.map { it.value } +
                        listOfNotNull(context.contextualName)
                } +
                telemetry.recorder.events.flatMap { listOf(it.name, it.contextualName) }
            for (text in texts) {
                for (sentinel in sentinels) {
                    assertFalse(text.contains(sentinel)) { "Payload '$sentinel' in: $text" }
                }
            }
        }

        @Test
        fun `a request failure through the decorator logs WARN with service, reason and elapsed time`() {
            val telemetry = Telemetry()
            val delegate = Native().apply {
                onNative = { DecisionResponse.failed(it.spec, FailureReason.UNAVAILABLE) }
            }

            val events = capture(Level.TRACE) { ObservedDecisionService(delegate, telemetry.registry).ask(allThree) }

            val warn = events.at(Level.WARN).single()
            assertContains(warn, "service=native-service", "requestFailure=UNAVAILABLE", "elapsedMs=")
            assertFalse(warn.contains("mode="))
            assertEquals("request_failure", tags(telemetry.recorder.ask())["outcome"])
            assertNoPayload(events, telemetry)
        }

        @Test
        fun `an ask through the decorator logs its start at DEBUG with no payload`() {
            val telemetry = Telemetry()

            val events = capture(Level.DEBUG) {
                ObservedDecisionService(Hooked(), telemetry.registry).ask(allThree)
            }

            val started = events.at(Level.DEBUG).single { it.startsWith("Decision ask started") }
            assertContains(started, "service=hooked-service", "questions=3")
            assertFalse(started.contains("mode="))
            assertTrue(events.at(Level.WARN).isEmpty())
            assertNoPayload(events, telemetry)
        }

        @Test
        fun `an answer anomaly and a partial response log WARN naming the question and no payload`() {
            val telemetry = Telemetry()
            val delegate = Hooked().apply { onClassify = { ClassificationResult.Selected("marketing", provenance) } }

            val events = capture(Level.TRACE) {
                ObservedDecisionService(delegate, telemetry.registry).ask(allThree)
            }

            val warns = events.at(Level.WARN)
            assertContains(warns.single { it.contains("anomaly") }, "service=hooked-service", "question='team'", "OUT_OF_DOMAIN")
            assertContains(
                warns.single { it.contains("partially failed") },
                "service=hooked-service", "'team' INVALID_RESPONSE", "elapsedMs=",
            )
            assertFalse(warns.any { it.contains("marketing") })
            assertNoPayload(events, telemetry)
        }

        @Test
        fun `a guard failure throws a message with cause and remedy and no payload`() {
            val telemetry = Telemetry()
            val delegate = Legacy()

            val thrown = assertThrows<IllegalStateException> {
                ObservedDecisionService(delegate, telemetry.registry).rate(sentinelInput, anger)
            }

            assertContains(thrown.message!!, "legacy-service", "RatingAssessment", "Implement RatingAssessment")
            sentinels.forEach { assertFalse(thrown.message!!.contains(it)) }
        }
    }
}
