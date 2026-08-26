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
package com.embabel.agent.config.models.deepseek

import com.embabel.agent.api.models.DeepSeekModels
import com.embabel.agent.config.models.deepseek.DeepSeekProperties.Companion.PREFIX
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.OptionsConverter
import com.embabel.common.ai.model.PerTokenPricingModel
import com.embabel.common.util.ExcludeFromJacocoGeneratedReport
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.ai.deepseek.DeepSeekChatModel
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.deepseek.DeepSeekChatOptions
import org.springframework.ai.deepseek.api.DeepSeekApi
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.convert.DurationStyle
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.time.LocalDate

/**
 * Configuration properties for Deepseek models.
 * These properties are bound from the Spring configuration with the prefix
 * "embabel.agent.platform.models.deepseek" and control retry behavior
 * when calling Deepseek APIs.
 */
@ConfigurationProperties(prefix = PREFIX)
class DeepSeekProperties : RetryProperties {
    /**
     * Base URL for DeepSeek API requests.
     */
    var baseUrl: String? = null

    /**
     * API key for authenticating with DeepSeek services.
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

    override val propertyPrefix: String = PREFIX
    companion object {
        const val PREFIX  = "embabel.agent.platform.models.deepseek"
    }
}

/**
 * Configuration class for DeepSeek models.
 * This class provides beans for various DeepSeek models (chat, reasoner)
 * and handles the creation of DeepSeek API clients with proper authentication.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeepSeekProperties::class)
@ExcludeFromJacocoGeneratedReport(reason = "DeepSeek configuration can't be unit tested")
class DeepSeekModelsConfig(
    @param:Value("\${DEEPSEEK_BASE_URL:#{null}}")
    private val envBaseUrl: String?,
    @param:Value("\${DEEPSEEK_API_KEY:#{null}}")
    private val envApiKey: String?,
    private val properties: DeepSeekProperties,
    private val observationRegistry: ObjectProvider<ObservationRegistry>,
    @param:Qualifier("aiModelRestClientBuilder")
    private val restClientBuilderProvider: ObjectProvider<RestClient.Builder>,
    @param:Qualifier("aiModelWebClientBuilder")
    private val webClientBuilderProvider: ObjectProvider<WebClient.Builder>,
    @param:Value("\${embabel.agent.platform.http-client.read-timeout:5m}")
    private val httpReadTimeout: String,
) {
    private val logger = LoggerFactory.getLogger(DeepSeekModelsConfig::class.java)

    private val baseUrl: String? = envBaseUrl ?: properties.baseUrl
    private val apiKey: String = envApiKey ?: properties.apiKey
    ?: error("DeepSeek API key required: set DEEPSEEK_API_KEY env var or embabel.agent.platform.models.deepseek.api-key")

    init {
        logger.info("DeepSeek models are available: {}", properties)
    }

    @Bean
    fun deepSeekChat(): SpringAiLlmService {
        return deepSeekLlmOf(
            DeepSeekModels.DEEPSEEK_CHAT,
            knowledgeCutoffDate = LocalDate.of(2025, 8, 21),
        )
            // https://api-docs.deepseek.com/quick_start/pricing
            // 1M Input tokens Cache hit $0.0028
            // 1M Input tokens Cache miss $0.14
            .copy(
                pricingModel = PerTokenPricingModel(
                    usdPer1mInputTokens = 0.14,
                    usdPer1mOutputTokens = 0.28,
                )
            )
    }

    @Bean
    fun deepSeekReasoner(): SpringAiLlmService = deepSeekLlmOf(
        DeepSeekModels.DEEPSEEK_REASONER,
        knowledgeCutoffDate = LocalDate.of(2025, 5, 28),
    )
        // https://api-docs.deepseek.com/quick_start/pricing
        // 1M Input tokens Cache hit $0.0028
        // 1M Input tokens Cache miss $0.14
        .copy(
            pricingModel = PerTokenPricingModel(
                usdPer1mInputTokens = 0.14,
                usdPer1mOutputTokens = 0.28,
            )
        )

    @Bean
    fun deepSeekV4Flash(): SpringAiLlmService = deepSeekLlmOf(
        DeepSeekModels.DEEPSEEK_V4_FLASH,
        knowledgeCutoffDate = null,
    )
        // https://api-docs.deepseek.com/quick_start/pricing
        // 1M Input tokens Cache hit $0.0028
        // 1M Input tokens Cache miss $0.14
        .copy(
            pricingModel = PerTokenPricingModel(
                usdPer1mInputTokens = 0.14,
                usdPer1mOutputTokens = 0.28,
            )
        )

    @Bean
    fun deepSeekV4Pro(): SpringAiLlmService = deepSeekLlmOf(
        DeepSeekModels.DEEPSEEK_V4_PRO,
        knowledgeCutoffDate = null,
    )
        // https://api-docs.deepseek.com/quick_start/pricing
        // 1M Input tokens Cache hit $0.003625
        // 1M Input tokens Cache miss $0.435
        .copy(
            pricingModel = PerTokenPricingModel(
                usdPer1mInputTokens = 0.435,
                usdPer1mOutputTokens = 0.87,
            )
        )

    private fun deepSeekLlmOf(
        name: String,
        knowledgeCutoffDate: LocalDate?,
    ): SpringAiLlmService {
        val deepSeekChatModel = DeepSeekChatModel
            .builder()
            .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
            .toolCallingManager(
                ToolCallingManager.builder()
                    .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
                    .build()
            )
            .options(
                DeepSeekChatOptions.builder()
                    .model(name)
                    .build()
            )
            .deepSeekApi(createDeepSeekApi())
            .retryTemplate(properties.coreRetryTemplate())
            .build()
        return SpringAiLlmService(
            name = name,
            chatModel = deepSeekChatModel,
            provider = DeepSeekModels.PROVIDER,
            optionsConverter = DeepSeekOptionsConverter,
            knowledgeCutoffDate = knowledgeCutoffDate,
        )
    }

    private fun createDeepSeekApi(): DeepSeekApi {
        val builder = DeepSeekApi.builder().apiKey(apiKey)
        // If baseUrl is blank, use default baseUrl https://api.deepseek.com
        if (!baseUrl.isNullOrBlank()) {
            logger.info("Using custom DeepSeek base URL: {}", baseUrl)
            builder.baseUrl(baseUrl)
        }
        // Shared platform builder, like every other provider. A bare RestClient here would let Spring's
        // classpath detection choose the transport: it lands on Apache HttpClient, which advertises brotli,
        // which DeepSeek honours and this client cannot decode. Clone so adding the observation registry
        // never mutates the shared singleton.
        val sharedRestClientBuilder = restClientBuilderProvider.getIfAvailable(::fallbackRestClientBuilder)
            .clone()
            .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
        val sharedWebClientBuilder = webClientBuilderProvider.getIfAvailable(WebClient::builder)
            .clone()
            .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })

        return builder
            .restClientBuilder(sharedRestClientBuilder)
            .webClientBuilder(sharedWebClientBuilder)
            .build()
    }

    /**
     * Fallback client builder for contexts where the shared [aiModelRestClientBuilder] bean is absent.
     * Names the request factory rather than letting it be detected, and applies the platform read timeout
     * ([httpReadTimeout]) so a slow response is not aborted at the ~10s ReactorClientHttpRequestFactory
     * default.
     */
    private fun fallbackRestClientBuilder(): RestClient.Builder {
        val readTimeout: Duration = DurationStyle.detectAndParse(httpReadTimeout)
        val requestFactory = JdkClientHttpRequestFactory().apply { setReadTimeout(readTimeout) }
        return RestClient.builder().requestFactory(requestFactory)
    }
}

object DeepSeekOptionsConverter : OptionsConverter {
    override fun convertOptions(options: LlmOptions, model: String): ChatOptions =
        DeepSeekChatOptions.builder()
            .model(model)
            .frequencyPenalty(options.frequencyPenalty)
            .maxTokens(options.maxTokens)
            .presencePenalty(options.presencePenalty)
            .temperature(options.temperature)
            .topP(options.topP)
            .build()

    // logprobs/topLogprobs/responseFormat
}
