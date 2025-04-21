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

import com.embabel.common.util.kotlin.loggerFor
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@ConfigurationProperties("embabel.model")
@Validated
data class ModelProperties(
    val llms: Map<String, String> = emptyMap(),
    val embeddingServices: Map<String, String> = emptyMap(),
)

/**
 * Take LLM definitions from application properties file
 */
class ApplicationPropertiesModelProvider(
    private val llms: List<Llm>,
    private val embeddingServices: List<EmbeddingService>,
    private val properties: ModelProperties,
) : ModelProvider {

    init {
        loggerFor<ApplicationPropertiesModelProvider>().info("Available LLMs: ${llms.map { it.name }}")
        properties.llms.forEach { (role, model) ->
            if (llms.none { it.name == model }) {
                error("LLM for role $role is not available")
            } else {
                loggerFor<ApplicationPropertiesModelProvider>().info("LLM for role '$role' is $model")
            }
        }
        properties.embeddingServices.forEach { (role, model) ->
            if (embeddingServices.none { it.name == model }) {
                error("Embedding model for role $role is not available")
            } else {
                loggerFor<ApplicationPropertiesModelProvider>().info("Embedding service for role '$role' is $model")
            }
        }
    }

    override fun listRoles(modelClass: Class<out AiModel<*>>): List<String> {
        return when (modelClass) {
            Llm::class.java -> properties.llms.keys.toList()
            EmbeddingService::class.java -> properties.embeddingServices.keys.toList()
            else -> throw IllegalArgumentException("Unsupported model class: $modelClass")
        }
    }

    override fun listModelNames(modelClass: Class<out AiModel<*>>): List<String> {
        return when (modelClass) {
            Llm::class.java -> llms.map { it.name }
            EmbeddingService::class.java -> embeddingServices.map { it.name }
            else -> throw IllegalArgumentException("Unsupported model class: $modelClass")
        }
    }

    override fun getLlm(criteria: ModelSelectionCriteria): Llm =
        when (criteria) {
            is ByRoleModelSelectionCriteria -> {
                val modelName = properties.llms[criteria.role] ?: throw NoSuitableModelException(criteria, llms)
                llms.firstOrNull { it.name == modelName } ?: throw NoSuitableModelException(criteria, llms)
            }

            is ByNameModelSelectionCriteria -> {
                llms.firstOrNull { it.name == criteria.name } ?: throw NoSuitableModelException(criteria, llms)
            }

            is AutoModelSelectionCriteria -> {
                TODO("Auto model selection criteria not implemented")
            }
        }

    override fun getEmbeddingService(criteria: ModelSelectionCriteria): EmbeddingService =
        when (criteria) {
            is ByRoleModelSelectionCriteria -> {
                val modelName =
                    properties.embeddingServices[criteria.role] ?: throw NoSuitableModelException(
                        criteria,
                        embeddingServices,
                    )
                embeddingServices.firstOrNull { it.name == modelName } ?: throw NoSuitableModelException(
                    criteria,
                    embeddingServices,
                )
            }

            else -> throw IllegalArgumentException("Unsupported embedding model selection criteria: $criteria")
        }
}
