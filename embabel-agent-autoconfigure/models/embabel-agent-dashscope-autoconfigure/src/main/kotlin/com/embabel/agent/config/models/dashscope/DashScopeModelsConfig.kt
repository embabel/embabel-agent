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
package com.embabel.agent.config.models.dashscope

import com.embabel.agent.api.models.DashScopeModels
import com.embabel.agent.config.models.dashscope.DashScopeProperties.Companion.PREFIX
import com.embabel.agent.openai.OpenAiCompatibleModelFactory
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.common.ai.autoconfig.LlmAutoConfigMetadataLoader
import com.embabel.common.ai.autoconfig.ProviderInitialization
import com.embabel.common.ai.autoconfig.RegisteredModel
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.OptionsConverter
import com.embabel.common.ai.model.PerTokenPricingModel
import com.embabel.common.ai.model.PricingModel
import com.embabel.common.util.ExcludeFromJacocoGeneratedReport
import com.embabel.common.util.loggerFor
import io.micrometer.observation.ObservationRegistry
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient

/**
 * Configuration properties for Alibaba Cloud DashScope models.
 * These properties are bound from the Spring configuration with the prefix
 * "embabel.agent.platform.models.dashscope" and control retry behavior
 * when calling DashScope APIs.
 *
 * @since 1.5.0
 */
@ConfigurationProperties(prefix = PREFIX)
class DashScopeProperties : RetryProperties {
    /**
     * Base URL for DashScope API requests. DashScope exposes an OpenAI-compatible
     * chat-completions endpoint, so this is the base URL for the compatible mode;
     * the OpenAI client appends `/chat/completions` itself.
     */
    var baseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1"

    /**
     * API key for authenticating with DashScope services.
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
        const val PREFIX  = "embabel.agent.platform.models.dashscope"
    }
}

/**
 * Configuration class for Alibaba Cloud DashScope Qwen models.
 *
 * DashScope exposes an OpenAI-compatible chat-completions API, so models are served through
 * [OpenAiCompatibleModelFactory] pointed at DashScope's international compatible-mode endpoint.
 * (Spring AI does not provide a dedicated DashScope module.)
 *
 * Model definitions are loaded from `classpath:models/dashscope-models.yml` and each is
 * registered as a singleton [LlmService] bean via [ConfigurableBeanFactory.registerSingleton].
 *
 * To use, set the following environment variables:
 * ```
 * DASHSCOPE_API_KEY=your-api-key
 * ```
 *
 * @see <a href="https://modelstudio.console.alibabacloud.com">DashScope Model Studio</a>
 * @since 1.5.0
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DashScopeProperties::class)
@ExcludeFromJacocoGeneratedReport(reason = "DashScope configuration can't be unit tested")
class DashScopeModelsConfig(
    @param:Value("\${DASHSCOPE_BASE_URL:#{null}}")
    envBaseUrl: String?,
    @param:Value("\${DASHSCOPE_API_KEY:#{null}}")
    envApiKey: String?,
    observationRegistry: ObjectProvider<ObservationRegistry>,
    private val properties: DashScopeProperties,
    private val configurableBeanFactory: ConfigurableBeanFactory,
    @Qualifier("aiModelRestClientBuilder")
    restClientBuilder: ObjectProvider<RestClient.Builder>,
    @Qualifier("aiModelWebClientBuilder")
    webClientBuilder: ObjectProvider<WebClient.Builder>,
    private val modelLoader: LlmAutoConfigMetadataLoader<DashScopeModelDefinitions> = DashScopeModelLoader(),
) : OpenAiCompatibleModelFactory(
    baseUrl = envBaseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: properties.baseUrl,
    apiKey = envApiKey?.trim()?.takeIf { it.isNotEmpty() }
        ?: properties.apiKey?.trim()?.takeIf { it.isNotEmpty() }
        ?: error("DashScope API key required: set DASHSCOPE_API_KEY env var or embabel.agent.platform.models.dashscope.api-key"),
    completionsPath = null,
    embeddingsPath = null,
    observationRegistry = observationRegistry.getIfUnique { ObservationRegistry.NOOP },
    restClientBuilder = restClientBuilder,
    webClientBuilder = webClientBuilder,
) {

    init {
        logger.info("DashScope models are available: {}", properties)
    }

    @Bean
    fun dashScopeModelsInitializer(): ProviderInitialization {
        val registeredLlms = buildList {
            modelLoader.loadAutoConfigMetadata().models.forEach { modelDef ->
                try {
                    val llm = openAiCompatibleLlm(
                        model = modelDef.modelId,
                        provider = DashScopeModels.PROVIDER,
                        knowledgeCutoffDate = modelDef.knowledgeCutoffDate,
                        optionsConverter = DashScopeOptionsConverter,
                        pricingModel = modelDef.pricingModel?.let {
                            PerTokenPricingModel(
                                usdPer1mInputTokens = it.usdPer1mInputTokens,
                                usdPer1mOutputTokens = it.usdPer1mOutputTokens,
                            )
                        } ?: PricingModel.ALL_YOU_CAN_EAT,
                        retryTemplate = properties.retryTemplate("dashscope-${modelDef.modelId}"),
                    )

                    // Register as singleton bean with the configured bean name
                    configurableBeanFactory.registerSingleton(modelDef.name, llm)
                    add(RegisteredModel(beanName = modelDef.name, modelId = modelDef.modelId))

                    logger.info("Registered DashScope model bean: {} -> {}", modelDef.name, modelDef.modelId)
                } catch (e: Exception) {
                    logger.error("Failed to create model: {} ({})", modelDef.name, modelDef.modelId)
                    throw e
                }
            }
        }

        return ProviderInitialization(
            provider = DashScopeModels.PROVIDER,
            registeredLlms = registeredLlms,
        ).also { logger.info(it.summary()) }
    }
}

/**
 * Options converter for Alibaba Cloud DashScope Qwen models.
 * DashScope supports temperature in the range [0.0, 2.0) and top_p in the range (0, 1.0].
 * Values outside these ranges are clamped accordingly:
 * - Temperature: values are clamped to [0.0, 1.99]
 * - Top P: values <= 0 are raised to 0.01, values > 1.0 are lowered to 1.0
 *
 * @since 1.5.0
 */
object DashScopeOptionsConverter : OptionsConverter {

    private const val MIN_TEMPERATURE = 0.0
    private const val MAX_TEMPERATURE = 1.99
    private const val MIN_TOP_P = 0.01
    private const val MAX_TOP_P = 1.0

    override fun convertOptions(options: LlmOptions, model: String): ChatOptions {
        val temperature = options.temperature?.let { temp ->
            temp.coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE).also { clamped ->
                if (clamped != temp) {
                    loggerFor<DashScopeOptionsConverter>().debug(
                        "DashScope temperature clamped from {} to {} (valid range: [{}, {}])",
                        temp, clamped, MIN_TEMPERATURE, MAX_TEMPERATURE
                    )
                }
            }
        }

        val topP = options.topP?.let { p ->
            p.coerceIn(MIN_TOP_P, MAX_TOP_P).also { clamped ->
                if (clamped != p) {
                    loggerFor<DashScopeOptionsConverter>().debug(
                        "DashScope topP clamped from {} to {} (valid range: ({}, {}])",
                        p, clamped, MIN_TOP_P, MAX_TOP_P
                    )
                }
            }
        }

        return OpenAiChatOptions.builder()
            .model(model)
            .temperature(temperature)
            .topP(topP)
            .maxTokens(options.maxTokens)
            .presencePenalty(options.presencePenalty)
            .frequencyPenalty(options.frequencyPenalty)
            .build()
    }
}
