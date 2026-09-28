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
import io.netty.channel.ChannelOption
import org.slf4j.LoggerFactory
import org.springframework.ai.deepseek.DeepSeekChatModel
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.deepseek.DeepSeekChatOptions
import org.springframework.ai.deepseek.api.DeepSeekApi
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.convert.DurationStyle
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.http.client.ReactorClientHttpRequestFactory
import org.springframework.http.client.reactive.ClientHttpConnector
import org.springframework.http.client.reactive.JdkClientHttpConnector
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.net.http.HttpClient as JdkHttpClient
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

    /**
     * How long to wait to connect to DeepSeek. Unset uses
     * `embabel.agent.platform.http-client.connect-timeout`.
     *
     * Setting this or [readTimeout] gives DeepSeek its own HTTP client, so a proxy, TLS or other
     * transport customisation on the shared `aiModelRestClientBuilder` / `aiModelWebClientBuilder`
     * beans does not apply to it. That client is reactor-netty, like the shared one, unless
     * `embabel.agent.platform.http-client.use-reactor-netty` is false; then it is the JDK client, whose
     * read timeout bounds only the wait for response headers, not a stream that stalls mid-body.
     */
    var connectTimeout: Duration? = null

    /**
     * How long to wait for a response from DeepSeek, and between reads of a streamed one. Unset
     * uses `embabel.agent.platform.http-client.read-timeout`.
     *
     * Setting this or [connectTimeout] gives DeepSeek its own HTTP client, so a proxy, TLS or other
     * transport customisation on the shared `aiModelRestClientBuilder` / `aiModelWebClientBuilder`
     * beans does not apply to it. That client is reactor-netty, like the shared one, unless
     * `embabel.agent.platform.http-client.use-reactor-netty` is false; then it is the JDK client, whose
     * read timeout bounds only the wait for response headers, not a stream that stalls mid-body.
     */
    var readTimeout: Duration? = null

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
class DeepSeekModelsConfig @Autowired constructor(
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
    @param:Value("\${$HTTP_READ_TIMEOUT:5m}")
    private val httpReadTimeout: String,
    @param:Value("\${$HTTP_CONNECT_TIMEOUT:$DEFAULT_CONNECT_TIMEOUT}")
    private val httpConnectTimeout: String,
    @param:Value("\${$HTTP_USE_REACTOR_NETTY:true}")
    private val httpUseReactorNetty: String,
) {

    /**
     * The constructor as it was before [httpConnectTimeout], so Java and compiled Kotlin callers
     * keep working. Uses the http-client connect timeout's default and reactor-netty.
     */
    constructor(
        envBaseUrl: String?,
        envApiKey: String?,
        properties: DeepSeekProperties,
        observationRegistry: ObjectProvider<ObservationRegistry>,
        restClientBuilderProvider: ObjectProvider<RestClient.Builder>,
        webClientBuilderProvider: ObjectProvider<WebClient.Builder>,
        httpReadTimeout: String,
    ) : this(
        envBaseUrl,
        envApiKey,
        properties,
        observationRegistry,
        restClientBuilderProvider,
        webClientBuilderProvider,
        httpReadTimeout,
        DEFAULT_CONNECT_TIMEOUT,
        "true",
    )

    private val logger = LoggerFactory.getLogger(DeepSeekModelsConfig::class.java)

    private val baseUrl: String? = envBaseUrl ?: properties.baseUrl
    private val apiKey: String = envApiKey ?: properties.apiKey
    ?: error("DeepSeek API key required: set DEEPSEEK_API_KEY env var or embabel.agent.platform.models.deepseek.api-key")

    /** Whether this provider sets a timeout of its own rather than using the http-client ones. */
    private val ownTimeouts = properties.connectTimeout != null || properties.readTimeout != null

    /**
     * The timeouts this provider's clients carry. Read the use-reactor-netty opt-out as
     * NettyClientAutoConfiguration's `@ConditionalOnProperty` does: only `true`, in any case, is true.
     */
    internal val timeouts = ProviderClientTimeouts(
        connect = properties.connectTimeout ?: parseTimeout(HTTP_CONNECT_TIMEOUT, httpConnectTimeout),
        read = properties.readTimeout ?: parseTimeout(HTTP_READ_TIMEOUT, httpReadTimeout),
        reactorNetty = httpUseReactorNetty.equals("true", ignoreCase = true),
    )

    init {
        logger.info("DeepSeek models are available: {}", properties)
        if (ownTimeouts) {
            logger.info(
                "DeepSeek uses its own HTTP client (connect timeout {}, read timeout {}) in place of the " +
                    "shared aiModelRestClientBuilder / aiModelWebClientBuilder transport; proxy or TLS " +
                    "settings on those do not apply to it",
                timeouts.connect, timeouts.read,
            )
            if (!timeouts.reactorNetty) {
                logger.warn(
                    "{} uses the JDK HTTP client, as {} is false: its read timeout bounds the wait for " +
                        "response headers only, not a stream that stalls mid-body",
                    "DeepSeek", HTTP_USE_REACTOR_NETTY,
                )
            }
        }
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
            .retryTemplate(properties.coreRetryTemplate(name))
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
        // Timeouts set under this provider's prefix replace the shared client's transport with a
        // reactor-netty one configured like NettyClientAutoConfiguration's, carrying them; the shared
        // builders hold the http-client timeouts and can't be changed.
        val sharedRestClientBuilder = restClientBuilderProvider.getIfAvailable(::fallbackRestClientBuilder)
            .clone()
            .let { if (ownTimeouts) it.requestFactory(timeouts.requestFactory()) else it }
            .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
        val sharedWebClientBuilder = webClientBuilderProvider.getIfAvailable(WebClient::builder)
            .clone()
            .let { if (ownTimeouts) it.clientConnector(timeouts.connector()) else it }
            .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })

        return builder
            .restClientBuilder(sharedRestClientBuilder)
            .webClientBuilder(sharedWebClientBuilder)
            .build()
    }

    /**
     * Fallback client builder for contexts where the shared [aiModelRestClientBuilder] bean is absent.
     * Names the request factory rather than letting it be detected, and applies the timeouts so a slow
     * response is not aborted at the ~10s ReactorClientHttpRequestFactory default.
     */
    private fun fallbackRestClientBuilder(): RestClient.Builder =
        RestClient.builder().requestFactory(timeouts.jdkRequestFactory())
}

