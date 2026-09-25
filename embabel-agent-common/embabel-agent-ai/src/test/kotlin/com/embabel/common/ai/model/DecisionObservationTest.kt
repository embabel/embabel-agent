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
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.read.ListAppender
import ch.qos.logback.core.spi.FilterReply
import com.embabel.common.ai.classification.Category
import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.io.IOException
import java.util.concurrent.CancellationException

class DecisionObservationTest {
    private val secret = "sensitive-payload-and-credential"
    private val telemetryFailureMessage = "telemetry failed"
    private val observationLoggerName = "com.embabel.common.ai.model.ServiceCallObservation"
    private val provenance = ModelProvenance(secret, secret, secret, secret)
    private val request = ClassificationRequest(secret, listOf(Category("dog", secret)))
    private val proposition = PropositionRequest(secret, secret)

    private class Recorder : ObservationHandler<Observation.Context> {
        val stopped = mutableListOf<Observation.Context>()
        val errors = mutableListOf<Throwable>()
        val errorOutcomes = mutableListOf<String?>()
        val closedScopeOutcomes = mutableListOf<String?>()
        override fun supportsContext(context: Observation.Context) = true
        override fun onError(context: Observation.Context) {
            errors += context.error!!
            errorOutcomes += context.getLowCardinalityKeyValue("outcome")?.value
        }
        override fun onScopeClosed(context: Observation.Context) {
            closedScopeOutcomes += context.getLowCardinalityKeyValue("outcome")?.value
        }
        override fun onStop(context: Observation.Context) {
            stopped += context
        }
    }

    private class Telemetry {
        val recorder = Recorder()
        val meters = SimpleMeterRegistry()
        val registry = ObservationRegistry.create().apply {
            observationConfig().observationHandler(recorder)
                .observationHandler(DefaultMeterObservationHandler(meters))
        }
    }

    /** Supply a classifier whose model identity can expose accidental telemetry leaks. */
    private fun classifier(work: (ClassificationRequest) -> ClassificationResult) = object : ClassificationService {
        override val name = secret
        override val provider = secret
        override fun classify(request: ClassificationRequest) = work(request)
    }

    /** Exercise both decision capabilities with controlled results and sensitive model identity. */
    private fun decision(
        classify: (ClassificationRequest) -> ClassificationResult = { ClassificationResult.NoMatch(provenance) },
        assess: (PropositionRequest) -> PropositionResult = { PropositionResult.Answered(false, provenance) },
    ) = object : DecisionService {
        override val name = secret
        override val provider = secret
        override fun classify(request: ClassificationRequest) = classify.invoke(request)
        override fun assess(request: PropositionRequest) = assess.invoke(request)
    }

    /** Ensure even exporters that inspect causes, stacks or suppressed exceptions cannot expose provider data. */
    private fun assertSafeError(error: Throwable?, outcome: String) {
        assertNotNull(error)
        assertEquals(outcome, error!!.message)
        assertNull(error.cause)
        assertTrue(error.suppressed.isEmpty())
        assertTrue(error.stackTrace.isEmpty())
        assertFalse(error.toString().contains(secret))
    }

