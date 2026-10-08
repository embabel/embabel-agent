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
package com.embabel.agent.spi.decision

import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.agent.spi.support.decision.LlmClassificationService
import com.embabel.agent.spi.support.decision.LlmDecisionService
import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.ClassificationService
import com.embabel.common.ai.classification.ClassificationSpec
import com.embabel.common.ai.decision.DecisionCapabilities
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.decision.DecisionServiceMetadata
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.ai.model.ModelSelectionCriteria
import com.embabel.common.ai.model.NoSuitableModelException
import com.embabel.common.ai.model.PreResolvedModelSelectionCriteria
import com.embabel.common.ai.model.observation.ObservedClassificationService
import com.embabel.common.ai.model.observation.ObservedDecisionService
import io.micrometer.observation.ObservationRegistry
import org.jetbrains.annotations.ApiStatus

/**
 * Builds decision and classification services that ask a chat model.
 *
 * Each service records one `embabel.ai.decision` or `embabel.ai.classification` observation per
 * call. A model named here is looked up once, when the service is built. To use a model the caller
 * already holds, such as one built for a user's own API key, pass the [LlmService] instead. A service
 * built from [ModelSelectionCriteria] looks its model up on every call.
 *
 * Applications inject the `LlmDecisionServiceFactory` bean from the Spring context.
 */
@ApiStatus.Experimental
class LlmDecisionServiceFactory @ApiStatus.Internal @JvmOverloads constructor(
    private val llmOperations: LlmOperations,
    private val modelProvider: ModelProvider,
    private val retry: RetryProperties,
    private val observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
) {

    /**
     * Builds a decision service for the model with this name.
     *
     * @throws IllegalArgumentException if the name is blank
     * @throws NoSuitableModelException if no model has this name
     */
    @Throws(NoSuitableModelException::class)
    fun decisionService(llmName: String): DecisionService = decisionService(llmNamed(llmName))

    /** Builds a decision service for a model the caller already holds. */
    fun decisionService(llm: LlmService<*>): DecisionService =
        observedDecisionService(llmDecisionService(llm, retry, "decision-${llm.name}"))

    /**
     * Builds a decision service that asks the model provider for its model on every call,
     * for criteria whose answer can change while the application runs, such as the default
     * model or a role. A model configured after startup is used without a restart. Each call
     * builds the service it forwards to, which only wraps objects.
     */
    fun decisionService(criteria: ModelSelectionCriteria): DecisionService =
        ResolvingDecisionService(criteria)

    /**
     * Builds a classification service for the model with this name.
     *
     * @throws IllegalArgumentException if the name is blank
     * @throws NoSuitableModelException if no model has this name
     */
    @Throws(NoSuitableModelException::class)
    fun classificationService(llmName: String): ClassificationService = classificationService(llmNamed(llmName))

    /** Builds a classification service for a model the caller already holds. */
    fun classificationService(llm: LlmService<*>): ClassificationService =
        observedClassificationService(llmDecisionService(llm, retry, "classification-${llm.name}"))

    /**
     * Looks up the model with this name.
     *
     * @param llmName the model name to resolve
     * @return the matching LLM
     * @throws IllegalArgumentException if the name is blank
     * @throws NoSuitableModelException if no model has this name
     */
    private fun llmNamed(llmName: String): LlmService<*> {
        require(llmName.isNotBlank()) { "LLM name must not be blank" }
        return modelProvider.getLlm(ModelSelectionCriteria.byName(llmName))
    }

    /**
     * Wraps a decision service so every call is recorded as an observation.
     *
     * @param service the raw decision service
     * @return the observed decision service
     */
    private fun observedDecisionService(service: LlmDecisionService): DecisionService =
        ObservedDecisionService(service, observationRegistry)

    /**
     * Wraps a decision service as a classification service, recording every call as an observation.
     *
     * @param service the raw decision service
     * @return the observed classification service
     */
    private fun observedClassificationService(service: LlmDecisionService): ClassificationService =
        ObservedClassificationService(LlmClassificationService(service), observationRegistry)

    /**
     * Builds a decision service pinned to this exact model, so the model provider is never asked
     * again for it.
     *
     * @param llm the resolved model to use
     * @param retry the retry settings for calls
     * @param retryName the name retry log lines carry
     * @return the built decision service
     */
    private fun llmDecisionService(llm: LlmService<*>, retry: RetryProperties, retryName: String) =
        LlmDecisionService(llmOperations, llm, LlmOptions(PreResolvedModelSelectionCriteria(llm)), retry, retryName)

    /**
     * A decision service that asks the model provider for its model on every call and forwards the
     * call to a service built over that model. One ask resolves the model once, so every question
     * in it is answered by the same model.
     *
     * @param criteria how to pick the model on each call
     */
    private inner class ResolvingDecisionService(private val criteria: ModelSelectionCriteria) : DecisionService {

        override val name: String get() = current().name

        override val provider: String get() = current().provider

        override fun classify(request: ClassificationRequest): ClassificationResult = current().classify(request)

        override fun classify(input: String, spec: ClassificationSpec): ClassificationResult =
            current().classify(input, spec)

        override fun assess(request: PropositionRequest): PropositionResult = current().assess(request)

        override fun capabilities(): DecisionCapabilities = current().capabilities()

        override fun ask(input: String, spec: DecisionSpec): DecisionResponse = current().ask(input, spec)

        override fun ask(request: DecisionRequest): DecisionResponse = current().ask(request)

        override fun metadata(): DecisionServiceMetadata = current().metadata()

        override fun infoString(verbose: Boolean?, indent: Int): String = current().infoString(verbose, indent)

        /**
         * Builds the service for the model the provider picks right now.
         *
         * @return a decision service over the current model
         * @throws NoSuitableModelException if no model matches the criteria
         */
        private fun current(): DecisionService = decisionService(modelProvider.getLlm(criteria))
    }
}
