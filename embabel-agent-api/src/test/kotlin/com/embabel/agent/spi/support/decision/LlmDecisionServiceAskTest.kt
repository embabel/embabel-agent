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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.chat.Message
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.RatingResult
import com.embabel.common.ai.decision.spi.DecisionContentCapture
import com.embabel.common.ai.decision.spi.QuestionSetExecution
import com.embabel.common.ai.decision.spi.PropositionAssessment
import com.embabel.common.ai.decision.spi.RatingAssessment
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.PreResolvedModelSelectionCriteria
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.springframework.ai.retry.TransientAiException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException

class LlmDecisionServiceAskTest {

    private class QuickRetry : RetryProperties {
        override val maxAttempts = 3
        override val backoffMillis = 1L
        override val backoffMultiplier = 2.0
        override val backoffMaxInterval = 4L
        override val propertyPrefix = "embabel.agent.platform.decisions.test"
    }

    private val provenance = ModelProvenance("gpt-test", "TestProvider")

    private val llm = mockk<LlmService<*>> {
        every { name } returns "gpt-test"
        every { provider } returns "TestProvider"
    }

    private val llmOperations = mockk<LlmOperations>()

    private val interactions = mutableListOf<LlmInteraction>()

    private val service = LlmDecisionService(
        llmOperations,
        llm,
        LlmOptions(modelSelectionCriteria = PreResolvedModelSelectionCriteria(llm)),
        QuickRetry(),
    )

    private val urgent = Questions.named("urgent").proposition("Does this convey urgency?").build()

    private val department = Questions.named("department")
        .choice("Which team should handle this?")
        .option("billing", "Payments, invoicing, refunds")
        .option("technical", "Bugs, outages, integrations")
        .build()

    private val frustration = Questions.named("frustration")
        .rating("How frustrated is the customer?")
        .level("calm", "No sign of frustration")
        .level("frustrated", "Clearly annoyed")
        .level("angry", "Hostile or threatening")
        .build()

    private val spec = DecisionSpec.of(urgent, department, frustration)

    private val inputSentinel = "INPUT-SENTINEL-7731"

    private val request = DecisionRequest.of("My card was charged twice. $inputSentinel", spec)

    private val validReply =
        """{"answers":[{"question":"q1","verdict":"TRUE"},""" +
            """{"question":"q2","verdict":"SELECTED","categoryId":"billing"},""" +
            """{"question":"q3","verdict":"RATED","levelId":"frustrated"}]}"""

    private fun modelReplies() =
        every { llmOperations.doTransform(any<List<Message>>(), capture(interactions), String::class.java, null) }

    private fun assertAnswered(response: DecisionResponse) {
        assertEquals(null, response.requestFailure)
        assertEquals(PropositionResult.Answered(true, provenance), response.answer(urgent))
        assertEquals(ClassificationResult.Selected("billing", provenance), response.answer(department))
        assertEquals(RatingResult.Answered(provenance, selectedLevelId = "frustrated"), response.answer(frustration))
    }

    private fun assertRequestFailed(response: DecisionResponse) {
        assertEquals(DecisionResponse.failed(spec, FailureReason.INVALID_RESPONSE), response)
    }

    @Nested
    inner class QuestionSetAsk {

        @Test
        fun `one model call with text output answers every question`() {
            modelReplies() returns validReply
            assertAnswered(service.askQuestionSet(request))
            assertEquals(1, interactions.size)
            assertEquals("ask", interactions.single().id.value)
        }

        @Test
        fun `ask through the service interface answers the question set in one call`() {
            modelReplies() returns validReply
            assertAnswered(service.ask(request))
            assertAnswered(service.ask(request.input, spec))
            assertEquals(2, interactions.size)
            assertEquals(listOf("ask", "ask"), interactions.map { it.id.value })
        }

        @Test
        fun `a transport failure then a reply retries inside the template`() {
            modelReplies() throws TransientAiException("busy") andThen validReply
            assertAnswered(service.askQuestionSet(request))
            assertEquals(2, interactions.size)
        }

        @Test
        fun `an unsafe envelope makes one call and fails the request without a retry`() {
            modelReplies() returns "This is not JSON"
            assertRequestFailed(service.askQuestionSet(request))
            assertEquals(1, interactions.size)
        }

        @Test
        fun `a selected id outside the options makes one call and fails only that question`() {
            modelReplies() returns validReply.replace("\"billing\"", "\"legal\"")
            val response = service.askQuestionSet(request)
            assertEquals(1, interactions.size)
            assertEquals(null, response.requestFailure)
            assertEquals(ClassificationResult.Failure(FailureReason.INVALID_RESPONSE), response.answer(department))
            assertEquals(PropositionResult.Answered(true, provenance), response.answer(urgent))
            assertEquals(RatingResult.Answered(provenance, selectedLevelId = "frustrated"), response.answer(frustration))
        }

        @Test
        fun `an interruption is thrown as a cancellation with the flag set and no further attempt`() {
            val interrupted = InterruptedException("stop")
            modelReplies() throws RuntimeException(interrupted)
            try {
                val thrown = assertThrows<CancellationException> { service.askQuestionSet(request) }
                assertSame(interrupted, thrown.cause)
                assertTrue(Thread.currentThread().isInterrupted)
                assertEquals(1, interactions.size)
            } finally {
                Thread.interrupted()
            }
        }
    }