    @Nested
    inner class Results {
        @Test
        fun `classification variants produce one timer sample each with bounded tags`() {
            val telemetry = Telemetry()
            val results = listOf(
                ClassificationResult.Selected("dog", provenance) to "selected",
                ClassificationResult.NoMatch(provenance) to "no_match",
                ClassificationResult.Inconclusive(provenance) to "inconclusive",
                ClassificationResult.Failure(FailureReason.UNAVAILABLE) to "failure",
            )
            results.forEach { (result, outcome) ->
                val observed = ObservedClassificationService(classifier { result }, telemetry.registry)
                assertSame(result, observed.classify(request))
                assertEquals(1L, telemetry.meters.get("embabel.ai.classification")
                    .tags("operation", "classify", "outcome", outcome).timer().count())
            }
            assertEquals(results.size, telemetry.recorder.stopped.size)
            telemetry.recorder.stopped.zip(results).forEach { (context, expected) ->
                assertEquals(mapOf("operation" to "classify", "outcome" to expected.second),
                    context.lowCardinalityKeyValues.associate { it.key to it.value })
                assertTrue(context.highCardinalityKeyValues.none())
                if (expected.second == "failure") assertSafeError(context.error, "failure")
                else assertNull(context.error)
            }
            assertNull(telemetry.registry.currentObservation)
        }

        @Test
        fun `false and true proposition answers are successful and retain original evidence`() {
            val telemetry = Telemetry()
            val results = listOf(
                PropositionResult.Answered(false, provenance) to "answered",
                PropositionResult.Answered(true, provenance, 0.7) to "answered",
                PropositionResult.Inconclusive(provenance) to "inconclusive",
                PropositionResult.Failure(FailureReason.INVALID_RESPONSE) to "failure",
            )
            results.forEach { (result, _) ->
                assertSame(result, ObservedDecisionService(decision(assess = { result }), telemetry.registry)
                    .assess(proposition))
            }
            assertEquals(results.map { it.second }, telemetry.recorder.stopped.map {
                it.getLowCardinalityKeyValue("outcome")!!.value
            })
            assertEquals(2L, telemetry.meters.get("embabel.ai.decision")
                .tags("operation", "assess", "outcome", "answered").timer().count())
            assertEquals(4, telemetry.recorder.stopped.size)
            assertEquals(1, telemetry.recorder.errors.size)
            assertSafeError(telemetry.recorder.errors.single(), "failure")
        }

        @Test
        fun `decision classification observes exactly once and forwards metadata snapshot`() {
            val telemetry = Telemetry()
            var calls = 0
            val delegate = decision(classify = { calls++; ClassificationResult.NoMatch(provenance) })
            val observed: ClassificationService = ObservedDecisionService(delegate, telemetry.registry)
            observed.classify(request)
            assertEquals(1, calls)
            assertEquals(1, telemetry.recorder.stopped.size)
            assertEquals("embabel.ai.classification", telemetry.recorder.stopped.single().name)
            assertEquals(ModelType.DECISION, observed.type)
            assertEquals(delegate.metadata(), observed.metadata())
            assertFalse(observed.metadata() is ClassificationService)
            val classifierView = ObservedClassificationService(delegate, telemetry.registry)
            assertEquals(ModelType.DECISION, classifierView.type)
            assertEquals(delegate.metadata(), classifierView.metadata())
            assertEquals(delegate.infoString(false, 2), classifierView.infoString(false, 2))
        }

        @Test
        fun `NOOP registries execute and validate without changing results`() {
            val result = ClassificationResult.NoMatch(provenance)
            assertSame(result, ObservedClassificationService(classifier { result }).classify(request))
            val answer = PropositionResult.Answered(false, provenance)
            assertSame(answer, ObservedDecisionService(decision(assess = { answer })).assess(proposition))
            assertThrows<IllegalArgumentException> {
                ObservedClassificationService(classifier { ClassificationResult.Selected("unknown", provenance) })
                    .classify(request)
            }
        }
    }

