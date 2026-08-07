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
package com.embabel.agent.config.models.openai

import com.embabel.agent.api.models.OpenAiModels
import com.embabel.agent.config.models.openai.OpenAiProperties.Companion.PREFIX
import com.embabel.agent.openai.Gpt5ChatOptionsConverter
import com.embabel.agent.openai.OpenAiCompatibleModelFactory
import com.embabel.agent.openai.StandardOpenAiOptionsConverter
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.agent.spi.support.springai.SpringAiNativeStructuredOutputConfigurer
import com.embabel.common.ai.autoconfig.LlmAutoConfigMetadataLoader
import com.embabel.common.ai.autoconfig.ProviderInitialization
import com.embabel.common.ai.autoconfig.RegisteredModel
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.LlmOptionsProperties
import com.embabel.common.ai.model.PerTokenPricingModel
import com.embabel.common.ai.model.PricingModel
import com.embabel.common.util.ExcludeFromJacocoGeneratedReport
import io.micrometer.observation.ObservationRegistry
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient

/**
 * Configuration properties for OpenAI model settings.
 * These properties can be set in application.properties/yaml using the
 * prefix embabel.agent.platform.models.openai.
 */
@ConfigurationProperties(prefix = PREFIX)
class OpenAiProperties : RetryProperties {
    /**
     * Base URL for OpenAI API requests.
     */
    var baseUrl: String? = null

    /**
     * API key for authenticating with OpenAI services.
     */
    var apiKey: String? = null

    /**
     * Path to completions endpoint or configuration.
     */
    var completions: String? = null

    /**
     * Path to embeddings endpoint or configuration.
     */
    var embeddingsPath: String? = null

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
        const val PREFIX  = "embabel.agent.platform.models.openai"
    }
}

/**
 * The configured key, or `null` if there isn't one.
 *
 * A **blank** key counts as absent. That is not hypothetical tidiness: compose passes
 * `OPENAI_API_KEY=${OPENAI_API_KEY:-}`, so inside a container the variable is routinely
 * set-but-empty, and `@Value("\${OPENAI_API_KEY:#{null}}")` resolves that to `""` rather
 * than `null`. Treating blank as absent keeps the empty case on the same path as the
 * missing one instead of handing a blank key to the client.
 */
private fun configuredOpenAiApiKey(envApiKey: String?, properties: OpenAiProperties): String? =
    envApiKey?.takeIf(String::isNotBlank) ?: properties.apiKey?.takeIf(String::isNotBlank)

