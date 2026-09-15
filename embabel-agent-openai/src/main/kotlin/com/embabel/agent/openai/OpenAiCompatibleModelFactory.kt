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

import com.embabel.agent.api.models.AtlasCloudModels
import com.embabel.agent.api.models.DeepSeekModels
import com.embabel.agent.api.models.GoogleGenAiModels
import com.embabel.agent.api.models.MistralAiModels
import com.embabel.agent.api.models.OpenAiModels
import com.embabel.agent.api.models.OrcaRouterModels
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.*
import com.embabel.common.byok.ByokFactory
import com.embabel.common.byok.InvalidApiKeyException
import com.embabel.common.byok.requireUsableApiKey
import com.embabel.common.byok.validatedEmbeddingService
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
import org.springframework.ai.chat.prompt.ChatOptions
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

        private val OPEN_AI = ProviderEndpoint(OpenAiModels.PROVIDER, null)
        private val DEEP_SEEK = ProviderEndpoint(DeepSeekModels.PROVIDER, "https://api.deepseek.com")
        private val MISTRAL = ProviderEndpoint(MistralAiModels.PROVIDER, "https://api.mistral.ai/v1")
        private val GEMINI = ProviderEndpoint(
            GoogleGenAiModels.PROVIDER,
            "https://generativelanguage.googleapis.com/v1beta/openai",
        )
        private val ATLAS_CLOUD = ProviderEndpoint(AtlasCloudModels.PROVIDER, "https://api.atlascloud.ai/v1")
        private val ORCA_ROUTER = ProviderEndpoint(OrcaRouterModels.PROVIDER, "https://api.orcarouter.ai/v1")

        private val ENDPOINTS_BY_PROVIDER: Map<String, ProviderEndpoint> =
            listOf(OPEN_AI, DEEP_SEEK, MISTRAL, GEMINI, ATLAS_CLOUD, ORCA_ROUTER)
                .associateBy { it.provider.lowercase() }

        /**
         * The endpoint for [provider], or null if this module does not speak to it.
         *
         * Lets a caller holding only a provider name - a user's stored credential, say - build a
         * service for any provider this module supports, without repeating the base URLs. Every
         * such copy is a place to mistype a URL or fall behind one that changes.
         *
         * Null means "not one of ours", which is distinct from [ProviderEndpoint.baseUrl] being
         * null: OpenAI itself is supported and takes the SDK default.
         */
        @JvmStatic
        fun endpointFor(provider: String): ProviderEndpoint? =
            ENDPOINTS_BY_PROVIDER[provider.trim().lowercase()]

        /**
         * Returns a [ByokSpec] for OpenAI.
         * Validates against [OpenAiModels.GPT_41_MINI] by default.
         */
        fun openAi(apiKey: String): ByokSpec =
            ByokSpec(OPEN_AI.baseUrl, apiKey, OpenAiModels.GPT_41_MINI, OPEN_AI.provider)

        /**
         * Returns a [ByokSpec] for DeepSeek (OpenAI-compatible endpoint).
         * Validates against [DeepSeekModels.DEEPSEEK_V4_FLASH] by default.
         *
         * Note: uses the OpenAI wire protocol, not the native Spring AI DeepSeek client.
         */
        fun deepSeek(apiKey: String): ByokSpec =
            ByokSpec(DEEP_SEEK.baseUrl, apiKey, DeepSeekModels.DEEPSEEK_V4_FLASH, DEEP_SEEK.provider)

        /**
         * Returns a [ByokSpec] for Mistral AI (OpenAI-compatible endpoint).
         * Validates against [MistralAiModels.MINISTRAL_8B] by default.
         *
         * Note: uses the OpenAI wire protocol, not the native Spring AI Mistral client.
         */
        fun mistral(apiKey: String): ByokSpec =
            ByokSpec(MISTRAL.baseUrl, apiKey, MistralAiModels.MINISTRAL_8B, MISTRAL.provider)

        /**
         * Returns a [ByokSpec] for Google Gemini (OpenAI-compatible endpoint).
         * Validates against [GoogleGenAiModels.GEMINI_2_5_FLASH] by default.
         */
        fun gemini(apiKey: String): ByokSpec =
            ByokSpec(GEMINI.baseUrl, apiKey, GoogleGenAiModels.GEMINI_2_5_FLASH, GEMINI.provider)

        /**
         * Returns a [ByokSpec] for Atlas Cloud (OpenAI-compatible endpoint).
         * Validates against [AtlasCloudModels.QWEN3_5_FLASH] by default.
         */
        fun atlasCloud(apiKey: String): ByokSpec =
            ByokSpec(ATLAS_CLOUD.baseUrl, apiKey, AtlasCloudModels.QWEN3_5_FLASH, ATLAS_CLOUD.provider)

        /**
         * Returns a [ByokSpec] for OrcaRouter (OpenAI-compatible gateway endpoint).
         * Validates against [OrcaRouterModels.AUTO] by default.
         */
        fun orcaRouter(apiKey: String): ByokSpec =
            ByokSpec(ORCA_ROUTER.baseUrl, apiKey, OrcaRouterModels.AUTO, ORCA_ROUTER.provider)

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

        /**
         * Returns a [ByokEmbeddingSpec] for OpenAI.
         *
         * [model] is required — see [ByokEmbeddingSpec] for why an embedding model is never
         * defaulted or detected.
         */
        fun openAiEmbedding(
            apiKey: String,
            model: String,
            pricingModel: PricingModel? = null,
        ): ByokEmbeddingSpec =
            ByokEmbeddingSpec(null, apiKey, model, OpenAiModels.PROVIDER, pricingModel)

        /**
         * Returns a [ByokEmbeddingSpec] for a custom OpenAI-compatible provider.
         *
         * ```kotlin
         * OpenAiCompatibleModelFactory.byokEmbedding(
         *     baseUrl = "https://api.myprovider.com",
         *     apiKey = apiKey,
         *     model = "my-embed-small",
         *     provider = "MyProvider",
         * )
         * ```
         */
        fun byokEmbedding(
            baseUrl: String?,
            apiKey: String,
            model: String,
            provider: String,
            pricingModel: PricingModel? = null,
        ): ByokEmbeddingSpec =
            ByokEmbeddingSpec(baseUrl, apiKey, model, provider, pricingModel)
    }

    /**
     * Where an OpenAI-compatible provider lives, under the name this framework knows it by.
     *
     * [provider] is the canonical constant - `OpenAiModels.PROVIDER` and friends - rather than
     * whatever spelling a caller looked it up with, so a service built from a stored credential
     * reports the same provider as one built from configuration.
     *
     * A null [baseUrl] means the SDK default, which is OpenAI's own endpoint.
     */
    data class ProviderEndpoint(
        val provider: String,
        val baseUrl: String?,
    )

    /**
     * A self-contained BYOK spec for an OpenAI-compatible provider. Implements [ByokFactory]
     * so it can be passed directly to [com.embabel.common.byok.detectProvider].
     *
     * Obtained via the companion factory methods ([openAi], [deepSeek], [mistral], [gemini],
     * [atlasCloud], or [byok] for custom providers). Use [validating] to override the default
     * validation model and provider — for example if the key only grants access to a specific
     * model tier.
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

    /**
     * A self-contained BYOK spec that validates an API key and returns a ready
     * [EmbeddingService], so a key supplied *after* startup can activate an embedding model
     * without a restart.
     *
     * Obtained via [openAiEmbedding] or [byokEmbedding].
     *
     * Deliberately unlike [ByokSpec] in two ways, because an embedding model is not the same
     * kind of thing as an LLM:
     *
     * 1. **No provider detection.** An LLM is stateless per call, so racing candidate factories
     *    and taking whichever accepts the key is a fine answer. An embedding model is a schema
     *    commitment: the vector index is built at a fixed dimension and every stored vector must
     *    share that model and dimension. Which provider answers first must not decide the index
     *    dimension, so this type is not intended for
     *    [com.embabel.common.byok.detectProvider] and the model is always explicit.
     * 2. **Installation or world scope, not per-user.** Per-user LLM keys are coherent; per-user
     *    *embedding* keys are not, because vectors from different models and dimensions would
     *    land in one shared index. The dimension is a property of the store, not of the user.
     *    Do not inherit a per-user credential story here.
     *
     * The returned service reports the dimension actually observed during validation, so a
     * caller writing into an existing index can compare it against that index's width before
     * storing anything. That check belongs to whatever owns the index, not here.
     *
     * Changing the embedding model of an existing corpus still requires re-embedding it. This
     * type only builds and validates a service from a runtime-supplied key.
     */
    class ByokEmbeddingSpec internal constructor(
        private val baseUrl: String?,
        private val apiKey: String,
        private val model: String,
        private val provider: String,
        private val pricingModel: PricingModel? = null,
        private val observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
    ) : ByokFactory<EmbeddingService> {

        override fun buildValidated(): EmbeddingService =
            OpenAiCompatibleModelFactory(baseUrl, apiKey, null, null, observationRegistry = observationRegistry)
                .buildValidatedEmbeddingService(
                    model = model,
                    provider = provider,
                    pricingModel = pricingModel,
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
        optionsConverter: OptionsConverter = OpenAiChatOptionsConverter,
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
     *
     * A blank key is rejected before any network call — see [requireUsableApiKey] for why a key
     * is set-but-empty far more often than it looks.
     *
     * Note this narrows the constructor's contract, where [apiKey] may be null meaning "no
     * authentication". That remains true of the factory; it is not true of *validation*, which
     * exists to answer "is this key usable" and has nothing to answer for an absent one. A keyless
     * endpoint — a local LM Studio or vLLM server — should be built with [openAiCompatibleLlm]
     * rather than validated here.
     *
     * @throws InvalidApiKeyException if the key is blank, absent, or invalid.
     */
    fun buildValidated(
        model: String,
        pricingModel: PricingModel,
        provider: String,
        knowledgeCutoffDate: LocalDate?,
    ): LlmService<*> {
        requireUsableApiKey(apiKey)
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

    /**
     * Validates the configured API key by embedding a short probe text, then returns a
     * production [EmbeddingService] carrying the width the model actually produced.
     *
     * The probe-and-stamp logic is provider-agnostic and lives in
     * [com.embabel.common.byok.validatedEmbeddingService]; this method supplies the
     * OpenAI-compatible builder and the blank-key guard.
     *
     * @throws InvalidApiKeyException if the key is blank or invalid, the provider is
     * unreachable, or the model returns no vector.
     */
    fun buildValidatedEmbeddingService(
        model: String,
        provider: String,
        pricingModel: PricingModel? = null,
    ): EmbeddingService {
        requireUsableApiKey(apiKey)
        return validatedEmbeddingService(model = model, provider = provider) { configuredDimensions ->
            openAiCompatibleEmbeddingService(
                model = model,
                provider = provider,
                configuredDimensions = configuredDimensions,
                pricingModel = pricingModel,
            )
        }
    }

    open fun openAiCompatibleEmbeddingService(
        model: String,
        provider: String,
        configuredDimensions: Int? = null,
        pricingModel: PricingModel? = null,
    ): EmbeddingService {
        val embeddingModel = OpenAiEmbeddingModel.builder()
            .openAiClient(openAiClient)
            .metadataMode(MetadataMode.EMBED)
            .options(OpenAiEmbeddingOptions.builder()
                .model(model)
                .build())
            .observationRegistry(observationRegistry)
            .build()
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
object OpenAiChatOptionsConverter : OptionsConverter {

    override fun convertOptions(options: LlmOptions, model: String): ChatOptions =
        OpenAiChatOptions.builder()
            .model(model)
            .temperature(options.temperature)
            .topP(options.topP)
            .maxTokens(options.maxTokens)
            .presencePenalty(options.presencePenalty)
            .frequencyPenalty(options.frequencyPenalty)
            //.streamUsage(true)  additional feature note
            .build()
}
