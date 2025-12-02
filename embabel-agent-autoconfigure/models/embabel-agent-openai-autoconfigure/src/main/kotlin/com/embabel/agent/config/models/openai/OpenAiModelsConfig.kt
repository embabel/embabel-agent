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
package com.embabel.agent.config.models.openai

import com.embabel.agent.api.models.OpenAiModels
import com.embabel.agent.openai.OpenAiCompatibleModelFactory
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.common.ai.autoconfig.LlmAutoConfigMetadataLoader
import com.embabel.common.ai.model.*
import com.embabel.common.util.ExcludeFromJacocoGeneratedReport
import com.embabel.common.util.loggerFor
import io.micrometer.observation.ObservationRegistry
import jakarta.annotation.PostConstruct
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.LocalDate

/**
 * Configuration properties for OpenAI model settings.
 * These properties can be set in application.properties/yaml using the
 * prefix embabel.agent.platform.models.openai.
 */
@ConfigurationProperties(prefix = "embabel.agent.platform.models.openai")
class OpenAiProperties : RetryProperties {
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
@ExcludeFromJacocoGeneratedReport(reason = "OpenAi configuration can't be unit tested")
class OpenAiModelsConfig(
    @Value("\${OPENAI_BASE_URL:#{null}}")
    baseUrl: String?,
    @Value("\${OPENAI_API_KEY}")
    apiKey: String,
    @Value("\${OPENAI_COMPLETIONS_PATH:#{null}}")
    completionsPath: String?,
    @Value("\${OPENAI_EMBEDDINGS_PATH:#{null}}")
    embeddingsPath: String?,
    observationRegistry: ObjectProvider<ObservationRegistry>,
    private val properties: OpenAiProperties,
    private val configurableBeanFactory: ConfigurableBeanFactory,
    private val modelLoader: LlmAutoConfigMetadataLoader<OpenAiModelDefinitions> = OpenAiModelLoader(),
) : OpenAiCompatibleModelFactory(
    baseUrl = baseUrl,
    apiKey = apiKey,
    completionsPath = completionsPath,
    embeddingsPath = embeddingsPath,
    observationRegistry = observationRegistry.getIfUnique { ObservationRegistry.NOOP }
) {

    init {
        logger.info("OpenAI models are available: {}", properties)
    }

    @PostConstruct
    fun registerModelBeans() {
        val definitions = modelLoader.loadAutoConfigMetadata()

        // Register LLM models
        definitions.models.forEach { modelDef ->
            try {
                val llm = createOpenAiLlm(modelDef)
                configurableBeanFactory.registerSingleton(modelDef.name, llm)
                logger.info(
                    "Registered OpenAI model bean: {} -> {}",
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

        // Register embedding models
        definitions.embeddingModels.forEach { embeddingDef ->
            try {
                val embeddingService = createOpenAiEmbedding(embeddingDef)
                configurableBeanFactory.registerSingleton(embeddingDef.name, embeddingService)
                logger.info(
                    "Registered OpenAI embedding model bean: {} -> {}",
                    embeddingDef.name, embeddingDef.modelId
                )
            } catch (e: Exception) {
                logger.error(
                    "Failed to create embedding model: {} ({})",
                    embeddingDef.name, embeddingDef.modelId, e
                )
                throw e
            }
        }
    }

    /**
     * Creates an individual OpenAI LLM from configuration.
     * Uses custom Llm constructor when pricing model is not available.
     */
    private fun createOpenAiLlm(modelDef: OpenAiModelDefinition): Llm {
        // Determine the appropriate options converter based on model configuration
        val optionsConverter = if (modelDef.specialHandling?.supportsTemperature == false) {
            Gpt5ChatOptionsConverter
        } else {
            StandardOpenAiOptionsConverter
        }

        val chatModel = chatModelOf(
            model = modelDef.modelId,
            retryTemplate = properties.retryTemplate(modelDef.modelId)
        )

        // Create pricing model if present
        val pricingModel = modelDef.pricingModel?.let {
            PerTokenPricingModel(
                usdPer1mInputTokens = it.usdPer1mInputTokens,
                usdPer1mOutputTokens = it.usdPer1mOutputTokens,
            )
        }

        // Use Llm constructor directly to handle nullable pricing model
        return Llm(
            name = modelDef.modelId,
            model = chatModel,
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
        )
    }
}

/**
 * Options converter for GPT-5 models that don't support temperature adjustment.
 */
internal object Gpt5ChatOptionsConverter : OptionsConverter<OpenAiChatOptions> {

    override fun convertOptions(options: LlmOptions): OpenAiChatOptions {
        if (options.temperature != null && options.temperature != 1.0) {
            loggerFor<Gpt5ChatOptionsConverter>().warn(
                "GPT-5 models do not support temperature settings other than default 1.0. You set {} but it will be ignored.",
                options.temperature,
            )
        }
        return OpenAiChatOptions.builder()
            .topP(options.topP)
            .maxTokens(options.maxTokens)
            .presencePenalty(options.presencePenalty)
            .frequencyPenalty(options.frequencyPenalty)
            .build()
    }
}

/**
 * Standard options converter for OpenAI models that support all parameters.
 */
internal object StandardOpenAiOptionsConverter : OptionsConverter<OpenAiChatOptions> {

    override fun convertOptions(options: LlmOptions): OpenAiChatOptions {
        return OpenAiChatOptions.builder()
            .temperature(options.temperature)
            .topP(options.topP)
            .maxTokens(options.maxTokens)
            .presencePenalty(options.presencePenalty)
            .frequencyPenalty(options.frequencyPenalty)
            .build()
    }
}
