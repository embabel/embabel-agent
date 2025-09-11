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
package com.embabel.agent.config.models.anthropic

import com.embabel.agent.common.RetryProperties
import com.embabel.agent.config.models.AnthropicModels
import com.embabel.common.ai.model.Llm
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.OptionsConverter
import com.embabel.common.ai.model.PerTokenPricingModel
import com.embabel.common.util.ExcludeFromJacocoGeneratedReport
import org.slf4j.LoggerFactory
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.anthropic.api.AnthropicApi
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.LocalDate


/**
 * Configuration properties for Anthropic models.
 * These properties are bound from the Spring configuration with the prefix
 * "embabel.agent.platform.models.anthropic" and control retry behavior
 * when calling Anthropic APIs.
 */
@ConfigurationProperties(prefix = "embabel.agent.platform.models.anthropic")
data class AnthropicProperties(
    override val maxAttempts: Int = 10,
    override val backoffMillis: Long = 5000L,
    override val backoffMultiplier: Double = 5.0,
    override val backoffMaxInterval: Long = 180000L,
) : RetryProperties


/**
 * Configuration class for Anthropic models.
 * This class provides beans for various Claude models (Opus, Sonnet, Haiku)
 * and handles the creation of Anthropic API clients with proper authentication.
 */
@Configuration
@ExcludeFromJacocoGeneratedReport(reason = "Anthropic configuration can't be unit tested")
class AnthropicModelsConfig(
    @param:Value("\${ANTHROPIC_BASE_URL:}")
    private val baseUrl: String,
    @param:Value("\${ANTHROPIC_API_KEY}")
    private val apiKey: String,
    private val properties: AnthropicProperties,
) {
    private val logger = LoggerFactory.getLogger(AnthropicModels::class.java)

    init {
        logger.info("Anthropic models are available: {}", properties)
    }

    @Bean
    fun claudeOpus4(): Llm {
        return anthropicLlmOf(
            AnthropicModels.Companion.CLAUDE_40_OPUS,
            knowledgeCutoffDate = LocalDate.of(2025, 3, 31),
        )
            .copy(
                pricingModel = PerTokenPricingModel(
                    usdPer1mInputTokens = 15.0,
                    usdPer1mOutputTokens = 75.0,
                )
            )
    }

    @Bean
    fun claudeSonnet(): Llm {
        return anthropicLlmOf(
            AnthropicModels.Companion.CLAUDE_37_SONNET,
            knowledgeCutoffDate = LocalDate.of(2024, 10, 31),
        )
            .copy(
                pricingModel = PerTokenPricingModel(
                    usdPer1mInputTokens = 3.0,
                    usdPer1mOutputTokens = 15.0,
                )
            )
    }

    @Bean
    fun claudeHaiku(): Llm = anthropicLlmOf(
        AnthropicModels.Companion.CLAUDE_35_HAIKU,
        knowledgeCutoffDate = LocalDate.of(2024, 10, 22),
    )
        .copy(
            pricingModel = PerTokenPricingModel(
                usdPer1mInputTokens = .80,
                usdPer1mOutputTokens = 4.0,
            )
        )

    private fun anthropicLlmOf(
        name: String,
        knowledgeCutoffDate: LocalDate?,
    ): Llm {
        val chatModel = AnthropicChatModel
            .builder()
            .defaultOptions(
                AnthropicChatOptions.builder()
                    .model(name)
                    .build()
            )
            .anthropicApi(createAnthropicApi())
            .retryTemplate(properties.retryTemplate("anthropic-$name"))
            .build()
        return Llm(
            name = name,
            model = chatModel,
            provider = AnthropicModels.Companion.PROVIDER,
            optionsConverter = AnthropicOptionsConverter,
            knowledgeCutoffDate = knowledgeCutoffDate,
        )
    }

    private fun createAnthropicApi(): AnthropicApi {
        val builder = AnthropicApi.builder().apiKey(apiKey)
        if (baseUrl.isNotBlank()) {
            logger.info("Using custom Anthropic base URL: {}", baseUrl)
            builder.baseUrl(baseUrl)
        }
        return builder.build()
    }

}

object AnthropicOptionsConverter : OptionsConverter<AnthropicChatOptions> {

    /**
     * Anthropic's default is too low and results in truncated responses.
     */
    const val DEFAULT_MAX_TOKENS = 8192

    override fun convertOptions(options: LlmOptions): AnthropicChatOptions =
        AnthropicChatOptions.builder()
            .temperature(options.temperature)
            .topP(options.topP)
            .maxTokens(options.maxTokens ?: DEFAULT_MAX_TOKENS)
            .thinking(
                if (options.thinking?.enabled == true) AnthropicApi.ChatCompletionRequest.ThinkingConfig(
                    AnthropicApi.ThinkingType.ENABLED,
                    options.thinking!!.tokenBudget,
                ) else AnthropicApi.ChatCompletionRequest.ThinkingConfig(
                    AnthropicApi.ThinkingType.DISABLED,
                    null,
                )
            )
            .topP(options.topP)
            .build()
}
