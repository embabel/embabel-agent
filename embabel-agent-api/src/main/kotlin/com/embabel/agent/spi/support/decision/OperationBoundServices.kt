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
package com.embabel.agent.spi.support.decision

import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.ClassificationSpec
import com.embabel.common.ai.decision.DecisionCapabilities
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.PropositionQuestionSpec
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.RatingQuestionSpec
import com.embabel.common.ai.decision.RatingResult
import com.embabel.common.ai.decision.spi.DelegatingDecisionService
import com.embabel.common.ai.decision.spi.NativeQuestionSetExecution
import com.embabel.common.ai.decision.spi.PropositionAssessment
import com.embabel.common.ai.decision.spi.RatingAssessment
import com.embabel.common.ai.model.ClassificationService
import com.embabel.common.ai.model.ClassificationServiceMetadata
import com.embabel.common.ai.model.DecisionService
import com.embabel.common.ai.model.DecisionServiceMetadata
import com.embabel.common.ai.model.DecisionServiceRegistry
import com.embabel.common.ai.model.ModelType
import com.embabel.common.ai.model.ServiceSelector
import com.embabel.common.ai.model.observation.ObservedClassificationService
import com.embabel.common.ai.model.observation.ObservedDecisionService
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry

/**
 * Selectors that bind each selected service to the observation current at selection.
 *
 * A workflow operation selects a service while its action observation is current. Each call on
 * the returned binding runs with that observation as the parent, on any thread, so provider-call
 * observations nest under the operation. The binding creates no observation of its own and
 * registers nothing. A service passed to `using` that is not already an observed decorator is
 * wrapped in one on the registry's observation registry, so each of its asks records one
 * `embabel.ai.ask` observation.
 */
internal object OperationBoundServices {

    /**
     * Returns a decision selector over [registry] whose terminals return operation-bound services.
     *
     * @param registry the registry to resolve names, roles and defaults against
     * @return the binding selector
     */
    fun decisions(registry: DecisionServiceRegistry): ServiceSelector<DecisionService> =
        BindingSelector(
            registry.decisions(),
            registry.observationRegistry,
            { service -> observedDecision(service, registry.observationRegistry) },
        ) { service, parent ->
            bindDecision(service, parent, registry.observationRegistry)
        }

    /**
     * Returns a classification selector over [registry] whose terminals return operation-bound
     * services. A selected decision service stays a decision service.
     *
     * @param registry the registry to resolve names, roles and defaults against
     * @return the binding selector
     */
    fun classifications(registry: DecisionServiceRegistry): ServiceSelector<ClassificationService> =
        BindingSelector(
            registry.classifications(),
            registry.observationRegistry,
            { service -> observedClassification(service, registry.observationRegistry) },
        ) { service, parent ->
            bindClassification(service, parent, registry.observationRegistry)
        }

    /**
     * Wraps a decision service in an observed decorator unless it already is one. A binding is
     * unwrapped first, so an observed service under a binding is not wrapped again.
     *
     * @param service the decision service to wrap
     * @param observationRegistry registry used by the wrapping decorator
     * @return the service, observed exactly once
     */
    private fun observedDecision(service: DecisionService, observationRegistry: ObservationRegistry): DecisionService {
        val raw = if (service is OperationBoundDecisionService) service.delegate else service
        return raw as? ObservedDecisionService ?: ObservedDecisionService(raw, observationRegistry)
    }

    /**
     * Wraps a classification service in an observed decorator unless it already is one, keeping a
     * decision service wrapped as a decision decorator. A binding is unwrapped first.
     *
     * @param service the classification service to wrap
     * @param observationRegistry registry used by the wrapping decorator
     * @return the service, observed exactly once
     */
    private fun observedClassification(
        service: ClassificationService,
        observationRegistry: ObservationRegistry,
    ): ClassificationService = when (val raw = unbound(service)) {
        is ObservedDecisionService, is ObservedClassificationService -> raw
        is DecisionService -> ObservedDecisionService(raw, observationRegistry)
        else -> ObservedClassificationService(raw, observationRegistry)
    }

