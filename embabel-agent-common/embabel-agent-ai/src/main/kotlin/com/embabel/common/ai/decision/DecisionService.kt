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
     * The decision model kind, and a way to make a decision service from one function.
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
    }
}

private data class DecisionServiceMetadataImpl(
    override val name: String,
    override val provider: String,
) : DecisionServiceMetadata
