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
package com.embabel.agent.config.models.orcarouter

import com.embabel.agent.api.models.OrcaRouterModels
import com.embabel.agent.config.models.orcarouter.OrcaRouterProperties.Companion.PREFIX
import com.embabel.agent.openai.OpenAiCompatibleModelFactory
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.OptionsConverter
import com.embabel.common.ai.model.PricingModel
import com.embabel.common.util.ExcludeFromJacocoGeneratedReport
import com.embabel.common.util.loggerFor
import io.micrometer.observation.ObservationRegistry
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient
import java.time.LocalDate

/**
 * Configuration properties for OrcaRouter models.
 * These properties are bound from the Spring configuration with the prefix
 * "embabel.agent.platform.models.orcarouter" and control retry behavior
 * when calling OrcaRouter APIs.
 */
@ConfigurationProperties(prefix = PREFIX)
class OrcaRouterProperties : RetryProperties {
    /**
     * Base URL for OrcaRouter API requests.
     */
    var baseUrl: String = "https://api.orcarouter.ai/v1"

    /**
     * API key for authenticating with OrcaRouter services.
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
        const val PREFIX  = "embabel.agent.platform.models.orcarouter"
    }
}

/**
 * Configuration class for OrcaRouter models.
 * This class provides beans for OrcaRouter models (auto, fusion, fusion-flash, fusion-mini)
 * via the OpenAI-compatible API provided by OrcaRouter.
 *
 * To use, set the following environment variables:
 * ```
 * ORCAROUTER_API_KEY=your-api-key
 * ```
 *
 * @see <a href="https://www.orcarouter.ai">OrcaRouter</a>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrcaRouterProperties::class)
@ExcludeFromJacocoGeneratedReport(reason = "OrcaRouter configuration can't be unit tested")
class OrcaRouterModelsConfig(
    @param:Value("\${ORCAROUTER_BASE_URL:#{null}}")
    private val envBaseUrl: String?,
    @param:Value("\${ORCAROUTER_API_KEY:#{null}}")
    private val envApiKey: String?,
    observationRegistry: ObjectProvider<ObservationRegistry>,
    private val properties: OrcaRouterProperties,
    @Qualifier("aiModelRestClientBuilder")
    restClientBuilder: ObjectProvider<RestClient.Builder>,
    @Qualifier("aiModelWebClientBuilder")
    webClientBuilder: ObjectProvider<WebClient.Builder>,
) : OpenAiCompatibleModelFactory(
    baseUrl = envBaseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: properties.baseUrl,
    apiKey = envApiKey?.trim()?.takeIf { it.isNotEmpty() }
        ?: properties.apiKey?.trim()?.takeIf { it.isNotEmpty() }
        ?: error("OrcaRouter API key required: set ORCAROUTER_API_KEY env var or embabel.agent.platform.models.orcarouter.api-key"),
    completionsPath = null,
    embeddingsPath = null,
    observationRegistry = observationRegistry.getIfUnique { ObservationRegistry.NOOP },
    restClientBuilder = restClientBuilder,
    webClientBuilder = webClientBuilder,
) {

    init {
        logger.info("OrcaRouter models are available: {}", properties)
    }

    @Bean
    fun orcaRouterAuto(): LlmService<*> {
        return openAiCompatibleLlm(
            model = OrcaRouterModels.AUTO,
            provider = OrcaRouterModels.PROVIDER,
            knowledgeCutoffDate = null,
            optionsConverter = OrcaRouterOptionsConverter,
            pricingModel = PricingModel.ALL_YOU_CAN_EAT,
            retryTemplate = properties.retryTemplate(OrcaRouterModels.AUTO),
        )
    }

    @Bean
    fun orcaRouterFusion(): LlmService<*> {
        return openAiCompatibleLlm(
            model = OrcaRouterModels.FUSION,
            provider = OrcaRouterModels.PROVIDER,
            knowledgeCutoffDate = null,
            optionsConverter = OrcaRouterOptionsConverter,
            pricingModel = PricingModel.ALL_YOU_CAN_EAT,
            retryTemplate = properties.retryTemplate(OrcaRouterModels.FUSION),
        )
    }

    @Bean
    fun orcaRouterFusionFlash(): LlmService<*> {
        return openAiCompatibleLlm(
            model = OrcaRouterModels.FUSION_FLASH,
            provider = OrcaRouterModels.PROVIDER,
            knowledgeCutoffDate = null,
            optionsConverter = OrcaRouterOptionsConverter,
            pricingModel = PricingModel.ALL_YOU_CAN_EAT,
            retryTemplate = properties.retryTemplate(OrcaRouterModels.FUSION_FLASH),
        )
    }

    @Bean
    fun orcaRouterFusionMini(): LlmService<*> {
        return openAiCompatibleLlm(
            model = OrcaRouterModels.FUSION_MINI,
            provider = OrcaRouterModels.PROVIDER,
            knowledgeCutoffDate = null,
            optionsConverter = OrcaRouterOptionsConverter,
            pricingModel = PricingModel.ALL_YOU_CAN_EAT,
            retryTemplate = properties.retryTemplate(OrcaRouterModels.FUSION_MINI),
        )
    }
}

/**
 * Options converter for OrcaRouter models.
 * OrcaRouter accepts OpenAI-compatible chat options unchanged.
 */
object OrcaRouterOptionsConverter : OptionsConverter {
    override fun convertOptions(options: LlmOptions, model: String): ChatOptions =
        OpenAiChatOptions.builder()
            .model(model)
            .temperature(options.temperature)
            .topP(options.topP)
            .maxTokens(options.maxTokens)
            .presencePenalty(options.presencePenalty)
            .frequencyPenalty(options.frequencyPenalty)
            .build()
}
