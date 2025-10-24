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

import com.embabel.common.ai.autoconfig.AbstractYamlModelLoader
import com.embabel.common.ai.autoconfig.LlmAutoConfigMetadata
import com.embabel.common.ai.autoconfig.LlmAutoConfigProvider
import com.embabel.common.ai.model.PerTokenPricingModel
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.ResourceLoader
import java.time.LocalDate

/**
 * Container for Anthropic model definitions loaded from YAML.
 *
 * Implements [LlmAutoConfigProvider] to supply Anthropic-specific model metadata
 * for auto-configuration purposes.
 *
 * @property models list of Anthropic model definitions
 */
data class AnthropicModelDefinitions(
    override val models: List<AnthropicModelDefinition> = emptyList()
) : LlmAutoConfigProvider<AnthropicModelDefinition>

/**
 * Anthropic-specific model definition.
 *
 * Implements [LlmAutoConfigMetadata] with Anthropic-specific features
 * like thinking mode and custom parameter defaults.
 *
 * @property name the unique name of the model
 * @property modelId the Anthropic API model identifier
 * @property displayName optional human-readable name
 * @property knowledgeCutoffDate optional knowledge cutoff date
 * @property pricingModel optional per-token pricing information
 * @property maxTokens maximum tokens for completion (default 8192)
 * @property temperature sampling temperature (default 1.0)
 * @property topP nucleus sampling parameter
 * @property topK top-k sampling parameter
 * @property thinking optional thinking mode configuration
 */
data class AnthropicModelDefinition(
    override val name: String,
    override val modelId: String,
    override val displayName: String? = null,
    override val knowledgeCutoffDate: LocalDate? = null,
    override val pricingModel: PerTokenPricingModel? = null,
    val maxTokens: Int = 8192,
    val temperature: Double = 1.0,
    val topP: Double? = null,
    val topK: Int? = null,
    val thinking: ThinkingConfiguration? = null,
) : LlmAutoConfigMetadata

/**
 * Configuration for Anthropic's extended thinking mode.
 *
 * @property tokenBudget maximum tokens allocated for thinking
 */
data class ThinkingConfiguration(
    val tokenBudget: Int? = null
)

/**
 * Loader for Anthropic model definitions from YAML configuration.
 *
 * Reads model metadata from the configured resource path (default: `classpath:models/anthropic-models.yml`)
 * and deserializes it into [AnthropicModelDefinitions]. Validates loaded models to ensure data integrity.
 *
 * @property resourceLoader Spring resource loader for accessing classpath resources
 * @property configPath path to the YAML configuration file
 */
class AnthropicModelLoader(
    resourceLoader: ResourceLoader = DefaultResourceLoader(),
    configPath: String = DEFAULT_CONFIG_PATH
) : AbstractYamlModelLoader<AnthropicModelDefinitions>(resourceLoader, configPath) {

    override fun getProviderClass() = AnthropicModelDefinitions::class

    override fun createEmptyProvider() = AnthropicModelDefinitions()

    override fun getProviderName() = "Anthropic"

    override fun validateModels(provider: AnthropicModelDefinitions) {
        provider.models.forEach { model ->
            validateCommonFields(model)
            require(model.maxTokens > 0) { "Max tokens must be positive for model ${model.name}" }
            require(model.temperature in 0.0..2.0) {
                "Temperature must be between 0 and 2 for model ${model.name}"
            }
            model.topP?.let {
                require(it in 0.0..1.0) { "Top P must be between 0 and 1 for model ${model.name}" }
            }
            model.topK?.let {
                require(it > 0) { "Top K must be positive for model ${model.name}" }
            }
            model.thinking?.tokenBudget?.let {
                require(it > 0) { "Thinking token budget must be positive for model ${model.name}" }
            }
        }
    }

    companion object {
        /**
         * Default path to the Anthropic models YAML configuration file.
         */
        private const val DEFAULT_CONFIG_PATH = "classpath:models/anthropic-models.yml"
    }
}
