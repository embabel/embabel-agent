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
package com.embabel.agent.config.models.gemini

import com.embabel.agent.api.models.GeminiModels
import com.embabel.agent.openai.OpenAiChatOptionsConverter
import com.embabel.agent.openai.OpenAiCompatibleModelFactory
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.common.ai.autoconfig.LlmAutoConfigMetadataLoader
import com.embabel.common.ai.autoconfig.ProviderInitialization
import com.embabel.common.ai.autoconfig.RegisteredModel
import com.embabel.common.ai.model.Llm
import com.embabel.common.ai.model.PerTokenPricingModel
import com.embabel.common.ai.model.PricingModel
import com.embabel.common.util.ExcludeFromJacocoGeneratedReport
import io.micrometer.observation.ObservationRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Configuration properties for Gemini models.
 * These properties are bound from the Spring configuration with the prefix
 * "embabel.agent.platform.models.gemini" and control retry behavior
 * when calling Google Gemini APIs.
 */
@ConfigurationProperties(prefix = "embabel.agent.platform.models.gemini")
class GeminiProperties : RetryProperties {
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
 * Configuration class for Google Gemini models.
 * This class dynamically loads and registers Gemini models from YAML configuration,
 * using OpenAI-compatible API endpoints for seamless integration.
 *
 * Models are loaded from `classpath:models/gemini-models.yml` and registered
 * as Spring beans at startup via @PostConstruct.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GeminiProperties::class)
@ExcludeFromJacocoGeneratedReport(reason = "Gemini configuration can't be unit tested")
class GeminiModelsConfig(
    @Value("\${GEMINI_BASE_URL:https://generativelanguage.googleapis.com/v1beta/openai}")
    baseUrl: String,
    @Value("\${GEMINI_API_KEY}")
    apiKey: String,
    observationRegistry: ObjectProvider<ObservationRegistry>,
    private val properties: GeminiProperties,
    private val configurableBeanFactory: ConfigurableBeanFactory,
    private val modelLoader: LlmAutoConfigMetadataLoader<GeminiModelDefinitions> = GeminiModelLoader(),
) : OpenAiCompatibleModelFactory(
    baseUrl = baseUrl,
    apiKey = apiKey,
    completionsPath = null,
    embeddingsPath = null,
    observationRegistry = observationRegistry.getIfUnique { ObservationRegistry.NOOP }
) {

    init {
        logger.info("Google Gemini models are available: {}", properties)
    }

    @Bean
    fun geminiModelsInitializer(): ProviderInitialization {
        val registeredLlms = buildList {
            modelLoader
                .loadAutoConfigMetadata().models.forEach { modelDef ->
                    try {
                        val llm = createGeminiLlm(modelDef)

                        // Register as singleton bean with the configured bean name
                        configurableBeanFactory.registerSingleton(modelDef.name, llm)
                        add(RegisteredModel(beanName = modelDef.name, modelId = modelDef.modelId))

                        logger.info(
                            "Registered Gemini model bean: {} -> {}",
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
            provider = GeminiModels.PROVIDER,
            registeredLlms = registeredLlms,
        ).also { logger.info(it.summary()) }
    }

    /**
     * Creates an individual Gemini LLM from configuration.
     * Uses OpenAI-compatible API format via the parent factory.
     */
    private fun createGeminiLlm(modelDef: GeminiModelDefinition): Llm {
        return openAiCompatibleLlm(
            modelDef.modelId,
            modelDef.pricingModel?.let {
                PerTokenPricingModel(
                    usdPer1mInputTokens = it.usdPer1mInputTokens,
                    usdPer1mOutputTokens = it.usdPer1mOutputTokens,
                )
            } as PricingModel,
            provider = GeminiModels.PROVIDER,
            knowledgeCutoffDate = modelDef.knowledgeCutoffDate,
            optionsConverter = OpenAiChatOptionsConverter,
            retryTemplate = properties.retryTemplate(modelDef.modelId)
        )
    }
}
