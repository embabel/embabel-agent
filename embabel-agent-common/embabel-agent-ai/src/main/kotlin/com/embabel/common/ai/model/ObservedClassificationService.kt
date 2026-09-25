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

import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.decision.PropositionResult
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.jetbrains.annotations.ApiStatus
import org.slf4j.LoggerFactory
import java.util.concurrent.CancellationException

/**
 * Opt-in observations for classification, with the delegate's metadata and model family preserved.
 * Each call emits `embabel.ai.classification` with fixed `operation` and `outcome` tags and validates
 * the selected category against the request. Provider observations inherit the open call scope.
 * Payloads, model identifiers and raw exceptions are never added to these observations or debug logs.
 * Operational failures and thrown exceptions record a fixed, stackless error marker with no cause.
 * The original exception is rethrown unchanged; the wrapper never changes thread interruption state.
 * Non-fatal observation lifecycle exceptions use bounded diagnostics when logging is available and
 * never replace service behavior. JVM error types propagate.
 */
@ApiStatus.Experimental
class ObservedClassificationService @JvmOverloads constructor(
    private val delegate: ClassificationService,
    observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
) : ClassificationService by delegate {
    private val observation = ServiceCallObservation(observationRegistry)

    override fun classify(request: ClassificationRequest): ClassificationResult =
        observation.classify(request) { delegate.classify(request) }
}

/** Shared call lifecycle for both service decorators, without retaining requests or results in telemetry. */
@ApiStatus.Internal
internal class ServiceCallObservation(private val registry: ObservationRegistry) {
    private enum class Operation(val observationName: String, val tag: String) {
        CLASSIFY("embabel.ai.classification", "classify"),
        ASSESS("embabel.ai.decision", "assess"),
    }

    private enum class Outcome(val tag: String) {
        SELECTED("selected"),
        NO_MATCH("no_match"),
        INCONCLUSIVE("inconclusive"),
        FAILURE("failure"),
        ANSWERED("answered"),
        EXCEPTION("exception"),
        CANCELLED("cancelled"),
        INTERRUPTED("interrupted"),
    }

    private enum class TelemetryPhase(val tag: String) {
        START("start"),
        OPEN_SCOPE("open_scope"),
        RECORD_OUTCOME("record_outcome"),
        RECORD_ERROR("record_error"),
        CLOSE_SCOPE("close_scope"),
        RESTORE_SCOPE("restore_scope"),
        STOP("stop"),
    }

    private class SafeFailure(outcome: Outcome) : RuntimeException(outcome.tag, null, false, false)

    fun classify(request: ClassificationRequest, work: () -> ClassificationResult): ClassificationResult =
        observe(Operation.CLASSIFY, ::classificationOutcome) { request.validate(work()) }

    fun assess(work: () -> PropositionResult): PropositionResult = observe(Operation.ASSESS, ::propositionOutcome, work)

    /** Validate and execute inside the call scope, recording only bounded diagnostics on every completion. */
    private fun <T> observe(operation: Operation, outcomeOf: (T) -> Outcome, work: () -> T): T {
        val observation = startObservation(operation)
        val scope = observation?.let { openScope(it, operation) }
        var outcome = Outcome.EXCEPTION
        try {
            return work().also {
                outcome = outcomeOf(it)
                observation?.let { current ->
                    recordOutcome(current, operation, outcome)
                    if (outcome == Outcome.FAILURE) recordError(current, operation, outcome)
                }
            }
        } catch (failure: Throwable) {
            outcome = when (failure) {
                is InterruptedException -> Outcome.INTERRUPTED
                is CancellationException -> Outcome.CANCELLED
                else -> Outcome.EXCEPTION
            }
            observation?.let {
                recordOutcome(it, operation, outcome)
                recordError(it, operation, outcome)
            }
            throw failure
        } finally {
            scope?.let { closeScope(it, operation) }
            observation?.let {
                recordOutcome(it, operation, outcome)
                stop(it, operation)
            }
            logCompletion(operation, outcome)
        }
    }

    /** Start telemetry without allowing a broken convention or handler to prevent the provider call. */
    private fun startObservation(operation: Operation): Observation? {
        var observation: Observation? = null
        return try {
            observation = Observation.createNotStarted(operation.observationName, registry)
                .lowCardinalityKeyValue(OPERATION, operation.tag)
            observation.start()
        } catch (_: Exception) {
            observation?.let { stop(it, operation) }
            logTelemetryFailure(operation, TelemetryPhase.START)
            null
        }
    }

