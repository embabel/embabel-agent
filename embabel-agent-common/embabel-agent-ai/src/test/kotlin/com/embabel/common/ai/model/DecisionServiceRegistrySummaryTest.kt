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
package com.embabel.common.ai.model

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.ClassificationService
import com.embabel.common.ai.decision.DecisionCapabilities
import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.QuestionKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import java.util.EnumSet

class DecisionServiceRegistrySummaryTest {

    /** Holds a marker in its string form so the tests can check the summary never prints the instance. */
    private open class FakeDecision(
        override val name: String,
        override val provider: String,
        private val capabilities: () -> DecisionCapabilities? = { null },
    ) : DecisionService {
        var capabilityReads = 0

        override fun classify(request: ClassificationRequest): ClassificationResult = error("not called")
        override fun assess(request: PropositionRequest): PropositionResult = error("not called")
        override fun capabilities(): DecisionCapabilities {
            capabilityReads++
            return capabilities.invoke() ?: super.capabilities()
        }

        override fun toString(): String = "PAYLOAD-$name"
    }

    private class FakeClassifier(override val name: String, override val provider: String) : ClassificationService {
        override fun classify(request: ClassificationRequest): ClassificationResult = error("not called")
        override fun toString(): String = "PAYLOAD-$name"
    }

    private val nativeCapabilities = DecisionCapabilities.of(
        EnumSet.of(QuestionKind.PROPOSITION, QuestionKind.CHOICE, QuestionKind.RATING),
    )

    private fun captured(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(DecisionServiceRegistry::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val previous = logger.level
        logger.addAppender(appender)
        logger.level = Level.TRACE
        try {
            block()
        } finally {
            logger.detachAppender(appender)
            logger.level = previous
        }
        return appender.list.toList()
    }

    @Test
    fun `summary names every registration with service name provider type and capabilities`() {
        val jev = FakeDecision("jev-1", "TypeSafe") { nativeCapabilities }
        val prompted = FakeDecision("gpt-x", "OpenAI")
        val classifier = FakeClassifier("clf-1", "acme")
        val events = captured {
            DecisionServiceRegistry.builder()
                .register("typeSafeDecisionService", jev)
                .register("gpt", prompted)
                .register("clf", classifier)
                .decisionDefault("typeSafeDecisionService")
                .decisionRole("support-triage", "gpt")
                .build()
        }
        val message = events.single().formattedMessage
        listOf(
            "typeSafeDecisionService (name jev-1, provider TypeSafe, type DECISION, capabilities kinds " +
                "[PROPOSITION, CHOICE, RATING])",
            "gpt (name gpt-x, provider OpenAI, type DECISION, capabilities kinds [PROPOSITION, CHOICE])",
            "clf (name clf-1, provider acme, type CLASSIFICATION)",
            "decision default: 'typeSafeDecisionService' (explicit)",
            "decision roles: {support-triage=gpt}",
        ).forEach { assertTrue(message.contains(it), "missing '$it' in: $message") }
        assertFalse(message.contains("PAYLOAD"), "service instance printed in: $message")
    }

    @Test
    fun `a classification-only service is listed without capabilities`() {
        val events = captured {
            DecisionServiceRegistry.builder()
                .register("clf", FakeClassifier("clf-1", "acme"))
                .build()
        }
        val message = events.single().formattedMessage
        assertTrue(message.contains("clf (name clf-1, provider acme, type CLASSIFICATION)"), message)
        assertFalse(message.contains("capabilities"), message)
    }

    @Test
    fun `empty logs nothing`() {
        assertTrue(captured { DecisionServiceRegistry.empty() }.isEmpty())
    }

    @Test
    fun `each build logs exactly one INFO line and reads capabilities once`() {
        val jev = FakeDecision("jev-1", "TypeSafe") { nativeCapabilities }
        val builder = DecisionServiceRegistry.builder().register("jev", jev)
        val events = captured {
            builder.build()
            builder.build()
        }
        assertEquals(2, events.size)
        events.forEach { assertEquals(Level.INFO, it.level) }
        assertEquals(2, jev.capabilityReads)
    }

    @Test
    fun `capabilities are read at build even when INFO is off`() {
        val jev = FakeDecision("jev-1", "TypeSafe") { nativeCapabilities }
        val logger = LoggerFactory.getLogger(DecisionServiceRegistry::class.java) as Logger
        val previous = logger.level
        logger.level = Level.WARN
        try {
            DecisionServiceRegistry.builder().register("jev", jev).build()
        } finally {
            logger.level = previous
        }
        assertEquals(1, jev.capabilityReads)
    }

    @Test
    fun `a throwing capabilities fails build naming the registration`() {
        val cause = IllegalArgumentException("PAYLOAD-provider-text")
        val broken = FakeDecision("jev-1", "TypeSafe") { throw cause }
        lateinit var error: IllegalStateException
        val events = captured {
            error = assertThrows<IllegalStateException> {
                DecisionServiceRegistry.builder()
                    .register("ok", FakeClassifier("clf-1", "acme"))
                    .register("typeSafeDecisionService", broken)
                    .build()
            }
        }
        val message = error.message.orEmpty()
        listOf("typeSafeDecisionService", "jev-1", "TypeSafe", "IllegalArgumentException", "capabilities()")
            .forEach { assertTrue(message.contains(it), "missing '$it' in: $message") }
        assertFalse(message.contains("PAYLOAD"), message)
        assertSame(cause, error.cause)
        assertTrue(events.isEmpty())
    }
}