    /**
     * Returns a service's binding delegate, or the service itself when it isn't bound.
     *
     * @param service the classification service to unwrap
     * @return the unbound service
     */
    private fun unbound(service: ClassificationService): ClassificationService = when (service) {
        is OperationBoundDecisionService -> service.delegate
        is OperationBoundClassificationService -> service.delegate
        else -> service
    }

    /**
     * Binds a decision service to the given parent observation, replacing any existing binding.
     *
     * @param service the decision service to bind
     * @param parent the observation future calls should run under
     * @param observationRegistry registry the binding runs against
     * @return the bound service
     */
    private fun bindDecision(
        service: DecisionService,
        parent: Observation?,
        observationRegistry: ObservationRegistry,
    ): DecisionService {
        val raw = if (service is OperationBoundDecisionService) service.delegate else service
        return OperationBoundDecisionService(raw, parent, observationRegistry)
    }

    /**
     * Binds a classification service to the given parent observation, keeping a decision service
     * bound as a decision service.
     *
     * @param service the classification service to bind
     * @param parent the observation future calls should run under
     * @param observationRegistry registry the binding runs against
     * @return the bound service
     */
    private fun bindClassification(
        service: ClassificationService,
        parent: Observation?,
        observationRegistry: ObservationRegistry,
    ): ClassificationService {
        val raw = unbound(service)
        return if (raw is DecisionService) {
            OperationBoundDecisionService(raw, parent, observationRegistry)
        } else {
            OperationBoundClassificationService(raw, parent, observationRegistry)
        }
    }
}

/**
 * A selector that resolves through [selector] and binds the result to the observation current on
 * [observationRegistry] when the terminal runs. A service passed to [using] goes through [observe]
 * before it is bound.
 */
private class BindingSelector<S : ClassificationService>(
    private val selector: ServiceSelector<S>,
    private val observationRegistry: ObservationRegistry,
    private val observe: (S) -> S,
    private val bind: (S, Observation?) -> S,
) : ServiceSelector<S> {

    /**
     * Resolves the family default from the wrapped selector and binds it to the current observation.
     *
     * @return the bound default service
     */
    override fun defaultService(): S = bound(selector.defaultService())

    /**
     * Resolves the named service from the wrapped selector and binds it to the current observation.
     *
     * @param name the service's registration name
     * @return the bound service
     */
    override fun named(name: String): S = bound(selector.named(name))

    /**
     * Resolves the service bound to the given role from the wrapped selector and binds it to the
     * current observation.
     *
     * @param role the role to resolve
     * @return the bound service
     */
    override fun byRole(role: String): S = bound(selector.byRole(role))

    /**
     * Observes the given service through the wrapped selector and binds it to the current
     * observation.
     *
     * @param service the service to use directly
     * @return the bound service
     */
    override fun using(service: S): S = bound(observe(selector.using(service)))

    /**
     * Binds the given service to the observation current on [observationRegistry].
     *
     * @param service the service to bind
     * @return the bound service
     */
    private fun bound(service: S): S = bind(service, observationRegistry.currentObservation)
}

/**
 * Runs [block] with [parent] as the current observation. When [parent] is null or already current,
 * [block] runs directly. Otherwise the parent's scope is opened for the call and closed afterwards,
 * which restores the scope the thread held before.
 */
private inline fun <R> withParent(
    parent: Observation?,
    observationRegistry: ObservationRegistry,
    block: () -> R,
): R {
    if (parent == null || observationRegistry.currentObservation === parent) return block()
    val scope = parent.openScope()
    try {
        return block()
    } finally {
        scope.close()
    }
}

/**
 * A decision service that forwards every call to [delegate] with the selecting operation's
 * observation as the parent.
 *
 * It implements every execution hook so that a decorator above it can reach the delegate's hooks,
 * and reports the delegate's hook source so that decorator's preflight inspects the hooks of the
 * service that does the work. A hook call throws [IllegalStateException] when the delegate does not
 * implement that hook. [ask] calls the delegate's own `ask`, so the delegate's preflight checks each
 * request.
 */
