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
package com.embabel.agent.config.models.dashscope

import com.embabel.agent.api.models.DashScopeModels
import com.embabel.common.ai.autoconfig.AbstractYamlModelLoader
import com.embabel.common.ai.autoconfig.LlmAutoConfigMetadata
import com.embabel.common.ai.autoconfig.LlmAutoConfigProvider
import com.embabel.common.ai.model.PerTokenPricingModel
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.ResourceLoader
import java.time.LocalDate

/**
 * Container for Alibaba Cloud DashScope model definitions loaded from YAML.
 *
 * Implements [LlmAutoConfigProvider] to supply DashScope-specific model metadata
 * for auto-configuration purposes.
 *
 * @property models list of DashScope Qwen model definitions
 * @since 1.5.0
 */
data class DashScopeModelDefinitions(
    override val models: List<DashScopeModelDefinition> = emptyList()
) : LlmAutoConfigProvider<DashScopeModelDefinition>

/**
 * DashScope Qwen-specific model definition.
 *
 * Implements [LlmAutoConfigMetadata].
 *
 * @property name the unique bean name of the model
 * @property modelId the DashScope API model identifier (e.g. "qwen-max")
 * @property displayName optional human-readable name
 * @property knowledgeCutoffDate optional knowledge cutoff date
 * @property pricingModel optional per-token pricing information
 * @property maxTokens maximum tokens for completion
 * @property temperature default sampling temperature
 * @property topP nucleus sampling parameter
 * @since 1.5.0
 */
data class DashScopeModelDefinition(
    override val name: String,
    override val modelId: String,
    override val displayName: String? = null,
    override val knowledgeCutoffDate: LocalDate? = null,
    override val pricingModel: PerTokenPricingModel? = null,
    val maxTokens: Int = 8192,
    val temperature: Double = 0.7,
    val topP: Double? = null,
) : LlmAutoConfigMetadata

/**
 * Loader for DashScope Qwen model definitions from YAML configuration.
 *
 * Reads model metadata from the configured resource path
 * (default: `classpath:models/dashscope-models.yml`) and deserializes it into
 * [DashScopeModelDefinitions]. Validates loaded models to ensure data integrity.
 *
 * @property resourceLoader Spring resource loader for accessing classpath resources
 * @property configPath path to the YAML configuration file
 * @since 1.5.0
 */
class DashScopeModelLoader(
    resourceLoader: ResourceLoader = DefaultResourceLoader(),
    configPath: String = DEFAULT_CONFIG_PATH
) : AbstractYamlModelLoader<DashScopeModelDefinitions>(resourceLoader, configPath) {

    override fun getProviderClass() = DashScopeModelDefinitions::class

    override fun createEmptyProvider() = DashScopeModelDefinitions()

    override fun getProviderName() = DashScopeModels.PROVIDER

    override fun validateModels(provider: DashScopeModelDefinitions) {
        provider.models.forEach { model ->
            validateCommonFields(model)
            require(model.maxTokens > 0) { "Max tokens must be positive for model ${model.name}" }
            require(model.temperature in 0.0..<2.0) {
                "Temperature must be in [0.0, 2.0) for DashScope model ${model.name}"
            }
            model.topP?.let {
                require(it > 0.0 && it <= 1.0) {
                    "TopP must be in (0.0, 1.0] for model ${model.name}" }
            }
        }
    }

    companion object {
        /**
         * Default path to the DashScope models YAML configuration file.
         */
        private const val DEFAULT_CONFIG_PATH = "classpath:models/dashscope-models.yml"
    }
}