    /** Open the provider scope and unwind callbacks that completed before a later handler failed. */
    private fun openScope(observation: Observation, operation: Operation): Observation.Scope? {
        val previous = registry.currentObservationScope
        return try {
            observation.openScope()
        } catch (_: Exception) {
            val partial = registry.currentObservationScope
            if (partial != null && partial !== previous && partial.currentObservation === observation) {
                closeScope(partial, operation, previous)
            } else {
                restoreRegistryScope(previous, operation)
            }
            logTelemetryFailure(operation, TelemetryPhase.OPEN_SCOPE)
            null
        }
    }

    /** Close the provider scope without allowing a handler failure to leak it or replace the call result. */
    private fun closeScope(
        scope: Observation.Scope,
        operation: Operation,
        previous: Observation.Scope? = scope.previousObservationScope,
    ) {
        try {
            scope.close()
        } catch (_: Exception) {
            restoreRegistryScope(previous, operation)
            logTelemetryFailure(operation, TelemetryPhase.CLOSE_SCOPE)
        }
    }

    /** Restore the registry pointer after a handler interrupts Micrometer's normal scope cleanup. */
    private fun restoreRegistryScope(previous: Observation.Scope?, operation: Operation) {
        try {
            registry.setCurrentObservationScope(previous)
        } catch (_: Exception) {
            logTelemetryFailure(operation, TelemetryPhase.RESTORE_SCOPE)
        }
    }

    /** Record a bounded outcome while keeping telemetry failures outside the service contract. */
    private fun recordOutcome(observation: Observation, operation: Operation, outcome: Outcome) {
        try {
            observation.lowCardinalityKeyValue(OUTCOME, outcome.tag)
        } catch (_: Exception) {
            logTelemetryFailure(operation, TelemetryPhase.RECORD_OUTCOME)
        }
    }

    /** Notify handlers with a stackless marker without exposing or replacing the provider failure. */
    private fun recordError(observation: Observation, operation: Operation, outcome: Outcome) {
        try {
            observation.error(SafeFailure(outcome))
        } catch (_: Exception) {
            logTelemetryFailure(operation, TelemetryPhase.RECORD_ERROR)
        }
    }

    /** Stop telemetry without allowing exporters to replace a successful result or provider failure. */
    private fun stop(observation: Observation, operation: Operation) {
        try {
            observation.stop()
        } catch (_: Exception) {
            logTelemetryFailure(operation, TelemetryPhase.STOP)
        }
    }

    /** Log only bounded lifecycle labels; handler exceptions can contain provider payloads or credentials. */
    private fun logTelemetryFailure(operation: Operation, phase: TelemetryPhase) {
        try {
            logger.warn("AI {} observation failed during {}", operation.tag, phase.tag)
        } catch (_: Exception) {
            // Diagnostics must remain outside the service contract even when the logging backend fails.
        }
    }

    /** Emit the bounded completion diagnostic without allowing a logging backend to alter the call. */
    private fun logCompletion(operation: Operation, outcome: Outcome) {
        try {
            logger.debug("AI {} completed with outcome {}", operation.tag, outcome.tag)
        } catch (_: Exception) {
            // Diagnostics must remain outside the service contract even when the logging backend fails.
        }
    }

    /** Map only result variants to labels; provider evidence never enters the observation. */
    private fun classificationOutcome(result: ClassificationResult): Outcome = when (result) {
        is ClassificationResult.Selected -> Outcome.SELECTED
        is ClassificationResult.NoMatch -> Outcome.NO_MATCH
        is ClassificationResult.Inconclusive -> Outcome.INCONCLUSIVE
        is ClassificationResult.Failure -> Outcome.FAILURE
    }

    /** Map proposition result variants to labels without inspecting provider evidence. */
    private fun propositionOutcome(result: PropositionResult): Outcome = when (result) {
        is PropositionResult.Answered -> Outcome.ANSWERED
        is PropositionResult.Inconclusive -> Outcome.INCONCLUSIVE
        is PropositionResult.Failure -> Outcome.FAILURE
    }

    private companion object {
        const val OPERATION = "operation"
        const val OUTCOME = "outcome"
        val logger = LoggerFactory.getLogger(ServiceCallObservation::class.java)
    }
}
