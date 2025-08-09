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
package com.embabel.agent.config.models

import com.embabel.agent.common.RetryProperties
import com.embabel.common.ai.model.*
import com.embabel.common.util.loggerFor
import io.micrometer.observation.ObservationRegistry
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.time.LocalDate

@ConfigurationProperties(prefix = "embabel.openai")
data class OpenAiProperties(
    override val maxAttempts: Int = 10,
    override val backoffMillis: Long = 5000L,
    override val backoffMultiplier: Double = 5.0,
    override val backoffMaxInterval: Long = 180000L,
) : RetryProperties

/**
 * Well-known models from OpenAI.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty("OPENAI_API_KEY")
class OpenAiModels(
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
            model = GPT_5,
            provider = PROVIDER,
            knowledgeCutoffDate = LocalDate.of(2024, 10, 1),
            pricingModel = PerTokenPricingModel(
                usdPer1mInputTokens = 1.25,
                usdPer1mOutputTokens = 10.0,
            ),
            retryTemplate = properties.retryTemplate(GPT_5),
            optionsConverter = Gpt5ChatOptionsConverter,
        )
    }

    @Bean
    fun gpt5mini(): Llm {
        return openAiCompatibleLlm(
            model = GPT_5_MINI,
            provider = PROVIDER,
            knowledgeCutoffDate = LocalDate.of(2024, 5, 31),
            pricingModel = PerTokenPricingModel(
                usdPer1mInputTokens = .25,
                usdPer1mOutputTokens = 2.0,
            ),
            retryTemplate = properties.retryTemplate(GPT_5_MINI),
            optionsConverter = Gpt5ChatOptionsConverter,
        )
    }

    @Bean
    fun gpt5nano(): Llm {
        return openAiCompatibleLlm(
            model = GPT_5_NANO,
            provider = PROVIDER,
            knowledgeCutoffDate = LocalDate.of(2024, 5, 31),
            pricingModel = PerTokenPricingModel(
                usdPer1mInputTokens = .05,
                usdPer1mOutputTokens = .40,
            ),
            optionsConverter = Gpt5ChatOptionsConverter,
            retryTemplate = properties.retryTemplate(GPT_5_NANO),
        )
    }

    @Bean
    fun gpt41mini(): Llm {
        return openAiCompatibleLlm(
            model = GPT_41_MINI,
            provider = PROVIDER,
            knowledgeCutoffDate = LocalDate.of(2024, 7, 18),
            pricingModel = PerTokenPricingModel(
                usdPer1mInputTokens = .40,
                usdPer1mOutputTokens = 1.6,
            ),
            retryTemplate = properties.retryTemplate(GPT_41_MINI),
        )
    }

    @Bean
    fun gpt41(): Llm {
        return openAiCompatibleLlm(
            model = GPT_41,
            provider = PROVIDER,
            knowledgeCutoffDate = LocalDate.of(2024, 8, 6),
            pricingModel = PerTokenPricingModel(
                usdPer1mInputTokens = 2.0,
                usdPer1mOutputTokens = 8.0,
            ),
            retryTemplate = properties.retryTemplate(GPT_41),
        )
    }

    @Bean
    fun gpt41nano(): Llm {
        return openAiCompatibleLlm(
            model = GPT_41_NANO,
            provider = PROVIDER,
            knowledgeCutoffDate = LocalDate.of(2024, 8, 6),
            pricingModel = PerTokenPricingModel(
                usdPer1mInputTokens = .1,
                usdPer1mOutputTokens = .4,
            ),
            retryTemplate = properties.retryTemplate(GPT_41_NANO),
        )
    }

    @Bean
    fun defaultOpenAiEmbeddingService(): EmbeddingService {
        return openAiCompatibleEmbeddingService(
            model = DEFAULT_TEXT_EMBEDDING_MODEL,
            provider = PROVIDER,
        )
    }

    companion object {

        const val GPT_41_MINI = "gpt-4.1-mini"

        const val GPT_41 = "gpt-4.1"

        const val GPT_41_NANO = "gpt-4.1-nano"

        const val GPT_5 = "gpt-5"

        const val GPT_5_MINI = "gpt-5-mini"

        const val GPT_5_NANO = "gpt-5-nano"

        const val PROVIDER = "OpenAI"

        const val TEXT_EMBEDDING_3_SMALL = "text-embedding-3-small"

        const val DEFAULT_TEXT_EMBEDDING_MODEL = TEXT_EMBEDDING_3_SMALL
    }
}

internal object Gpt5ChatOptionsConverter : OptionsConverter<OpenAiChatOptions> {

    override fun convertOptions(options: LlmOptions): OpenAiChatOptions {
        if (options.temperature != 1.0) {
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
