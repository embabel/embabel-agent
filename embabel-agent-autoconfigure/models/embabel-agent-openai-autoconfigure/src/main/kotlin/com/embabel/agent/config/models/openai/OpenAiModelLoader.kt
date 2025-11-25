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
package com.embabel.agent.config.models.openai

import com.embabel.common.ai.autoconfig.AbstractYamlModelLoader
import com.embabel.common.ai.autoconfig.LlmAutoConfigMetadata
import com.embabel.common.ai.autoconfig.LlmAutoConfigProvider
import com.embabel.common.ai.model.PerTokenPricingModel
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.ResourceLoader
import java.time.LocalDate

/**
 * Container for OpenAI model definitions loaded from YAML.
 *
 * Implements [LlmAutoConfigProvider] to supply OpenAI-specific model metadata
 * for auto-configuration purposes.
 *
 * @property models list of OpenAI LLM model definitions
 * @property embeddingModels list of OpenAI embedding model definitions
 */
data class OpenAiModelDefinitions(
    override val models: List<OpenAiModelDefinition> = emptyList(),
    val embeddingModels: List<OpenAiEmbeddingModelDefinition> = emptyList()
) : LlmAutoConfigProvider<OpenAiModelDefinition>

/**
 * OpenAI-specific LLM model definition.
 *
 * Implements [LlmAutoConfigMetadata] with OpenAI-specific features
 * like temperature control and max tokens configuration.
 *
 * @property name the unique name of the model
 * @property modelId the OpenAI API model identifier
 * @property displayName optional human-readable name
 * @property knowledgeCutoffDate optional knowledge cutoff date
 * @property pricingModel optional per-token pricing information
 * @property maxTokens maximum tokens for completion (default 16384)
 * @property temperature sampling temperature (default 1.0)
 * @property topP nucleus sampling parameter
 * @property specialHandling optional special handling configuration (e.g., GPT-5 temperature)
 */
data class OpenAiModelDefinition(
    override val name: String,
    override val modelId: String,
    override val displayName: String? = null,
    override val knowledgeCutoffDate: LocalDate? = null,
    override val pricingModel: PerTokenPricingModel? = null,
    val maxTokens: Int = 16384,
    val temperature: Double = 1.0,
    val topP: Double? = null,
    val specialHandling: SpecialHandlingConfiguration? = null,
) : LlmAutoConfigMetadata

/**
 * Special handling configuration for models with unique requirements.
 *
 * @property supportsTemperature whether the model supports temperature adjustment
 */
data class SpecialHandlingConfiguration(
    val supportsTemperature: Boolean = true
)

/**
 * OpenAI embedding model definition.
 *
 * @property name the unique name of the model
 * @property modelId the model identifier
 * @property displayName optional human-readable name
 * @property dimensions embedding vector dimensions
 * @property pricingModel optional pricing for embeddings
 */
data class OpenAiEmbeddingModelDefinition(
    val name: String,
    val modelId: String,
    val displayName: String? = null,
    val dimensions: Int? = null,
    val pricingModel: EmbeddingPricingModel? = null
)

/**
 * Pricing model for embedding models.
 *
 * @property usdPer1mTokens cost per 1 million tokens (USD)
 */
data class EmbeddingPricingModel(
    val usdPer1mTokens: Double
)

/**
 * Loader for OpenAI model definitions from YAML configuration.
 *
 * Reads model metadata from the configured resource path (default: `classpath:models/openai-models.yml`)
 * and deserializes it into [OpenAiModelDefinitions]. Validates loaded models to ensure data integrity.
 *
 * @property resourceLoader Spring resource loader for accessing classpath resources
 * @property configPath path to the YAML configuration file
 */
class OpenAiModelLoader(
    resourceLoader: ResourceLoader = DefaultResourceLoader(),
    configPath: String = DEFAULT_CONFIG_PATH
) : AbstractYamlModelLoader<OpenAiModelDefinitions>(resourceLoader, configPath) {

    override fun getProviderClass() = OpenAiModelDefinitions::class

    override fun createEmptyProvider() = OpenAiModelDefinitions()

    override fun getProviderName() = "OpenAI"

    override fun validateModels(provider: OpenAiModelDefinitions) {
        // Validate LLM models
        provider.models.forEach { model ->
            validateCommonFields(model)
            require(model.maxTokens > 0) { "Max tokens must be positive for model ${model.name}" }
            require(model.temperature in 0.0..2.0) {
                "Temperature must be between 0 and 2 for model ${model.name}"
            }
            model.topP?.let {
                require(it in 0.0..1.0) { "Top P must be between 0 and 1 for model ${model.name}" }
            }
        }

        // Validate embedding models
        provider.embeddingModels.forEach { model ->
            require(model.name.isNotBlank()) { "Embedding model name cannot be blank" }
            require(model.modelId.isNotBlank()) { "Embedding model ID cannot be blank" }
            model.dimensions?.let {
                require(it > 0) { "Dimensions must be positive for embedding model ${model.name}" }
            }
            model.pricingModel?.let {
                require(it.usdPer1mTokens >= 0) {
                    "Pricing must be non-negative for embedding model ${model.name}"
                }
            }
        }
    }

    companion object {
        /**
         * Default path to the OpenAI models YAML configuration file.
         */
        private const val DEFAULT_CONFIG_PATH = "classpath:models/openai-models.yml"
    }
}
