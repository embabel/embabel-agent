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
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.DecisionAnswer
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.LevelProbability
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.RatingResult
import com.embabel.common.ai.decision.RatingScore
import com.embabel.common.ai.decision.RatingStatistic
import com.embabel.common.ai.decision.spi.DecisionResponseAssembler.Anomaly
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory

class DecisionResponseAssemblerTest {

    private val jev = ModelProvenance("jev-latest", "typesafe")

    private val urgent = Questions.named("is_urgent").proposition("Does this convey urgency?").build()

    private val department = Questions.named("department")
        .choice("Which team should handle this?")
        .option("billing", "Payments, invoicing, refunds")
        .option("technical", "Bugs, outages, integrations")
        .build()

    private val frustration = Questions.named("frustration")
        .rating("How frustrated is the customer?")
        .level("Calm")
        .level("Frustrated")
        .level("Very angry")
        .build()

    private val channel = Questions.named("channel")
        .choice("Which channel did this arrive through?")
        .option("email", "Email")
        .option("phone", "Phone")
        .build()

    private val spec = DecisionSpec.of(urgent, department, frustration)

    private val yes = PropositionResult.Answered(true, jev, 0.93)
    private val billing = ClassificationResult.Selected("billing", jev, 0.91)
    private val rated = RatingResult.Answered(jev, "Frustrated")

    private fun assembler(spec: DecisionSpec = this.spec) =
        DecisionResponseAssembler.forSpec(spec, "jev-latest")

    private fun failed(response: DecisionResponse, name: String): Boolean =
        when (val answer = response.answer(name)) {
            is DecisionAnswer.Proposition -> answer.outcome == PropositionResult.Failure(FailureReason.INVALID_RESPONSE)
            is DecisionAnswer.Choice -> answer.outcome == ClassificationResult.Failure(FailureReason.INVALID_RESPONSE)
            is DecisionAnswer.Rating -> answer.outcome == RatingResult.Failure(FailureReason.INVALID_RESPONSE)
        }

    private fun assertRequestFailed(response: DecisionResponse) {
        assertEquals(DecisionResponse.failed(spec, FailureReason.INVALID_RESPONSE), response)
        assertEquals(FailureReason.INVALID_RESPONSE, response.requestFailure)
    }

