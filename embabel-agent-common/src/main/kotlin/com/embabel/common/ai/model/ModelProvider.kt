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

import com.embabel.common.ai.prompt.KnowledgeCutoffDate
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.ai.prompt.PromptContributorConsumer
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.model.Model
import java.time.LocalDate

/**
 * Wraps a Spring AI model and allows metadata to be attached to a model
 */
interface AiModel<M : Model<*, *>> {
    val name: String
    val model: M
}

/**
 * Wraps a Spring AI ChatModel exposing an LLM.
 * @param name name of the LLM
 * @param model the Spring AI ChatModel to call
 * @param promptContributors list of prompt contributors to be used with this model.
 * Knowledge cutoff is most important.
 */
data class Llm(
    override val name: String,
    override val model: ChatModel,
    override val promptContributors: List<PromptContributor> = emptyList(),
) : AiModel<ChatModel>, PromptContributorConsumer {

    companion object {

        fun withKnowledgeCutoff(
            name: String,
            model: ChatModel,
            knowledgeCutoffDate: LocalDate,
        ) = Llm(name, model, listOf(KnowledgeCutoffDate(knowledgeCutoffDate)))
    }
}

/**
 * Wraps a Spring AI EmbeddingModel exposing an embedding service.
 */
data class EmbeddingService(
    override val name: String,
    override val model: EmbeddingModel,
) : AiModel<EmbeddingModel>


class NoSuitableModelException(criteria: ModelSelectionCriteria, models: List<AiModel<*>>) :
    RuntimeException(
        """
        No suitable model found for $criteria.
        ${models.size} models available: $models
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

        fun byRole(role: String): ModelSelectionCriteria = ByRoleModelSelectionCriteria(role)

        fun byName(name: String): ModelSelectionCriteria = ByNameModelSelectionCriteria(name)
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

/**
 * Choose an LLM automatically: For example, in a platform, based
 * on runtime analysis, or based on analysis of the prompt
 */
object AutoModelSelectionCriteria : ModelSelectionCriteria

/**
 * Provide AI models for requested roles, and expose data about available models.
 */
interface ModelProvider {

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

        const val SCHEMA_ROLE = "schema"

        const val CHUNK_ROLE = "chunk"
    }

}
