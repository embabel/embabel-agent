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
import com.embabel.common.ai.model.ClassificationService
import com.embabel.common.ai.model.DecisionService
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
 * already holds, such as one built for a user's own API key, pass the [LlmService] instead.
 *
 * Applications inject the `LlmDecisionServiceFactory` bean from the Spring context.
 */
@ApiStatus.Experimental
class LlmDecisionServiceFactory @ApiStatus.Internal @JvmOverloads internal constructor(
    private val llmOperations: LlmOperations,
    private val modelProvider: ModelProvider,
    private val observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
    private val retry: RetryProperties = LlmDecisionRetryProperties(),
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
        ObservedDecisionService(llmDecisionService(llm), observationRegistry)

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
        ObservedClassificationService(LlmClassificationService(llmDecisionService(llm)), observationRegistry)

    private fun llmNamed(llmName: String): LlmService<*> {
        require(llmName.isNotBlank()) { "LLM name must not be blank" }
        return modelProvider.getLlm(ModelSelectionCriteria.byName(llmName))
    }

    // Every call selects this exact model, so the model provider is never asked again.
    private fun llmDecisionService(llm: LlmService<*>) =
        LlmDecisionService(llmOperations, llm, LlmOptions(PreResolvedModelSelectionCriteria(llm)), retry)
}

/**
 * Retry settings for LLM-backed decision services. The defaults match the other platform services
 * that call a model.
 *
 * @property maxAttempts most calls made for one decision, counting the first
 * @property backoffMillis wait before the first retry, in milliseconds
 * @property backoffMultiplier how much each wait grows over the last
 * @property backoffMaxInterval longest wait between retries, in milliseconds
 * @property propertyPrefix where these settings live in configuration
 */
@ApiStatus.Experimental
data class LlmDecisionRetryProperties @JvmOverloads constructor(
    override val maxAttempts: Int = 5,
    override val backoffMillis: Long = 100L,
    override val backoffMultiplier: Double = 5.0,
    override val backoffMaxInterval: Long = 180000L,
    override val propertyPrefix: String = "embabel.agent.platform.decisions.llm",
) : RetryProperties
