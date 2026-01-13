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
package com.embabel.agent.config.models.googlegenai

import com.embabel.agent.api.models.GoogleGenAiModels
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.autoconfig.LlmAutoConfigMetadataLoader
import com.embabel.common.ai.autoconfig.ProviderInitialization
import com.embabel.common.ai.autoconfig.RegisteredModel
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.OptionsConverter
import com.embabel.common.ai.model.PerTokenPricingModel
import com.embabel.common.util.ExcludeFromJacocoGeneratedReport
import com.google.genai.Client
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.ai.google.genai.GoogleGenAiChatModel
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Configuration properties for Google GenAI models.
 * These properties are bound from the Spring configuration with the prefix
 * "embabel.agent.platform.models.googlegenai" and control retry behavior
 * when calling Google GenAI APIs.
 *
 * Authentication can be configured via:
 * - API Key: Set [apiKey] for Gemini Developer API access
 * - Vertex AI: Set [projectId] and [location] for Google Cloud Vertex AI access
 *
 * If both are configured, Vertex AI (project/location) takes precedence.
 */
@ConfigurationProperties(prefix = "embabel.agent.platform.models.googlegenai")
class GoogleGenAiProperties : RetryProperties {

    /**
     * API key for Google Gemini Developer API.
     * Can also be set via GOOGLE_API_KEY environment variable.
     */
    var apiKey: String? = null

    /**
     * Google Cloud project ID for Vertex AI mode.
     * Can also be set via GOOGLE_PROJECT_ID environment variable.
     */
    var projectId: String? = null

    /**
     * Google Cloud region/location for Vertex AI mode (e.g., "us-central1").
     * Can also be set via GOOGLE_LOCATION environment variable.
     */
    var location: String? = null

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
 * Configuration class for Google GenAI models.
 * This class provides beans for various Gemini models (3 Pro, 2.5 Pro, 2.5 Flash, etc.)
 * and handles the creation of Google GenAI API clients with proper authentication.
 *
 * Uses native Spring AI Google GenAI support (spring-ai-google-genai).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GoogleGenAiProperties::class)
