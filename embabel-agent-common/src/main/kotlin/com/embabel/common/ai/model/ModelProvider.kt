/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.common.ai.model

import com.embabel.common.core.types.HasInfoString
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.springframework.ai.chat.prompt.ChatOptions

/**
 * Convert our LLM options to Spring AI ChatOptions
 */
typealias OptionsConverter = (LlmOptions) -> ChatOptions

/**
 * Save default. Some models may not support all options.
 */
val DefaultOptionsConverter = { options: LlmOptions ->
    ChatOptions.builder()
        .temperature(options.temperature)
        .topP(options.topP)
        .maxTokens(options.maxTokens)
        .presencePenalty(options.presencePenalty)
        .frequencyPenalty(options.frequencyPenalty)
        .topP(options.topP)
        .build()
}


class NoSuitableModelException(criteria: ModelSelectionCriteria, models: List<AiModel<*>>) :
    RuntimeException(
        """
        No suitable model found for $criteria.
        ${models.size} models available: ${models.map { it.name }}
        """
    )

/**
 * Superinterface for model selection criteria
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes(
    JsonSubTypes.Type(value = ByNameModelSelectionCriteria::class),
    JsonSubTypes.Type(value = ByRoleModelSelectionCriteria::class),
)
sealed interface ModelSelectionCriteria {

    companion object {

        @JvmStatic
        fun byRole(role: String): ModelSelectionCriteria = ByRoleModelSelectionCriteria(role)

        @JvmStatic
        fun byName(name: String): ModelSelectionCriteria = ByNameModelSelectionCriteria(name)

        @JvmStatic
        fun randomOf(vararg names: String): ModelSelectionCriteria =
            RandomByNameModelSelectionCriteria(names.toList())

        @JvmStatic
        fun firstOf(vararg names: String): ModelSelectionCriteria =
            FallbackByNameModelSelectionCriteria(names.toList())
    }
}

/**
 * Select an LLM by role
 */
data class ByRoleModelSelectionCriteria(
    val role: String,
) : ModelSelectionCriteria

data class ByNameModelSelectionCriteria(
    val name: String,
) : ModelSelectionCriteria

data class RandomByNameModelSelectionCriteria(
    val names: List<String>,
) : ModelSelectionCriteria

data class FallbackByNameModelSelectionCriteria(
    val names: List<String>,
) : ModelSelectionCriteria

/**
 * Choose an LLM automatically: For example, in a platform, based
 * on runtime analysis, or based on analysis of the prompt
 */
object AutoModelSelectionCriteria : ModelSelectionCriteria

/**
 * Provide AI models for requested roles, and expose data about available models.
 */
interface ModelProvider : HasInfoString {

    @Throws(NoSuitableModelException::class)
    fun getLlm(criteria: ModelSelectionCriteria): Llm

    @Throws(NoSuitableModelException::class)
    fun getEmbeddingService(criteria: ModelSelectionCriteria): EmbeddingService

    /**
     * List the roles available for this class of model
     */
    fun listRoles(modelClass: Class<out AiModel<*>>): List<String>

    fun listModelNames(klass: Class<out AiModel<*>>): List<String>

    /**
     * Well known roles for models
     * Useful but not exhaustive
     * @see ByRoleModelSelectionCriteria
     */
    companion object {

        const val BEST_ROLE = "best"

        const val CHEAPEST_ROLE = "cheapest"

    }

}
