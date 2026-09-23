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
package com.embabel.agent.decision

import org.jetbrains.annotations.ApiStatus
import java.util.concurrent.Callable

/** Finite provider families suitable for logs, metrics, and traces. */
@ApiStatus.Experimental
enum class DecisionProviderFamily { TYPESAFE, PROMPTED, NONE, STUB, CUSTOM }

/** Bounded execution events. No request or provider data is attached. */
@ApiStatus.Experimental
enum class DecisionTelemetryEvent {
    PROVIDER_ATTEMPT,
    RETRY,
    TRANSPORT_SUCCESS,
    TRANSPORT_RATE_LIMITED,
    TRANSPORT_UNAVAILABLE,
    TRANSPORT_REJECTED,
    CAPACITY_REJECTED,
    MODEL_CLOSED,
    CANCELLATION,
    TIMEOUT,
    REJECTION,
}

/** Terminal state of one observed call. */
@ApiStatus.Experimental
enum class DecisionCompletionStatus { SUCCESS, FAILURE }

/** Payload-free context created once for an observed call. */
@ApiStatus.Experimental
class DecisionObservationContext private constructor(
    val providerFamily: DecisionProviderFamily,
    val questionCount: Int,
) {
    internal companion object {
        fun create(providerFamily: DecisionProviderFamily, questionCount: Int) =
            DecisionObservationContext(providerFamily, questionCount)
    }
}

/** Payload-free terminal summary created once for an observed call. */
@ApiStatus.Experimental
class DecisionCompletion private constructor(
    val providerFamily: DecisionProviderFamily,
    val status: DecisionCompletionStatus,
    val safeCode: DecisionSafeCode?,
    val keySuccessCount: Int,
    val keyFailureCount: Int,
    val elapsedNanos: Long,
) {
    internal companion object {
        fun create(
            providerFamily: DecisionProviderFamily,
            status: DecisionCompletionStatus,
            safeCode: DecisionSafeCode?,
            keySuccessCount: Int,
            keyFailureCount: Int,
            elapsedNanos: Long,
        ) = DecisionCompletion(
            providerFamily,
            status,
            safeCode,
            keySuccessCount,
            keyFailureCount,
            elapsedNanos,
        )
    }
}

/**
 * Optional instrumentation entry point.
 *
 * Implementations must be thread-safe. They receive bounded metadata only and must not recover or
 * retain decision payloads through thread-local state. A session belongs to one call.
 */
@ApiStatus.Experimental
fun interface DecisionInstrumentation {
    @ApiStatus.Experimental
    fun start(context: DecisionObservationContext): DecisionObservation

    companion object {
        /** Returns the allocation-free instrumentation used when no adapter is installed. */
        @ApiStatus.Experimental
        @JvmStatic
        fun noop(): DecisionInstrumentation = NoOpDecisionInstrumentation
    }
}

/**
 * One instrumentation session. [wrap] captures adapter-owned context for provider execution.
 *
 * Implementations must call wrapped work exactly once and return promptly without blocking the
 * caller's deadline. Methods can run concurrently when provider work emits events from a worker
 * thread while the caller owns the terminal lifecycle. Provider events admitted before the facade
 * seals their sink may finish concurrently; no new provider event is admitted after sealing.
 */
@ApiStatus.Experimental
interface DecisionObservation : AutoCloseable {
    @ApiStatus.Experimental
    fun <T> wrap(work: Callable<T>): Callable<T>
    @ApiStatus.Experimental
    fun event(event: DecisionTelemetryEvent)
    @ApiStatus.Experimental
    fun complete(completion: DecisionCompletion)
    @ApiStatus.Experimental
    override fun close()
}

private object NoOpDecisionInstrumentation : DecisionInstrumentation {
    override fun start(context: DecisionObservationContext): DecisionObservation = NoOpDecisionObservation
}

private object NoOpDecisionObservation : DecisionObservation {
    override fun <T> wrap(work: Callable<T>): Callable<T> = work
    override fun event(event: DecisionTelemetryEvent) = Unit
    override fun complete(completion: DecisionCompletion) = Unit
    override fun close() = Unit
}
