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
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.common.ai.autoconfig.ProviderInitialization
import com.embabel.common.ai.autoconfig.RegisteredModel
import com.embabel.common.ai.model.PricingModel
import com.embabel.common.util.ExcludeFromJacocoGeneratedReport
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

@ConfigurationProperties(prefix = "embabel.agent.platform.models.lmstudio")
class LmStudioProperties : RetryProperties {

    /**
     * Base URL for LM Studio endpoint
     */
    var baseUrl: String = "http://127.0.0.1:1234"

    /**
     * API key for LM Studio. Defaults to null as apiKey isn't supported yet.
     */
    var apiKey: String? = null

    /**
     *  Maximum number of attempts.
     */
    override var maxAttempts: Int = 10

    /**
     * Initial backoff interval (in milliseconds).
     */
    override var backoffMillis: Long = 5000L

    /**
     * Backoff interval multiplier.
     */
    override var backoffMultiplier: Double = 5.0

    /**
     * Maximum backoff interval (in milliseconds).
     */
    override var backoffMaxInterval: Long = 180000L
}

/**
 * Configuration for LM Studio models.
 * Dynamically discovers models available in the local LM Studio instance
 * and registers them as beans.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LmStudioProperties::class)
class LmStudioModelsConfig(
    private val lmStudioProperties: LmStudioProperties,
    private val configurableBeanFactory: ConfigurableBeanFactory,
    observationRegistry: ObjectProvider<ObservationRegistry>,
) : OpenAiCompatibleModelFactory(
    baseUrl = lmStudioProperties.baseUrl,
    apiKey = lmStudioProperties.apiKey,
    completionsPath = null,
    embeddingsPath = null,
    observationRegistry = observationRegistry.getIfUnique { ObservationRegistry.NOOP }
) {

    private val log = LoggerFactory.getLogger(LmStudioModelsConfig::class.java)

    // OpenAI-compatible models response
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ModelResponse(
        @param:JsonProperty("data") val data: List<ModelData>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ModelData(
        @param:JsonProperty("id") val id: String,
    )

    @Bean
    fun lmStudioModelsInitializer(): ProviderInitialization {
        val models = loadModelsFromUrl()

        if (models.isEmpty()) {
            log.warn(
                "No LM Studio models discovered at {}. Ensure LM Studio is running and the server is started.",
                baseUrl
            )
        }

        log.info("Discovered {} LM Studio models: {}", models.size, models)

        val registeredLlms = buildList {
            models.forEach { modelId ->
                try {
                    val llm = openAiCompatibleLlm(
                        model = modelId,
                        pricingModel = PricingModel.ALL_YOU_CAN_EAT,
                        provider = LmStudioModels.PROVIDER,
                        knowledgeCutoffDate = null,
                        retryTemplate = lmStudioProperties.retryTemplate("lmstudio-$modelId")
                    )

                    val beanName = "lmStudioModel-${normalizeModelName(modelId)}"
                    configurableBeanFactory.registerSingleton(beanName, llm)
                    add(RegisteredModel(beanName = beanName, modelId = modelId))
                    log.debug("Successfully registered LM Studio LLM {} as bean {}", modelId, beanName)

                } catch (e: Exception) {
                    log.error("Failed to register LM Studio model {}: {}", modelId, e.message)
                }
            }
        }

        return ProviderInitialization(
            provider = LmStudioModels.PROVIDER,
            registeredLlms = registeredLlms,
        ).also { logger.info(it.summary()) }
    }

    private fun loadModelsFromUrl(): List<String> {
        return try {
            val requestFactory = SimpleClientHttpRequestFactory()
            requestFactory.setConnectTimeout(2000)
            requestFactory.setReadTimeout(2000)

            val restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build()

            val cleanBaseUrl = baseUrl?.trimEnd('/') ?: "http://127.0.0.1:1234"
            // Ensure we hit /v1/models
            val url = if (cleanBaseUrl.endsWith("/v1")) {
                "$cleanBaseUrl/models"
            } else {
                "$cleanBaseUrl/v1/models"
            }

            log.info("Attempting to fetch models from: {}", url)

            val responseBody = restClient.get()
                .uri(url)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String::class.java)

            log.debug("Received response from LM Studio: {}", responseBody)

            if (responseBody == null) {
                log.warn("Received empty response from LM Studio")
                return emptyList()
            }

            val objectMapper = ObjectMapper()
            val response = objectMapper.readValue(responseBody, ModelResponse::class.java)

            response.data?.map { it.id } ?: emptyList()
        } catch (e: Exception) {
            log.warn("Failed to load models from {}: {}", baseUrl, e.message)
            emptyList()
        }
    }

    private fun normalizeModelName(modelId: String): String {
        // Replace characters that might be invalid in bean names or just to be consistent
        return modelId.replace(":", "-")
            .replace("/", "-")
            .replace("\\", "-")
            .lowercase()
    }
}
