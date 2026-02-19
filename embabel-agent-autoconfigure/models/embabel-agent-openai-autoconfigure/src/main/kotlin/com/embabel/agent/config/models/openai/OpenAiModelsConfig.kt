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
import com.embabel.agent.openai.Gpt5ChatOptionsConverter
import com.embabel.agent.openai.OpenAiCompatibleModelFactory
import com.embabel.agent.openai.StandardOpenAiOptionsConverter
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.autoconfig.LlmAutoConfigMetadataLoader
import com.embabel.common.ai.autoconfig.ProviderInitialization
import com.embabel.common.ai.autoconfig.RegisteredModel
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.LlmOptionsProperties
import com.embabel.common.ai.model.PerTokenPricingModel
import com.embabel.common.util.ExcludeFromJacocoGeneratedReport
import io.micrometer.observation.ObservationRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.ClientHttpRequestFactory

/**
 * Configuration properties for OpenAI model settings.
 * These properties can be set in application.properties/yaml using the
 * prefix embabel.agent.platform.models.openai.
 */
@ConfigurationProperties(prefix = "embabel.agent.platform.models.openai")
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
}

/**
 * Configuration for OpenAI language and embedding models.
 * This class dynamically loads and registers OpenAI models from YAML configuration,
 * similar to the Anthropic and Bedrock configuration patterns.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenAiProperties::class, LlmOptionsProperties::class)
@ExcludeFromJacocoGeneratedReport(reason = "OpenAi configuration can't be unit tested")
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
    @Qualifier("aiModelHttpRequestFactory") requestFactory: ObjectProvider<ClientHttpRequestFactory>,
    private val properties: OpenAiProperties,
    private val llmOptionsProperties: LlmOptionsProperties,
    private val configurableBeanFactory: ConfigurableBeanFactory,
    private val modelLoader: LlmAutoConfigMetadataLoader<OpenAiModelDefinitions> = OpenAiModelLoader(),
) : OpenAiCompatibleModelFactory(
    baseUrl = envBaseUrl ?: properties.baseUrl,
    apiKey = envApiKey ?: properties.apiKey
    ?: error("OpenAI API key required: set OPENAI_API_KEY env var or embabel.agent.platform.models.openai.api-key"),
    completionsPath = envCompletionsPath ?: properties.completions,
    embeddingsPath = envEmbeddingsPath ?: properties.embeddingsPath,
    httpHeaders = llmOptionsProperties.httpHeaders,
    observationRegistry = observationRegistry.getIfUnique { ObservationRegistry.NOOP },
    requestFactory = requestFactory,
) {

    init {
        logger.info("OpenAI models are available: {}", properties)
    }

    @Bean
    fun openAiModelsInitializer(): ProviderInitialization {
        val definitions = modelLoader.loadAutoConfigMetadata()

        val registeredLlms = buildList {
            // Register LLM models
            definitions.models.forEach { modelDef ->
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

        val chatModel = chatModelOf(
            model = modelDef.modelId, retryTemplate = properties.retryTemplate(modelDef.modelId)
        )

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
        )
    }

    /**
     * Creates an embedding service from configuration.
     */
    private fun createOpenAiEmbedding(embeddingDef: OpenAiEmbeddingModelDefinition): EmbeddingService {
        return openAiCompatibleEmbeddingService(
            model = embeddingDef.modelId,
            provider = OpenAiModels.PROVIDER,
            configuredDimensions = embeddingDef.dimensions,
        )
    }
}
