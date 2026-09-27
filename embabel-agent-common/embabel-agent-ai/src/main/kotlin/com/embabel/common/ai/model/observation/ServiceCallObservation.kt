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
import com.embabel.common.ai.decision.ChoiceQuestionSpec
import com.embabel.common.ai.decision.DecisionAnswer
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.RatingResult
import com.embabel.common.ai.decision.UnsupportedDecisionException
import io.micrometer.common.KeyValues
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.jetbrains.annotations.ApiStatus
import org.slf4j.LoggerFactory
import java.util.concurrent.CancellationException

// The observation name of every decision provider call except classify.
private const val DECISION_OBSERVATION = "embabel.ai.decision"

/**
 * Shared call lifecycle for the service decorators, without retaining requests or results in telemetry.
 *
 * Provider calls emit `embabel.ai.classification` or `embabel.ai.decision` with the tag keys `operation`
 * and `outcome` for every operation, so all meters under one name share one key set. A logical ask emits
 * `embabel.ai.ask` with the keys `operation`, `outcome`, `service`, `provider` and `question_count`,
 * all set on every ask. Every tag value comes from a fixed label set except `service`
 * and `provider`. `service` is the answering service's own name and `provider` its provider. Both are
 * bounded by configuration, so a service must not take its name from request or user data.
 */
@ApiStatus.Internal
internal class ServiceCallObservation(private val registry: ObservationRegistry) {
    private enum class Operation(val observationName: String, val tag: String) {
        CLASSIFY("embabel.ai.classification", "classify"),
        ASSESS(DECISION_OBSERVATION, "assess"),
        ASK("embabel.ai.ask", "ask"),
        ASK_NATIVE(DECISION_OBSERVATION, "ask_native"),
        CHOOSE(DECISION_OBSERVATION, "choose"),
        RATE(DECISION_OBSERVATION, "rate"),
    }

    // Outcomes that mark the observation with the stackless error marker although the call returned.
    private enum class Outcome(val tag: String, val marksError: Boolean = false) {
        SELECTED("selected"),
        NO_MATCH("no_match"),
        INCONCLUSIVE("inconclusive"),
        FAILURE("failure", marksError = true),
        INVALID_RESPONSE("invalid_response", marksError = true),
        ANSWERED("answered"),
        COMPLETE("complete"),
        PARTIAL("partial"),
        REQUEST_FAILURE("request_failure", marksError = true),
        UNSUPPORTED("unsupported"),
        EXCEPTION("exception"),
        CANCELLED("cancelled"),
        INTERRUPTED("interrupted"),
    }

    private enum class TelemetryPhase(val tag: String) {
        START("start"),
        OPEN_SCOPE("open_scope"),
        RECORD_OUTCOME("record_outcome"),
        RECORD_ERROR("record_error"),
        RECORD_EVENT("record_event"),
        CLOSE_SCOPE("close_scope"),
        RESTORE_SCOPE("restore_scope"),
        STOP("stop"),
    }

    private class SafeFailure(outcome: Outcome) : RuntimeException(outcome.tag, null, false, false)

    fun classify(request: ClassificationRequest, work: () -> ClassificationResult): ClassificationResult =
        observe(Operation.CLASSIFY, ::classificationOutcome, validate = request.spec::validate, work = work)

    fun assess(work: () -> PropositionResult): PropositionResult = observe(Operation.ASSESS, ::propositionOutcome, work = work)

    /**
     * Observes one logical ask as `embabel.ai.ask`. Provider calls made inside [work] become child
     * observations. An [UnsupportedDecisionException] records the outcome `unsupported`. After every tag is
     * final, each answer records one event named `answer.<kind>.<outcome>` whose contextual name is
     * `<question name> <kind> <outcome>`. The meter handler counts it as
     * `embabel.ai.ask.answer.<kind>.<outcome>` with the ask's tags, and tracing records the contextual
     * name as a span event. Question names appear in contextual names only.
     *
     * @param service the answering service's own name
     * @param provider the answering service's provider
     * @param request the request, read only for its question count and spec id
     * @param work the execution that produces the response
     * @return the response [work] returned, unchanged
     */
    fun ask(service: String, provider: String, request: DecisionRequest, work: () -> DecisionResponse): DecisionResponse {
        val startTags = KeyValues.of(
            SERVICE, service,
            PROVIDER, provider,
            QUESTION_COUNT, questionCountBucket(request.spec.questions.size),
        )
        return observe(
            Operation.ASK,
            ::responseOutcome,
            startTags = startTags,
            resultEvents = { answerEvents(request, it) },
            work = work,
        )
    }

    /** Observes one native question-set call as `embabel.ai.decision` with the operation `ask_native`. */
    fun native(work: () -> DecisionResponse): DecisionResponse = observe(Operation.ASK_NATIVE, ::responseOutcome, work = work)

    /**
     * Observes one choice call as `embabel.ai.decision` with the operation `choose`. The selection is
     * validated against [question], as `classify` validates against its request. A selection outside
     * the question's options records the outcome `invalid_response`.
     */
    fun choose(question: ChoiceQuestionSpec, work: () -> ClassificationResult): ClassificationResult =
        observe(Operation.CHOOSE, ::classificationOutcome, validate = question::validate, work = work)

