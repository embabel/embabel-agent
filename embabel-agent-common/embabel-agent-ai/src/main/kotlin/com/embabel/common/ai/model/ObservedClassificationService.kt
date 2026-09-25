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

    private class SafeFailure(outcome: Outcome) : RuntimeException(outcome.tag, null, false, false)

    fun classify(request: ClassificationRequest, work: () -> ClassificationResult): ClassificationResult =
        observe(Operation.CLASSIFY, ::classificationOutcome) { request.validate(work()) }

    fun assess(work: () -> PropositionResult): PropositionResult = observe(Operation.ASSESS, ::propositionOutcome, work)

    /** Validate and execute inside the call scope, recording only bounded diagnostics on every completion. */
    private fun <T> observe(operation: Operation, outcomeOf: (T) -> Outcome, work: () -> T): T {
        val observation = Observation.createNotStarted(operation.observationName, registry)
            .lowCardinalityKeyValue(OPERATION, operation.tag)
            .start()
        var outcome = Outcome.EXCEPTION
        try {
            return observation.openScope().use {
                try {
                    work().also {
                        outcome = outcomeOf(it)
                        if (outcome == Outcome.FAILURE) observation.error(SafeFailure(outcome))
                    }
                } catch (failure: Throwable) {
                    outcome = when (failure) {
                        is InterruptedException -> Outcome.INTERRUPTED
                        is CancellationException -> Outcome.CANCELLED
                        else -> Outcome.EXCEPTION
                    }
                    observation.error(SafeFailure(outcome))
                    throw failure
                }
            }
        } finally {
            observation.lowCardinalityKeyValue(OUTCOME, outcome.tag)
            observation.stop()
            logger.debug("AI {} completed with outcome {}", operation.tag, outcome.tag)
        }
    }

    /** Map only result variants to labels; provider evidence never enters the observation. */
    private fun classificationOutcome(result: ClassificationResult): Outcome = when (result) {
        is ClassificationResult.Selected -> Outcome.SELECTED
        is ClassificationResult.NoMatch -> Outcome.NO_MATCH
        is ClassificationResult.Inconclusive -> Outcome.INCONCLUSIVE
        is ClassificationResult.Failure -> Outcome.FAILURE
    }

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
