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
package com.embabel.agent.config.models.openai.custom

import com.embabel.agent.openai.OpenAiCompatibleModelFactory
import com.embabel.agent.openai.StandardOpenAiOptionsConverter
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.autoconfig.ProviderInitialization
import com.embabel.common.ai.autoconfig.RegisteredModel
import com.embabel.common.util.ExcludeFromJacocoGeneratedReport
import io.micrometer.observation.ObservationRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.ClientHttpRequestFactory

/**
 * Configuration properties for OpenAI Custom model settings.
 * These properties can be set in application.properties/yaml using the
 * prefix embabel.agent.platform.models.openai.custom
 */
@ConfigurationProperties(prefix = "embabel.agent.platform.models.openai.custom")
class OpenAiCustomProperties : RetryProperties {
    /**
     * Base URL for OpenAI Custom API requests.
     */
    var baseUrl: String? = null

    /**
     * API key for authenticating with OpenAI Custom services.
     */
    var apiKey: String? = null

    /**
     * Comma-separated list of custom model IDs to register.
     */
    var models: String? = null

    /**
     *  Maximum number of attempts.
     */
    override var maxAttempts: Int = 10

    /**
     * Initial backoff interval (in milliseconds).
     */
    override var backoffMillis: Long = 5000L

    /**
     * Backoff interval multiplier.
     */
    override var backoffMultiplier: Double = 5.0

    /**
     * Maximum backoff interval (in milliseconds).
     */
    override var backoffMaxInterval: Long = 180000L
}

/**
 * Configuration for OpenAI Compatible language and embedding models.
 * You can specify custom models via the
 * `OPENAI_CUSTOM_MODELS` environment variable (comma-separated list). This is useful
 * when using OpenAI-compatible APIs (like Groq, Together AI, etc.) that may use
 * different model names.
 *
 * Example:
 * ```
 * OPENAI_CUSTOM_BASE_URL=https://api.groq.com/openai/v1
 * OPENAI_CUSTOM_API_KEY=your-api-key
 * OPENAI_CUSTOM_MODELS=llama-3.3-70b-versatile,mixtral-8x7b-32768,gemma2-9b-it
 * EMBABEL_MODELS_DEFAULT_LLM=llama-3.3-70b-versatile
 * ```
 *
 * All custom models will be registered and available. Use `EMBABEL_MODELS_DEFAULT_LLM`
 * to specify which model should be the default.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenAiCustomProperties::class)
@ExcludeFromJacocoGeneratedReport(reason = "OpenAi Custom configuration can't be unit tested")
class OpenAiCustomModelsConfig(
    @param:Value("\${OPENAI_CUSTOM_BASE_URL:#{null}}")
    private val envBaseUrl: String?,
    @param:Value("\${OPENAI_CUSTOM_API_KEY:#{null}}")
    private val envApiKey: String?,
    @param:Value("\${OPENAI_CUSTOM_MODELS:#{null}}")
    private val envCustomModels: String?,
    observationRegistry: ObjectProvider<ObservationRegistry>,
    private val properties: OpenAiCustomProperties,
    private val configurableBeanFactory: ConfigurableBeanFactory,
    requestFactory: ObjectProvider<ClientHttpRequestFactory>,
) : OpenAiCompatibleModelFactory(
    baseUrl = envBaseUrl ?: properties.baseUrl,
    apiKey = envApiKey ?: properties.apiKey
    ?: error("OpenAI Custom API key required: set OPENAI_CUSTOM_API_KEY env var or embabel.agent.platform.models.openai.custom.api-key"),
    completionsPath = null,
    embeddingsPath = null,
    observationRegistry = observationRegistry.getIfUnique { ObservationRegistry.NOOP },
    requestFactory,
) {

    private val customModelList: List<String> = (envCustomModels ?: properties.models)
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?: emptyList()

    init {
        logger.info("OpenAI Custom models are available: {}", properties)
        if (customModelList.isNotEmpty()) {
            logger.info("Custom OpenAI-Custom models configured: {}", customModelList)
        }
    }

    @Bean
    fun openAiCustomModelsInitializer(): ProviderInitialization {
        val registeredLlms = registerCustomModels()

        return ProviderInitialization(
            provider = CUSTOM_PROVIDER,
            registeredLlms = registeredLlms,
        ).also { logger.info(it.summary()) }
    }

    /**
     * Registers custom models specified via OPENAI_CUSTOM_MODELS environment variable.
     */
    private fun registerCustomModels(): List<RegisteredModel> {
        return customModelList.map { modelId ->
            try {
                val llm = createCustomLlm(modelId)
                configurableBeanFactory.registerSingleton(modelId, llm)
                logger.info(
                    "Registered custom OpenAI-compatible model bean: {}",
                    modelId
                )
                RegisteredModel(beanName = modelId, modelId = modelId)
            } catch (e: Exception) {
                logger.error("Failed to create custom model: {}", modelId, e)
                throw e
            }
        }
    }

    /**
     * Creates an LLM for a custom model specified via OPENAI_CUSTOM_MODELS.
     * Uses standard OpenAI options converter since we don't know the model's capabilities.
     */
    private fun createCustomLlm(modelId: String): LlmService<*> {
        val chatModel = chatModelOf(
            model = modelId,
            retryTemplate = properties.retryTemplate(modelId)
        )

        return SpringAiLlmService(
            name = modelId,
            chatModel = chatModel,
            provider = CUSTOM_PROVIDER,
            optionsConverter = StandardOpenAiOptionsConverter,
        )
    }

    companion object {
        private const val CUSTOM_PROVIDER = "OpenAI-Custom"
    }
}
