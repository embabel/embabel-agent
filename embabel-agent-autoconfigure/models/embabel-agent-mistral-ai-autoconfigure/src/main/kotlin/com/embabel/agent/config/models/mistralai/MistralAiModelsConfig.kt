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
package com.embabel.agent.config.models.mistralai

import com.embabel.agent.api.models.MistralAiModels
import com.embabel.agent.config.models.mistralai.MistralAiProperties.Companion.PREFIX
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.autoconfig.LlmAutoConfigMetadataLoader
import com.embabel.common.ai.autoconfig.ProviderInitialization
import com.embabel.common.ai.autoconfig.RegisteredModel
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.OptionsConverter
import com.embabel.common.ai.model.PerTokenPricingModel
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.ai.mistralai.MistralAiChatModel
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.mistralai.MistralAiChatOptions
import org.springframework.ai.mistralai.api.MistralAiApi
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.convert.DurationStyle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.http.client.reactive.ClientHttpConnector
import org.springframework.http.client.reactive.JdkClientHttpConnector
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient
import java.net.http.HttpClient
import java.time.Duration

/**
 * Configuration properties for Mistral AI models.
 * These properties control retry behavior when calling Mistral AI APIs.
 */
@ConfigurationProperties(prefix = PREFIX)
class MistralAiProperties : RetryProperties {
    /**
     * Base URL for Mistral AI API requests.
     */
    var baseUrl: String? = null

    /**
     * API key for authenticating with Mistral AI services.
     */
    var apiKey: String? = null

    /**
     * Maximum number of attempts.
     */
    override var maxAttempts: Int = 10

    /**
     * Initial backoff interval (in milliseconds).
     */
    override var backoffMillis: Long = 5_000L

    /**
     * Backoff interval multiplier.
     */
    override var backoffMultiplier: Double = 5.0

    /**
     * Maximum backoff interval (in milliseconds).
     */
    override var backoffMaxInterval: Long = 180_000L

    /**
     * How long to wait to connect to Mistral AI. Unset uses
     * `embabel.agent.platform.http-client.connect-timeout`.
     */
    var connectTimeout: Duration? = null

    /**
     * How long to wait for a response from Mistral AI. Unset uses
     * `embabel.agent.platform.http-client.read-timeout`.
     */
    var readTimeout: Duration? = null

    override val propertyPrefix: String = PREFIX
    companion object {
        const val PREFIX  = "embabel.agent.platform.models.mistralai"
    }
}

