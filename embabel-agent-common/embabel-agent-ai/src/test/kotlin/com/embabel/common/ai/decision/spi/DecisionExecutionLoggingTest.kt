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
package com.embabel.common.ai.decision.spi

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.ChoiceQuestionSpec
import com.embabel.common.ai.decision.DecisionCapabilities
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.RatingQuestionSpec
import com.embabel.common.ai.decision.RatingResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.slf4j.LoggerFactory
import java.util.EnumSet

@ResourceLock("DecisionContentCapture")
class DecisionExecutionLoggingTest {

    private val loggerName = "com.embabel.common.ai.decision.spi.DecisionExecution"

    private val sentinelInput = "SENTINEL-INPUT-5c1e"
    private val sentinelInstruction = "SENTINEL-INSTRUCTION-2b8f"
    private val sentinelOption = "SENTINEL-OPTION-9d0a"
    private val sentinels = listOf(sentinelInput, sentinelInstruction, sentinelOption)

    private val provenance = ModelProvenance("model-a", "provider-a")

    private val urgent = Questions.named("urgent").proposition("$sentinelInstruction Is it urgent?").build()

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

    private class Hooked(
        private val onAssess: () -> PropositionResult,
        private val onClassify: () -> ClassificationResult,
    ) : DecisionService, RatingAssessment {
        override val name = "log-service"
        override val provider = "log-provider"

        override fun classify(request: ClassificationRequest): ClassificationResult = onClassify()

        override fun assess(request: PropositionRequest): PropositionResult = onAssess()

        override fun rate(input: String, question: RatingQuestionSpec): RatingResult =
            RatingResult.Answered(ModelProvenance("model-a", "provider-a"), selectedLevelId = "angry")
    }

    private fun hooked(
        onAssess: () -> PropositionResult = { PropositionResult.Answered(true, provenance) },
        onClassify: () -> ClassificationResult = { ClassificationResult.Selected("billing", provenance) },
    ) = Hooked(onAssess, onClassify)

    private class FailingNative : DecisionService, NativeQuestionSetExecution {
        override val name = "native-service"
        override val provider = "native-provider"

        override fun capabilities(): DecisionCapabilities =
            DecisionCapabilities.of(EnumSet.allOf(QuestionKind::class.java))

        override fun classify(request: ClassificationRequest): ClassificationResult = error("not used")

        override fun assess(request: PropositionRequest): PropositionResult = error("not used")

        override fun askNative(request: DecisionRequest): DecisionResponse =
            DecisionResponse.failed(request.spec, FailureReason.UNAVAILABLE)
    }

    private fun capture(level: Level, contentCapture: Boolean, block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(loggerName) as Logger
        val previous = logger.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        logger.level = level
        if (contentCapture) DecisionContentCapture.enable() else DecisionContentCapture.disable()
        try {
            block()
            return appender.list.toList()
        } finally {
            DecisionContentCapture.disable()
            logger.level = previous
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private fun List<ILoggingEvent>.at(level: Level) = filter { it.level == level }.map { it.formattedMessage }

    private fun assertContains(line: String, vararg fragments: String) {
        for (fragment in fragments) {
            assertTrue(line.contains(fragment)) { "Expected '$fragment' in: $line" }
        }
    }

    private fun assertNoContent(events: List<ILoggingEvent>) {
        for (event in events) {
            for (sentinel in sentinels) {
                assertFalse(event.formattedMessage.contains(sentinel)) { "Unexpected '$sentinel' in: ${event.formattedMessage}" }
            }
        }
    }

    @Test
    fun `debug start and completion lines hold the service fields and each answer`() {
        val events = capture(Level.DEBUG, contentCapture = false) {
            hooked().ask(allThree)
        }
        val debug = events.at(Level.DEBUG)
        assertEquals(2, debug.size) { "Lines: $debug" }
        assertContains(
            debug[0],
            "service=log-service",
            "provider=log-provider",
            "questions=3",
        )
        assertContains(
            debug[1],
            "service=log-service",
            "'urgent' PROPOSITION answered",
            "'team' CHOICE selected",
            "'anger' RATING answered",
            "elapsedMs=",
        )
        assertTrue(events.at(Level.WARN).isEmpty())
        assertTrue(debug.none { it.contains("mode=") }) { "Lines: $debug" }
        assertNoContent(events)
    }

    @Test
    fun `a request failure logs a warning with the reason`() {
        val events = capture(Level.INFO, contentCapture = false) {
            FailingNative().ask(DecisionRequest.of(sentinelInput, urgent, team))
        }
        val warn = events.at(Level.WARN).single()
        assertContains(
            warn,
            "service=native-service",
            "provider=native-provider",
            "requestFailure=UNAVAILABLE",
            "elapsedMs=",
        )
        assertNoContent(events)
    }

    @Test
    fun `a partial response logs a warning naming the failed questions and reasons`() {
        val events = capture(Level.INFO, contentCapture = false) {
            hooked(onAssess = { PropositionResult.Failure(FailureReason.UNAVAILABLE) })
                .ask(allThree)
        }
        val warn = events.at(Level.WARN).single()
        assertContains(
            warn,
            "service=log-service",
            "provider=log-provider",
            "'urgent' UNAVAILABLE",
            "elapsedMs=",
        )
        assertFalse(warn.contains("'team'"))
        assertNoContent(events)
    }

    @Test
    fun `an out of domain answer logs a warning naming the question`() {
        val events = capture(Level.INFO, contentCapture = false) {
            hooked(onClassify = { ClassificationResult.Selected("SENTINEL-CATEGORY", provenance) })
                .ask(allThree)
        }
        val warn = events.at(Level.WARN)
        assertEquals(2, warn.size) { "Lines: $warn" }
        assertContains(warn[0], "service=log-service", "question='team'", "OUT_OF_DOMAIN")
        assertContains(warn[1], "'team' INVALID_RESPONSE")
        assertTrue(warn.none { it.contains("SENTINEL-CATEGORY") })
        assertNoContent(events)
    }

    @Test
    fun `trace with capture off logs no content`() {
        val events = capture(Level.TRACE, contentCapture = false) {
            hooked().ask(allThree)
        }
        assertTrue(events.isNotEmpty())
        assertNoContent(events)
    }

    @Test
    fun `capture on with the logger at debug logs no content`() {
        val events = capture(Level.DEBUG, contentCapture = true) {
            hooked().ask(allThree)
        }
        assertTrue(events.at(Level.TRACE).isEmpty())
        assertNoContent(events)
    }

    @Test
    fun `capture on with trace logs the request and the response`() {
        val events = capture(Level.TRACE, contentCapture = true) {
            hooked().ask(allThree)
        }
        val trace = events.at(Level.TRACE)
        assertEquals(2, trace.size) { "Lines: $trace" }
        assertContains(trace[0], "service=log-service", sentinelInput, sentinelInstruction, sentinelOption, "'team' CHOICE")
        assertContains(trace[1], "service=log-service", "billing", "model-a", "angry")
    }

    @Test
    fun `the capture switch is off by default and toggles`() {
        DecisionContentCapture.disable()
        try {
            assertFalse(DecisionContentCapture.isEnabled())
            DecisionContentCapture.enable()
            assertTrue(DecisionContentCapture.isEnabled())
        } finally {
            DecisionContentCapture.disable()
        }
        assertFalse(DecisionContentCapture.isEnabled())
    }
}
