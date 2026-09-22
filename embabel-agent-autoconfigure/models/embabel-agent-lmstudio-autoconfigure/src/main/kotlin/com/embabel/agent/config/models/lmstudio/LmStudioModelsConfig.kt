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
package com.embabel.agent.config.models.lmstudio

import com.embabel.agent.api.models.LmStudioModels
import com.embabel.agent.config.models.lmstudio.LmStudioProperties.Companion.PREFIX
import com.embabel.agent.openai.OpenAiCompatibleModelFactory
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.common.ai.autoconfig.ProviderInitialization
import com.embabel.common.ai.autoconfig.RegisteredModel
import com.embabel.common.ai.model.ConfigurableModelProviderProperties
import com.embabel.common.ai.model.local.DiscoveryFailureReporter
import com.embabel.common.ai.model.local.LocalModel
import com.embabel.common.ai.model.local.LocalModelBeans
import com.embabel.common.ai.model.local.LocalModelCatalog
import com.embabel.common.ai.model.local.LocalModelDiscoveryProperties
import com.embabel.common.ai.model.local.LocalModelEmbeddingRoleResolver
import com.embabel.common.ai.model.local.LocalModelKind
import com.embabel.common.ai.model.local.LocalModelRoleResolver
import com.embabel.common.ai.model.local.LocalModelSource
import com.embabel.common.ai.model.PricingModel
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.ObjectMapper
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient

@ConfigurationProperties(prefix = PREFIX)
class LmStudioProperties : RetryProperties {

    /**
     * Base URL for LM Studio endpoint
     */
    var baseUrl: String = DEFAULT_BASE_URL

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

    override val propertyPrefix: String = PREFIX
    companion object {
        const val PREFIX = "embabel.agent.platform.models.lmstudio"
        const val DEFAULT_HOST = "localhost"
        const val DEFAULT_PORT = 1234
        const val DEFAULT_BASE_URL = "http://$DEFAULT_HOST:$DEFAULT_PORT"
    }
}