    @Nested
    inner class Answers {

        @Test
        fun `happy path places every kind in spec order`() {
            val response = assembler().proposition("is_urgent", yes).choice("department", billing)
                .rating("frustration", rated).build()

            assertNull(response.requestFailure)
            assertEquals(yes, response.answer(urgent))
            assertEquals(billing, response.answer(department))
            assertEquals(rated, response.answer(frustration))
        }

        @Test
        fun `reordered arrival gives spec order`() {
            val response = assembler().rating("frustration", rated).choice("department", billing)
                .proposition("is_urgent", yes).build()

            assertEquals(listOf("is_urgent", "department", "frustration"), response.answers.map { it.name })
        }

        @Test
        fun `outcomes pass through unchanged, including absent confidence`() {
            val noConfidence = ClassificationResult.Selected("technical", jev)
            val scored = RatingResult.Answered(
                jev,
                distribution = listOf(
                    LevelProbability("Calm", 0.1),
                    LevelProbability("Frustrated", 0.2),
                    LevelProbability("Very angry", 0.7),
                ),
                score = RatingScore(1.6, RatingStatistic.EXPECTED_LEVEL_INDEX),
            )
            val response = assembler().proposition("is_urgent", PropositionResult.Inconclusive(jev))
                .choice("department", noConfidence).rating("frustration", scored).build()

            assertEquals(PropositionResult.Inconclusive(jev), response.answer(urgent))
            assertNull((response.answer(department) as ClassificationResult.Selected).confidence)
            assertNull((response.answer(frustration) as RatingResult.Answered).confidence)
            assertEquals(scored, response.answer(frustration))
        }

        @Test
        fun `missing answer fails only that question`() {
            val response = assembler().proposition("is_urgent", yes).rating("frustration", rated).build()

            assertNull(response.requestFailure)
            assertTrue(failed(response, "department"))
            assertEquals(yes, response.answer(urgent))
            assertEquals(rated, response.answer(frustration))
        }

        @Test
        fun `unknown name is ignored, counted and siblings kept`() {
            val assembler = assembler().proposition("is_urgent", yes).choice("department", billing)
                .rating("frustration", rated).proposition("SENTINEL_KEY", yes)
            val response = assembler.build()

            assertEquals(1, assembler.unexpectedCount())
            assertNull(response.requestFailure)
            assertEquals(yes, response.answer(urgent))
            assertEquals(billing, response.answer(department))
            assertEquals(rated, response.answer(frustration))
        }

        @Test
        fun `unexpected keys are counted and siblings kept`() {
            val assembler = assembler().proposition("is_urgent", yes).unexpected().unexpected()
                .choice("department", billing).rating("frustration", rated)
            val response = assembler.build()

            assertEquals(2, assembler.unexpectedCount())
            assertNull(response.requestFailure)
            assertEquals(billing, response.answer(department))
        }

        @Test
        fun `wrong kind fails only that question`() {
            val response = assembler().proposition("is_urgent", yes).proposition("department", yes)
                .rating("frustration", rated).build()

            assertNull(response.requestFailure)
            assertTrue(failed(response, "department"))
            assertEquals(yes, response.answer(urgent))
            assertEquals(rated, response.answer(frustration))
        }

        @Test
        fun `unreadable fails only that question`() {
            val response = assembler().proposition("is_urgent", yes).unreadable("department")
                .rating("frustration", rated).build()

            assertNull(response.requestFailure)
            assertTrue(failed(response, "department"))
            assertEquals(yes, response.answer(urgent))
        }

        @Test
        fun `unreadable with a range or distribution anomaly fails only that question`() {
            val response = assembler().proposition("is_urgent", yes).choice("department", billing)
                .unreadable("frustration", Anomaly.OUT_OF_RANGE).build()

            assertTrue(failed(response, "frustration"))
            assertEquals(billing, response.answer(department))
        }

        @Test
        fun `unreadable rejects an anomaly the caller cannot report`() {
            listOf(Anomaly.MISSING, Anomaly.WRONG_KIND, Anomaly.OUT_OF_DOMAIN, Anomaly.DUPLICATE, Anomaly.UNEXPECTED, Anomaly.UNSAFE_ENVELOPE)
                .forEach { anomaly -> assertThrows<IllegalArgumentException> { assembler().unreadable("department", anomaly) } }
        }

        @Test
        fun `choice with an id outside the options fails only that question`() {
            val response = assembler().proposition("is_urgent", yes)
                .choice("department", ClassificationResult.Selected("SENTINEL_CATEGORY", jev, 0.9))
                .rating("frustration", rated).build()

            assertNull(response.requestFailure)
            assertTrue(failed(response, "department"))
            assertEquals(rated, response.answer(frustration))
        }

        @Test
        fun `rating distribution missing a level fails only that question`() {
            val partial = RatingResult.Answered(
                jev,
                distribution = listOf(LevelProbability("Calm", 0.4), LevelProbability("Frustrated", 0.6)),
            )
            val response = assembler().proposition("is_urgent", yes).choice("department", billing)
                .rating("frustration", partial).build()

            assertNull(response.requestFailure)
            assertTrue(failed(response, "frustration"))
            assertEquals(billing, response.answer(department))
        }

        @Test
        fun `rating with an unknown level or a score above the scale fails only that question`() {
            val unknownLevel = assembler().proposition("is_urgent", yes).choice("department", billing)
                .rating("frustration", RatingResult.Answered(jev, "Furious")).build()
            val highScore = assembler().proposition("is_urgent", yes).choice("department", billing)
                .rating("frustration", RatingResult.Answered(jev, score = RatingScore(2.5, RatingStatistic.EXPECTED_LEVEL_INDEX)))
                .build()

            assertTrue(failed(unknownLevel, "frustration"))
            assertTrue(failed(highScore, "frustration"))
            assertEquals(billing, highScore.answer(department))
        }
    }

    @Nested
    inner class RequestFailures {

        @Test
        fun `duplicate name with the same kind and outcome fails the request`() {
            assertRequestFailed(
                assembler().proposition("is_urgent", yes).proposition("is_urgent", yes)
                    .choice("department", billing).rating("frustration", rated).build(),
            )
        }

        @Test
        fun `duplicate name with different kinds fails the request`() {
            assertRequestFailed(
                assembler().proposition("is_urgent", yes).rating("is_urgent", rated)
                    .choice("department", billing).rating("frustration", rated).build(),
            )
        }

        @Test
        fun `duplicate through unreadable fails the request`() {
            assertRequestFailed(
                assembler().proposition("is_urgent", yes).unreadable("is_urgent")
                    .choice("department", billing).rating("frustration", rated).build(),
            )
        }

        @Test
        fun `duplicate of an unknown name fails the request`() {
            assertRequestFailed(
                assembler().proposition("is_urgent", yes).choice("department", billing).rating("frustration", rated)
                    .proposition("SENTINEL_KEY", yes).unreadable("SENTINEL_KEY").build(),
            )
        }

        @Test
        fun `unsafe fails every question`() {
            val response = assembler().proposition("is_urgent", yes).choice("department", billing)
                .rating("frustration", rated).unsafe().build()

            assertRequestFailed(response)
            assertTrue(spec.questions.all { failed(response, it.name) })
        }

        @Test
        fun `failed request answers the spec`() {
            val response = DecisionResponseAssembler.forSpec(spec, "svc").unsafe().build()

            response.requireMatches(spec)
            assertEquals(FailureReason.INVALID_RESPONSE, response.requestFailure)
        }
    }

