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

import com.embabel.agent.anthropic.AnthropicModelFactory
import com.embabel.agent.anthropic.AnthropicOptionsConverter
import com.embabel.agent.api.models.AnthropicModels
import com.embabel.agent.config.models.anthropic.AnthropicProperties.Companion.PREFIX
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.agent.spi.support.springai.SpringAiNativeStructuredOutputConfigurer
import com.embabel.common.ai.autoconfig.LlmAutoConfigMetadataLoader
import com.embabel.common.ai.autoconfig.ProviderInitialization
import com.embabel.common.ai.autoconfig.RegisteredModel
import com.embabel.common.ai.model.PerTokenPricingModel
import com.embabel.common.util.ExcludeFromJacocoGeneratedReport
import io.micrometer.observation.ObservationRegistry
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient


/**
 * Configuration properties for Anthropic models.
 * These properties are bound from the Spring configuration with the prefix
 * "embabel.agent.platform.models.anthropic" and control retry behavior
 * when calling Anthropic APIs.
 */
@ConfigurationProperties(prefix = PREFIX)
class AnthropicProperties : RetryProperties {
    /**
     * Base URL for Anthropic API requests.
     */
    var baseUrl: String? = null

    /**
     * API key for authenticating with Anthropic services.
     */
    var apiKey: String? = null

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

    override val propertyPrefix: String = PREFIX
    companion object {
        const val PREFIX  = "embabel.agent.platform.models.anthropic"
    }
}


/**
 * Placeholder handed to the superclass when no key is configured. It is never used to
 * build a client: [AnthropicModelsConfig.anthropicModelsInitializer] registers nothing
 * in that case. It exists only because [AnthropicModelFactory.apiKey] is non-nullable
 * and a superclass constructor argument has to evaluate to something.
 */
private const val UNCONFIGURED_API_KEY = "anthropic-api-key-not-configured"

/**
 * The configured key, or `null` if there isn't one.
 *
 * A **blank** key counts as absent. That is not hypothetical tidiness: compose passes
 * `ANTHROPIC_API_KEY=${ANTHROPIC_API_KEY:-}`, so inside a container the variable is
 * routinely set-but-empty, and `@Value("\${ANTHROPIC_API_KEY:#{null}}")` resolves that
 * to `""` rather than `null`. Treating blank as absent is what keeps the empty case on
 * the same path as the missing one instead of handing a blank key to the client.
 */
private fun configuredAnthropicApiKey(envApiKey: String?, properties: AnthropicProperties): String? =
    envApiKey?.takeIf(String::isNotBlank) ?: properties.apiKey?.takeIf(String::isNotBlank)

