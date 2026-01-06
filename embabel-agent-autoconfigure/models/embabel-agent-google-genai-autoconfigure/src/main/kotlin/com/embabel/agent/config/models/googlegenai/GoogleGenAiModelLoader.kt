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
package com.embabel.agent.config.models.googlegenai

import com.embabel.common.ai.autoconfig.AbstractYamlModelLoader
import com.embabel.common.ai.autoconfig.LlmAutoConfigMetadata
import com.embabel.common.ai.autoconfig.LlmAutoConfigProvider
import com.embabel.common.ai.model.PerTokenPricingModel
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.ResourceLoader
import java.time.LocalDate

/**
 * Container for Google GenAI model definitions loaded from YAML.
 *
 * Implements [LlmAutoConfigProvider] to supply Google GenAI-specific model metadata
 * for auto-configuration purposes.
 *
 * @property models list of Google GenAI model definitions
 */
data class GoogleGenAiModelDefinitions(
    override val models: List<GoogleGenAiModelDefinition> = emptyList()
) : LlmAutoConfigProvider<GoogleGenAiModelDefinition>

/**
 * Google GenAI-specific model definition.
 *
 * Implements [LlmAutoConfigMetadata] with Google GenAI-specific features
 * like thinking budget and custom parameter defaults.
 *
 * @property name the unique name of the model
 * @property modelId the Google GenAI API model identifier
 * @property displayName optional human-readable name
 * @property knowledgeCutoffDate optional knowledge cutoff date
 * @property pricingModel optional per-token pricing information
 * @property maxOutputTokens maximum tokens for completion (default 8192)
 * @property temperature sampling temperature (default 0.7)
 * @property topP nucleus sampling parameter
 * @property topK top-k sampling parameter
 * @property thinkingBudget optional thinking process token allocation
 */
data class GoogleGenAiModelDefinition(
    override val name: String,
    override val modelId: String,
    override val displayName: String? = null,
    override val knowledgeCutoffDate: LocalDate? = null,
    override val pricingModel: PerTokenPricingModel? = null,
    val maxOutputTokens: Int = 8192,
    val temperature: Double = 0.7,
    val topP: Double? = null,
    val topK: Int? = null,
    val thinkingBudget: Int? = null,
) : LlmAutoConfigMetadata

/**
 * Loader for Google GenAI model definitions from YAML configuration.
 *
 * Reads model metadata from the configured resource path (default: `classpath:models/google-genai-models.yml`)
 * and deserializes it into [GoogleGenAiModelDefinitions]. Validates loaded models to ensure data integrity.
 *
 * @property resourceLoader Spring resource loader for accessing classpath resources
 * @property configPath path to the YAML configuration file
 */
class GoogleGenAiModelLoader(
    resourceLoader: ResourceLoader = DefaultResourceLoader(),
    configPath: String = DEFAULT_CONFIG_PATH
) : AbstractYamlModelLoader<GoogleGenAiModelDefinitions>(resourceLoader, configPath) {

    override fun getProviderClass() = GoogleGenAiModelDefinitions::class

    override fun createEmptyProvider() = GoogleGenAiModelDefinitions()

    override fun getProviderName() = "GoogleGenAI"

    override fun validateModels(provider: GoogleGenAiModelDefinitions) {
        provider.models.forEach { model ->
            validateCommonFields(model)
            require(model.maxOutputTokens > 0) { "Max output tokens must be positive for model ${model.name}" }
            require(model.temperature in 0.0..2.0) {
                "Temperature must be between 0 and 2 for model ${model.name}"
            }
            model.topP?.let {
                require(it in 0.0..1.0) { "Top P must be between 0 and 1 for model ${model.name}" }
            }
            model.topK?.let {
                require(it > 0) { "Top K must be positive for model ${model.name}" }
            }
            model.thinkingBudget?.let {
                require(it > 0) { "Thinking budget must be positive for model ${model.name}" }
            }
        }
    }

    companion object {
        /**
         * Default path to the Google GenAI models YAML configuration file.
         */
        private const val DEFAULT_CONFIG_PATH = "classpath:models/google-genai-models.yml"
    }
}
