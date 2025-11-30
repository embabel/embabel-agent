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

import com.embabel.agent.api.models.OllamaModels
import com.embabel.common.ai.model.*
import com.fasterxml.jackson.annotation.JsonProperty
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.ai.ollama.OllamaEmbeddingModel
import org.springframework.ai.ollama.api.OllamaApi
import org.springframework.ai.ollama.api.OllamaChatOptions
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
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
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OllamaNodeProperties::class)
class OllamaModelsConfig(
    @param:Value("\${spring.ai.ollama.base-url}")
    private val baseUrl: String,
    private val nodeProperties: OllamaNodeProperties?,
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

    private fun loadModelsFromUrl(baseUrl: String): List<Model> =
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

    private fun loadModels(): List<Model> {
        return loadModelsFromUrl(this.baseUrl)
    }


    @Bean
    fun ollamaModelsInitializer(): String {
        val nodes = nodeProperties?.nodes?.takeIf { it.isNotEmpty() }
        val hasDefaultUrl = baseUrl.isNotBlank()

        when {
            hasDefaultUrl && nodes == null -> {
                logger.info("Using default Ollama instance at {}", baseUrl)
                registerDefaultMode()
            }

            !hasDefaultUrl && nodes != null -> {
                logger.info("Using {} Ollama nodes", nodes.size)
                registerMultiNodeOnlyMode()
            }

            hasDefaultUrl && nodes != null -> {
                logger.info("Using default instance + {} nodes", nodes.size)
                registerHybridMode()
            }

            else -> {
                logger.warn("No Ollama configuration found. Skipping model registration.")
            }
        }
        return "ollamaModelsInitializer"
    }

    private fun ollamaLlmOf(modelName: String, baseUrl: String, nodeName: String? = null): Llm {
        val uniqueModelName = createUniqueModelName(modelName, nodeName)
        val springChatModel = OllamaChatModel.builder()
            .ollamaApi(
                OllamaApi.builder()
                    .baseUrl(baseUrl)
                    .restClientBuilder(
                        RestClient.builder()
                            .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
                    )
                    .webClientBuilder(
                        WebClient.builder()
                            .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
                    )
                    .build()
            )
            .defaultOptions(
                OllamaChatOptions.builder()
                    .model(uniqueModelName)
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
            name = uniqueModelName,
            model = springChatModel,
            provider = OllamaModels.PROVIDER,
            pricingModel = PricingModel.ALL_YOU_CAN_EAT,
            optionsConverter = OllamaOptionsConverter,
        )
    }

    private fun ollamaLlmOf(name: String): Llm {
        return ollamaLlmOf(name, this.baseUrl)
    }


    private fun ollamaEmbeddingServiceOf(
        modelName: String,
        baseUrl: String,
        nodeName: String? = null
    ): EmbeddingService {
        val uniqueModelName = createUniqueModelName(modelName, nodeName)
        val springEmbeddingModel = OllamaEmbeddingModel.builder()
            .ollamaApi(
                OllamaApi.builder()
                    .baseUrl(baseUrl)
                    .restClientBuilder(
                        RestClient.builder()
                            .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
                    )
                    .webClientBuilder(
                        WebClient.builder()
                            .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
                    )
                    .build()
            )
            .defaultOptions(
                OllamaEmbeddingOptions.builder()
                    .model(uniqueModelName)
                    .build()
            )
            .build()

        return EmbeddingService(
            name = uniqueModelName,
            model = springEmbeddingModel,
            provider = OllamaModels.PROVIDER,
        )
    }

    private fun ollamaEmbeddingServiceOf(name: String): EmbeddingService {
        return ollamaEmbeddingServiceOf(name, this.baseUrl)
    }

    private fun normalizeModelNameForBean(model: Model): String {
        return model.model.replace(":", "-").lowercase()
    }

    private fun createUniqueModelName(modelName: String, nodeName: String?): String {
        return nodeName?.let { "$it-$modelName" } ?: modelName
    }

    private fun registerModelsFromUrl(
        baseUrl: String,
        nodeName: String? = null,
        beanNameProvider: (Model) -> List<String>
    ) {
        val models = loadModelsFromUrl(baseUrl)
        val contextName = if (nodeName == null) "default instance" else "node '$nodeName'"

        if (models.isEmpty()) {
            logger.warn("No Ollama models discovered from {} at {}. Check server configuration.", contextName, baseUrl)
        } else {
            logger.info("Discovered {} Ollama models from {}: {}", models.size, contextName, models.map { it.name })
        }

        models.forEach { model ->
            try {
                if (properties.allWellKnownEmbeddingServiceNames().contains(model.model)) {
                    val embeddingService = ollamaEmbeddingServiceOf(model.model, baseUrl, nodeName)

                    // Use node-aware naming for embeddings too
                    beanNameProvider(model).forEach { beanName ->
                        val embeddingBeanName = beanName.replace("ollamaModel-", "ollamaEmbeddingModel-")
                        configurableBeanFactory.registerSingleton(embeddingBeanName, embeddingService)
                        logger.debug(
                            "Successfully registered Ollama embedding service {} as bean {}",
                            model.name,
                            embeddingBeanName,
                        )
                    }
                } else {
                    val llm = ollamaLlmOf(model.model, baseUrl, nodeName)

                    // Register with all provided bean names
                    beanNameProvider(model).forEach { beanName ->
                        configurableBeanFactory.registerSingleton(beanName, llm)
                        logger.debug(
                            "Successfully registered Ollama LLM {} as bean {}",
                            model.name,
                            beanName,
                        )
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to register Ollama model {}: {}", model.name, e.message)
            }
        }
    }

    private fun registerDefaultMode() {
        registerModelsFromUrl(baseUrl, nodeName = null) { model ->
            val normalizedName = normalizeModelNameForBean(model)
            listOf(
                "ollamaModel-${normalizedName}"           // backward compatibility only
            )
        }
    }

    private fun registerMultiNodeOnlyMode() {
        nodeProperties?.nodes?.forEach { node ->
            registerNodeModels(node.name, node.baseUrl)
        }
    }

    private fun registerHybridMode() {
        registerDefaultMode()
        registerMultiNodeOnlyMode()
    }

    private fun registerNodeModels(nodeName: String, nodeBaseUrl: String) {
        registerModelsFromUrl(nodeBaseUrl, nodeName = nodeName) { model ->
            val normalizedName = normalizeModelNameForBean(model)
            listOf("ollamaModel-${nodeName}-${normalizedName}")
        }
    }
}

object OllamaOptionsConverter : OptionsConverter<OllamaChatOptions> {
    override fun convertOptions(options: LlmOptions): OllamaChatOptions =
        OllamaChatOptions.builder()
            .temperature(options.temperature)
            .topP(options.topP)
            .presencePenalty(options.presencePenalty)
            .frequencyPenalty(options.frequencyPenalty)
            .topK(options.topK)
            .build()
}
