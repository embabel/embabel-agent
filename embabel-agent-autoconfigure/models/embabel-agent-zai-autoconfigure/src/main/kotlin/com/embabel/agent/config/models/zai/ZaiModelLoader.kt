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
package com.embabel.agent.config.models.zai

import com.embabel.common.ai.autoconfig.AbstractYamlModelLoader
import com.embabel.common.ai.autoconfig.LlmAutoConfigMetadata
import com.embabel.common.ai.autoconfig.LlmAutoConfigProvider
import com.embabel.common.ai.model.PerTokenPricingModel
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.ResourceLoader
import java.time.LocalDate

/**
 * Container for Z.ai (Zhipu AI) GLM model definitions loaded from YAML.
 *
 * Implements [LlmAutoConfigProvider] to supply Z.ai-specific model metadata
 * for auto-configuration purposes.
 *
 * @property models list of Z.ai GLM model definitions
 */
data class ZaiModelDefinitions(
    override val models: List<ZaiModelDefinition> = emptyList()
) : LlmAutoConfigProvider<ZaiModelDefinition>

/**
 * Z.ai (Zhipu AI) GLM-specific model definition.
 *
 * Implements [LlmAutoConfigMetadata]. GLM models require the sampling temperature
 * to be in the range (0.0, 1.0]; the default and validation reflect that.
 *
 * @property name the unique bean name of the model
 * @property modelId the Z.ai API model identifier (e.g. "glm-4.7")
 * @property displayName optional human-readable name
 * @property knowledgeCutoffDate optional knowledge cutoff date
 * @property pricingModel optional per-token pricing information
 * @property maxTokens maximum tokens for completion
 * @property temperature default sampling temperature, in (0.0, 1.0]
 * @property topP nucleus sampling parameter
 * @property thinking whether the model natively supports reasoning ("thinking")
 */
data class ZaiModelDefinition(
    override val name: String,
    override val modelId: String,
    override val displayName: String? = null,
    override val knowledgeCutoffDate: LocalDate? = null,
    override val pricingModel: PerTokenPricingModel? = null,
    val maxTokens: Int = 32768,
    val temperature: Double = 0.7,
    val topP: Double? = null,
    val thinking: Boolean = false,
) : LlmAutoConfigMetadata

/**
 * Loader for Z.ai GLM model definitions from YAML configuration.
 *
 * Reads model metadata from the configured resource path
 * (default: `classpath:models/zai-models.yml`) and deserializes it into
 * [ZaiModelDefinitions]. Validates loaded models to ensure data integrity.
 *
 * @property resourceLoader Spring resource loader for accessing classpath resources
 * @property configPath path to the YAML configuration file
 */
class ZaiModelLoader(
    resourceLoader: ResourceLoader = DefaultResourceLoader(),
    configPath: String = DEFAULT_CONFIG_PATH
) : AbstractYamlModelLoader<ZaiModelDefinitions>(resourceLoader, configPath) {

    override fun getProviderClass() = ZaiModelDefinitions::class

    override fun createEmptyProvider() = ZaiModelDefinitions()

    override fun getProviderName() = "Z.ai"

    override fun validateModels(provider: ZaiModelDefinitions) {
        provider.models.forEach { model ->
            validateCommonFields(model)
            require(model.maxTokens > 0) { "Max tokens must be positive for model ${model.name}" }
            require(model.temperature > 0.0 && model.temperature <= 1.0) {
                "Temperature must be in (0.0, 1.0] for GLM model ${model.name}"
            }
            model.topP?.let {
                require(it in 0.0..1.0) { "Top P must be between 0 and 1 for model ${model.name}" }
            }
        }
    }

    companion object {
        /**
         * Default path to the Z.ai models YAML configuration file.
         */
        private const val DEFAULT_CONFIG_PATH = "classpath:models/zai-models.yml"
    }
}