    @Nested
    inner class Tamper {

        @Test
        fun `tamper duplicate name carrying a valid choice and a proposition fails the request`() {
            assertRequestFailed(
                assembler().proposition("is_urgent", yes).choice("department", billing)
                    .proposition("department", PropositionResult.Answered(false, jev)).rating("frustration", rated).build(),
            )
        }

        @Test
        fun `tamper choice id from another question's options fails only that question`() {
            val twoChoices = DecisionSpec.of(department, channel)
            val response = assembler(twoChoices).choice("department", ClassificationResult.Selected("email", jev))
                .choice("channel", ClassificationResult.Selected("phone", jev)).build()

            assertNull(response.requestFailure)
            assertTrue(failed(response, "department"))
            assertEquals(ClassificationResult.Selected("phone", jev), response.answer(channel))
        }

        @Test
        fun `tamper right name with the other kind fails only that question`() {
            val response = assembler().proposition("is_urgent", yes).rating("department", rated)
                .choice("frustration", billing).build()

            assertNull(response.requestFailure)
            assertTrue(failed(response, "department"))
            assertTrue(failed(response, "frustration"))
            assertEquals(yes, response.answer(urgent))
        }
    }

    @Nested
    inner class Lifecycle {

        @Test
        fun `second build throws`() {
            val assembler = assembler().proposition("is_urgent", yes)
            assembler.build()

            assertThrows<IllegalStateException> { assembler.build() }
        }

        @Test
        fun `adding after build throws`() {
            val assembler = assembler()
            assembler.build()

            assertThrows<IllegalStateException> { assembler.proposition("is_urgent", yes) }
            assertThrows<IllegalStateException> { assembler.unexpected() }
            assertThrows<IllegalStateException> { assembler.unsafe() }
        }

        @Test
        fun `blank service name is rejected`() {
            assertThrows<IllegalArgumentException> { DecisionResponseAssembler.forSpec(spec, " ") }
        }
    }