/**
 * Configuration for LM Studio models.
 * Dynamically discovers models available in the local LM Studio instance
 * and registers them as beans.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(
    LmStudioProperties::class,
    ConfigurableModelProviderProperties::class,
    LocalModelDiscoveryProperties::class,
)
class LmStudioModelsConfig(
    private val lmStudioProperties: LmStudioProperties,
    private val configurableBeanFactory: ConfigurableBeanFactory,
    private val modelProviderProperties: ConfigurableModelProviderProperties,
    private val localModelDiscoveryProperties: LocalModelDiscoveryProperties,
    observationRegistry: ObjectProvider<ObservationRegistry>,
    @Qualifier("aiModelRestClientBuilder")
    restClientBuilder: ObjectProvider<RestClient.Builder>,
    @Qualifier("aiModelWebClientBuilder")
    webClientBuilder: ObjectProvider<WebClient.Builder>,
) : OpenAiCompatibleModelFactory(
    // The SDK appends `/chat/completions` to whatever it is given — the factory's
    // own docs say to bake the full path into the base URL — while discovery
    // below appends `/v1/models` to the SAME property. Both cannot be satisfied
    // by one configured value, and the mismatch is silent: models are listed
    // (discovery is right) and then every completion 404s with LM Studio's
    // "Unexpected endpoint or method", which surfaces to the caller as the
    // baffling "`choices` is not set". Normalise here so the configured value
    // stays the plain server address a user would write.
    baseUrl = chatBaseUrl(lmStudioProperties.baseUrl),
    apiKey = lmStudioProperties.apiKey,
    completionsPath = null,
    embeddingsPath = null,
    observationRegistry = observationRegistry.getIfUnique { ObservationRegistry.NOOP },
    restClientBuilder = restClientBuilder,
    webClientBuilder = webClientBuilder,
) {

    private val log = LoggerFactory.getLogger(LmStudioModelsConfig::class.java)

    private val discoveryFailures = DiscoveryFailureReporter(log)

    companion object {
        /**
         * The URL the OpenAI SDK should treat as its API root: LM Studio serves
         * the OpenAI surface under `/v1`, so append it unless the operator has
         * already done so.
         */
        internal fun chatBaseUrl(baseUrl: String): String {
            val clean = baseUrl.trimEnd('/')
            return if (clean.endsWith("/v1")) clean else "$clean/v1"
        }
    }

    // OpenAI-compatible models response
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ModelResponse(
        @param:JsonProperty("models") val models: List<ModelData>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ModelData(
        @param:JsonProperty("key") val key: String,
        @param:JsonProperty("type") val type: LlmType,
    )

    enum class LlmType {
        embedding,
        llm
    }

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
            models
                .filter  { it.type == LlmType.llm }
                .forEach { modelData   ->
                try {
                    val llm = lmStudioLlmOf(modelData.key)

                    val beanName = "lmStudioModel-${normalizeModelName(modelData.key)}"
                    configurableBeanFactory.registerSingleton(beanName, llm)
                    add(RegisteredModel(beanName = beanName, modelId = modelData.key))
                    log.debug("Successfully registered LM Studio LLM {} as bean {}", modelData.key, beanName)

                } catch (e: Exception) {
                    log.error("Failed to register LM Studio model {}: {}", modelData.key, e.message)
                }
            }
        }

        val registeredEmbeddings = buildList {
            models
                .filter  { it.type == LlmType.embedding }
                .forEach { modelData ->
                    try {
                        val llm = lmStudioEmbeddingServiceOf(modelData.key)

                        val beanName = "lmStudioModel-${normalizeModelName(modelData.key)}"
                        configurableBeanFactory.registerSingleton(beanName, llm)
                        add(RegisteredModel(beanName = beanName, modelId = modelData.key))
                        log.debug("Successfully registered LM Studio LLM {} as bean {}", modelData.key, beanName)

                    } catch (e: Exception) {
                        log.error("Failed to register LM Studio model {}: {}", modelData.key, e.message)
                    }
                }
        }

        return ProviderInitialization(
            provider = LmStudioModels.PROVIDER,
            registeredLlms = registeredLlms,
            registeredEmbeddings = registeredEmbeddings
        ).also { logger.info(it.summary()) }
    }

    private fun loadModelsFromUrl(): List<ModelData> {
        return try {
            val requestFactory = SimpleClientHttpRequestFactory()
            requestFactory.setConnectTimeout(2000)
            requestFactory.setReadTimeout(2000)

            val restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build()

            // The RAW configured address, deliberately — NOT the inherited `baseUrl`,
            // which is normalised to end in /v1 for the SDK. Reading that here
            // built `…/v1/api/v1/models`, discovered nothing, and left the
            // appliance unable to start with a local default model.
            val cleanBaseUrl = lmStudioProperties.baseUrl.trimEnd('/')
            val apiUrl = if (cleanBaseUrl.contains("/api")) {
                cleanBaseUrl
            } else {
                "$cleanBaseUrl/api"
            }
            // Ensure we hit /v1/models
            val url = if (apiUrl.endsWith("/v1")) {
                "$apiUrl/models"
            } else {
                "$apiUrl/v1/models"
            }

            log.debug("Attempting to fetch models from: {}", url)

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

            discoveryFailures.succeeded(lmStudioProperties.baseUrl)
            response.models?: emptyList()
        } catch (e: Exception) {
            discoveryFailures.failed(lmStudioProperties.baseUrl, e)
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

    private fun lmStudioLlmOf(model: String) = openAiCompatibleLlm(
        model = model,
        pricingModel = PricingModel.ALL_YOU_CAN_EAT,
        provider = LmStudioModels.PROVIDER,
        knowledgeCutoffDate = null,
        retryTemplate = lmStudioProperties.retryTemplate("lmstudio-$model"),
    )

    private fun lmStudioEmbeddingServiceOf(model: String) = openAiCompatibleEmbeddingService(
        model = model,
        provider = LmStudioModels.PROVIDER,
    )

    /**
     * The LM Studio server, asked per call rather than once at startup.
     *
     * [lmStudioModelsInitializer] still registers what was loaded at boot; this covers a model
     * loaded since, which otherwise needed a restart to become usable however plainly LM Studio was
     * serving it.
     */
    private inner class LmStudioModelSource : LocalModelSource {

        override val provider: String = LmStudioModels.PROVIDER

        // LM Studio reports a type per model, so unlike Docker and Ollama this needs no
        // configuration to tell a chat model from an embedding one.
        override fun servedModels(): Set<LocalModel> =
            loadModelsFromUrl().map {
                LocalModel(
                    name = it.key,
                    kind = when (it.type) {
                        LlmType.embedding -> LocalModelKind.EMBEDDING
                        LlmType.llm -> LocalModelKind.CHAT
                    },
                )
            }.toSet()

        override fun llmService(model: String) = lmStudioLlmOf(model)

        override fun embeddingService(model: String) = lmStudioEmbeddingServiceOf(model)
    }

    /**
     * The catalog and the two resolvers, built once over one [LocalModelSource].
     *
     * Lazy, so a configuration that is loaded but never asked for a model never constructs one, and
     * a field rather than a bean method because with `proxyBeanMethods = false` a bean method called
     * three times would build three catalogs and the resolvers would stop sharing a cache.
     */
    private val localModelBeans: LocalModelBeans by lazy {
        LocalModelBeans(LmStudioModelSource(), modelProviderProperties, localModelDiscoveryProperties)
    }

    @Bean
    fun lmStudioLocalModelCatalog(): LocalModelCatalog = localModelBeans.catalog

    @Bean
    fun lmStudioLocalModelRoleResolver(): LocalModelRoleResolver = localModelBeans.roleResolver

    @Bean
    fun lmStudioLocalModelEmbeddingRoleResolver(): LocalModelEmbeddingRoleResolver =
        localModelBeans.embeddingRoleResolver
}