/**
 * Configuration for well-known MistralAI language and embedding models.
 * Provides bean definitions for various models with their corresponding
 * capabilities, knowledge cutoff dates, and pricing models.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MistralAiProperties::class)
class MistralAiModelsConfig @Autowired constructor(
    @param:Value("\${MISTRAL_BASE_URL:#{null}}")
    private val envBaseUrl: String?,
    @param:Value("\${MISTRAL_API_KEY:#{null}}")
    private val envApiKey: String?,
    private val properties: MistralAiProperties,
    private val observationRegistry: ObjectProvider<ObservationRegistry>,
    private val configurableBeanFactory: ConfigurableBeanFactory,
    @param:Qualifier("aiModelRestClientBuilder")
    private val restClientBuilderProvider: ObjectProvider<RestClient.Builder>,
    @param:Qualifier("aiModelWebClientBuilder")
    private val webClientBuilderProvider: ObjectProvider<WebClient.Builder>,
    @param:Value("\${embabel.agent.platform.http-client.read-timeout:5m}")
    private val httpReadTimeout: String,
    private val modelLoader: LlmAutoConfigMetadataLoader<MistralAiModelDefinitions> = MistralAiModelLoader(),
    @param:Value("\${embabel.agent.platform.http-client.connect-timeout:25s}")
    private val httpConnectTimeout: String = DEFAULT_CONNECT_TIMEOUT,
) {

    /** The constructor as it was before [httpConnectTimeout], kept for Java callers. */
    constructor(
        envBaseUrl: String?,
        envApiKey: String?,
        properties: MistralAiProperties,
        observationRegistry: ObjectProvider<ObservationRegistry>,
        configurableBeanFactory: ConfigurableBeanFactory,
        restClientBuilderProvider: ObjectProvider<RestClient.Builder>,
        webClientBuilderProvider: ObjectProvider<WebClient.Builder>,
        httpReadTimeout: String,
        modelLoader: LlmAutoConfigMetadataLoader<MistralAiModelDefinitions>,
    ) : this(
        envBaseUrl,
        envApiKey,
        properties,
        observationRegistry,
        configurableBeanFactory,
        restClientBuilderProvider,
        webClientBuilderProvider,
        httpReadTimeout,
        modelLoader,
        DEFAULT_CONNECT_TIMEOUT,
    )

    private val logger = LoggerFactory.getLogger(MistralAiModelsConfig::class.java)

    private val baseUrl: String? = envBaseUrl ?: properties.baseUrl
    private val apiKey: String = envApiKey ?: properties.apiKey
    ?: error("Mistral AI API key required: set MISTRAL_API_KEY env var or embabel.agent.platform.models.mistralai.api-key")

    /** Whether this provider sets a timeout of its own rather than using the http-client ones. */
    private val ownTimeouts = properties.connectTimeout != null || properties.readTimeout != null

    private val timeouts = JdkClientTimeouts(
        connect = properties.connectTimeout ?: DurationStyle.detectAndParse(httpConnectTimeout),
        read = properties.readTimeout ?: DurationStyle.detectAndParse(httpReadTimeout),
    )

    init {
        logger.info("Mistral AI models are available: {}", properties)
    }

    @Bean
    fun mistralAiModelsInitializer(): ProviderInitialization {
        val registeredLlms = buildList {
            modelLoader
                .loadAutoConfigMetadata().models.forEach { modelDef ->
                    try {
                        val llm = createMistralAiLlm(modelDef)

                        // Register as singleton bean with the configured bean name
                        configurableBeanFactory.registerSingleton(modelDef.name, llm)
                        add(RegisteredModel(beanName = modelDef.name, modelId = modelDef.modelId))

                        logger.info(
                            "Registered Mistral AI model bean: {} -> {}",
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
        }

        return ProviderInitialization(
            provider = MistralAiModels.PROVIDER,
            registeredLlms = registeredLlms,
        ).also { logger.info(it.summary()) }
    }

    /**
     * Creates an individual Mistral AI model from configuration.
     */
    private fun createMistralAiLlm(modelDef: MistralAiModelDefinition): LlmService<*> {
        val mistralChatModel = MistralAiChatModel
            .builder()
            .options(createDefaultOptions(modelDef))
            .mistralAiApi(createMistralAiApi())
            .toolCallingManager(
                ToolCallingManager.builder()
                    .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
                    .build()
            )
            .retryTemplate(properties.coreRetryTemplate(modelDef.modelId))
            .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
            .build()

        return SpringAiLlmService(
            name = modelDef.modelId,
            chatModel = mistralChatModel,
            provider = MistralAiModels.PROVIDER,
            optionsConverter = MistralAiOptionsConverter,
            knowledgeCutoffDate = modelDef.knowledgeCutoffDate,
            pricingModel = modelDef.pricingModel?.let {
                PerTokenPricingModel(
                    usdPer1mInputTokens = it.usdPer1mInputTokens,
                    usdPer1mOutputTokens = it.usdPer1mOutputTokens,
                )
            }
        )
    }

    /**
     * Creates default options for a model based on YAML configuration.
     */
    private fun createDefaultOptions(modelDef: MistralAiModelDefinition): MistralAiChatOptions {
        return MistralAiChatOptions.builder()
            .model(modelDef.modelId)
            .maxTokens(modelDef.maxTokens)
            .temperature(modelDef.temperature)
            .apply {
                modelDef.topP?.let { topP(it) }
            }
            .build()
    }

    /**
     * A [MistralAiApi] is stateless and model-agnostic — the model id and options live on
     * [MistralAiChatOptions], not on the API — so all models share one instance, exactly as
     * [org.springframework.ai.openai.api.OpenAiApi] is shared across providers built on
     * OpenAiCompatibleModelFactory. Since the Spring AI 2.0.0 upgrade reasoning content ("magistral"
     * models) is supported natively, so a single plain API serves every model.
     */
    private fun createMistralAiApi(): MistralAiApi {
        if (!baseUrl.isNullOrBlank()) {
            logger.info("Using custom Mistral AI base URL: {}", baseUrl)
        }

        // Build the HTTP client from the shared platform builder (aiModelRestClientBuilder /
        // aiModelWebClientBuilder), like every other model provider, so the platform read/connect timeouts
        // and the use-reactor-netty opt-out live in one place (NettyClientAutoConfiguration). Clone so adding
        // the observation registry never mutates the shared singleton. When that bean is absent (e.g. an app
        // without the netty client autoconfigure), fall back to a builder that still honours the platform read
        // timeout rather than the ~10s ReactorClientHttpRequestFactory default, which otherwise aborts slow
        // generations (e.g. reasoning models) with a ReadTimeoutException.
        // Timeouts set under this provider's prefix replace the shared client's transport with one
        // that carries them; the shared builders hold the http-client timeouts and can't be changed.
        val restClientBuilder = restClientBuilderProvider.getIfAvailable(::fallbackRestClientBuilder)
            .clone()
            .let { if (ownTimeouts) it.requestFactory(timeouts.requestFactory()) else it }
            .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
        val webClientBuilder = webClientBuilderProvider.getIfAvailable(WebClient::builder)
            .clone()
            .let { if (ownTimeouts) it.clientConnector(timeouts.connector()) else it }
            .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })

        val builder = MistralAiApi.builder()
            .apiKey(apiKey)
            .restClientBuilder(restClientBuilder)
            .webClientBuilder(webClientBuilder)
        if (!baseUrl.isNullOrBlank()) {
            builder.baseUrl(baseUrl)
        }
        return builder.build()
    }

    /**
     * Fallback client builder for contexts where the shared [aiModelRestClientBuilder] bean is absent.
     * Applies the timeouts so a slow response is not aborted at the ~10s ReactorClientHttpRequestFactory
     * default.
     */
    private fun fallbackRestClientBuilder(): RestClient.Builder =
        RestClient.builder().requestFactory(timeouts.requestFactory())
}

/** The http-client connect timeout's default, for a caller that does not pass one. */
private const val DEFAULT_CONNECT_TIMEOUT = "25s"

/**
 * JDK HTTP clients carrying [connect] and [read]: a request factory for blocking calls and a
 * connector for streamed ones.
 */
internal data class JdkClientTimeouts(
    val connect: Duration,
    val read: Duration,
) {

    fun requestFactory(): ClientHttpRequestFactory =
        JdkClientHttpRequestFactory(httpClient()).apply { setReadTimeout(read) }

    fun connector(): ClientHttpConnector =
        JdkClientHttpConnector(httpClient()).apply { setReadTimeout(read) }

    private fun httpClient(): HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(connect)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
}

object MistralAiOptionsConverter : OptionsConverter {

    override fun convertOptions(options: LlmOptions, model: String): ChatOptions =
        MistralAiChatOptions.builder()
            .model(model)
            .temperature(options.temperature)
            .topP(options.topP)
            .maxTokens(options.maxTokens)
            .build()
}