/**
 * Configuration for OpenAI language and embedding models.
 * This class dynamically loads and registers OpenAI models from YAML configuration,
 * similar to the Anthropic and Bedrock configuration patterns.
 *
 * With no API key this registers no models and lets the context start, the way the
 * LM Studio config does when its server is unreachable. A missing key is a deployment
 * that has not been given one yet — an appliance whose operator supplies keys through
 * first-run setup — not a programming error, so it must not kill the context. Failing
 * in the constructor took the whole application down, because a `@Configuration` whose
 * superclass constructor throws cannot be conditioned away.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenAiProperties::class, LlmOptionsProperties::class)
@ExcludeFromJacocoGeneratedReport(reason = "Model registration needs a live OpenAI account; the no-key path is covered by OpenAiModelsConfigNoKeyTest")
class OpenAiModelsConfig(
    @param:Value("\${OPENAI_BASE_URL:#{null}}")
    private val envBaseUrl: String?,
    @param:Value("\${OPENAI_API_KEY:#{null}}")
    private val envApiKey: String?,
    @param:Value("\${OPENAI_COMPLETIONS_PATH:#{null}}")
    private val envCompletionsPath: String?,
    @param:Value("\${OPENAI_EMBEDDINGS_PATH:#{null}}")
    private val envEmbeddingsPath: String?,
    observationRegistry: ObjectProvider<ObservationRegistry>,
    @Qualifier("aiModelRestClientBuilder")
    restClientBuilder: ObjectProvider<RestClient.Builder>,
    private val properties: OpenAiProperties,
    llmOptionsProperties: LlmOptionsProperties,
    private val configurableBeanFactory: ConfigurableBeanFactory,
    private val modelLoader: LlmAutoConfigMetadataLoader<OpenAiModelDefinitions> = OpenAiModelLoader(),
    @Qualifier("aiModelWebClientBuilder")
    webClientBuilder: ObjectProvider<WebClient.Builder>,
    private val nativeStructuredOutputConfigurer: SpringAiNativeStructuredOutputConfigurer =
        OpenAiNativeStructuredOutputConfigurer,
) : OpenAiCompatibleModelFactory(
    baseUrl = envBaseUrl ?: properties.baseUrl,
    // The factory already models an absent key: resolvedApiKey() substitutes a
    // placeholder, because the SDK rejects a null key even for no-auth servers.
    apiKey = configuredOpenAiApiKey(envApiKey, properties),
    completionsPath = envCompletionsPath ?: properties.completions,
    embeddingsPath = envEmbeddingsPath ?: properties.embeddingsPath,
    httpHeaders = llmOptionsProperties.httpHeaders,
    observationRegistry = observationRegistry.getIfUnique { ObservationRegistry.NOOP },
    restClientBuilder = restClientBuilder,
    webClientBuilder = webClientBuilder,
) {

    /**
     * Resolved the same way as the one handed to the superclass, which keeps it private. Only the
     * Responses adapter needs it here — Spring AI's own chat model is given it by the factory.
     */
    private val resolvedObservationRegistry: ObservationRegistry =
        observationRegistry.getIfUnique { ObservationRegistry.NOOP }

    private val apiKeyConfigured: Boolean = configuredOpenAiApiKey(envApiKey, properties) != null

    init {
        if (apiKeyConfigured) {
            logger.info("OpenAI models are available: {}", properties)
        } else {
            logger.info(
                "No OpenAI API key configured: set OPENAI_API_KEY or {}.api-key to enable OpenAI models. Continuing without them.",
                PREFIX,
            )
        }
    }

    @Bean
    fun openAiModelsInitializer(): ProviderInitialization {
        if (!apiKeyConfigured) {
            return ProviderInitialization(
                provider = OpenAiModels.PROVIDER,
                registeredLlms = emptyList(),
                registeredEmbeddings = emptyList(),
            )
        }
        val definitions = modelLoader.loadAutoConfigMetadata()
        val effectiveModels = definitions.effectiveModels()

        val registeredLlms = buildList {
            // Register LLM models
            effectiveModels.forEach { modelDef ->
                try {
                    val llm = createOpenAiLlm(modelDef)
                    configurableBeanFactory.registerSingleton(modelDef.name, llm)
                    add(RegisteredModel(beanName = modelDef.name, modelId = modelDef.modelId))
                    logger.info(
                        "Registered OpenAI model bean: {} -> {}", modelDef.name, modelDef.modelId
                    )
                } catch (e: Exception) {
                    logger.error(
                        "Failed to create model: {} ({})", modelDef.name, modelDef.modelId, e
                    )
                    throw e
                }
            }
        }

        val registeredEmbeddings = buildList {
            // Register embedding models
            definitions.embeddingModels.forEach { embeddingDef ->
                try {
                    val embeddingService = createOpenAiEmbedding(embeddingDef)
                    configurableBeanFactory.registerSingleton(embeddingDef.name, embeddingService)
                    add(RegisteredModel(beanName = embeddingDef.name, modelId = embeddingDef.modelId))
                    logger.info(
                        "Registered OpenAI embedding model bean: {} -> {}", embeddingDef.name, embeddingDef.modelId
                    )
                } catch (e: Exception) {
                    logger.error(
                        "Failed to create embedding model: {} ({})", embeddingDef.name, embeddingDef.modelId, e
                    )
                    throw e
                }
            }
        }

        return ProviderInitialization(
            provider = OpenAiModels.PROVIDER,
            registeredLlms = registeredLlms,
            registeredEmbeddings = registeredEmbeddings
        ).also { logger.info(it.summary()) }
    }

    /**
     * Creates an individual OpenAI LLM from configuration.
     * Uses custom SpringAiLlm constructor when pricing model is not available.
     */
    private fun createOpenAiLlm(modelDef: OpenAiModelDefinition): LlmService<*> {
        // Determine the appropriate options converter based on model configuration
        val optionsConverter = if (modelDef.specialHandling?.supportsTemperature == false) {
            Gpt5ChatOptionsConverter
        } else {
            StandardOpenAiOptionsConverter
        }

        // Transport is declared per model, like the converter above: most models speak Chat
        // Completions, the *-pro family is served only over the Responses API.
        val chatModel = when (modelDef.apiFormat) {
            OpenAiApiFormat.CHAT_COMPLETIONS -> chatModelOf(
                model = modelDef.modelId, retryTemplate = properties.retryTemplate(modelDef.modelId)
            )

            OpenAiApiFormat.RESPONSES -> OpenAiResponsesChatModel(
                client = openAiClient,
                defaultOptions = OpenAiChatOptions.builder().model(modelDef.modelId).build(),
                observationRegistry = resolvedObservationRegistry,
            )
        }

        // Create pricing model if present
        val pricingModel = modelDef.pricingModel?.let {
            PerTokenPricingModel(
                usdPer1mInputTokens = it.usdPer1mInputTokens,
                usdPer1mOutputTokens = it.usdPer1mOutputTokens,
            )
        }

        // Use SpringAiLlm constructor directly to handle nullable pricing model
        return SpringAiLlmService(
            name = modelDef.modelId,
            chatModel = chatModel,
            provider = OpenAiModels.PROVIDER,
            optionsConverter = optionsConverter,
            knowledgeCutoffDate = modelDef.knowledgeCutoffDate,
            pricingModel = pricingModel,
            thinkingSupported = true,
            nativeStructuredOutputConfigurer = nativeStructuredOutputConfigurer,
            nativeSupport = modelDef.nativeSupport,
        )
    }

    /**
     * Creates an embedding service from configuration.
     */
    private fun createOpenAiEmbedding(embeddingDef: OpenAiEmbeddingModelDefinition): EmbeddingService {
        val pricing = embeddingDef.pricingModel?.let {
            PricingModel.usdPer1MTokens(it.usdPer1mTokens, 0.0)
        }
        return openAiCompatibleEmbeddingService(
            model = embeddingDef.modelId,
            provider = OpenAiModels.PROVIDER,
            configuredDimensions = embeddingDef.dimensions,
            pricingModel = pricing,
        )
    }
}