private const val HTTP_CONNECT_TIMEOUT = "embabel.agent.platform.http-client.connect-timeout"
private const val HTTP_READ_TIMEOUT = "embabel.agent.platform.http-client.read-timeout"
private const val HTTP_USE_REACTOR_NETTY = "embabel.agent.platform.http-client.use-reactor-netty"

/** The http-client connect timeout's default, as NettyClientAutoConfiguration has it. */
private const val DEFAULT_CONNECT_TIMEOUT = "25s"

/** Parses a duration, naming [property] when [value] is not one. */
private fun parseTimeout(property: String, value: String): Duration =
    try {
        DurationStyle.detectAndParse(value)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("$property must be a duration such as 30s or 5m, but was '$value'", e)
    }

// Twin of ProviderClientTimeouts in embabel-agent-mistral-ai-autoconfigure; change both together.
/**
 * HTTP clients carrying [connect] and [read]: a request factory for blocking calls and a connector
 * for streamed ones.
 *
 * With [reactorNetty] they are configured as NettyClientAutoConfiguration configures the shared
 * client, and [read] is netty's response timeout, which bounds each wait between reads, so a stream
 * that stalls mid-body fails too. Without it they are JDK clients, as the application opted out of
 * reactor-netty, and [read] bounds only the wait for response headers.
 */
internal data class ProviderClientTimeouts(
    val connect: Duration,
    val read: Duration,
    val reactorNetty: Boolean,
) {

    fun requestFactory(): ClientHttpRequestFactory =
        if (reactorNetty) ReactorClientHttpRequestFactory(nettyClient()) else jdkRequestFactory()

    fun connector(): ClientHttpConnector =
        if (reactorNetty) ReactorClientHttpConnector(nettyClient())
        else JdkClientHttpConnector(jdkClient()).apply { setReadTimeout(read) }

    /** A JDK request factory, whatever [reactorNetty] says. */
    fun jdkRequestFactory(): ClientHttpRequestFactory =
        JdkClientHttpRequestFactory(jdkClient()).apply { setReadTimeout(read) }

    private fun jdkClient(): JdkHttpClient = JdkHttpClient.newBuilder().connectTimeout(connect).build()

    private fun nettyClient(): HttpClient =
        HttpClient.create()
            .followRedirect(true)
            .responseTimeout(read)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connect.toMillis().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
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
