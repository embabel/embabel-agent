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
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.Llm
import com.embabel.common.ai.model.PerTokenPricingModel
import io.micrometer.observation.ObservationRegistry
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
 * Well-known OpenAI models.
 */
@Configuration(proxyBeanMethods=false)
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
) : OpenAiCompatibleModelFactory(baseUrl = baseUrl, apiKey = apiKey, completionsPath = completionsPath, embeddingsPath = embeddingsPath, observationRegistry = observationRegistry) {

    init {
        logger.info("Open AI models are available: {}", properties)
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

        const val PROVIDER = "OpenAI"

        const val TEXT_EMBEDDING_3_SMALL = "text-embedding-3-small"

        const val DEFAULT_TEXT_EMBEDDING_MODEL = TEXT_EMBEDDING_3_SMALL
    }
}
