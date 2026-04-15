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
package com.embabel.agent.config.models.anthropic

import com.embabel.agent.api.models.AnthropicModels
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.byok.ByokFactory
import com.embabel.common.byok.InvalidApiKeyException
import com.embabel.common.util.ObjectProviders
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.anthropic.api.AnthropicApi
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.retry.RetryUtils
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.retry.support.RetryTemplate
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient

/**
 * Builds Anthropic [LlmService] instances from a raw API key.
 *
 * Intended as the BYOK entry point for Anthropic: no Spring context required.
 * [AnthropicModelsConfig] extends this class and delegates API client construction to it.
 *
 * Implements [ByokFactory] so instances can be passed directly to [com.embabel.common.byok.detectProvider]:
 * ```kotlin
 * detectProvider(
 *     AnthropicModelFactory(apiKey = userKey),
 *     OpenAiCompatibleModelFactory.openAi(userKey),
 * )
 * ```
 *
 * To override the model used for key validation (e.g. if the key only grants access to
 * a specific set of models):
 * ```kotlin
 * AnthropicModelFactory(apiKey = userKey, validationModel = AnthropicModels.CLAUDE_SONNET_4_5)
 * ```
 *
 * @param apiKey Anthropic API key.
 * @param baseUrl Optional base URL override; defaults to the standard Anthropic endpoint.
 * @param validationModel Model used for the key-validation probe. Defaults to [VALIDATION_MODEL].
 * @param observationRegistry Micrometer registry for HTTP client instrumentation.
 * @param requestFactory Optional HTTP request factory (e.g. for custom timeouts).
 */
open class AnthropicModelFactory(
    private val apiKey: String,
    private val baseUrl: String? = null,
    private val validationModel: String = VALIDATION_MODEL,
    protected val observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
    private val restClientBuilder: ObjectProvider<RestClient.Builder> = ObjectProviders.empty(),
) : ByokFactory<LlmService<*>> {

    protected val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        /** Default model used for key validation probes — cheapest available. */
        const val VALIDATION_MODEL = AnthropicModels.CLAUDE_HAIKU_4_5

        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 600000

        private val PASS_THROUGH_RETRY_TEMPLATE: RetryTemplate =
            RetryTemplate.builder().maxAttempts(1).build()
    }

    /**
     * Builds an [AnthropicApi] client from the configured credentials.
     * Protected so that [AnthropicModelsConfig] can reuse it for its own model wiring.
     */
    protected fun createAnthropicApi(): AnthropicApi {
        val builder = AnthropicApi.builder().apiKey(apiKey)
        if (!baseUrl.isNullOrBlank()) {
            logger.info("Using custom Anthropic base URL: {}", baseUrl)
            builder.baseUrl(baseUrl)
        }
        builder.restClientBuilder(
            restClientBuilder.getIfAvailable {
                RestClient.builder()
                    .requestFactory(
                        SimpleClientHttpRequestFactory().apply {
                            setConnectTimeout(CONNECT_TIMEOUT_MS)
                            setReadTimeout(READ_TIMEOUT_MS)
                        })
            }
                .observationRegistry(observationRegistry)
        )
        builder.webClientBuilder(
            WebClient.builder().observationRegistry(observationRegistry)
        )
        return builder.build()
    }

    /**
     * Builds an [LlmService] for the given Anthropic model.
     *
     * @param model Model identifier, e.g. [AnthropicModels.CLAUDE_HAIKU_4_5].
     * @param retryTemplate Retry policy; defaults to Spring AI's standard retry template.
     */
    fun build(
        model: String,
        retryTemplate: RetryTemplate = RetryUtils.DEFAULT_RETRY_TEMPLATE,
    ): LlmService<*> {
        val chatModel = AnthropicChatModel.builder()
            .defaultOptions(AnthropicChatOptions.builder().model(model).build())
            .anthropicApi(createAnthropicApi())
            .toolCallingManager(
                ToolCallingManager.builder().observationRegistry(observationRegistry).build()
            )
            .retryTemplate(retryTemplate)
            .observationRegistry(observationRegistry)
            .build()

        return SpringAiLlmService(
            name = model,
            chatModel = chatModel,
            provider = AnthropicModels.PROVIDER,
            optionsConverter = AnthropicOptionsConverter,
        )
    }

    /**
     * Validates the API key using [validationModel] (set at construction time), then returns
     * a production [LlmService]. Satisfies [ByokFactory] for use with [com.embabel.common.byok.detectProvider].
     *
     * @throws InvalidApiKeyException if the key is invalid.
     */
    override fun buildValidated(): LlmService<*> = buildValidated(validationModel)

    /**
     * Validates the API key with a probe call on the given [model], then returns a production
     * [LlmService] if successful.
     *
     * The probe uses a single-attempt retry template so an invalid key fails fast without
     * retries or noisy stack traces. On any exception the provider-specific error is
     * translated to [InvalidApiKeyException], keeping Spring AI types out of the caller.
     *
     * @param model Model to use for the probe.
     * @throws InvalidApiKeyException if the key is invalid.
     */
    fun buildValidated(model: String): LlmService<*> {
        val probe = build(model, PASS_THROUGH_RETRY_TEMPLATE)
        try {
            probe.createMessageSender(LlmOptions()).call(listOf(UserMessage("Hi")), emptyList())
        } catch (e: Exception) {
            throw InvalidApiKeyException(e.message ?: "Invalid API key")
        }
        return build(model)
    }
}