    @Nested
    inner class CodeFence {

        @ParameterizedTest
        @ValueSource(
            strings = [
                "```json\n%s\n```",
                "```\n%s\n```",
                "  \n```json\n%s\n```\n  ",
                "```json\r\n%s\r\n```",
                "```json\n%s```",
            ],
        )
        fun `one fence around the whole reply is stripped before the strict parse`(template: String) {
            modelReplies() returns template.format(validReply)
            assertAnswered(service.askQuestionSet(request))
            assertEquals(1, interactions.size)
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                "Here are the answers:\n```json\n%s\n```",
                "```json\n%s\n```\nThat is all.",
                "```json\n%s\n```\n```json\n%s\n```",
                "```yaml\n%s\n```",
                "```json %s```",
                "``json\n%s\n``",
            ],
        )
        fun `text around the fence, two fences or another fence shape stay an unsafe envelope`(template: String) {
            modelReplies() returns template.replace("%s", validReply)
            assertRequestFailed(service.askQuestionSet(request))
            assertEquals(1, interactions.size)
        }
    }

    @Nested
    inner class OneQuestionHooks {

        @Test
        fun `rate makes one call and returns the question's outcome`() {
            modelReplies() returns """{"answers":[{"question":"q1","verdict":"RATED","levelId":"angry"}]}"""
            assertEquals(RatingResult.Answered(provenance, selectedLevelId = "angry"), service.rate(request.input, frustration))
            assertEquals(1, interactions.size)
            assertEquals("rate", interactions.single().id.value)
        }

        @Test
        fun `a question assess makes one proposition call with the question's instructions`() {
            val messages = mutableListOf<List<Message>>()
            every {
                llmOperations.doTransform(capture(messages), capture(interactions), PropositionAnswer::class.java, null)
            } returns PropositionAnswer(PropositionVerdict.FALSE)
            assertEquals(PropositionResult.Answered(false, provenance), service.assess(request.input, urgent))
            assertEquals("assess", interactions.single().id.value)
            assertTrue(messages.single().first().content.contains("Does this convey urgency?"))
        }

        @Test
        fun `an unreadable one-question reply is that question's invalid response`() {
            modelReplies() returns "nope"
            assertEquals(RatingResult.Failure(FailureReason.INVALID_RESPONSE), service.rate(request.input, frustration))
            assertEquals(1, interactions.size)
        }
    }

    @Test
    fun `descriptor and hooks agree`() {
        val capabilities = service.capabilities()
        assertEquals(QuestionKind.entries.toSet(), capabilities.questionKinds)
        assertTrue(service is QuestionSetExecution)
        assertTrue(service is PropositionAssessment)
        assertTrue(service is RatingAssessment)
    }

    @Nested
    @ResourceLock("DecisionContentCapture")
    inner class Diagnostics {

        private val rawSentinel = "RAW-SENTINEL-4412"

        private val replyWithSentinel = validReply.replace("{\"answers\"", "{\"note\":\"$rawSentinel\",\"answers\"")

        private fun capturing(level: Level, block: () -> Unit): List<ILoggingEvent> {
            val logger = LoggerFactory.getLogger("com.embabel") as Logger
            val previous = logger.level
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.level = level
            logger.addAppender(appender)
            try {
                block()
            } finally {
                logger.detachAppender(appender)
                logger.level = previous
                appender.stop()
                DecisionContentCapture.disable()
            }
            return appender.list.toList()
        }

        private fun serviceWarnings(events: List<ILoggingEvent>) = events
            .filter { it.loggerName == LlmDecisionService::class.java.name && it.level == Level.WARN }
            .map { it.formattedMessage }

        @Test
        fun `an exhausted transport failure logs one warning with the cause and no provider text`() {
            modelReplies() throws SocketTimeoutException("provider said $inputSentinel")
            val events = capturing(Level.DEBUG) {
                assertEquals(
                    DecisionResponse.failed(spec, FailureReason.UNAVAILABLE),
                    service.askQuestionSet(request),
                )
            }
            val warning = serviceWarnings(events).single()
            listOf(
                "service=gpt-test", "provider=TestProvider", "operation=ask", "reason=UNAVAILABLE",
                "cause=timeout", "httpStatus=none", "attempts=3", "elapsedMs=", "exception=SocketTimeoutException",
                "embabel.agent.platform.decisions.test",
            ).forEach { assertTrue(warning.contains(it), "$it missing from: $warning") }
            assertFalse(warning.contains("provider said"), warning)
            assertEquals(3, interactions.size)
        }

        @Test
        fun `a one-question call logs its operation and no mode`() {
            modelReplies() throws SocketTimeoutException("timed out")
            val events = capturing(Level.DEBUG) { service.rate("A charge.", frustration) }
            val warning = serviceWarnings(events).single()
            assertTrue(warning.contains("operation=rate"), warning)
            assertFalse(warning.contains("mode="), warning)
        }

        @Test
        fun `an http failure logs its status class and category`() {
            modelReplies() throws
                HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "down", HttpHeaders.EMPTY, ByteArray(0), null)
            val events = capturing(Level.DEBUG) { service.askQuestionSet(request) }
            val warning = serviceWarnings(events).single()
            assertTrue(warning.contains("cause=http_5xx"), warning)
            assertTrue(warning.contains("httpStatus=5xx"), warning)
            assertTrue(warning.contains("attempts=${interactions.size}"), warning)
        }

        @Test
        fun `adapter diagnostics cover invalid replies rate limits and unknown wrapped failures`() {
            val cases = listOf(
                InvalidDecisionAnswerException("private provider text") to "invalid_response",
                TransientAiException("rate_limit_error private provider text") to "rate_limited",
                HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "private", HttpHeaders.EMPTY, ByteArray(0), null) to "http_4xx",
                IllegalStateException("private provider text") to "other",
            )
            for ((failure, category) in cases) {
                for (exception in listOf(failure, RuntimeException("wrapper", failure))) {
                    modelReplies() throws exception
                    val events = capturing(Level.DEBUG) { service.askQuestionSet(request) }
                    val warning = serviceWarnings(events).single()
                    assertTrue(warning.contains("cause=$category"), warning)
                    assertFalse(warning.contains("private"), warning)
                }
            }
        }

        @Test
        fun `JVM errors propagate instead of becoming unavailable results`() {
            val failure = AssertionError("provider failed")
            modelReplies() throws failure
            assertSame(failure, assertThrows<AssertionError> { service.askQuestionSet(request) })
        }

        @Test
        fun `input and raw text appear in no line up to debug`() {
            modelReplies() returns replyWithSentinel
            DecisionContentCapture.enable()
            val events = capturing(Level.DEBUG) { assertAnswered(service.ask(request)) }
            assertTrue(events.isNotEmpty())
            events.forEach {
                assertFalse(it.formattedMessage.contains(inputSentinel), it.formattedMessage)
                assertFalse(it.formattedMessage.contains(rawSentinel), it.formattedMessage)
            }
        }

        @Test
        fun `trace without capture logs no content`() {
            modelReplies() returns replyWithSentinel
            val events = capturing(Level.TRACE) { assertAnswered(service.ask(request)) }
            events.forEach {
                assertFalse(it.formattedMessage.contains(inputSentinel), it.formattedMessage)
                assertFalse(it.formattedMessage.contains(rawSentinel), it.formattedMessage)
            }
        }

        @Test
        fun `trace with capture logs the raw model text`() {
            modelReplies() returns replyWithSentinel
            DecisionContentCapture.enable()
            val events = capturing(Level.TRACE) { assertAnswered(service.ask(request)) }
            val raw = events.filter {
                it.loggerName == LlmDecisionService::class.java.name && it.level == Level.TRACE &&
                    it.formattedMessage.contains(rawSentinel)
            }
            assertEquals(1, raw.size)
        }
    }
}
