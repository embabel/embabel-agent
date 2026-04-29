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
package com.embabel.agent.config.models.perplexityai

import com.embabel.agent.api.models.PerplexityAiModels
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.autoconfig.LlmAutoConfigMetadataLoader
import com.embabel.common.ai.autoconfig.ProviderInitialization
import com.embabel.common.ai.autoconfig.RegisteredModel
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.OptionsConverter
import com.embabel.common.ai.model.PerTokenPricingModel
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient

/**
 * Configuration properties for Perplexity AI models.
 * These properties control retry behavior when calling Perplexity AI APIs.
 */
@ConfigurationProperties(prefix = "embabel.agent.platform.models.perplexityai")
class PerplexityAiProperties : RetryProperties {
    /**
     * Base URL for Perplexity AI API requests.
     */
    var baseUrl: String? = null

    /**
     * API key for authenticating with Perplexity AI services.
     */
    var apiKey: String? = null

    /**
     * Maximum number of attempts.
     */
    override var maxAttempts: Int = 10

    /**
     * Initial backoff interval (in milliseconds).
     */
    override var backoffMillis: Long = 5_000L

    /**
     * Backoff interval multiplier.
     */
    override var backoffMultiplier: Double = 5.0

    /**
     * Maximum backoff interval (in milliseconds).
     */
    override var backoffMaxInterval: Long = 180_000L
}

/**
 * Configuration for well-known PerplexityAI language and embedding models.
 * Provides bean definitions for various models with their corresponding
 * capabilities, knowledge cutoff dates, and pricing models.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PerplexityAiProperties::class)
class PerplexityAiModelsConfig(
    @param:Value("\${PERPLEXITY_BASE_URL:#{null}}")
    private val envBaseUrl: String?,
    @param:Value("\${PERPLEXITY_API_KEY:#{null}}")
    private val envApiKey: String?,
    private val properties: PerplexityAiProperties,
    private val observationRegistry: ObjectProvider<ObservationRegistry>,
    private val configurableBeanFactory: ConfigurableBeanFactory,
    private val modelLoader: LlmAutoConfigMetadataLoader<PerplexityAiModelDefinitions> = PerplexityAiModelLoader(),
) {
    private val logger = LoggerFactory.getLogger(PerplexityAiModelsConfig::class.java)

    private val baseUrl: String? = envBaseUrl ?: properties.baseUrl
    private val apiKey: String = envApiKey ?: properties.apiKey
    ?: error("Perplexity AI API key required: set PERPLEXITY_API_KEY env var or embabel.agent.platform.models.perplexityai.api-key")

    init {
        logger.info("Perplexity AI models are available: {}", properties)
    }

    @Bean
    fun perplexityAiModelsInitializer(): ProviderInitialization {
        val registeredLlms = buildList {
            modelLoader
                .loadAutoConfigMetadata().models.forEach { modelDef ->
                    try {
                        val llm = createPerplexityAiLlm(modelDef)

                        // Register as singleton bean with the configured bean name
                        configurableBeanFactory.registerSingleton(modelDef.name, llm)
                        add(RegisteredModel(beanName = modelDef.name, modelId = modelDef.modelId))

                        logger.info(
                            "Registered Perplexity AI model bean: {} -> {}",
                            modelDef.name, modelDef.modelId
                        )

                    } catch (e: Exception) {
                        logger.error(
                            "Failed to create model: {} ({})",
                            modelDef.name, modelDef.modelId, e
                        )
                        throw e
                    }
                }
        }

        return ProviderInitialization(
            provider = PerplexityAiModels.PROVIDER,
            registeredLlms = registeredLlms,
        ).also { logger.info(it.summary()) }
    }

    /**
     * Creates an individual Perplexity AI model from configuration.
     */
    private fun createPerplexityAiLlm(modelDef: PerplexityAiModelDefinition): LlmService<*> {
        // Spring AI integrates with Perplexity AI by reusing the existing OpenAI client.
        // See https://docs.spring.io/spring-ai/reference/api/chat/perplexity-chat.html.
        val perplexityChatModel = OpenAiChatModel
            .builder()
            .defaultOptions(createDefaultOptions(modelDef))
            .openAiApi(createPerplexityAiApi())
            .toolCallingManager(
                ToolCallingManager.builder()
                    .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
                    .build()
            )
            .retryTemplate(properties.retryTemplate("perplexity-ai-${modelDef.modelId}"))
            .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
            .build()

        return SpringAiLlmService(
            name = modelDef.modelId,
            chatModel = perplexityChatModel,
            provider = PerplexityAiModels.PROVIDER,
            optionsConverter = PerplexityAiOptionsConverter,
            knowledgeCutoffDate = modelDef.knowledgeCutoffDate,
            pricingModel = modelDef.pricingModel?.let {
                PerTokenPricingModel(
                    usdPer1mInputTokens = it.usdPer1mInputTokens,
                    usdPer1mOutputTokens = it.usdPer1mOutputTokens,
                )
            }
        )
    }

    /**
     * Creates default options for a model based on YAML configuration.
     */
    private fun createDefaultOptions(modelDef: PerplexityAiModelDefinition): OpenAiChatOptions {
        return OpenAiChatOptions.builder()
            .model(modelDef.modelId)
            .maxTokens(modelDef.maxTokens)
            .temperature(modelDef.temperature)
            .apply {
                modelDef.topP?.let { topP(it) }
            }
            .build()
    }

    private fun createPerplexityAiApi(): OpenAiApi {
        val builder = OpenAiApi.builder().apiKey(apiKey)
        if (!baseUrl.isNullOrBlank()) {
            logger.info("Using custom Perplexity AI base URL: {}", baseUrl)
            builder.baseUrl(baseUrl)
        }
        // add observation registry to rest and web client builders
        builder
            .restClientBuilder(
                RestClient.builder()
                    .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
            )
        builder
            .webClientBuilder(
                WebClient.builder()
                    .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
            )

        return builder.build()
    }
}

object PerplexityAiOptionsConverter : OptionsConverter<OpenAiChatOptions> {

    override fun convertOptions(options: LlmOptions): OpenAiChatOptions =
        OpenAiChatOptions.builder()
            .temperature(options.temperature)
            .topP(options.topP)
            .maxTokens(options.maxTokens)
            .build()
}