/**
 * Configuration class for Anthropic models.
 * Extends [AnthropicModelFactory] so that the API client construction is shared
 * with the BYOK path. This class adds the Spring autoconfigure wiring on top:
 * loading model definitions from YAML, registering them as beans, and applying
 * the retry policy from [AnthropicProperties].
 *
 * With no API key this registers no models and lets the context start, the way the
 * LM Studio config does when its server is unreachable. A missing key is a deployment
 * that has not been given one yet — an appliance whose operator supplies keys through
 * first-run setup — not a programming error, so it must not kill the context. Failing
 * in the constructor took the whole application down, because a `@Configuration` whose
 * superclass constructor throws cannot be conditioned away.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnthropicProperties::class)
@ExcludeFromJacocoGeneratedReport(reason = "Model registration needs a live Anthropic account; the no-key path is covered by AnthropicModelsConfigNoKeyTest")
class AnthropicModelsConfig(
    @param:Value("\${ANTHROPIC_BASE_URL:#{null}}")
    private val envBaseUrl: String?,
    @param:Value("\${ANTHROPIC_API_KEY:#{null}}")
    private val envApiKey: String?,
    private val properties: AnthropicProperties,
    observationRegistry: ObjectProvider<ObservationRegistry>,
    @Qualifier("aiModelRestClientBuilder")
    restClientBuilder: ObjectProvider<RestClient.Builder>,
    private val configurableBeanFactory: ConfigurableBeanFactory,
    private val modelLoader: LlmAutoConfigMetadataLoader<AnthropicModelDefinitions> = AnthropicModelLoader(),
    private val nativeStructuredOutputConfigurer: SpringAiNativeStructuredOutputConfigurer =
        SpringAiNativeStructuredOutputConfigurer.NOOP,
) : AnthropicModelFactory(
    apiKey = configuredAnthropicApiKey(envApiKey, properties) ?: UNCONFIGURED_API_KEY,
    baseUrl = envBaseUrl ?: properties.baseUrl,
    observationRegistry = observationRegistry.getIfUnique { ObservationRegistry.NOOP },
    restClientBuilder = restClientBuilder,
) {

    private val apiKeyConfigured: Boolean = configuredAnthropicApiKey(envApiKey, properties) != null

    init {
        if (apiKeyConfigured) {
            logger.info("Anthropic models are available: {}", properties)
        } else {
            logger.info(
                "No Anthropic API key configured: set ANTHROPIC_API_KEY or {}.api-key to enable Anthropic models. Continuing without them.",
                PREFIX,
            )
        }
    }

    @Bean
    fun anthropicModelsInitializer(): ProviderInitialization {
        if (!apiKeyConfigured) {
            return ProviderInitialization(
                provider = AnthropicModels.PROVIDER,
                registeredLlms = emptyList(),
            )
        }
        val definitions = modelLoader.loadAutoConfigMetadata()
        val effectiveModels = definitions.effectiveModels()
        val registeredLlms = buildList {
            effectiveModels.forEach { modelDef ->
                try {
                    val llm = createAnthropicLlm(modelDef)

                        // Register as singleton bean with the configured bean name
                        configurableBeanFactory.registerSingleton(modelDef.name, llm)
                        add(RegisteredModel(beanName = modelDef.name, modelId = modelDef.modelId))

                        logger.info(
                            "Registered Anthropic model bean: {} -> {}",
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
            provider = AnthropicModels.PROVIDER,
            registeredLlms = registeredLlms,
        ).also { logger.info(it.summary()) }
    }

    /**
     * Creates an individual Anthropic model from configuration, applying full model
     * definition settings (thinking mode, token budgets, pricing, etc.) that are not
     * needed in the BYOK path.
     *
     * Spring AI 2.0 dropped the spring-retry `RetryTemplate` parameter on the chat model
     * builder; retries are wrapped at the ChatClientLlmOperations layer instead.
     */
    private fun createAnthropicLlm(modelDef: AnthropicModelDefinition): LlmService<*> {
        val chatModel = AnthropicChatModel
            .builder()
            .options(createDefaultOptions(modelDef))
            .anthropicClient(createAnthropicClient())
            .toolCallingManager(
                ToolCallingManager.builder()
                    .observationRegistry(observationRegistry)
                    .build()
            )
            .observationRegistry(observationRegistry)
            .build()

        return SpringAiLlmService(
            name = modelDef.modelId,
            chatModel = chatModel,
            provider = AnthropicModels.PROVIDER,
            optionsConverter = AnthropicOptionsConverter,
            thinkingSupported = true,
            knowledgeCutoffDate = modelDef.knowledgeCutoffDate,
            pricingModel = modelDef.pricingModel?.let {
                PerTokenPricingModel(
                    usdPer1mInputTokens = it.usdPer1mInputTokens,
                    usdPer1mOutputTokens = it.usdPer1mOutputTokens,
                )
            },
            nativeStructuredOutputConfigurer = nativeStructuredOutputConfigurer,
            nativeSupport = modelDef.nativeSupport,
        )
    }

    /**
     * Creates default options for a model based on YAML configuration.
     *
     * Spring AI 2.0 replaced the `AnthropicApi.ChatCompletionRequest.ThinkingConfig`
     * constructor with first-class `thinkingEnabled(tokenBudget)` / `thinkingDisabled()`
     * methods on the options builder.
     */
    private fun createDefaultOptions(modelDef: AnthropicModelDefinition): AnthropicChatOptions {
        return AnthropicChatOptions.builder()
            .model(modelDef.modelId)
            .maxTokens(modelDef.maxTokens)
            .temperature(modelDef.temperature)
            .apply {
                modelDef.topP?.let { topP(it) }
                modelDef.topK?.let { topK(it) }

                // Configure thinking mode if specified
                val thinkingBudget = modelDef.thinking?.tokenBudget
                if (thinkingBudget != null && thinkingBudget > 0) {
                    thinkingEnabled(thinkingBudget.toLong())
                } else {
                    thinkingDisabled()
                }
            }
            .build()
    }
}
