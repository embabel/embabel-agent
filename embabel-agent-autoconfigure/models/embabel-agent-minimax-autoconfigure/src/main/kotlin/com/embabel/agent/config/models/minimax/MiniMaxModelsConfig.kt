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
package com.embabel.agent.config.models.minimax

import com.embabel.agent.api.models.MiniMaxModels
import com.embabel.agent.openai.OpenAiCompatibleModelFactory
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.OptionsConverter
import com.embabel.common.ai.model.PerTokenPricingModel
import com.embabel.common.util.ExcludeFromJacocoGeneratedReport
import com.embabel.common.util.loggerFor
import io.micrometer.observation.ObservationRegistry
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.ClientHttpRequestFactory
import java.time.LocalDate

/**
 * Configuration properties for MiniMax models.
 * These properties are bound from the Spring configuration with the prefix
 * "embabel.agent.platform.models.minimax" and control retry behavior
 * when calling MiniMax APIs.
 */
@ConfigurationProperties(prefix = "embabel.agent.platform.models.minimax")
class MiniMaxProperties : RetryProperties {
    /**
     * Base URL for MiniMax API requests.
     */
    var baseUrl: String = "https://api.minimax.io/v1"

    /**
     * API key for authenticating with MiniMax services.
     */
    var apiKey: String? = null

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
 * Configuration class for MiniMax models.
 * This class provides beans for MiniMax models (M2.7, M2.7-highspeed)
 * via the OpenAI-compatible API provided by MiniMax.
 *
 * MiniMax models require temperature values in the range (0.0, 1.0].
 * The [MiniMaxOptionsConverter] handles clamping temperature to this range.
 *
 * To use, set the following environment variables:
 * ```
 * MINIMAX_API_KEY=your-api-key
 * ```
 *
 * @see <a href="https://www.minimax.io">MiniMax AI</a>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MiniMaxProperties::class)
@ExcludeFromJacocoGeneratedReport(reason = "MiniMax configuration can't be unit tested")
class MiniMaxModelsConfig(
    @param:Value("\${MINIMAX_BASE_URL:#{null}}")
    private val envBaseUrl: String?,
    @param:Value("\${MINIMAX_API_KEY:#{null}}")
    private val envApiKey: String?,
    observationRegistry: ObjectProvider<ObservationRegistry>,
    private val properties: MiniMaxProperties,
    requestFactory: ObjectProvider<ClientHttpRequestFactory>,
) : OpenAiCompatibleModelFactory(
    baseUrl = envBaseUrl ?: properties.baseUrl,
    apiKey = envApiKey?.trim()?.takeIf { it.isNotEmpty() }
        ?: properties.apiKey?.trim()?.takeIf { it.isNotEmpty() }
        ?: error("MiniMax API key required: set MINIMAX_API_KEY env var or embabel.agent.platform.models.minimax.api-key"),
    completionsPath = null,
    embeddingsPath = null,
    observationRegistry = observationRegistry.getIfUnique { ObservationRegistry.NOOP },
    requestFactory = requestFactory,
) {

    init {
        logger.info("MiniMax models are available: {}", properties)
    }

    @Bean
    fun miniMaxM27(): LlmService<*> {
        return openAiCompatibleLlm(
            model = MiniMaxModels.MINIMAX_M2_7,
            provider = MiniMaxModels.PROVIDER,
            knowledgeCutoffDate = LocalDate.of(2025, 6, 1),
            optionsConverter = MiniMaxOptionsConverter,
            pricingModel = PerTokenPricingModel(
                usdPer1mInputTokens = 1.10,
                usdPer1mOutputTokens = 4.40,
            ),
            retryTemplate = properties.retryTemplate(MiniMaxModels.MINIMAX_M2_7),
        )
    }

    @Bean
    fun miniMaxM27Highspeed(): LlmService<*> {
        return openAiCompatibleLlm(
            model = MiniMaxModels.MINIMAX_M2_7_HIGHSPEED,
            provider = MiniMaxModels.PROVIDER,
            knowledgeCutoffDate = LocalDate.of(2025, 6, 1),
            optionsConverter = MiniMaxOptionsConverter,
            pricingModel = PerTokenPricingModel(
                usdPer1mInputTokens = 0.55,
                usdPer1mOutputTokens = 2.20,
            ),
            retryTemplate = properties.retryTemplate(MiniMaxModels.MINIMAX_M2_7_HIGHSPEED),
        )
    }
}

/**
 * Options converter for MiniMax models.
 * MiniMax requires temperature to be in the range (0.0, 1.0].
 * Values outside this range are clamped accordingly.
 */
object MiniMaxOptionsConverter : OptionsConverter<OpenAiChatOptions> {

    private const val MIN_TEMPERATURE = 0.01
    private const val MAX_TEMPERATURE = 1.0

    override fun convertOptions(options: LlmOptions): OpenAiChatOptions {
        val temperature = options.temperature?.let { temp ->
            temp.coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE).also { clamped ->
                if (clamped != temp) {
                    loggerFor<MiniMaxOptionsConverter>().debug(
                        "MiniMax temperature clamped from {} to {} (valid range: ({}, {}])",
                        temp, clamped, 0.0, MAX_TEMPERATURE
                    )
                }
            }
        }
        return OpenAiChatOptions.builder()
            .temperature(temperature)
            .topP(options.topP)
            .maxTokens(options.maxTokens)
            .presencePenalty(options.presencePenalty)
            .frequencyPenalty(options.frequencyPenalty)
            .build()
    }
}
