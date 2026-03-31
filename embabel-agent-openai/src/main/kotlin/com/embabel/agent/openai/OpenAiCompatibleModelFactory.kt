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
import com.embabel.agent.spi.ByokFactory
import com.embabel.agent.spi.InvalidApiKeyException
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.*
import com.embabel.common.util.ObjectProviders
import com.embabel.common.util.loggerFor
import io.micrometer.observation.ObservationRegistry
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.document.MetadataMode
import org.springframework.ai.model.NoopApiKey
import org.springframework.ai.model.SimpleApiKey
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.OpenAiEmbeddingModel
import org.springframework.ai.openai.OpenAiEmbeddingOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.ai.retry.RetryUtils
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.retry.support.RetryTemplate
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient
import java.time.LocalDate

/**
 * Generic support for OpenAI compatible models.
 * Use to register LLM beans.
 * @param baseUrl The base URL of the OpenAI API. Null for OpenAI default.
 * @param apiKey The API key for the OpenAI compatible provider, or null for no authentication.
 */
open class OpenAiCompatibleModelFactory(
    val baseUrl: String?,
    private val apiKey: String?,
    private val completionsPath: String?,
    private val embeddingsPath: String?,
    private val httpHeaders: Map<String,String> = emptyMap(),
    private val observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
    private val requestFactory: ObjectProvider<ClientHttpRequestFactory> = ObjectProviders.empty()
) {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 600000
        private val PASS_THROUGH_RETRY_TEMPLATE: RetryTemplate = RetryTemplate.builder().maxAttempts(1).build()

        /**
         * Returns a [ByokSpec] for OpenAI.
         * Validates against [OpenAiModels.GPT_41_MINI] by default.
         */
        fun openAi(apiKey: String): ByokSpec =
            ByokSpec(null, apiKey, OpenAiModels.GPT_41_MINI, OpenAiModels.PROVIDER)

        /**
         * Returns a [ByokSpec] for DeepSeek (OpenAI-compatible endpoint).
         * Validates against [DeepSeekModels.DEEPSEEK_CHAT] by default.
         *
         * Note: uses the OpenAI wire protocol, not the native Spring AI DeepSeek client.
         */
        fun deepSeek(apiKey: String): ByokSpec =
            ByokSpec("https://api.deepseek.com", apiKey, DeepSeekModels.DEEPSEEK_CHAT, DeepSeekModels.PROVIDER)

        /**
         * Returns a [ByokSpec] for Mistral AI (OpenAI-compatible endpoint).
         * Validates against [MistralAiModels.MINISTRAL_8B] by default.
         *
         * Note: uses the OpenAI wire protocol, not the native Spring AI Mistral client.
         */
        fun mistral(apiKey: String): ByokSpec =
            ByokSpec("https://api.mistral.ai/v1", apiKey, MistralAiModels.MINISTRAL_8B, MistralAiModels.PROVIDER)

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
         *         baseUrl = "https://api.myprovider.com/v1",
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
     * so it can be passed directly to [com.embabel.agent.spi.detectProvider].
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
    ) : ByokFactory {

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
    }

    protected val openAiApi = createOpenAiApi()

    private fun createOpenAiApi(): OpenAiApi {
        val builder = OpenAiApi.builder()
            .apiKey(if (apiKey != null) SimpleApiKey(apiKey) else NoopApiKey())
        if (baseUrl != null) {
            loggerFor<OpenAiModels>().info("Using custom OpenAI base URL: {}", baseUrl)
            builder.baseUrl(baseUrl)
        }
        if (completionsPath != null) {
            loggerFor<OpenAiModels>().info("Using custom OpenAI completions path: {}", completionsPath)
            builder.completionsPath(completionsPath)
        }
        if (embeddingsPath != null) {
            loggerFor<OpenAiModels>().info("Using custom OpenAI embeddings path: {}", embeddingsPath)
            builder.embeddingsPath(embeddingsPath)
        }

        //add observation registry to rest and web client builders
        builder
            .restClientBuilder(
                RestClient.builder()
                    .requestFactory(requestFactory.getIfAvailable {
                        SimpleClientHttpRequestFactory().apply {
                            setConnectTimeout(CONNECT_TIMEOUT_MS)
                            setReadTimeout(READ_TIMEOUT_MS)
                        }
                    })
                    .observationRegistry(observationRegistry)
            )
        builder
            .webClientBuilder(
                WebClient.builder()
                    .observationRegistry(observationRegistry)
            )

        return builder.build()
    }

    @JvmOverloads
    fun openAiCompatibleLlm(
        model: String,
        pricingModel: PricingModel,
        provider: String,
        knowledgeCutoffDate: LocalDate?,
        optionsConverter: OptionsConverter<*> = OpenAiChatOptionsConverter,
        retryTemplate: RetryTemplate = RetryUtils.DEFAULT_RETRY_TEMPLATE,
    ): LlmService<*> {
        return SpringAiLlmService(
            name = model,
            chatModel = chatModelOf(model, retryTemplate),
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
     * The probe uses a single-attempt retry template (no retries) so a 401 fails fast.
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
            retryTemplate = PASS_THROUGH_RETRY_TEMPLATE,
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
    ): EmbeddingService {
        val embeddingModel = OpenAiEmbeddingModel(
            openAiApi,
            MetadataMode.EMBED,
            OpenAiEmbeddingOptions.builder()
                .model(model)
                .build(),
        )
        return SpringAiEmbeddingService(
            name = model,
            model = embeddingModel,
            provider = provider,
            configuredDimensions = configuredDimensions,
        )
    }

    protected fun chatModelOf(
        model: String,
        retryTemplate: RetryTemplate,
    ): ChatModel {
        return OpenAiChatModel.builder()
            .defaultOptions(
                OpenAiChatOptions.builder()
                    .model(model)
                    .httpHeaders(httpHeaders)
                    .build()
            )
            .toolCallingManager(
                ToolCallingManager.builder()
                    .observationRegistry(observationRegistry)
                    .build()
            )
            .openAiApi(openAiApi)
            .retryTemplate(retryTemplate)
            .observationRegistry(
                observationRegistry
            ).build()
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
