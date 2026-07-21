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
package com.embabel.agent.anthropic

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
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
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.beans.factory.ObjectProvider
import org.springframework.retry.support.RetryTemplate
import org.springframework.web.client.RestClient
import java.time.Duration

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
 * Spring AI 2.0 swapped its hand-rolled `AnthropicApi` for the official anthropic-java SDK
 * ([AnthropicClient]). The migration removes Spring's `RestClient`/`WebClient` from the
 * HTTP path entirely — the SDK uses OkHttp internally. The [restClientBuilder] parameter
 * is kept for source compatibility with the previous constructor signature but is no
 * longer wired into HTTP calls.
 *
 * @param apiKey Anthropic API key.
 * @param baseUrl Optional base URL override; defaults to the standard Anthropic endpoint.
 * @param validationModel Model used for the key-validation probe. Defaults to [VALIDATION_MODEL].
 * @param observationRegistry Micrometer registry for Spring AI's chat model instrumentation.
 * @param restClientBuilder Unused since Spring AI 2.0; retained for source compatibility.
 */
open class AnthropicModelFactory(
    private val apiKey: String,
    private val baseUrl: String? = null,
    private val validationModel: String = VALIDATION_MODEL,
    protected val observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
    @Suppress("UNUSED_PARAMETER")
    restClientBuilder: ObjectProvider<RestClient.Builder> = ObjectProviders.empty(),
) : ByokFactory<LlmService<*>> {

    protected val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        /** Default model used for key validation probes — cheapest available. */
        const val VALIDATION_MODEL = AnthropicModels.CLAUDE_HAIKU_4_5

        private val READ_TIMEOUT: Duration = Duration.ofMillis(600_000)
    }

    /**
     * Builds an [AnthropicClient] (from anthropic-java, the SDK Spring AI 2.0 delegates to)
     * from the configured credentials. Protected so [AnthropicModelsConfig] can reuse it.
     *
     * Uses [AnthropicOkHttpClient.builder] directly rather than `AnthropicSetup` so we don't
     * inherit Spring AI's `ANTHROPIC_BASE_URL` / `ANTHROPIC_API_KEY` environment-variable
     * precedence — the factory's caller has already resolved env vs. properties at construction
     * time.
     */
    protected fun createAnthropicClient(): AnthropicClient {
        val builder = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .timeout(READ_TIMEOUT)
        if (!baseUrl.isNullOrBlank()) {
            logger.info("Using custom Anthropic base URL: {}", baseUrl)
            builder.baseUrl(baseUrl)
        }
        return builder.build()
    }

    /**
     * Builds an [LlmService] for the given Anthropic model.
     *
     * Spring AI 2.0 dropped the spring-retry `RetryTemplate` parameter on the chat model
     * builder. The [retryTemplate] param is kept here so existing call sites compile, but
     * is ignored — retries are handled at the ChatClientLlmOperations layer via spring-retry.
     *
     * @param model Model identifier, e.g. [AnthropicModels.CLAUDE_HAIKU_4_5].
     */
    @JvmOverloads
    fun build(
        model: String,
        @Suppress("UNUSED_PARAMETER")
        retryTemplate: RetryTemplate? = null,
    ): LlmService<*> {
        val chatModel = AnthropicChatModel.builder()
            .options(AnthropicChatOptions.builder().model(model).build())
            .anthropicClient(createAnthropicClient())
            .toolCallingManager(
                ToolCallingManager.builder().observationRegistry(observationRegistry).build()
            )
            .observationRegistry(observationRegistry)
            .build()

        return SpringAiLlmService(
            name = model,
            chatModel = chatModel,
            provider = AnthropicModels.PROVIDER,
            optionsConverter = AnthropicOptionsConverter,
            thinkingSupported = true,
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
     * Spring AI 2.0 no longer accepts a spring-retry [RetryTemplate] on the model builder,
     * so the probe relies on the anthropic-java SDK's own no-retry default (any 401 fails fast).
     * On any exception the provider-specific error is translated to [InvalidApiKeyException],
     * keeping Spring AI types out of the caller.
     *
     * @param model Model to use for the probe.
     * @throws InvalidApiKeyException if the key is invalid.
     */
    fun buildValidated(model: String): LlmService<*> {
        val probe = build(model)
        try {
            probe.createMessageSender(LlmOptions()).call(listOf(UserMessage("Hi")), emptyList())
        } catch (e: Exception) {
            throw InvalidApiKeyException(e.message ?: "Invalid API key")
        }
        return build(model)
    }
}
