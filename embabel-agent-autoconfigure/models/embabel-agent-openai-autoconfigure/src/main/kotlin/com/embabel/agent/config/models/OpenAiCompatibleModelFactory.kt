/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.config.models

import com.embabel.common.ai.model.*
import com.embabel.common.util.loggerFor
import io.micrometer.observation.ObservationRegistry
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.document.MetadataMode
import org.springframework.ai.model.NoopApiKey
import org.springframework.ai.model.SimpleApiKey
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.OpenAiEmbeddingModel
import org.springframework.ai.openai.OpenAiEmbeddingOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.ai.retry.RetryUtils
import org.springframework.retry.support.RetryTemplate
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
    private val observationRegistry: ObservationRegistry,
) {

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
    ): Llm {
        return Llm(
            name = model,
            model = chatModelOf(model, retryTemplate),
            provider = provider,
            optionsConverter = optionsConverter,
            pricingModel = pricingModel,
            knowledgeCutoffDate = knowledgeCutoffDate,
        )
    }

    fun openAiCompatibleEmbeddingService(
        model: String,
        provider: String,
    ): EmbeddingService {
        val embeddingModel = OpenAiEmbeddingModel(
            openAiApi,
            MetadataMode.EMBED,
            OpenAiEmbeddingOptions.builder()
                .model(model)
                .build(),
        )
        return EmbeddingService(
            name = model,
            model = embeddingModel,
            provider = provider,
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
            .build()
}
