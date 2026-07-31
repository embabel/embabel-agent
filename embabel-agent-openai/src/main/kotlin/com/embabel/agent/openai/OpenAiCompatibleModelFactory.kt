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
package com.embabel.agent.openai

import com.embabel.agent.api.models.DeepSeekModels
import com.embabel.agent.api.models.GoogleGenAiModels
import com.embabel.agent.api.models.MistralAiModels
import com.embabel.agent.api.models.OpenAiModels
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.*
import com.embabel.common.byok.ByokFactory
import com.embabel.common.byok.InvalidApiKeyException
import com.embabel.common.util.ObjectProviders
import com.openai.client.OpenAIClient
import com.openai.client.OpenAIClientAsync
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.client.okhttp.OpenAIOkHttpClientAsync
import io.micrometer.observation.ObservationRegistry
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.document.MetadataMode
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.OpenAiEmbeddingModel
import org.springframework.ai.openai.OpenAiEmbeddingOptions
import org.springframework.beans.factory.ObjectProvider
import org.springframework.retry.support.RetryTemplate
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.time.LocalDate

/**
 * Generic support for OpenAI compatible models.
 * Use to register LLM beans.
 *
 * Spring AI 2.0 swapped its hand-rolled `OpenAiApi` for the official openai-java SDK
 * (`OpenAIClient`). The migration removes Spring's `RestClient`/`WebClient` from the
 * HTTP path entirely — the SDK uses OkHttp internally. The [restClientBuilder] and
 * [webClientBuilder] parameters are kept for source compatibility with the previous
 * constructor signature but are no longer wired into HTTP calls. Likewise, retries
 * are now wrapped at the ChatClientLlmOperations layer via spring-retry, so any
 * `retryTemplate` argument here is a no-op.
 *
 * @param baseUrl The base URL of the OpenAI API. Null for OpenAI default.
 * @param apiKey The API key for the OpenAI compatible provider, or null for no authentication.
 * @param completionsPath Custom completions endpoint path (no longer settable on the
 *   openai-java SDK; logged as a warning and ignored — bake the full path into [baseUrl]).
 * @param embeddingsPath Custom embeddings endpoint path (same caveat as [completionsPath]).
 * @param httpHeaders Extra headers sent on every request, applied via OpenAIClient builder.
 * @param observationRegistry Micrometer registry for Spring AI's chat model instrumentation.
 * @param restClientBuilder Unused since Spring AI 2.0; retained for source compatibility.
 * @param webClientBuilder Unused since Spring AI 2.0; retained for source compatibility.
 */
