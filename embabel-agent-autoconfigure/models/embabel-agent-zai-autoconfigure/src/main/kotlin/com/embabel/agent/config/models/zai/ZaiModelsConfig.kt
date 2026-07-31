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
package com.embabel.agent.config.models.zai

import com.embabel.agent.api.models.ZaiModels
import com.embabel.agent.config.models.zai.ZaiProperties.Companion.PREFIX
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
 * Configuration properties for Z.ai (Zhipu AI) GLM models.
 * These properties are bound from the Spring configuration with the prefix
 * "embabel.agent.platform.models.zai" and control retry behavior
 * when calling Z.ai APIs.
 */
@ConfigurationProperties(prefix = PREFIX)
class ZaiProperties : RetryProperties {
    /**
     * Base URL for Z.ai API requests. Z.ai/GLM exposes an OpenAI-compatible chat-completions
     * endpoint under the "PaaS v4" path, so this is the host + `/api/paas/v4` prefix; the
     * OpenAI client appends `/chat/completions` itself.
     */
    var baseUrl: String = "https://api.z.ai/api/paas/v4"

    /**
     * API key for authenticating with Z.ai services.
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
        const val PREFIX  = "embabel.agent.platform.models.zai"
    }
}

/**
 * Configuration class for Z.ai (Zhipu AI) GLM models.
 *
 * Z.ai/GLM exposes an OpenAI-compatible chat-completions API, so models are served through
 * [OpenAiCompatibleModelFactory] pointed at Z.ai's international "PaaS v4" endpoint — the same
 * approach used by the MiniMax provider. (Spring AI 2.0 dropped the dedicated `spring-ai-zhipuai`
 * module the previous native implementation relied on.)
 *
 * Model definitions are loaded from `classpath:models/zai-models.yml` and each is
 * registered as a singleton [LlmService] bean via [ConfigurableBeanFactory.registerSingleton].
 *
 * GLM models require temperature values in the range (0.0, 1.0]. The [ZaiOptionsConverter]
 * clamps temperature into this range.
 *
 * To use, set the following environment variables:
 * ```
 * ZAI_API_KEY=your-api-key
 * ```
 *
 * @see <a href="https://z.ai">Z.ai</a>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ZaiProperties::class)
@ExcludeFromJacocoGeneratedReport(reason = "Z.ai configuration can't be unit tested")
class ZaiModelsConfig(
    @param:Value("\${ZAI_BASE_URL:#{null}}")
    envBaseUrl: String?,
    @param:Value("\${ZAI_API_KEY:#{null}}")
    envApiKey: String?,
    observationRegistry: ObjectProvider<ObservationRegistry>,
    private val properties: ZaiProperties,
    private val configurableBeanFactory: ConfigurableBeanFactory,
    @Qualifier("aiModelRestClientBuilder")
    restClientBuilder: ObjectProvider<RestClient.Builder>,
    @Qualifier("aiModelWebClientBuilder")
    webClientBuilder: ObjectProvider<WebClient.Builder>,
    private val modelLoader: LlmAutoConfigMetadataLoader<ZaiModelDefinitions> = ZaiModelLoader(),
) : OpenAiCompatibleModelFactory(
    baseUrl = envBaseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: properties.baseUrl,
    apiKey = envApiKey?.trim()?.takeIf { it.isNotEmpty() }
        ?: properties.apiKey?.trim()?.takeIf { it.isNotEmpty() }
        ?: error("Z.ai API key required: set ZAI_API_KEY env var or embabel.agent.platform.models.zai.api-key"),
    completionsPath = null,
    embeddingsPath = null,
    observationRegistry = observationRegistry.getIfUnique { ObservationRegistry.NOOP },
    restClientBuilder = restClientBuilder,
    webClientBuilder = webClientBuilder,
) {

    init {
        logger.info("Z.ai models are available: {}", properties)
    }

    @Bean
    fun zaiModelsInitializer(): ProviderInitialization {
        val registeredLlms = buildList {
            modelLoader.loadAutoConfigMetadata().models.forEach { modelDef ->
                try {
                    val llm = openAiCompatibleLlm(
                        model = modelDef.modelId,
                        provider = ZaiModels.PROVIDER,
                        knowledgeCutoffDate = modelDef.knowledgeCutoffDate,
                        optionsConverter = ZaiOptionsConverter,
                        pricingModel = modelDef.pricingModel?.let {
                            PerTokenPricingModel(
                                usdPer1mInputTokens = it.usdPer1mInputTokens,
                                usdPer1mOutputTokens = it.usdPer1mOutputTokens,
                            )
                        } ?: PricingModel.ALL_YOU_CAN_EAT,
                        retryTemplate = properties.retryTemplate("zai-${modelDef.modelId}"),
                    )

                    // Register as singleton bean with the configured bean name
                    configurableBeanFactory.registerSingleton(modelDef.name, llm)
                    add(RegisteredModel(beanName = modelDef.name, modelId = modelDef.modelId))

                    logger.info("Registered Z.ai model bean: {} -> {}", modelDef.name, modelDef.modelId)
                } catch (e: Exception) {
                    logger.error("Failed to create model: {} ({})", modelDef.name, modelDef.modelId)
                    throw e
                }
            }
        }

        return ProviderInitialization(
            provider = ZaiModels.PROVIDER,
            registeredLlms = registeredLlms,
        ).also { logger.info(it.summary()) }
    }
}

/**
 * Options converter for Z.ai (Zhipu AI) GLM models.
 * GLM models require temperature to be in the range (0.0, 1.0].
 * Values outside this range are clamped accordingly.
 */
object ZaiOptionsConverter : OptionsConverter<OpenAiChatOptions> {

    private const val MIN_TEMPERATURE = 0.01
    private const val MAX_TEMPERATURE = 1.0

    override fun convertOptions(options: LlmOptions): OpenAiChatOptions {
        val temperature = options.temperature?.let { temp ->
            temp.coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE).also { clamped ->
                if (clamped != temp) {
                    loggerFor<ZaiOptionsConverter>().debug(
                        "Z.ai temperature clamped from {} to {} (valid range: ({}, {}])",
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