    @Nested
    inner class Diagnostics {

        private fun capture(block: () -> Unit): List<ILoggingEvent> {
            val logger = LoggerFactory.getLogger(DecisionResponseAssembler::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val previous = logger.level
            logger.level = Level.TRACE
            logger.addAppender(appender)
            try {
                block()
            } finally {
                logger.detachAppender(appender)
                logger.level = previous
                appender.stop()
            }
            return appender.list.toList()
        }

        private fun singleWarn(events: List<ILoggingEvent>): String {
            val warns = events.filter { it.level.isGreaterOrEqual(Level.WARN) }
            assertEquals(1, warns.size, "exactly one WARN per build")
            assertEquals(Level.WARN, warns.single().level)
            assertNull(warns.single().throwableProxy)
            return warns.single().formattedMessage
        }

        @Test
        fun `clean response logs no warning`() {
            val events = capture {
                assembler().proposition("is_urgent", yes).choice("department", billing).rating("frustration", rated).build()
            }

            assertTrue(events.none { it.level.isGreaterOrEqual(Level.INFO) })
        }

        @Test
        fun `per-question anomalies are named with their kinds in one warning`() {
            val events = capture {
                DecisionResponseAssembler.forSpec(DecisionSpec.of(urgent, department, frustration, channel), "jev-latest")
                    .choice("is_urgent", ClassificationResult.Selected("SENTINEL_CATEGORY", jev))
                    .choice("department", ClassificationResult.Selected("SENTINEL_CATEGORY", jev))
                    .unreadable("frustration", Anomaly.OUT_OF_RANGE)
                    .proposition("SENTINEL_KEY", yes)
                    .build()
            }
            val message = singleWarn(events)

            assertEquals(
                "Decision service 'jev-latest' returned answers it could not use: is_urgent=WRONG_KIND, " +
                    "department=OUT_OF_DOMAIN, frustration=OUT_OF_RANGE, channel=MISSING; unexpected answers: 1",
                message,
            )
        }

        @Test
        fun `unreadable and bad distribution are named`() {
            val partial = RatingResult.Answered(
                jev,
                distribution = listOf(LevelProbability("Calm", 0.4), LevelProbability("Frustrated", 0.6)),
            )
            val unreadable = singleWarn(capture {
                assembler().unreadable("is_urgent").choice("department", billing).rating("frustration", partial).build()
            })
            val reported = singleWarn(capture {
                assembler().proposition("is_urgent", yes).choice("department", billing)
                    .unreadable("frustration", Anomaly.BAD_DISTRIBUTION).build()
            })

            assertTrue(unreadable.contains("'jev-latest'"))
            assertTrue(unreadable.contains("is_urgent=UNREADABLE"), unreadable)
            assertTrue(unreadable.contains("frustration=BAD_DISTRIBUTION"), unreadable)
            assertTrue(reported.contains("frustration=BAD_DISTRIBUTION"), reported)
        }

        @Test
        fun `rating level outside the scale is out of domain and a score above it is out of range`() {
            val level = singleWarn(capture {
                assembler().proposition("is_urgent", yes).choice("department", billing)
                    .rating("frustration", RatingResult.Answered(jev, "SENTINEL_LEVEL")).build()
            })
            val score = singleWarn(capture {
                assembler().proposition("is_urgent", yes).choice("department", billing)
                    .rating("frustration", RatingResult.Answered(jev, score = RatingScore(2.5, RatingStatistic.EXPECTED_LEVEL_INDEX)))
                    .build()
            })

            assertTrue(level.contains("frustration=OUT_OF_DOMAIN"), level)
            assertFalse(level.contains("SENTINEL_LEVEL"))
            assertTrue(score.contains("frustration=OUT_OF_RANGE"), score)
            assertFalse(score.contains("2.5"))
        }

        @Test
        fun `duplicate of a spec name is named and the request failure stated`() {
            val message = singleWarn(capture {
                assembler().proposition("is_urgent", yes).proposition("is_urgent", yes)
                    .proposition("SENTINEL_KEY", yes).proposition("SENTINEL_KEY", yes).build()
            })

            assertEquals(
                "Decision service 'jev-latest' returned answers it could not use: is_urgent=DUPLICATE; " +
                    "unexpected answers: 2; every question failed with INVALID_RESPONSE",
                message,
            )
        }

        @Test
        fun `duplicate of an unknown name is only counted`() {
            val message = singleWarn(capture {
                assembler().proposition("is_urgent", yes).choice("department", billing).rating("frustration", rated)
                    .proposition("SENTINEL_KEY", yes).unreadable("SENTINEL_KEY").build()
            })

            assertEquals(
                "Decision service 'jev-latest' returned answers it could not use: " +
                    "unexpected answers: 2; every question failed with INVALID_RESPONSE",
                message,
            )
        }

        @Test
        fun `unsafe envelope names the anomaly`() {
            val message = singleWarn(capture { assembler().proposition("SENTINEL_KEY", yes).unsafe().build() })

            assertEquals(
                "Decision service 'jev-latest' returned answers that cannot be matched to questions " +
                    "(UNSAFE_ENVELOPE); every question failed with INVALID_RESPONSE",
                message,
            )
        }

        @Test
        fun `received keys and ids appear in no log line or exception message`() {
            val events = capture {
                assembler().proposition("SENTINEL_KEY", yes)
                    .choice("department", ClassificationResult.Selected("SENTINEL_CATEGORY", jev))
                    .build()
                assembler().unreadable("SENTINEL_KEY").unreadable("SENTINEL_KEY").build()
            }
            val lines = events.map { it.formattedMessage }
            val built = assembler().proposition("is_urgent", yes)
            built.build()
            val second = assertThrows<IllegalStateException> { built.build() }
            val bad = assertThrows<IllegalArgumentException> { assembler().unreadable("SENTINEL_KEY", Anomaly.MISSING) }

            assertFalse(lines.isEmpty())
            (lines + listOf(second.message.orEmpty(), bad.message.orEmpty())).forEach { line ->
                assertFalse(line.contains("SENTINEL"), line)
            }
        }

        @Test
        fun `each warning is logged on the assembler class logger`() {
            val events = capture { assembler().unsafe().build() }

            assertInstanceOf(ILoggingEvent::class.java, events.single())
            assertEquals(DecisionResponseAssembler::class.java.name, events.single().loggerName)
        }
    }
}
