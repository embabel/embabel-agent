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
package com.embabel.agent.config.models.lmstudio

import com.embabel.agent.api.models.LmStudioModels
import com.embabel.agent.openai.OpenAiCompatibleModelFactory
import com.embabel.common.ai.model.PricingModel
import com.fasterxml.jackson.annotation.JsonProperty
import io.micrometer.observation.ObservationRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

/**
 * Configuration for LM Studio models.
 * Dynamically discovers models available in the local LM Studio instance
 * and registers them as beans.
 */
@Configuration(proxyBeanMethods = false)
class LmStudioModelsConfig(
    @Value("\${spring.ai.lmstudio.base-url:http://localhost:1234/v1}")
    baseUrl: String,
    private val configurableBeanFactory: ConfigurableBeanFactory,
    observationRegistry: ObjectProvider<ObservationRegistry>,
) : OpenAiCompatibleModelFactory(
    baseUrl = baseUrl,
    apiKey = "lm-studio", // Dummy key
    completionsPath = null,
    embeddingsPath = null,
    observationRegistry = observationRegistry.getIfUnique { ObservationRegistry.NOOP }
) {

    // OpenAI-compatible models response
    private data class ModelResponse(
        @param:JsonProperty("data") val data: List<ModelData>,
    )

    private data class ModelData(
        @param:JsonProperty("id") val id: String,
    )

    @PostConstruct
    fun registerModels() {
        val models = loadModelsFromUrl()

        if (models.isEmpty()) {
            logger.warn("No LM Studio models discovered at {}. Ensure LM Studio is running and the server is started.", baseUrl)
            return
        }

        logger.info("Discovered {} LM Studio models: {}", models.size, models)

        models.forEach { modelId ->
            try {
                val llm = openAiCompatibleLlm(
                    model = modelId,
                    pricingModel = PricingModel.ALL_YOU_CAN_EAT, // Local models are free
                    provider = LmStudioModels.PROVIDER,
                    knowledgeCutoffDate = null
                )

                // Register bean with a predictable name
                // e.g. "lmStudioModel-my-model-name"
                val beanName = "lmStudioModel-${normalizeModelName(modelId)}"
                configurableBeanFactory.registerSingleton(beanName, llm)
                logger.debug("Successfully registered LM Studio LLM {} as bean {}", modelId, beanName)

            } catch (e: Exception) {
                logger.error("Failed to register LM Studio model {}: {}", modelId, e.message)
            }
        }
    }

    private fun loadModelsFromUrl(): List<String> =
        try {
            // We can't use the RestClient from the parent because it's configured for the API
            // But we can use a simple one here to fetch the list
            val restClient = RestClient.create()
            // baseUrl usually includes /v1, so we append /models
            // If baseUrl ends with /, remove it
            val cleanBaseUrl = baseUrl?.trimEnd('/') ?: "http://localhost:1234/v1"
            val url = "$cleanBaseUrl/models"

            val response = restClient.get()
                .uri(url)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body<ModelResponse>()

            response?.data?.map { it.id } ?: emptyList()
        } catch (e: Exception) {
            logger.warn("Failed to load models from {}: {}", baseUrl, e.message)
            emptyList()
        }

    private fun normalizeModelName(modelId: String): String {
        // Replace characters that might be invalid in bean names or just to be consistent
        return modelId.replace(":", "-")
            .replace("/", "-")
            .replace("\\", "-")
            .lowercase()
    }
}
