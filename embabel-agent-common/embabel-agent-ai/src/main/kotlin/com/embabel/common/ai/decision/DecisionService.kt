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
package com.embabel.common.ai.decision

import com.embabel.common.ai.classification.ClassificationService
import com.embabel.common.ai.classification.ClassificationServiceMetadata
import com.embabel.common.ai.decision.spi.DecisionCalls
import com.embabel.common.ai.decision.spi.DecisionExecution
import com.embabel.common.ai.decision.spi.DecisionExecutor
import com.embabel.common.ai.decision.spi.ExecutorDecisionService
import com.embabel.common.ai.model.ModelKind
import com.embabel.common.ai.model.ModelName
import com.embabel.common.ai.model.ModelType
import org.jetbrains.annotations.ApiStatus
import tools.jackson.databind.annotation.JsonDeserialize
import java.util.function.Consumer

/** Pure metadata for the decision family, which also supports classification. */
@ApiStatus.Experimental
@JsonDeserialize(`as` = DecisionServiceMetadataImpl::class)
interface DecisionServiceMetadata : ClassificationServiceMetadata {
    override val type: ModelType get() = ModelType.DECISION

    companion object {
        /** Create serializable metadata without retaining a live decision service. */
        @JvmStatic
        fun create(name: String, provider: String): DecisionServiceMetadata = DecisionServiceMetadataImpl(name, provider)
    }
}

/**
 * Marks the receiver of the Kotlin block that configures a decision service, so code inside the
 * block can't reach an outer builder by accident.
 */
@ApiStatus.Experimental
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
annotation class DecisionServiceDsl

/** Classification plus proposition assessment. Applications own any routing or revision policy. */
@ApiStatus.Experimental
interface DecisionService : ClassificationService, DecisionServiceMetadata {
    /** Family remains DECISION even when this service is used only for classification. */
    override val type: ModelType get() = ModelType.DECISION

    /**
     * Assess the proposition; a false answer is successful evidence, not an operational failure.
     *
     * @throws UnsupportedDecisionException if provider-specific limits reject the request before any provider call
     */
    fun assess(request: PropositionRequest): PropositionResult

    /** Pure DECISION metadata snapshot; never serialize a live service in place of this value. */
    override fun metadata(): DecisionServiceMetadata = DecisionServiceMetadata.create(name, provider)

    /**
     * Returns what this service can accept.
     *
     * The default derives from the hook interfaces the service implements. Every service accepts
     * proposition questions, and choice questions because it can classify. `RatingAssessment` adds
     * rating questions. Override this method to list the kinds a `QuestionSetExecution`
     * service answers. Capabilities that claim a
     * kind the service backs with neither its hook nor question-set execution make every affected request
     * fail with an [IllegalStateException] before any provider call.
     *
     * @return this service's capabilities
     */
    fun capabilities(): DecisionCapabilities = DecisionExecution.defaultCapabilities(this)

    /**
     * Answers the questions of a spec against the given input. It behaves as [ask] with a request.
     *
     * @param input the text the model reasons over, which may be empty
     * @param spec the questions to answer
     * @return one answer per question, in spec order
     * @throws com.embabel.common.ai.decision.UnsupportedDecisionException if this service cannot
     * answer the spec. No provider call has been made.
     */
    fun ask(input: String, spec: DecisionSpec): DecisionResponse = ask(DecisionRequest.of(input, spec))

    /**
     * Answers a request.
     *
     * The whole request is checked against [capabilities] before any provider call. A service that
     * implements `QuestionSetExecution` answers the whole request in one call. Any other
     * service answers each question in spec order: a proposition through `PropositionAssessment`
     * when implemented and through [assess] otherwise, a choice through [classify] with a
     * classification request built from the question, and a rating through `RatingAssessment`. Provider failures come back as typed failure outcomes, and
     * the remaining questions are still asked. A decorator routes this call through the shared
     * execution path, so a delegate's own override of this method is not called through a
     * decorator. A service customizes execution through [capabilities] and the hook interfaces.
     *
     * An interruption surfaces as an unchecked [java.util.concurrent.CancellationException] whose
     * cause is the [InterruptedException], with the thread's interrupt flag set, so callers never
     * handle a checked exception.
     *
     * @param request the input and the questions to answer
     * @return one answer per question, in spec order
     * @throws com.embabel.common.ai.decision.UnsupportedDecisionException if this service cannot
     * answer the request. No provider call has been made.
     * @throws java.util.concurrent.CancellationException if the thread is interrupted during the ask
     */
    fun ask(request: DecisionRequest): DecisionResponse = DecisionExecution.execute(this, request)