open class OpenAiCompatibleModelFactory(
    val baseUrl: String?,
    private val apiKey: String?,
    private val completionsPath: String? = null,
    private val embeddingsPath: String? = null,
    private val httpHeaders: Map<String, String> = emptyMap(),
    private val observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
    @Suppress("UNUSED_PARAMETER")
    restClientBuilder: ObjectProvider<RestClient.Builder> = ObjectProviders.empty(),
    @Suppress("UNUSED_PARAMETER")
    webClientBuilder: ObjectProvider<WebClient.Builder> = ObjectProviders.empty(),
) {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000L
        private const val READ_TIMEOUT_MS = 600_000L

        /**
         * Returns a [ByokSpec] for OpenAI.
         * Validates against [OpenAiModels.GPT_41_MINI] by default.
         */
        fun openAi(apiKey: String): ByokSpec =
            ByokSpec(null, apiKey, OpenAiModels.GPT_41_MINI, OpenAiModels.PROVIDER)

        /**
         * Returns a [ByokSpec] for DeepSeek (OpenAI-compatible endpoint).
         * Validates against [DeepSeekModels.DEEPSEEK_V4_FLASH] by default.
         *
         * Note: uses the OpenAI wire protocol, not the native Spring AI DeepSeek client.
         */
        fun deepSeek(apiKey: String): ByokSpec =
            ByokSpec("https://api.deepseek.com", apiKey, DeepSeekModels.DEEPSEEK_V4_FLASH, DeepSeekModels.PROVIDER)

        /**
         * Returns a [ByokSpec] for Mistral AI (OpenAI-compatible endpoint).
         * Validates against [MistralAiModels.MINISTRAL_8B] by default.
         *
         * Note: uses the OpenAI wire protocol, not the native Spring AI Mistral client.
         */
        fun mistral(apiKey: String): ByokSpec =
            ByokSpec("https://api.mistral.ai", apiKey, MistralAiModels.MINISTRAL_8B, MistralAiModels.PROVIDER)

        /**
         * Returns a [ByokSpec] for Google Gemini (OpenAI-compatible endpoint).
         * Validates against [GoogleGenAiModels.GEMINI_2_5_FLASH] by default.
         */
        fun gemini(apiKey: String): ByokSpec =
            ByokSpec(
                "https://generativelanguage.googleapis.com/v1beta/openai",
                apiKey,
                GoogleGenAiModels.GEMINI_2_5_FLASH,
                GoogleGenAiModels.PROVIDER,
            )

        /**
         * Returns a [ByokSpec] for a custom OpenAI-compatible provider.
         *
         * Both [validationModel] and [validationProvider] are required — there is no
         * sensible default for an arbitrary endpoint.
         *
         * Use this as the basis for a provider-specific extension function:
         * ```kotlin
         * fun OpenAiCompatibleModelFactory.Companion.myProvider(apiKey: String) =
         *     OpenAiCompatibleModelFactory.byok(
         *         baseUrl = "https://api.myprovider.com",
         *         apiKey = apiKey,
         *         validationModel = "my-model-small",
         *         validationProvider = "MyProvider",
         *     )
         * ```
         */
        fun byok(
            baseUrl: String?,
            apiKey: String,
            validationModel: String,
            validationProvider: String,
        ): ByokSpec = ByokSpec(baseUrl, apiKey, validationModel, validationProvider)
    }

    /**
     * A self-contained BYOK spec for an OpenAI-compatible provider. Implements [ByokFactory]
     * so it can be passed directly to [com.embabel.common.byok.detectProvider].
     *
     * Obtained via the companion factory methods ([openAi], [deepSeek], [mistral], [gemini],
     * or [byok] for custom providers). Use [validating] to override the default validation
     * model and provider — for example if the key only grants access to a specific model tier.
     */
    class ByokSpec internal constructor(
        private val baseUrl: String?,
        private val apiKey: String,
        private val validationModel: String,
        private val validationProvider: String,
        private val observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
    ) : ByokFactory<LlmService<*>> {

        /**
         * Returns a new [ByokSpec] with the given model and provider used for the
         * key-validation probe.
         *
         * ```kotlin
         * OpenAiCompatibleModelFactory.openAi(userKey)
         *     .validating(OpenAiModels.GPT_41_NANO, OpenAiModels.PROVIDER)
         * ```
         */
        fun validating(model: String, provider: String): ByokSpec =
            ByokSpec(baseUrl, apiKey, model, provider, observationRegistry)

        override fun buildValidated(): LlmService<*> =
            OpenAiCompatibleModelFactory(baseUrl, apiKey, null, null, observationRegistry = observationRegistry)
                .buildValidated(
                    model = validationModel,
                    pricingModel = PricingModel.ALL_YOU_CAN_EAT,
                    provider = validationProvider,
                    knowledgeCutoffDate = null,
                )
    }

    protected val logger: Logger = LoggerFactory.getLogger(javaClass)

    // Subclasses should add their own more specific logging
    init {
        logger.info(
            "Open AI compatible models are available at {}. API key is {}",
            baseUrl ?: "default OpenAI location",
            if (apiKey == null) "not set" else "set",
        )
        if (completionsPath != null) {
            logger.warn(
                "completionsPath '{}' is no longer honoured by Spring AI 2.0 (openai-java SDK uses fixed endpoint paths). " +
                        "Bake the path into baseUrl instead.",
                completionsPath,
            )
        }
        if (embeddingsPath != null) {
            logger.warn(
                "embeddingsPath '{}' is no longer honoured by Spring AI 2.0 (openai-java SDK uses fixed endpoint paths).",
                embeddingsPath,
            )
        }
    }

    /**
     * Shared OpenAI clients used by chat + embedding paths.
     *
     * Spring AI 2.0's `OpenAiChatModel` ctor builds an async client lazily via
     * `OpenAiSetup.setupAsyncClient(...)` if `.openAiClientAsync(...)` wasn't supplied —
     * and that fallback reads `OPENAI_API_KEY` from the environment. Tests that pass an
     * explicit sync client but omit the async one work only when `OPENAI_API_KEY` is
     * already set in the shell (which silently fails on CI). To stay platform-independent
     * we build **both** clients from our resolved credentials and wire both into the
     * chat model.
     */
    protected val openAiClient: OpenAIClient = createOpenAiClient()
    protected val openAiClientAsync: OpenAIClientAsync = createOpenAiClientAsync()

    private fun resolvedApiKey(): String =
        // SDK rejects null/blank API keys at build time even for no-auth local servers,
        // so substitute a placeholder when apiKey is null.
        apiKey ?: "no-auth"

    private fun createOpenAiClient(): OpenAIClient {
        val builder = OpenAIOkHttpClient.builder()
            .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
            .apiKey(resolvedApiKey())
        if (baseUrl != null) {
            logger.info("Using custom OpenAI base URL: {}", baseUrl)
            builder.baseUrl(baseUrl)
        }
        httpHeaders.forEach { (name, value) -> builder.putHeader(name, value) }
        return builder.build()
    }

    private fun createOpenAiClientAsync(): OpenAIClientAsync {
        val builder = OpenAIOkHttpClientAsync.builder()
            .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
            .apiKey(resolvedApiKey())
        if (baseUrl != null) {
            builder.baseUrl(baseUrl)
        }
        httpHeaders.forEach { (name, value) -> builder.putHeader(name, value) }
        return builder.build()
    }

    @JvmOverloads
    fun openAiCompatibleLlm(
        model: String,
        pricingModel: PricingModel,
        provider: String,
        knowledgeCutoffDate: LocalDate?,
        optionsConverter: OptionsConverter<*> = OpenAiChatOptionsConverter,
        @Suppress("UNUSED_PARAMETER")
        retryTemplate: RetryTemplate? = null,
    ): LlmService<*> {
        return SpringAiLlmService(
            name = model,
            chatModel = chatModelOf(model),
            provider = provider,
            optionsConverter = optionsConverter,
            pricingModel = pricingModel,
            knowledgeCutoffDate = knowledgeCutoffDate,
        )
    }

    /**
     * Validates the configured API key by making a probe call, then returns a production
     * [LlmService] if successful.
     *
     * Spring AI 2.0 no longer accepts a spring-retry [RetryTemplate] on the model builder,
     * so the probe relies on the openai-java SDK's own no-retry default (any 401 fails fast).
     * On any exception the provider-specific error is translated to [InvalidApiKeyException],
     * keeping Spring AI types out of the caller.
     */
    fun buildValidated(
        model: String,
        pricingModel: PricingModel,
        provider: String,
        knowledgeCutoffDate: LocalDate?,
    ): LlmService<*> {
        val probe = openAiCompatibleLlm(
            model = model,
            pricingModel = pricingModel,
            provider = provider,
            knowledgeCutoffDate = knowledgeCutoffDate,
        )
        try {
            probe.createMessageSender(LlmOptions()).call(listOf(UserMessage("Hi")), emptyList())
        } catch (e: Exception) {
            throw InvalidApiKeyException(e.message ?: "Invalid API key")
        }
        return openAiCompatibleLlm(
            model = model,
            pricingModel = pricingModel,
            provider = provider,
            knowledgeCutoffDate = knowledgeCutoffDate,
        )
    }

    fun openAiCompatibleEmbeddingService(
        model: String,
        provider: String,
        configuredDimensions: Int? = null,
        pricingModel: PricingModel? = null,
    ): EmbeddingService {
        val embeddingModel = OpenAiEmbeddingModel(
            openAiClient,
            MetadataMode.EMBED,
            OpenAiEmbeddingOptions.builder()
                .model(model)
                .build(),
            observationRegistry,
        )
        return SpringAiEmbeddingService(
            name = model,
            model = embeddingModel,
            provider = provider,
            configuredDimensions = configuredDimensions,
            pricingModel = pricingModel,
        )
    }

    /**
     * Build the underlying [ChatModel] for [model].
     *
     * Spring AI 2.0 removed the spring-retry hook on this builder; retries are handled at the
     * ChatClientLlmOperations layer instead. The [retryTemplate] parameter is kept for source
     * compatibility with downstream subclasses (the previous Spring AI 1.x signature) but is
     * ignored.
     */
    @JvmOverloads
    protected fun chatModelOf(
        model: String,
        @Suppress("UNUSED_PARAMETER")
        retryTemplate: RetryTemplate? = null,
    ): ChatModel {
        return OpenAiChatModel.builder()
            .options(
                OpenAiChatOptions.builder()
                    .model(model)
                    .apply { if (httpHeaders.isNotEmpty()) customHeaders(httpHeaders) }
                    .build()
            )
            .toolCallingManager(
                ToolCallingManager.builder()
                    .observationRegistry(observationRegistry)
                    .build()
            )
            .openAiClient(openAiClient)
            // Supply the async client explicitly — otherwise OpenAiChatModel falls back
            // to OpenAiSetup.setupAsyncClient() which reads OPENAI_API_KEY from the env
            // and crashes when that var isn't set (e.g. on CI).
            .openAiClientAsync(openAiClientAsync)
            .observationRegistry(observationRegistry)
            .build()
    }
}

/**
 * Save default. Some models may not support all options.
 */
object OpenAiChatOptionsConverter : OptionsConverter<OpenAiChatOptions> {

    override fun convertOptions(options: LlmOptions): OpenAiChatOptions =
        OpenAiChatOptions.builder()
            .temperature(options.temperature)
            .topP(options.topP)
            .maxTokens(options.maxTokens)
            .presencePenalty(options.presencePenalty)
            .frequencyPenalty(options.frequencyPenalty)
            .topP(options.topP)
            //.streamUsage(true)  additional feature note
            .build()
}
