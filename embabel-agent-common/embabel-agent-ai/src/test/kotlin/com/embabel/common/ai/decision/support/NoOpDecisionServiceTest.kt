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
package com.embabel.common.ai.decision.support

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.embabel.common.ai.classification.Category
import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.classificationSpec
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.decision.ChoiceQuestionSpec
import com.embabel.common.ai.decision.DecisionAnswer
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.RatingQuestionSpec
import com.embabel.common.ai.decision.RatingResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.EnumSet

class NoOpDecisionServiceTest {

    private val urgent = Questions.named("urgent").proposition("Is it urgent?").build()

    private val team: ChoiceQuestionSpec = Questions.named("team")
        .choice("Which team?")
        .option("billing", "Payments")
        .option("support", "Help")
        .build()

    private val anger: RatingQuestionSpec = Questions.named("anger")
        .rating("How angry?")
        .level("calm")
        .level("angry")
        .build()

    private val service = NoOpDecisionService()

    @Test
    fun `identity is none by default and the name is configurable`() {
        assertEquals("none", service.name)
        assertEquals("none", service.provider)
        assertEquals("disabled-triage", NoOpDecisionService("disabled-triage").name)
    }

    @Test
    fun `capabilities hold every kind`() {
        assertEquals(EnumSet.allOf(QuestionKind::class.java), service.capabilities().questionKinds)
    }

    @Test
    fun `every single operation returns an unavailable failure`() {
        assertEquals(PropositionResult.Failure(FailureReason.UNAVAILABLE), service.assess(PropositionRequest("x", "p")))
        assertEquals(
            ClassificationResult.Failure(FailureReason.UNAVAILABLE),
            service.classify(ClassificationRequest.of("x", classificationSpec { asking("Which category fits?"); category("a", "A") })),
        )
        assertEquals(RatingResult.Failure(FailureReason.UNAVAILABLE), service.rate("x", anger))
    }

    @Test
    fun `a three kind ask is a native request failure with every answer unavailable`() {
        val response = service.ask(DecisionRequest.of("x", urgent, team, anger))
        assertEquals(FailureReason.UNAVAILABLE, response.requestFailure)
        assertEquals(PropositionResult.Failure(FailureReason.UNAVAILABLE), response.answer(urgent))
        assertEquals(ClassificationResult.Failure(FailureReason.UNAVAILABLE), response.answer(team))
        assertEquals(RatingResult.Failure(FailureReason.UNAVAILABLE), response.answer(anger))
        for (answer in response.answers) {
            val outcome: Any = when (answer) {
                is DecisionAnswer.Proposition -> answer.outcome
                is DecisionAnswer.Choice -> answer.outcome
                is DecisionAnswer.Rating -> answer.outcome
            }
            assertTrue(
                outcome is PropositionResult.Failure || outcome is ClassificationResult.Failure ||
                    outcome is RatingResult.Failure,
            ) { "Expected a failure, got $outcome" }
        }
    }

    @Test
    fun `construction logs one info line naming the service`() {
        val logger = LoggerFactory.getLogger(NoOpDecisionService::class.java) as Logger
        val previous = logger.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        logger.level = Level.INFO
        try {
            NoOpDecisionService("disabled-triage")
            val info = appender.list.filter { it.level == Level.INFO }.map { it.formattedMessage }
            assertEquals(
                listOf("Decision service 'disabled-triage' is disabled; every request returns an unavailable failure"),
                info,
            )
        } finally {
            logger.level = previous
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