@ExcludeFromJacocoGeneratedReport(reason = "Google GenAI configuration can't be unit tested")
class GoogleGenAiModelsConfig(
    @param:Value("\${GOOGLE_API_KEY:}")
    private val apiKey: String,
    @param:Value("\${GOOGLE_PROJECT_ID:}")
    private val projectId: String,
    @param:Value("\${GOOGLE_LOCATION:}")
    private val location: String,
    private val properties: GoogleGenAiProperties,
    private val observationRegistry: ObjectProvider<ObservationRegistry>,
    private val configurableBeanFactory: ConfigurableBeanFactory,
    private val modelLoader: LlmAutoConfigMetadataLoader<GoogleGenAiModelDefinitions> = GoogleGenAiModelLoader(),
) {
    private val logger = LoggerFactory.getLogger(GoogleGenAiModelsConfig::class.java)

    init {
        logger.info("Google GenAI models are available: {}", properties)
    }

    @Bean
    fun googleGenAiModelsInitializer(): ProviderInitialization {
        val registeredLlms = buildList {
            modelLoader
                .loadAutoConfigMetadata().models.forEach { modelDef ->
                    try {
                        val llm = createGoogleGenAiLlm(modelDef)

                        // Register as singleton bean with the configured bean name
                        configurableBeanFactory.registerSingleton(modelDef.name, llm)
                        add(RegisteredModel(beanName = modelDef.name, modelId = modelDef.modelId))

                        logger.info(
                            "Registered Google GenAI model bean: {} -> {}",
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
            provider = GoogleGenAiModels.PROVIDER,
            registeredLlms = registeredLlms,
        ).also { logger.info(it.summary()) }
    }

    /**
     * Creates an individual Google GenAI model from configuration.
     */
    private fun createGoogleGenAiLlm(modelDef: GoogleGenAiModelDefinition): LlmService<*> {
        val chatModel = GoogleGenAiChatModel(
            createGoogleGenAiClient(),
            createDefaultOptions(modelDef),
            ToolCallingManager.builder()
                .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
                .build(),
            properties.retryTemplate("googlegenai-${modelDef.modelId}"),
            observationRegistry.getIfUnique { ObservationRegistry.NOOP }
        )

        return SpringAiLlmService(
            name = modelDef.modelId,
            chatModel = chatModel,
            provider = GoogleGenAiModels.PROVIDER,
            optionsConverter = GoogleGenAiOptionsConverter,
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
    private fun createDefaultOptions(modelDef: GoogleGenAiModelDefinition): GoogleGenAiChatOptions {
        return GoogleGenAiChatOptions.builder()
            .model(modelDef.modelId)
            .maxOutputTokens(modelDef.maxOutputTokens)
            .temperature(modelDef.temperature)
            .apply {
                modelDef.topP?.let { topP(it) }
                modelDef.topK?.let { topK(it) }
                modelDef.thinkingBudget?.let { thinkingBudget(it) }
            }
            .build()
    }

    /**
     * Creates the Google GenAI Client.
     * Supports both API key authentication and Vertex AI (project/location) authentication.
     *
     * Priority order (following Spring AI conventions):
     * 1. YAML properties (embabel.agent.platform.models.googlegenai.*)
     * 2. Environment variables (GOOGLE_API_KEY, GOOGLE_PROJECT_ID, GOOGLE_LOCATION)
     *
     * If both API key and Vertex AI (project/location) are configured,
     * Vertex AI takes precedence.
     */
    private fun createGoogleGenAiClient(): Client {
        val builder = Client.builder()

        // Resolve credentials: YAML properties take precedence over environment variables
        val resolvedApiKey = properties.apiKey?.takeIf { it.isNotBlank() } ?: apiKey
        val resolvedProjectId = properties.projectId?.takeIf { it.isNotBlank() } ?: projectId
        val resolvedLocation = properties.location?.takeIf { it.isNotBlank() } ?: location

        // Vertex AI takes precedence if both project and location are configured
        if (resolvedProjectId.isNotBlank() && resolvedLocation.isNotBlank()) {
            logger.info(
                "Using Google GenAI with Vertex AI authentication (project: {}, location: {})",
                resolvedProjectId,
                resolvedLocation
            )
            builder.project(resolvedProjectId)
            builder.location(resolvedLocation)
            builder.vertexAI(true)
        } else if (resolvedApiKey.isNotBlank()) {
            logger.info("Using Google GenAI with API key authentication")
            builder.apiKey(resolvedApiKey)
        } else {
            throw IllegalStateException(
                "Google GenAI requires either api-key or both project-id and location to be set. " +
                        "Configure via YAML (embabel.agent.platform.models.googlegenai.*) " +
                        "or environment variables (GOOGLE_API_KEY, GOOGLE_PROJECT_ID, GOOGLE_LOCATION)"
            )
        }

        return builder.build()
    }
}

/**
 * Converts [LlmOptions] to [GoogleGenAiChatOptions].
 */
object GoogleGenAiOptionsConverter : OptionsConverter<GoogleGenAiChatOptions> {

    /**
     * Default max output tokens for Google GenAI models.
     */
    const val DEFAULT_MAX_OUTPUT_TOKENS = 8192

    override fun convertOptions(options: LlmOptions): GoogleGenAiChatOptions =
        GoogleGenAiChatOptions.builder()
            .temperature(options.temperature)
            .topP(options.topP)
            .topK(options.topK)
            .maxOutputTokens(options.maxTokens ?: DEFAULT_MAX_OUTPUT_TOKENS)
            .apply {
                options.thinking?.let { thinkingConfig ->
                    if (thinkingConfig.enabled) {
                        thinkingConfig.tokenBudget?.let { thinkingBudget(it) }
                    }
                }
            }
            .build()
}