    @Nested
    inner class ScopesAndExceptions {
        @Test
        fun `operational failure outcome is available to error and scope close handlers`() {
            val telemetry = Telemetry()
            ObservedClassificationService(classifier {
                ClassificationResult.Failure(FailureReason.UNAVAILABLE)
            }, telemetry.registry).classify(request)
            ObservedDecisionService(decision(assess = {
                PropositionResult.Failure(FailureReason.INVALID_RESPONSE)
            }), telemetry.registry).assess(proposition)
            assertEquals(listOf("failure", "failure"), telemetry.recorder.errorOutcomes)
            assertEquals(listOf("failure", "failure"), telemetry.recorder.closedScopeOutcomes)
        }

        @Test
        fun `thrown error outcome is available to error and scope close handlers`() {
            val telemetry = Telemetry()
            val failure = IllegalStateException(secret)
            assertSame(failure, assertThrows<IllegalStateException> {
                ObservedClassificationService(classifier { throw failure }, telemetry.registry).classify(request)
            })
            assertEquals(listOf("exception"), telemetry.recorder.errorOutcomes)
            assertEquals(listOf("exception"), telemetry.recorder.closedScopeOutcomes)
        }

        @Test
        fun `success outcome is available before scope closes`() {
            val telemetry = Telemetry()
            ObservedClassificationService(classifier {
                ClassificationResult.Selected("dog", provenance)
            }, telemetry.registry).classify(request)
            assertTrue(telemetry.recorder.errorOutcomes.isEmpty())
            assertEquals(listOf("selected"), telemetry.recorder.closedScopeOutcomes)
        }

        @Test
        fun `provider observations are children and previous scope is restored`() {
            val telemetry = Telemetry()
            val parent = Observation.start("caller", telemetry.registry)
            parent.openScope().use {
                val observed = ObservedClassificationService(classifier {
                    val active = telemetry.registry.currentObservation!!
                    assertSame(parent, active.context.parentObservation)
                    val nested = Observation.start("provider", telemetry.registry)
                    assertSame(active, nested.context.parentObservation)
                    nested.stop()
                    ClassificationResult.NoMatch(provenance)
                }, telemetry.registry)
                observed.classify(request)
                assertSame(parent, telemetry.registry.currentObservation)
            }
            parent.stop()
            assertNull(telemetry.registry.currentObservation)
        }

        @Test
        fun `thrown failures keep identity and restore scope without recording sensitive error`() {
            val telemetry = Telemetry()
            val parent = Observation.start("caller", telemetry.registry)
            val failure = IllegalStateException(secret)
            parent.openScope().use {
                val thrown = assertThrows<IllegalStateException> {
                    ObservedDecisionService(decision(assess = { throw failure }), telemetry.registry).assess(proposition)
                }
                assertSame(failure, thrown)
                assertSame(parent, telemetry.registry.currentObservation)
            }
            val context = telemetry.recorder.stopped.single()
            assertEquals("exception", context.getLowCardinalityKeyValue("outcome")!!.value)
            assertSafeError(context.error, "exception")
            assertEquals(listOf(context.error), telemetry.recorder.errors)
            assertEquals(1L, telemetry.meters.get("embabel.ai.decision").tag("outcome", "exception").timer().count())
            parent.stop()
        }

        @Test
        fun `unknown provider category is rejected inside observation`() {
            val telemetry = Telemetry()
            assertThrows<IllegalArgumentException> {
                ObservedDecisionService(decision(classify = {
                    ClassificationResult.Selected(secret, provenance)
                }), telemetry.registry).classify(request)
            }
            assertEquals("exception", telemetry.recorder.stopped.single().getLowCardinalityKeyValue("outcome")!!.value)
            assertNull(telemetry.registry.currentObservation)
        }

        @Test
        fun `telemetry error handlers cannot replace provider failures`() {
            val handlerFailure = IllegalStateException(telemetryFailureMessage)
            val registry = ObservationRegistry.create().apply {
                observationConfig().observationHandler(object : ObservationHandler<Observation.Context> {
                    override fun supportsContext(context: Observation.Context) = true
                    override fun onError(context: Observation.Context) = throw handlerFailure
                })
            }
            val providerFailure = IllegalStateException(secret)
            val thrown = assertThrows<IllegalStateException> {
                ObservedClassificationService(classifier { throw providerFailure }, registry).classify(request)
            }
            assertSame(providerFailure, thrown)
            assertNull(registry.currentObservation)
        }

        @Test
        fun `checked telemetry handler failures cannot replace service behavior`() {
            val registry = ObservationRegistry.create().apply {
                observationConfig().observationHandler(object : ObservationHandler<Observation.Context> {
                    override fun supportsContext(context: Observation.Context) = true
                    override fun onStop(context: Observation.Context) = throw IOException(telemetryFailureMessage)
                })
            }
            val result = ClassificationResult.NoMatch(provenance)
            assertSame(result, ObservedClassificationService(classifier { result }, registry).classify(request))
            assertNull(registry.currentObservation)
        }

        @Test
        fun `partial scope opens unwind handler thread locals before the provider runs`() {
            val handlerScope = ThreadLocal<String?>()
            val registry = ObservationRegistry.create().apply {
                observationConfig()
                    .observationHandler(object : ObservationHandler<Observation.Context> {
                        override fun supportsContext(context: Observation.Context) = true
                        override fun onScopeOpened(context: Observation.Context) = handlerScope.set(context.name)
                        override fun onScopeClosed(context: Observation.Context) = handlerScope.remove()
                    })
                    .observationHandler(object : ObservationHandler<Observation.Context> {
                        override fun supportsContext(context: Observation.Context) = true
                        override fun onScopeOpened(context: Observation.Context) = throw IOException(telemetryFailureMessage)
                    })
            }
            val result = ClassificationResult.NoMatch(provenance)
            val observed = ObservedClassificationService(classifier {
                assertNull(handlerScope.get())
                result
            }, registry)
            assertSame(result, observed.classify(request))
            assertNull(handlerScope.get())
            assertNull(registry.currentObservation)
        }

        @Test
        fun `telemetry stop handlers cannot replace results or provider failures`() {
            val registry = ObservationRegistry.create().apply {
                observationConfig().observationHandler(object : ObservationHandler<Observation.Context> {
                    override fun supportsContext(context: Observation.Context) = true
                    override fun onStop(context: Observation.Context) =
                        throw IllegalStateException(telemetryFailureMessage)
                })
            }
            val result = ClassificationResult.NoMatch(provenance)
            assertSame(result, ObservedClassificationService(classifier { result }, registry).classify(request))
            val providerFailure = IllegalStateException(secret)
            val thrown = assertThrows<IllegalStateException> {
                ObservedDecisionService(decision(assess = { throw providerFailure }), registry).assess(proposition)
            }
            assertSame(providerFailure, thrown)
            assertNull(registry.currentObservation)
        }

        @Test
        fun `telemetry scope close failures restore the previous scope`() {
            var failNextScopeClose = true
            val registry = ObservationRegistry.create().apply {
                observationConfig().observationHandler(object : ObservationHandler<Observation.Context> {
                    override fun supportsContext(context: Observation.Context) = true
                    override fun onScopeClosed(context: Observation.Context) {
                        if (failNextScopeClose) {
                            failNextScopeClose = false
                            throw IllegalStateException(telemetryFailureMessage)
                        }
                    }
                })
            }
            val parent = Observation.start("caller", registry)
            parent.openScope().use {
                val result = ClassificationResult.NoMatch(provenance)
                assertSame(result, ObservedClassificationService(classifier { result }, registry).classify(request))
                assertSame(parent, registry.currentObservation)
            }
            parent.stop()
            assertNull(registry.currentObservation)
        }

        @Test
        fun `cancellation and interruption propagate unchanged with fixed outcomes`() {
            val telemetry = Telemetry()
            val cancellation = CancellationException(secret)
            assertSame(cancellation, assertThrows<CancellationException> {
                ObservedClassificationService(classifier { throw cancellation }, telemetry.registry).classify(request)
            })
            val interruption = InterruptedException(secret)
            try {
                assertFalse(Thread.currentThread().isInterrupted)
                assertSame(interruption, assertThrows<InterruptedException> {
                    ObservedDecisionService(decision(assess = { throw interruption }), telemetry.registry).assess(proposition)
                })
                assertFalse(Thread.currentThread().isInterrupted)
                Thread.currentThread().interrupt()
                assertSame(interruption, assertThrows<InterruptedException> {
                    ObservedDecisionService(decision(assess = { throw interruption }), telemetry.registry).assess(proposition)
                })
                assertTrue(Thread.currentThread().isInterrupted)
                ObservedClassificationService(classifier { ClassificationResult.NoMatch(provenance) }, telemetry.registry)
                    .classify(request)
                assertTrue(Thread.currentThread().isInterrupted)
            } finally {
                Thread.interrupted()
            }
            assertEquals(listOf("cancelled", "interrupted", "interrupted", "no_match"), telemetry.recorder.stopped.map {
                it.getLowCardinalityKeyValue("outcome")!!.value
            })
            assertEquals(listOf("cancelled", "interrupted", "interrupted"), telemetry.recorder.errors.map { it.message })
            telemetry.recorder.errors.forEach { assertSafeError(it, it.message!!) }
            assertNull(telemetry.registry.currentObservation)
        }
    }

