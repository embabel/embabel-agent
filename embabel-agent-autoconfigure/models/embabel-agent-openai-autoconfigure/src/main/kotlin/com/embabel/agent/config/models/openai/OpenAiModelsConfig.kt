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

import com.embabel.agent.common.RetryProperties
import com.embabel.agent.config.models.OpenAiCompatibleModelFactory
import com.embabel.agent.config.models.OpenAiModels
import com.embabel.common.ai.model.*
import com.embabel.common.util.ExcludeFromJacocoGeneratedReport
import com.embabel.common.util.loggerFor
import io.micrometer.observation.ObservationRegistry
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.LocalDate


/**
 * Configuration properties for OpenAI model settings.
 * These properties can be set in application.properties/yaml using the
 * prefix embabel.agent.platform.models.openai.
 */
@ConfigurationProperties(prefix = "embabel.agent.platform.models.openai")
data class OpenAiProperties(
    override val maxAttempts: Int = 10,
    override val backoffMillis: Long = 5000L,
    override val backoffMultiplier: Double = 5.0,
    override val backoffMaxInterval: Long = 180000L,
) : RetryProperties

/**
 * Configuration for well-known OpenAI language and embedding models.
 * Provides bean definitions for various GPT models with their corresponding
 * capabilities, knowledge cutoff dates, and pricing models.
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
    observationRegistry: ObservationRegistry,
    private val properties: OpenAiProperties,
) : OpenAiCompatibleModelFactory(
    baseUrl = baseUrl,
    apiKey = apiKey,
    completionsPath = completionsPath,
    embeddingsPath = embeddingsPath,
    observationRegistry = observationRegistry
) {

    init {
        logger.info("Open AI models are available: {}", properties)
    }

    @Bean
    fun gpt5(): Llm {
        return openAiCompatibleLlm(
            model = OpenAiModels.GPT_5,
            provider = OpenAiModels.PROVIDER,
            knowledgeCutoffDate = LocalDate.of(2024, 10, 1),
            pricingModel = PerTokenPricingModel(
                usdPer1mInputTokens = 1.25,
                usdPer1mOutputTokens = 10.0,
            ),
            retryTemplate = properties.retryTemplate(OpenAiModels.GPT_5),
            optionsConverter = Gpt5ChatOptionsConverter,
        )
    }

    @Bean
    fun gpt5mini(): Llm {
        return openAiCompatibleLlm(
            model = OpenAiModels.GPT_5_MINI,
            provider = OpenAiModels.PROVIDER,
            knowledgeCutoffDate = LocalDate.of(2024, 5, 31),
            pricingModel = PerTokenPricingModel(
                usdPer1mInputTokens = .25,
                usdPer1mOutputTokens = 2.0,
            ),
            retryTemplate = properties.retryTemplate(OpenAiModels.GPT_5_MINI),
            optionsConverter = Gpt5ChatOptionsConverter,
        )
    }

    @Bean
    fun gpt5nano(): Llm {
        return openAiCompatibleLlm(
            model = OpenAiModels.GPT_5_NANO,
            provider = OpenAiModels.PROVIDER,
            knowledgeCutoffDate = LocalDate.of(2024, 5, 31),
            pricingModel = PerTokenPricingModel(
                usdPer1mInputTokens = .05,
                usdPer1mOutputTokens = .40,
            ),
            optionsConverter = Gpt5ChatOptionsConverter,
            retryTemplate = properties.retryTemplate(OpenAiModels.GPT_5_NANO),
        )
    }

    @Bean
    fun gpt41mini(): Llm {
        return openAiCompatibleLlm(
            model = OpenAiModels.GPT_41_MINI,
            provider = OpenAiModels.PROVIDER,
            knowledgeCutoffDate = LocalDate.of(2024, 7, 18),
            pricingModel = PerTokenPricingModel(
                usdPer1mInputTokens = .40,
                usdPer1mOutputTokens = 1.6,
            ),
            retryTemplate = properties.retryTemplate(OpenAiModels.GPT_41_MINI),
        )
    }

    @Bean
    fun gpt41(): Llm {
        return openAiCompatibleLlm(
            model = OpenAiModels.GPT_41,
            provider = OpenAiModels.PROVIDER,
            knowledgeCutoffDate = LocalDate.of(2024, 8, 6),
            pricingModel = PerTokenPricingModel(
                usdPer1mInputTokens = 2.0,
                usdPer1mOutputTokens = 8.0,
            ),
            retryTemplate = properties.retryTemplate(OpenAiModels.GPT_41),
        )
    }

    @Bean
    fun gpt41nano(): Llm {
        return openAiCompatibleLlm(
            model = OpenAiModels.GPT_41_NANO,
            provider = OpenAiModels.PROVIDER,
            knowledgeCutoffDate = LocalDate.of(2024, 8, 6),
            pricingModel = PerTokenPricingModel(
                usdPer1mInputTokens = .1,
                usdPer1mOutputTokens = .4,
            ),
            retryTemplate = properties.retryTemplate(OpenAiModels.GPT_41_NANO),
        )
    }

    @Bean
    fun defaultOpenAiEmbeddingService(): EmbeddingService {
        return openAiCompatibleEmbeddingService(
            model = OpenAiModels.DEFAULT_TEXT_EMBEDDING_MODEL,
            provider = OpenAiModels.PROVIDER,
        )
    }
}

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
            .topP(options.topP)
            .build()
    }
}
