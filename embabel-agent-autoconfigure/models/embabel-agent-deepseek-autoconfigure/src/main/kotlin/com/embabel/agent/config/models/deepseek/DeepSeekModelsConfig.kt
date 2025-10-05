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
package com.embabel.agent.config.models.deepseek

import com.embabel.agent.common.RetryProperties
import com.embabel.agent.config.models.DeepSeekModels
import com.embabel.common.ai.model.Llm
import com.embabel.common.ai.model.OptionsConverter
import com.embabel.common.ai.model.PerTokenPricingModel
import com.embabel.common.util.ExcludeFromJacocoGeneratedReport
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.ai.deepseek.DeepSeekChatModel
import org.springframework.ai.deepseek.DeepSeekChatOptions
import org.springframework.ai.deepseek.api.DeepSeekApi
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient
import java.time.LocalDate

/**
 * Configuration properties for Deepseek models.
 * These properties are bound from the Spring configuration with the prefix
 * "embabel.agent.platform.models.deepseek" and control retry behavior
 * when calling Deepseek APIs.
 */
@ConfigurationProperties(prefix = "embabel.agent.platform.models.deepseek")
class DeepSeekProperties : RetryProperties {
    /**
     *  Maximum number of attempts.
     */
    override var maxAttempts: Int = 4

    /**
     * Initial backoff interval (in milliseconds).
     */
    override var backoffMillis: Long = 1500L

    /**
     * Backoff interval multiplier.
     */
    override var backoffMultiplier: Double = 2.0

    /**
     * Maximum backoff interval (in milliseconds).
     */
    override var backoffMaxInterval: Long = 60000L
}

/**
 * Configuration class for DeepSeek models.
 * This class provides beans for various DeepSeek models (chat, reasoner)
 * and handles the creation of DeepSeek API clients with proper authentication.
 */
@Configuration(proxyBeanMethods = false)
@ExcludeFromJacocoGeneratedReport(reason = "DeepSeek configuration can't be unit tested")
class DeepSeekModelsConfig(
    @param:Value("\${DEEPSEEK_BASE_URL:}")
    private val baseUrl: String,
    @param:Value("\${DEEPSEEK_API_KEY}")
    private val apiKey: String,
    private val properties: DeepSeekProperties,
    private val observationRegistry: ObjectProvider<ObservationRegistry>,
) {
    private val logger = LoggerFactory.getLogger(DeepSeekModelsConfig::class.java)

    init {
        logger.info("DeepSeek models are available: {}", properties)
    }

    @Bean
    fun deepSeekChat(): Llm {
        return deepSeekLlmOf(
            DeepSeekModels.DEEPSEEK_CHAT,
            knowledgeCutoffDate = LocalDate.of(2025, 8, 21),
        )
            // https://api-docs.deepseek.com/quick_start/pricing
            // 1M Input tokens Cache hit $0.07
            // 1M Input tokens Cache miss $0.56
            .copy(
                pricingModel = PerTokenPricingModel(
                    usdPer1mInputTokens = 0.56,
                    usdPer1mOutputTokens = 1.68,
                )
            )
    }

    @Bean
    fun deepSeekReasoner(): Llm = deepSeekLlmOf(
        DeepSeekModels.DEEPSEEK_REASONER,
        knowledgeCutoffDate = LocalDate.of(2025, 5, 28),
    )
        // https://api-docs.deepseek.com/quick_start/pricing
        // 1M Input tokens Cache hit $0.07
        // 1M Input tokens Cache miss $0.56
        .copy(
            pricingModel = PerTokenPricingModel(
                usdPer1mInputTokens = 0.56,
                usdPer1mOutputTokens = 1.68,
            )
        )

    private fun deepSeekLlmOf(
        name: String,
        knowledgeCutoffDate: LocalDate?
    ): Llm {
        val chatModel = DeepSeekChatModel
            .builder()
            .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
            .toolCallingManager(ToolCallingManager.builder()
                .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
                .build())
            .defaultOptions(
                DeepSeekChatOptions.builder()
                    .model(name)
                    .build()
            )
            .deepSeekApi(createDeepSeekApi())
            .retryTemplate(properties.retryTemplate(name))
            .build()
        return Llm(
            name = name,
            model = chatModel,
            provider = DeepSeekModels.PROVIDER,
            optionsConverter = DeepSeekOptionsConverter,
            knowledgeCutoffDate = knowledgeCutoffDate,
        )
    }

    private fun createDeepSeekApi(): DeepSeekApi {
        val builder = DeepSeekApi.builder().apiKey(apiKey)
        // If baseUrl is blank, use default baseUrl https://api.deepseek.com
        if (baseUrl.isNotBlank()) {
            logger.info("Using custom DeepSeek base URL: {}", baseUrl)
            builder.baseUrl(baseUrl)
        }
        return builder
            .restClientBuilder(RestClient.builder()
                .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP }))
            .webClientBuilder(WebClient.builder()
                .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP }))
            .build()
    }
}

val DeepSeekOptionsConverter: OptionsConverter<DeepSeekChatOptions> =
    OptionsConverter { options ->
        DeepSeekChatOptions.builder()
            .frequencyPenalty(options.frequencyPenalty)
            .maxTokens(options.maxTokens)
            .presencePenalty(options.presencePenalty)
            .temperature(options.temperature)
            .topP(options.topP)
            .build()

        // logprobs/topLogprobs/responseFormat
    }
