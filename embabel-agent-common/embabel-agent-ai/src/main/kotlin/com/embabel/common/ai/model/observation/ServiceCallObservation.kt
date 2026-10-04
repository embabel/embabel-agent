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

import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.decision.PropositionResult
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.jetbrains.annotations.ApiStatus
import org.slf4j.LoggerFactory
import java.util.concurrent.CancellationException

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
        observe(Operation.CLASSIFY, ::classificationOutcome) { request.spec.validate(work()) }

    fun assess(work: () -> PropositionResult): PropositionResult = observe(Operation.ASSESS, ::propositionOutcome, work)

    /**
     * Runs the work inside the call scope and records only bounded diagnostics, however the call ends.
     *
     * @param operation the call being observed
     * @param outcomeOf turns the work's result into an outcome label
     * @param work the service call
     * @return the work's result
     */
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
                // Providers report an interruption as a cancellation caused by it.
                is CancellationException ->
                    if (failure.cause is InterruptedException) Outcome.INTERRUPTED else Outcome.CANCELLED
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

    /**
     * Starts the observation. A broken convention or handler cannot stop the provider call.
     *
     * @param operation the call being observed
     * @return the started observation, or null if starting failed
     */
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

    /**
     * Opens the provider scope, and unwinds the callbacks that ran before a later handler failed.
     *
     * @param observation the started observation
     * @param operation the call being observed
     * @return the open scope, or null if opening failed
     */
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

    /**
     * Closes the provider scope. A handler failure cannot leak the scope or replace the call result.
     *
     * @param scope the scope to close
     * @param operation the call being observed
     * @param previous the scope to restore if closing fails
     */
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

    /**
     * Puts back the registry's current scope after a handler breaks Micrometer's normal cleanup.
     *
     * @param previous the scope to make current again
     * @param operation the call being observed
     */
    private fun restoreRegistryScope(previous: Observation.Scope?, operation: Operation) {
        try {
            registry.setCurrentObservationScope(previous)
        } catch (_: Exception) {
            logTelemetryFailure(operation, TelemetryPhase.RESTORE_SCOPE)
        }
    }

    /**
     * Records the outcome tag. A telemetry failure here does not reach the caller.
     *
     * @param observation the observation to tag
     * @param operation the call being observed
     * @param outcome the outcome to record
     */
    private fun recordOutcome(observation: Observation, operation: Operation, outcome: Outcome) {
        try {
            observation.lowCardinalityKeyValue(OUTCOME, outcome.tag)
        } catch (_: Exception) {
            logTelemetryFailure(operation, TelemetryPhase.RECORD_OUTCOME)
        }
    }

    /**
     * Tells handlers about an error with a stackless marker. The provider failure itself stays hidden and unchanged.
     *
     * @param observation the observation to mark
     * @param operation the call being observed
     * @param outcome the outcome the marker names
     */
    private fun recordError(observation: Observation, operation: Operation, outcome: Outcome) {
        try {
            observation.error(SafeFailure(outcome))
        } catch (_: Exception) {
            logTelemetryFailure(operation, TelemetryPhase.RECORD_ERROR)
        }
    }

    /**
     * Stops the observation. An exporter failure cannot replace the result or the provider failure.
     *
     * @param observation the observation to stop
     * @param operation the call being observed
     */
    private fun stop(observation: Observation, operation: Operation) {
        try {
            observation.stop()
        } catch (_: Exception) {
            logTelemetryFailure(operation, TelemetryPhase.STOP)
        }
    }

    /**
     * Logs a telemetry failure with fixed labels only. Handler exceptions can hold payloads or credentials, so they stay out of the log.
     *
     * @param operation the call being observed
     * @param phase the lifecycle phase that failed
     */
    private fun logTelemetryFailure(operation: Operation, phase: TelemetryPhase) {
        try {
            logger.warn("AI {} observation failed during {}", operation.tag, phase.tag)
        } catch (_: Exception) {
            // Diagnostics must remain outside the service contract even when the logging backend fails.
        }
    }

    /**
     * Logs the call's completion with fixed labels. A logging backend failure cannot change the call.
     *
     * @param operation the call being observed
     * @param outcome how the call ended
     */
    private fun logCompletion(operation: Operation, outcome: Outcome) {
        try {
            logger.debug("AI {} completed with outcome {}", operation.tag, outcome.tag)
        } catch (_: Exception) {
            // Diagnostics must remain outside the service contract even when the logging backend fails.
        }
    }

    /**
     * Names the outcome of a classification result. Provider evidence never enters the observation.
     *
     * @param result the classification result
     * @return the outcome label
     */
    private fun classificationOutcome(result: ClassificationResult): Outcome = when (result) {
        is ClassificationResult.Selected -> Outcome.SELECTED
        is ClassificationResult.NoMatch -> Outcome.NO_MATCH
        is ClassificationResult.Inconclusive -> Outcome.INCONCLUSIVE
        is ClassificationResult.Failure -> Outcome.FAILURE
    }

    /**
     * Names the outcome of a proposition result without looking at the provider evidence.
     *
     * @param result the proposition result
     * @return the outcome label
     */
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