    /**
     * Answers a call, which can carry structured input and options for this call alone.
     *
     * A service that implements `DecisionCallExecution`, such as one made with [from], gets the
     * whole call. A decorator over such a service must implement it as well and forward the call,
     * or this method throws an [IllegalStateException]. Any other service gets a text request
     * through [ask]: a text input as it is and a JSON input as its text. Such a service refuses a
     * call with options, a call that requires native JSON, and any other input kind, so an option
     * is never silently dropped.
     *
     * @param call the input, the questions and any call options
     * @return one answer per question, in spec order
     * @throws com.embabel.common.ai.decision.UnsupportedDecisionException if this service cannot
     * answer the call. No provider call has been made.
     * @throws IllegalStateException if a decorator does not forward calls its hook source answers
     */
    fun ask(call: DecisionCall): DecisionResponse = DecisionCalls.ask(this, call)

    /**
     * The decision model kind, and ways to make a decision service from one function.
     */
    companion object {

        /** Decision models. A role binds one to a [ModelName]. */
        @JvmField
        val KIND: ModelKind<DecisionService, ModelName> = ModelKind("decision", DecisionService::class.java)

        /**
         * Makes a decision service from one function that answers a whole call.
         *
         * The service reports the given metadata and capabilities. Every way of asking it, including
         * [classify] and [assess], reaches the executor as one call. Before the executor runs, the
         * call is checked against the capabilities: question kinds, the question limit, option and
         * level counts, the input kind and the call options. A call that fails a check throws
         * [UnsupportedDecisionException] and the executor is not called. A JSON input goes to the
         * executor as JSON when the capabilities list the JSON input kind and as text otherwise.
         * A response that doesn't answer the call's spec becomes an invalid-response failure for
         * every question. An exception the executor throws reaches the caller unchanged.
         *
         * ```kotlin
         * val service = DecisionService.from(metadata, capabilities) { call ->
         *     myProvider.answer(call)
         * }
         * ```
         *
         * @param metadata the service's name and provider
         * @param capabilities what the service accepts
         * @param executor the function that answers a checked call
         * @return the decision service
         */
        @JvmStatic
        fun from(
            metadata: DecisionServiceMetadata,
            capabilities: DecisionCapabilities,
            executor: DecisionExecutor,
        ): DecisionService = ExecutorDecisionService(metadata, capabilities, executor)

        /**
         * Starts configuring a decision service made from one function. Use this from Java when
         * the service needs default options or a preflight check. A builder with nothing set
         * makes the same service as [from].
         *
         * Once a call passes the capability checks, the defaults fill in any option it leaves
         * unset and the preflight runs. When the call then has a [DecisionOptions.TIMEOUT], the
         * executor gets a call whose timeout is the time left since the ask began. With no time
         * left, every question fails as unavailable and the executor is not called. A timeout of
         * zero or less throws [UnsupportedDecisionException] before the executor is called.
         *
         * @param metadata the service's name and provider
         * @param capabilities what the service accepts
         * @param executor the function that answers a checked call
         * @return a builder with no defaults and no preflight
         */
        @JvmStatic
        fun builder(
            metadata: DecisionServiceMetadata,
            capabilities: DecisionCapabilities,
            executor: DecisionExecutor,
        ): DecisionServiceBuilder = DecisionServiceBuilder(metadata, capabilities, executor, null, emptyMap())

        /**
         * Makes a decision service from one function, configured with a Kotlin block. The block
         * sets the same things as [builder] and the service behaves the same way.
         *
         * ```kotlin
         * val service = DecisionService(metadata, capabilities, executor) {
         *     defaultOption(DecisionOptions.TIMEOUT, Duration.ofSeconds(10))
         *     preflight { call -> quota.check() }
         * }
         * ```
         *
         * @param metadata the service's name and provider
         * @param capabilities what the service accepts
         * @param executor the function that answers a checked call
         * @param block sets defaults and a preflight
         * @return the decision service
         * @throws IllegalArgumentException if a default is for an option the capabilities don't list
         */
        @JvmSynthetic
        operator fun invoke(
            metadata: DecisionServiceMetadata,
            capabilities: DecisionCapabilities,
            executor: DecisionExecutor,
            block: DecisionServiceScope.() -> Unit,
        ): DecisionService =
            DecisionServiceScope().apply(block).applyTo(builder(metadata, capabilities, executor)).build()
    }
}

