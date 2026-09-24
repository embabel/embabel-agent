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
package com.embabel.agent.decision.api

import org.jetbrains.annotations.ApiStatus
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private class FixedDecisionProvider(
    private val outcome: RawDecisionOutcome,
) : CallerBoundDecisionProvider {
    override fun invoke(request: PreparedDecisionRequest): RawDecisionOutcome = outcome
}

/** Creates a caller-bound model that consistently reports [CallFailure.Disabled]. */
@ApiStatus.Experimental
object NoDecisionModel {
    /** Returns a new facade named `none`; admitted provider calls report Disabled without external work. */
    @JvmStatic
    fun create(): DecisionModel = DecisionModel(
        FixedDecisionProvider(
            RawDecisionOutcome.failure(CallFailure.Disabled, DecisionSafeCode.DISABLED),
        ),
    ).named("none", "none")
}

/** One deterministic raw outcome, returned immediately or after an interruptible delay. */
@ApiStatus.Experimental
interface StubStep {
    companion object {
        /** Supplies raw evidence without a delay; the facade still validates it when the step is consumed. */
        @JvmStatic
        fun immediate(raw: RawDecisionOutcome): StubStep = StubStepData(Duration.ZERO, raw)

        /**
         * Supplies raw evidence after a nonnegative, interruptible delay on the calling thread.
         * A delay at least as long as the remaining request budget returns DeadlineExceeded without
         * sleeping. Interruption during the delay becomes Cancelled through the facade. Either case
         * consumes the step. Zero delay has the same behavior as [immediate].
         */
        @JvmStatic
        fun after(delay: Duration, raw: RawDecisionOutcome): StubStep {
            require(!delay.isNegative) { "delay must not be negative" }
            return StubStepData(delay, raw)
        }
    }
}

private data class StubStepData(
    val delay: Duration,
    val raw: RawDecisionOutcome,
) : StubStep

private class StubDecisionProvider(
    private val scripted: List<StubStepData>,
) : CallerBoundDecisionProvider {
    private val cursor = AtomicInteger()

    /**
     * Claims the next step before checking interruption or remaining time so failed attempts cannot
     * replay it. Atomic claims give concurrent invocations distinct steps; completion order can differ.
     */
    override fun invoke(request: PreparedDecisionRequest): RawDecisionOutcome {
        val step = scripted.getOrNull(cursor.getAndIncrement())
            ?: return RawDecisionOutcome.failure(CallFailure.Unsupported, DecisionSafeCode.UNSUPPORTED)
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val delay = step.delay.toNanos()
        if (delay >= request.remainingNanos()) {
            return RawDecisionOutcome.failure(
                CallFailure.DeadlineExceeded,
                DecisionSafeCode.DEADLINE_EXCEEDED,
            )
        }
        if (delay > 0) TimeUnit.NANOSECONDS.sleep(delay)
        return step.raw
    }
}

/**
 * Creates a caller-bound deterministic model for tests and offline examples.
 *
 * Steps are consumed in order after provider invocation begins. Exhaustion returns Unsupported;
 * delayed steps respect the request's remaining absolute deadline and sleep interruptibly.
 */
@ApiStatus.Experimental
object StubDecisionModel {
    /**
     * Copies a nonempty script made with [StubStep.immediate] and [StubStep.after] into a new model.
     * Custom StubStep implementations are rejected. Each provider invocation consumes one step;
     * request rejection or wrapper failure before invocation leaves the script untouched.
     * After exhaustion, invocations return Unsupported. Raw results pass through normal facade
     * validation, including canonical failure codes. Facades copied from this model share its cursor.
     */
    @JvmStatic
    fun create(steps: List<StubStep>): DecisionModel {
        require(steps.isNotEmpty()) { "steps must not be empty" }
        val scripted = steps.map { step ->
            step as? StubStepData ?: throw IllegalArgumentException("foreign stub step")
        }
        return DecisionModel(StubDecisionProvider(scripted)).named("stub", "stub")
    }
}