@Suppress("kotlin:S6514")
// Explicit forwarding keeps a default method added to the interface later from bypassing the operation binding.
internal class OperationBoundDecisionService(
    val delegate: DecisionService,
    private val parent: Observation?,
    private val observationRegistry: ObservationRegistry,
) : DecisionService,
    DelegatingDecisionService,
    NativeQuestionSetExecution,
    PropositionAssessment,
    RatingAssessment {

    override val hookSource: DecisionService = (delegate as? DelegatingDecisionService)?.hookSource ?: delegate

    override val name: String get() = delegate.name

    override val provider: String get() = delegate.provider

    override val type: ModelType get() = delegate.type

    override fun metadata(): DecisionServiceMetadata = delegate.metadata()

    override fun infoString(verbose: Boolean?, indent: Int): String = delegate.infoString(verbose, indent)

    override fun capabilities(): DecisionCapabilities = delegate.capabilities()

    override fun classify(request: ClassificationRequest): ClassificationResult =
        withParent(parent, observationRegistry) { delegate.classify(request) }

    override fun classify(input: String, spec: ClassificationSpec): ClassificationResult =
        withParent(parent, observationRegistry) { delegate.classify(input, spec) }

    override fun assess(request: PropositionRequest): PropositionResult =
        withParent(parent, observationRegistry) { delegate.assess(request) }

    override fun ask(input: String, spec: DecisionSpec): DecisionResponse =
        withParent(parent, observationRegistry) { delegate.ask(input, spec) }

    override fun ask(request: DecisionRequest): DecisionResponse =
        withParent(parent, observationRegistry) { delegate.ask(request) }

    override fun askNative(request: DecisionRequest): DecisionResponse {
        val hook = delegate as? NativeQuestionSetExecution
            ?: throw missingHook("NativeQuestionSetExecution", "askNative", "the question kinds it answers only natively")
        return withParent(parent, observationRegistry) { hook.askNative(request) }
    }

    override fun assess(input: String, question: PropositionQuestionSpec): PropositionResult {
        val hook = delegate as? PropositionAssessment
            ?: throw missingHook("PropositionAssessment", "assess", "PROPOSITION")
        return withParent(parent, observationRegistry) { hook.assess(input, question) }
    }

    override fun rate(input: String, question: RatingQuestionSpec): RatingResult {
        val hook = delegate as? RatingAssessment
            ?: throw missingHook("RatingAssessment", "rate", "RATING")
        return withParent(parent, observationRegistry) { hook.rate(input, question) }
    }

    override fun toString(): String = "OperationBoundDecisionService(delegate=$delegate)"

    /**
     * Builds the error for a delegate that doesn't implement an optional execution hook.
     *
     * @param hook the hook interface the delegate is missing
     * @param method the method that needed the hook
     * @param capability the capability the delegate should drop if it can't implement the hook
     * @return the exception to throw
     */
    private fun missingHook(hook: String, method: String, capability: String) = IllegalStateException(
        "Decision service '${delegate.name}' (${delegate.javaClass.name}) does not implement $hook, so $method " +
            "cannot run. Implement $hook on the service, or remove $capability from its capabilities().",
    )
}

/**
 * A classification service that forwards every call to [delegate] with the selecting operation's
 * observation as the parent.
 */
@Suppress("kotlin:S6514")
// Explicit forwarding keeps a default method added to the interface later from bypassing the operation binding.
internal class OperationBoundClassificationService(
    val delegate: ClassificationService,
    private val parent: Observation?,
    private val observationRegistry: ObservationRegistry,
) : ClassificationService {

    override val name: String get() = delegate.name

    override val provider: String get() = delegate.provider

    override val type: ModelType get() = delegate.type

    override fun metadata(): ClassificationServiceMetadata = delegate.metadata()

    override fun infoString(verbose: Boolean?, indent: Int): String = delegate.infoString(verbose, indent)

    override fun classify(request: ClassificationRequest): ClassificationResult =
        withParent(parent, observationRegistry) { delegate.classify(request) }

    override fun classify(input: String, spec: ClassificationSpec): ClassificationResult =
        withParent(parent, observationRegistry) { delegate.classify(input, spec) }

    override fun toString(): String = "OperationBoundClassificationService(delegate=$delegate)"
}
