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
import com.embabel.common.ai.decision.spi.DecisionExecution
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

    /** Assess the proposition; a false answer is successful evidence, not an operational failure. */
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
}

private data class DecisionServiceMetadataImpl(
    override val name: String,
    override val provider: String,
) : DecisionServiceMetadata