    @Nested
    inner class Diagnostics {
        @Test
        fun `logging failures cannot replace service results or provider failures`() {
            val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
            val filter = object : TurboFilter() {
                override fun decide(
                    marker: Marker?,
                    logger: Logger?,
                    level: Level?,
                    format: String?,
                    params: Array<out Any?>?,
                    throwable: Throwable?,
                ): FilterReply {
                    if (logger?.name == observationLoggerName) throw IOException(telemetryFailureMessage)
                    return FilterReply.NEUTRAL
                }
            }.apply {
                context = loggerContext
                start()
            }
            loggerContext.addTurboFilter(filter)
            try {
                val result = ClassificationResult.NoMatch(provenance)
                assertSame(result, ObservedClassificationService(classifier { result }).classify(request))

                val registry = ObservationRegistry.create().apply {
                    observationConfig().observationHandler(object : ObservationHandler<Observation.Context> {
                        override fun supportsContext(context: Observation.Context) = true
                        override fun onError(context: Observation.Context) =
                            throw IOException(telemetryFailureMessage)
                    })
                }
                val providerFailure = IllegalStateException(secret)
                val thrown = assertThrows<IllegalStateException> {
                    ObservedDecisionService(decision(assess = { throw providerFailure }), registry).assess(proposition)
                }
                assertSame(providerFailure, thrown)
                assertNull(registry.currentObservation)
            } finally {
                loggerContext.turboFilterList.remove(filter)
                filter.stop()
            }
        }

        @Test
        fun `debug diagnostics contain only fixed operation and outcome without throwable`() {
            val logger = LoggerFactory.getLogger(observationLoggerName) as Logger
            val previous = logger.level
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            logger.level = Level.DEBUG
            try {
                ObservedClassificationService(classifier { ClassificationResult.NoMatch(provenance) }).classify(request)
                assertThrows<IllegalStateException> {
                    ObservedDecisionService(decision(assess = { throw IllegalStateException(secret) })).assess(proposition)
                }
                assertEquals(listOf("AI classify completed with outcome no_match", "AI assess completed with outcome exception"),
                    appender.list.map { it.formattedMessage })
                assertTrue(appender.list.all { it.throwableProxy == null })
                assertTrue(appender.list.none { it.formattedMessage.contains(secret) })
            } finally {
                logger.level = previous
                logger.detachAppender(appender)
                appender.stop()
            }
        }
    }
}
