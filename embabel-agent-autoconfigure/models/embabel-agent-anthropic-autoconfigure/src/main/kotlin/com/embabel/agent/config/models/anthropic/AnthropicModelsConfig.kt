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
package com.embabel.agent.config.models.anthropic

import com.embabel.agent.api.models.AnthropicModels
import com.embabel.agent.api.models.OpenAiModels
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.common.ai.autoconfig.LlmAutoConfigMetadataLoader
import com.embabel.common.ai.autoconfig.ProviderInitialization
import com.embabel.common.ai.autoconfig.RegisteredModel
import com.embabel.common.ai.model.Llm
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.OptionsConverter
import com.embabel.common.ai.model.PerTokenPricingModel
import com.embabel.common.util.ExcludeFromJacocoGeneratedReport
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.anthropic.api.AnthropicApi
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient
import java.time.LocalDate


/**
 * Configuration properties for Anthropic models.
 * These properties are bound from the Spring configuration with the prefix
 * "embabel.agent.platform.models.anthropic" and control retry behavior
 * when calling Anthropic APIs.
 */
@ConfigurationProperties(prefix = "embabel.agent.platform.models.anthropic")
class AnthropicProperties : RetryProperties {
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
 * Configuration class for Anthropic models.
 * This class provides beans for various Claude models (Opus, Sonnet, Haiku)
 * and handles the creation of Anthropic API clients with proper authentication.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnthropicProperties::class)
@ExcludeFromJacocoGeneratedReport(reason = "Anthropic configuration can't be unit tested")
class AnthropicModelsConfig(
    @param:Value("\${ANTHROPIC_BASE_URL:}")
    private val baseUrl: String,
    @param:Value("\${ANTHROPIC_API_KEY}")
    private val apiKey: String,
    private val properties: AnthropicProperties,
    private val observationRegistry: ObjectProvider<ObservationRegistry>,
    @param:Qualifier("aiModelHttpRequestFactory")
    private val requestFactory: ObjectProvider<ClientHttpRequestFactory>,
    private val configurableBeanFactory: ConfigurableBeanFactory,
    private val modelLoader: LlmAutoConfigMetadataLoader<AnthropicModelDefinitions> = AnthropicModelLoader(),
) {
    private val logger = LoggerFactory.getLogger(AnthropicModelsConfig::class.java)

    init {
        logger.info("Anthropic models are available: {}", properties)
    }

    @Bean
    fun anthropicModelsInitializer(): ProviderInitialization {
        val registeredLlms = buildList {
            modelLoader
                .loadAutoConfigMetadata().models.forEach { modelDef ->
                    try {
                        val llm = createAnthropicLlm(modelDef)

                        // Register as singleton bean with the configured bean name
                        configurableBeanFactory.registerSingleton(modelDef.name, llm)
                        add(RegisteredModel(beanName = modelDef.name, modelId = modelDef.modelId))

                        logger.info(
                            "Registered Anthropic model bean: {} -> {}",
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
            provider = AnthropicModels.PROVIDER,
            registeredLlms = registeredLlms,
        ).also { logger.info(it.summary()) }
    }

    /**
     * Creates an individual Anthropic model from configuration.
     */
    private fun createAnthropicLlm(modelDef: AnthropicModelDefinition): Llm {
        val chatModel = AnthropicChatModel
            .builder()
            .defaultOptions(createDefaultOptions(modelDef))
            .anthropicApi(createAnthropicApi())
            .toolCallingManager(
                ToolCallingManager.builder()
                    .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
                    .build()
            )
            .retryTemplate(properties.retryTemplate("anthropic-${modelDef.modelId}"))
            .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
            .build()

        return Llm(
            name = modelDef.modelId,
            model = chatModel,
            provider = AnthropicModels.PROVIDER,
            optionsConverter = AnthropicOptionsConverter,
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
    private fun createDefaultOptions(modelDef: AnthropicModelDefinition): AnthropicChatOptions {
        return AnthropicChatOptions.builder()
            .model(modelDef.modelId)
            .maxTokens(modelDef.maxTokens)
            .temperature(modelDef.temperature)
            .apply {
                modelDef.topP?.let { topP(it) }
                modelDef.topK?.let { topK(it) }

                // Configure thinking mode if specified
                modelDef.thinking?.let { thinkingConfig ->
                    thinking(
                        AnthropicApi.ChatCompletionRequest.ThinkingConfig(
                            AnthropicApi.ThinkingType.ENABLED,
                            thinkingConfig.tokenBudget
                        )
                    )
                } ?: thinking(
                    AnthropicApi.ChatCompletionRequest.ThinkingConfig(
                        AnthropicApi.ThinkingType.DISABLED,
                        null
                    )
                )
            }
            .build()
    }

    private fun anthropicLlmOf(
        name: String,
        knowledgeCutoffDate: LocalDate?,
    ): Llm {
        val chatModel = AnthropicChatModel
            .builder()
            .defaultOptions(
                AnthropicChatOptions.builder()
                    .model(name)
                    .build()
            )
            .anthropicApi(createAnthropicApi())
            .toolCallingManager(
                ToolCallingManager.builder()
                    .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
                    .build()
            )
            .retryTemplate(properties.retryTemplate("anthropic-$name"))
            .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
            .build()

        return Llm(
            name = name,
            model = chatModel,
            provider = AnthropicModels.PROVIDER,
            optionsConverter = AnthropicOptionsConverter,
            knowledgeCutoffDate = knowledgeCutoffDate,
        )
    }

    private fun createAnthropicApi(): AnthropicApi {
        val builder = AnthropicApi.builder().apiKey(apiKey)
        if (baseUrl.isNotBlank()) {
            logger.info("Using custom Anthropic base URL: {}", baseUrl)
            builder.baseUrl(baseUrl)
        }
        // add observation registry to rest and web client builders
        builder
            .restClientBuilder(
                RestClient.builder()
                    .also { b -> requestFactory.ifAvailable { b.requestFactory(it) } }
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

object AnthropicOptionsConverter : OptionsConverter<AnthropicChatOptions> {

    /**
     * Anthropic's default is too low and results in truncated responses.
     */
    const val DEFAULT_MAX_TOKENS = 8192

    override fun convertOptions(options: LlmOptions): AnthropicChatOptions =
        AnthropicChatOptions.builder()
            .temperature(options.temperature)
            .topP(options.topP)
            .maxTokens(options.maxTokens ?: DEFAULT_MAX_TOKENS)
            .thinking(
                if (options.thinking?.enabled == true) AnthropicApi.ChatCompletionRequest.ThinkingConfig(
                    AnthropicApi.ThinkingType.ENABLED,
                    options.thinking!!.tokenBudget,
                ) else AnthropicApi.ChatCompletionRequest.ThinkingConfig(
                    AnthropicApi.ThinkingType.DISABLED,
                    null,
                )
            )
            .topK(options.topK)
            .build()
}
