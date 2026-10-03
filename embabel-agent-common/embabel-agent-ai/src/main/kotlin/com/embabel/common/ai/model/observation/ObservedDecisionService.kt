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
import com.embabel.common.ai.classification.ClassificationSpec
import com.embabel.common.ai.decision.DecisionCapabilities
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.PropositionQuestionSpec
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.RatingQuestionSpec
import com.embabel.common.ai.decision.RatingResult
import com.embabel.common.ai.decision.spi.DecisionExecution
import com.embabel.common.ai.decision.spi.DelegatingDecisionService
import com.embabel.common.ai.decision.spi.NativeQuestionSetExecution
import com.embabel.common.ai.decision.spi.PropositionAssessment
import com.embabel.common.ai.decision.spi.RatingAssessment
import io.micrometer.observation.ObservationRegistry
import org.jetbrains.annotations.ApiStatus

/**
 * Opt-in observations for decision services, preserving the delegate's DECISION metadata snapshot.
 * Classification emits one `embabel.ai.classification` observation; proposition assessment emits one
 * `embabel.ai.decision` observation. Both carry only fixed `operation` and `outcome` tags. False is an
 * `answered` outcome, just like true. Operational failures and exceptions use a fixed, stackless
 * error marker with no cause. Original exceptions are rethrown unchanged and never logged.
 * Thread interruption state is untouched. Non-fatal observation lifecycle exceptions use bounded
 * diagnostics when logging is available and never replace service behavior. JVM error types propagate.
 *
 * An ask emits one logical `embabel.ai.ask` observation plus one `embabel.ai.decision` observation
 * per provider call made inside it: `ask_native` for a native call, and `assess` or `rate` for each
 * proposition or rating question asked on its own. A choice question asked on its own goes through
 * `classify` and emits one `embabel.ai.classification` observation. The capabilities are the delegate's. Preflight checks them
 * against the hook interfaces of the delegate, so a delegate whose capabilities claim a hook it does
 * not implement fails with an [IllegalStateException] before any provider call. Every ask overload
 * runs through the shared execution path, so a delegate's own `ask` override is not called.
 */
// Members are forwarded by hand: interface delegation would send default methods such as ask past the observation.
@Suppress("kotlin:S6514")
@ApiStatus.Experimental
class ObservedDecisionService @JvmOverloads constructor(
    private val delegate: DecisionService,
    observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
) : DecisionService by delegate,
    DelegatingDecisionService,
    NativeQuestionSetExecution,
    PropositionAssessment,
    RatingAssessment {
    private val observation = ServiceCallObservation(observationRegistry)

    /**
     * The service that does the work. Nested decorators are unwrapped, so preflight and the hook
     * guards see the hooks of the innermost delegate, and a lying descriptor fails before any
     * provider observation opens.
     */
    override val hookSource: DecisionService = DecisionExecution.hookSourceOf(delegate)

    // Inspect the decorator that receives forwarded calls, even through nested observations.
    private val forwardingDelegate: DecisionService =
        (delegate as? ObservedDecisionService)?.forwardingDelegate ?: delegate

    override fun classify(request: ClassificationRequest): ClassificationResult =
        observation.classify(request) { delegate.classify(request) }

    // Interface delegation would forward this straight to the delegate and skip the observation.
    override fun classify(input: String, spec: ClassificationSpec): ClassificationResult =
        classify(ClassificationRequest.of(input, spec))

    override fun assess(request: PropositionRequest): PropositionResult = observation.assess { delegate.assess(request) }

    /**
     * Returns the delegate's capabilities.
     *
     * @return the delegate's capabilities, unchanged
     */
    override fun capabilities(): DecisionCapabilities = delegate.capabilities()

    override fun ask(input: String, spec: DecisionSpec): DecisionResponse = ask(DecisionRequest.of(input, spec))

    /**
     * Answers a request inside one `embabel.ai.ask` observation. Each provider call is a child
     * observation. Preflight uses the delegate's capabilities and hooks.
     *
     * @param request the input and the questions to answer
     * @return one answer per question, in spec order
     * @throws com.embabel.common.ai.decision.UnsupportedDecisionException if the delegate cannot
     * answer the request. No provider call has been made.
     * @throws IllegalStateException if the delegate's capabilities claim a hook it does not implement
     */
    override fun ask(request: DecisionRequest): DecisionResponse =
        observation.ask(name, provider, request) {
            if (forwardingDelegate is DelegatingDecisionService) {
                DecisionExecution.plan(name, capabilities(), hookSource, request, forwardingDelegate)
            }
            DecisionExecution.execute(this, request, hookSource = hookSource)
        }

    /**
     * Runs the delegate's native call inside one `ask_native` observation.
     *
     * @throws IllegalStateException if the delegate does not implement [NativeQuestionSetExecution]
     */
    override fun askNative(request: DecisionRequest): DecisionResponse {
        val hook = requireHook<NativeQuestionSetExecution>("NativeQuestionSetExecution")
        return observation.native { hook.askNative(request) }
    }

    /**
     * Runs the delegate's proposition question call inside one `assess` observation.
     *
     * @throws IllegalStateException if the delegate does not implement [PropositionAssessment]
     */
    override fun assess(input: String, question: PropositionQuestionSpec): PropositionResult {
        val hook = requireHook<PropositionAssessment>("PropositionAssessment")
        return observation.assess { hook.assess(input, question) }
    }

    /**
     * Runs the delegate's rating call inside one `rate` observation.
     *
     * @throws IllegalStateException if the delegate does not implement [RatingAssessment]
     */
    override fun rate(input: String, question: RatingQuestionSpec): RatingResult {
        val hook = requireHook<RatingAssessment>("RatingAssessment")
        return observation.rate { hook.rate(input, question) }
    }

    /**
     * Runs before any provider observation opens. The hook is called on the direct delegate, which
     * for a nested decorator applies its own observation.
     *
     * @param hookName the hook interface's name, for the error message
     * @return the delegate, cast to the hook interface
     * @throws IllegalStateException if the hook source or its forwarding decorator lacks the hook
     */
    private inline fun <reified H> requireHook(hookName: String): H {
        check(hookSource is H) {
            "Decision service '${hookSource.name}' does not implement $hookName, but ObservedDecisionService " +
                "was asked to run it. Implement $hookName in the service, or remove the question kinds it answers " +
                "from its capabilities."
        }
        check(forwardingDelegate is H) {
            "Decision decorator '${forwardingDelegate.name}' (${forwardingDelegate.javaClass.name}) does not " +
                "implement $hookName. Implement $hookName on the decorator and forward calls to the hook source."
        }
        return delegate as H
    }
}