/**
 * Configures a decision service made from one function, for callers in Java. Every method
 * hands back a new builder and leaves this one as it was, so a builder can be shared and
 * extended safely. Get one from [DecisionService.builder].
 *
 * ```java
 * DecisionService service = DecisionService.builder(metadata, capabilities, executor)
 *     .withDefaultOption(DecisionOptions.TIMEOUT, Duration.ofSeconds(10))
 *     .withPreflight(call -> quota.check())
 *     .build();
 * ```
 */
@ApiStatus.Experimental
class DecisionServiceBuilder internal constructor(
    private val metadata: DecisionServiceMetadata,
    private val capabilities: DecisionCapabilities,
    private val executor: DecisionExecutor,
    private val preflight: Consumer<DecisionCall>?,
    private val defaults: Map<DecisionOption<*>, Any>,
) {

    /**
     * Returns a builder whose service runs a check of its own before the executor. The check
     * gets the call the executor would get, after the capability checks pass and the defaults
     * are filled in, and stops the ask by throwing. The exception reaches the caller and the
     * executor is not called. Setting a preflight again replaces the earlier one.
     *
     * @param check the check to run before each executor call
     * @return a new builder
     */
    fun withPreflight(check: Consumer<DecisionCall>): DecisionServiceBuilder =
        DecisionServiceBuilder(metadata, capabilities, executor, check, defaults)

    /**
     * Returns a builder whose service uses the given value for an option whenever a call
     * doesn't set that option itself. A value set on the call always wins. Setting a default
     * for the same option again replaces its value.
     *
     * A default for [DecisionOptions.TIMEOUT] gives every call a time budget unless the call
     * brings its own.
     *
     * @param key the option to give a default
     * @param value the value to use when a call leaves the option unset
     * @return a new builder
     */
    fun <T : Any> withDefaultOption(key: DecisionOption<T>, value: T): DecisionServiceBuilder {
        val copy = LinkedHashMap(defaults)
        copy[key] = value
        return DecisionServiceBuilder(metadata, capabilities, executor, preflight, copy)
    }

    /**
     * Makes the decision service. It behaves as one made with [DecisionService.from], plus
     * the defaults and the preflight set on this builder.
     *
     * @return the decision service
     * @throws IllegalArgumentException if a default is for an option the capabilities don't
     * list, or its value isn't an instance of the option's type
     */
    fun build(): DecisionService = ExecutorDecisionService(
        metadata,
        capabilities,
        executor,
        preflight?.let { check -> { call: DecisionCall -> check.accept(call) } },
        defaults,
    )
}

/**
 * The receiver of the Kotlin block that configures a decision service made from one function.
 * Each setting works like the matching [DecisionServiceBuilder] method.
 */
@DecisionServiceDsl
@ApiStatus.Experimental
class DecisionServiceScope internal constructor() {

    private var preflight: Consumer<DecisionCall>? = null

    private val defaults = LinkedHashMap<DecisionOption<*>, Any>()

    /**
     * Runs a check of the provider's own before the executor, as
     * [DecisionServiceBuilder.withPreflight] does. Setting a preflight again replaces the earlier one.
     *
     * @param check the check to run before each executor call, which stops the ask by throwing
     */
    fun preflight(check: (DecisionCall) -> Unit) {
        preflight = Consumer { call -> check(call) }
    }

    /**
     * Uses a value for an option whenever a call doesn't set it, as
     * [DecisionServiceBuilder.withDefaultOption] does. Setting the same option again replaces its value.
     *
     * @param key the option to give a default
     * @param value the value to use when a call leaves the option unset
     */
    fun <T : Any> defaultOption(key: DecisionOption<T>, value: T) {
        defaults[key] = value
    }

    internal fun applyTo(builder: DecisionServiceBuilder): DecisionServiceBuilder {
        val withDefaults = defaults.entries.fold(builder) { current, (key, value) ->
            current.withDefault(key, value)
        }
        return preflight?.let { withDefaults.withPreflight(it) } ?: withDefaults
    }

    private fun <T : Any> DecisionServiceBuilder.withDefault(
        key: DecisionOption<T>,
        value: Any,
    ): DecisionServiceBuilder = withDefaultOption(key, key.type.cast(value))
}

private data class DecisionServiceMetadataImpl(
    override val name: String,
    override val provider: String,
) : DecisionServiceMetadata
