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
package com.embabel.agent.config.models.ollama

import com.embabel.agent.config.models.OllamaModels
import com.embabel.common.ai.model.*
import com.embabel.common.util.ExcludeFromJacocoGeneratedReport
import com.fasterxml.jackson.annotation.JsonProperty
import io.micrometer.observation.ObservationRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.ai.ollama.OllamaEmbeddingModel
import org.springframework.ai.ollama.api.OllamaApi
import org.springframework.ai.ollama.api.OllamaOptions
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import org.springframework.web.reactive.function.client.WebClient

/**
 * Load Ollama local models, both LLMs and embedding models.
 * This class will always be loaded, but models won't be loaded
 * from Ollama unless the "ollama" profile is set.
 */
@ExcludeFromJacocoGeneratedReport(reason = "Ollama configuration can't be unit tested")
@Configuration(proxyBeanMethods = false)
class OllamaModelsConfig(
    @param:Value("\${spring.ai.ollama.base-url}")
    private val baseUrl: String,
    private val configurableBeanFactory: ConfigurableBeanFactory,
    private val properties: ConfigurableModelProviderProperties,
    private val observationRegistry: ObjectProvider<ObservationRegistry>,
) {
    private val logger = LoggerFactory.getLogger(OllamaModelsConfig::class.java)

    private data class ModelResponse(
        @param:JsonProperty("models") val models: List<ModelDetails>,
    )

    private data class ModelDetails(
        @param:JsonProperty("name") val name: String,
        @param:JsonProperty("size") val size: Long,
        @param:JsonProperty("modified_at") val modifiedAt: String,
    )

    private data class Model(
        val name: String,
        val model: String,
        val size: Long,
    )

    private fun loadModels(): List<Model> =
        try {
            val restClient = RestClient.create()
            val response = restClient.get()
                .uri("$baseUrl/api/tags")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body<ModelResponse>()

            response?.models?.mapNotNull { modelDetails ->
                // Additional validation to ensure model names are valid
                if (modelDetails.name.isNotBlank()) {
                    Model(
                        name = modelDetails.name.replace(":", "-").lowercase(),
                        model = modelDetails.name,
                        size = modelDetails.size
                    )
                } else null
            } ?: emptyList()
        } catch (e: Exception) {
            logger.warn("Failed to load models from {}: {}", baseUrl, e.message)
            emptyList()
        }


    @PostConstruct
    fun registerModels() {
        logger.info("Ollama models will be discovered at {}", baseUrl)

        val models = loadModels()
        if (models.isEmpty()) {
            logger.warn("No Ollama models discovered. Check Ollama server configuration.")
        } else {
            logger.info("Discovered Ollama models: {}", models.map { it.name })
        }

        models.forEach { model ->
            try {
                val beanName = "ollamaModel-${model.name}"
                if (properties.allWellKnownEmbeddingServiceNames().contains(model.model)) {
                    val embeddingService = ollamaEmbeddingServiceOf(model.model)
                    val embeddingBeanName = "ollamaEmbeddingModel-${model.name}"
                    configurableBeanFactory.registerSingleton(embeddingBeanName, embeddingService)
                    logger.debug(
                        "Successfully registered Ollama embedding service {} as bean {}",
                        model.name,
                        embeddingBeanName,
                    )
                } else {
                    val llm = ollamaLlmOf(model.model)

                    // Use registerSingleton with a more descriptive bean name
                    configurableBeanFactory.registerSingleton(beanName, llm)
                    logger.debug(
                        "Successfully registered Ollama LLM {} as bean {}",
                        model.name,
                        beanName,
                    )
                }
            } catch (e: Exception) {
                logger.error("Failed to register Ollama model {}: {}", model.name, e.message)
            }
        }
    }

    private fun ollamaLlmOf(name: String): Llm {
        val springChatModel = OllamaChatModel.builder()
            .ollamaApi(
                OllamaApi.builder()
                    .baseUrl(baseUrl)
                    .restClientBuilder(RestClient.builder()
                        .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP }))
                    .webClientBuilder(WebClient.builder()
                        .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP }))
                    .build()
            )
            .defaultOptions(
                OllamaOptions.builder()
                    .model(name)
                    .build()
            )
            .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
            .toolCallingManager(
                ToolCallingManager.builder()
                    .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
                    .build()
            )
            .build()

        return Llm(
            name = name,
            model = springChatModel,
            provider = OllamaModels.PROVIDER,
            pricingModel = PricingModel.ALL_YOU_CAN_EAT,
            optionsConverter = OllamaOptionsConverter,
        )
    }


    private fun ollamaEmbeddingServiceOf(name: String): EmbeddingService {
        val springEmbeddingModel = OllamaEmbeddingModel.builder()
            .ollamaApi(
                OllamaApi.builder()
                    .baseUrl(baseUrl)
                    .restClientBuilder(RestClient.builder()
                        .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP }))
                    .webClientBuilder(WebClient.builder()
                        .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP }))
                    .build()
            )
            .defaultOptions(
                OllamaOptions.builder()
                    .model(name)
                    .build()
            )
            .build()

        return EmbeddingService(
            name = name,
            model = springEmbeddingModel,
            provider = OllamaModels.PROVIDER,
        )
    }
}

object OllamaOptionsConverter : OptionsConverter<OllamaOptions> {
    override fun convertOptions(options: LlmOptions): OllamaOptions =
        OllamaOptions.builder()
            .temperature(options.temperature)
            .topP(options.topP)
            .presencePenalty(options.presencePenalty)
            .frequencyPenalty(options.frequencyPenalty)
            .topK(options.topK)
            .build()
}
