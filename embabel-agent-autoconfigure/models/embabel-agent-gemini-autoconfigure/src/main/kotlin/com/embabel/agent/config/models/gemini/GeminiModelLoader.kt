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
package com.embabel.agent.config.models.gemini

import com.embabel.common.ai.autoconfig.AbstractYamlModelLoader
import com.embabel.common.ai.autoconfig.LlmAutoConfigMetadata
import com.embabel.common.ai.autoconfig.LlmAutoConfigProvider
import com.embabel.common.ai.model.PerTokenPricingModel
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.ResourceLoader
import java.time.LocalDate

/**
 * Container for all Gemini model definitions loaded from YAML.
 */
data class GeminiModelDefinitions(
    override val models: List<GeminiModelDefinition> = emptyList()
) : LlmAutoConfigProvider<GeminiModelDefinition>

/**
 * Definition for a single Gemini LLM model.
 */
data class GeminiModelDefinition(
    override val name: String,
    override val modelId: String,
    override val displayName: String? = null,
    override val knowledgeCutoffDate: LocalDate? = null,
    override val pricingModel: PerTokenPricingModel? = null,
    val maxTokens: Int = 8192,
    val temperature: Double = 1.0,
    val topP: Double? = null,
) : LlmAutoConfigMetadata

/**
 * Loader for Gemini model definitions from YAML configuration.
 */
class GeminiModelLoader(
    resourceLoader: ResourceLoader = DefaultResourceLoader(),
    configPath: String = DEFAULT_CONFIG_PATH
) : AbstractYamlModelLoader<GeminiModelDefinitions>(resourceLoader, configPath) {

    override fun getProviderClass() = GeminiModelDefinitions::class

    override fun createEmptyProvider() = GeminiModelDefinitions()

    override fun getProviderName() = "Gemini"

    override fun validateModels(provider: GeminiModelDefinitions) {
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
    }

    companion object {
        /**
         * Default path to the Gemini models YAML configuration file.
         */
        private const val DEFAULT_CONFIG_PATH = "classpath:models/gemini-models.yml"
    }
}