    /** Observes one rating call as `embabel.ai.decision` with the operation `rate`. */
    fun rate(work: () -> RatingResult): RatingResult = observe(Operation.RATE, ::ratingOutcome, work = work)

    /**
     * Executes and validates inside the call scope, recording only bounded diagnostics on every
     * completion. An [IllegalArgumentException] from [validate] means the provider's answer does not
     * fit the request, and records the outcome `invalid_response`.
     */
    private fun <T> observe(
        operation: Operation,
        outcomeOf: (T) -> Outcome,
        startTags: KeyValues = KeyValues.empty(),
        resultEvents: (T) -> List<Observation.Event> = { emptyList() },
        validate: (T) -> T = { it },
        work: () -> T,
    ): T {
        val observation = startObservation(operation, startTags)
        val scope = observation?.let { openScope(it, operation) }
        var outcome = Outcome.EXCEPTION
        var validating = false
        try {
            val result = work()
            validating = true
            return validate(result).also {
                validating = false
                outcome = outcomeOf(it)
                observation?.let { current ->
                    recordOutcome(current, operation, outcome)
                    if (outcome.marksError) recordError(current, operation, outcome)
                    resultEvents(it).forEach { event -> recordEvent(current, operation, event) }
                }
            }
        } catch (failure: Throwable) {
            outcome = when {
                validating && failure is IllegalArgumentException -> Outcome.INVALID_RESPONSE
                else -> thrownOutcome(operation, failure)
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

    private fun thrownOutcome(operation: Operation, failure: Throwable): Outcome = when (failure) {
        is UnsupportedDecisionException -> if (operation == Operation.ASK) Outcome.UNSUPPORTED else Outcome.EXCEPTION
        is InterruptedException -> Outcome.INTERRUPTED
        // Providers report an interruption as a cancellation caused by it.
        is CancellationException -> if (failure.cause is InterruptedException) Outcome.INTERRUPTED else Outcome.CANCELLED
        else -> Outcome.EXCEPTION
    }

    /** Start telemetry without allowing a broken convention or handler to prevent the provider call. */
    private fun startObservation(operation: Operation, startTags: KeyValues): Observation? {
        var observation: Observation? = null
        return try {
            observation = Observation.createNotStarted(operation.observationName, registry)
                .lowCardinalityKeyValue(OPERATION, operation.tag)
                .lowCardinalityKeyValues(startTags)
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

    /** Record one answer event. A failing handler loses that event and leaves the call result unchanged. */
    private fun recordEvent(observation: Observation, operation: Operation, event: Observation.Event) {
        try {
            observation.event(event)
        } catch (_: Exception) {
            logTelemetryFailure(operation, TelemetryPhase.RECORD_EVENT)
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

    /** Map rating result variants to labels without inspecting provider evidence. */
    private fun ratingOutcome(result: RatingResult): Outcome = when (result) {
        is RatingResult.Answered -> Outcome.ANSWERED
        is RatingResult.Inconclusive -> Outcome.INCONCLUSIVE
        is RatingResult.Failure -> Outcome.FAILURE
    }

    /** A request failure outranks per-question failures. Any failed answer makes the response partial. */
    private fun responseOutcome(response: DecisionResponse): Outcome = when {
        response.requestFailure != null -> Outcome.REQUEST_FAILURE
        response.answers.any { answerOutcome(it) == Outcome.FAILURE } -> Outcome.PARTIAL
        else -> Outcome.COMPLETE
    }

    private fun answerOutcome(answer: DecisionAnswer): Outcome = when (answer) {
        is DecisionAnswer.Proposition -> propositionOutcome(answer.outcome)
        is DecisionAnswer.Choice -> classificationOutcome(answer.outcome)
        is DecisionAnswer.Rating -> ratingOutcome(answer.outcome)
    }

    /**
     * One event per answer, in spec order. Events are recorded only for a response to the request's
     * spec, whose answer names are then the spec's question names.
     */
    private fun answerEvents(request: DecisionRequest, response: DecisionResponse): List<Observation.Event> {
        if (response.definitionId != request.spec.definitionId) return emptyList()
        return response.answers.map { answer ->
            val kind = when (answer) {
                is DecisionAnswer.Proposition -> "proposition"
                is DecisionAnswer.Choice -> "choice"
                is DecisionAnswer.Rating -> "rating"
            }
            val outcome = answerOutcome(answer).tag
            Observation.Event.of("answer.$kind.$outcome", "${answer.name} $kind $outcome")
        }
    }

    // Fixed buckets keep the tag bounded for any spec size.
    private fun questionCountBucket(count: Int): String = when {
        count <= 1 -> "1"
        count <= 4 -> "2-4"
        count <= 16 -> "5-16"
        else -> "17+"
    }

    private companion object {
        const val OPERATION = "operation"
        const val OUTCOME = "outcome"
        const val SERVICE = "service"
        const val PROVIDER = "provider"
        const val QUESTION_COUNT = "question_count"
        val logger = LoggerFactory.getLogger(ServiceCallObservation::class.java)
    }
}
