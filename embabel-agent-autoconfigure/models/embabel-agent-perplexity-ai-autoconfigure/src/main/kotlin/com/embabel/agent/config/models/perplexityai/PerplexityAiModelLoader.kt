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
package com.embabel.agent.config.models.perplexityai

import com.embabel.common.ai.autoconfig.AbstractYamlModelLoader
import com.embabel.common.ai.autoconfig.LlmAutoConfigMetadata
import com.embabel.common.ai.autoconfig.LlmAutoConfigProvider
import com.embabel.common.ai.model.PerTokenPricingModel
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.ResourceLoader
import java.time.LocalDate

/**
 * Container for Perplexity AI model definitions loaded from YAML.
 *
 * Implements [LlmAutoConfigProvider] to supply Perplexity AI-specific model metadata
 * for auto-configuration purposes.
 *
 * @property models list of Perplexity AI model definitions
 */
data class PerplexityAiModelDefinitions(
	override val models: List<PerplexityAiModelDefinition> = emptyList()
) : LlmAutoConfigProvider<PerplexityAiModelDefinition>

/**
 * Perplexity AI-specific model definition.
 *
 * Implements [LlmAutoConfigMetadata] with Perplexity AI-specific features
 * like thinking mode and custom parameter defaults.
 *
 * @property name the unique name of the model
 * @property modelId the Perplexity AI API model identifier
 * @property displayName optional human-readable name
 * @property knowledgeCutoffDate optional knowledge cutoff date
 * @property pricingModel optional per-token pricing information
 * @property maxTokens maximum tokens for completion (default 16384)
 * @property temperature sampling temperature (default 1.0)
 * @property topP nucleus sampling parameter
 */
data class PerplexityAiModelDefinition(
	override val name: String,
	override val modelId: String,
	override val displayName: String? = null,
	override val knowledgeCutoffDate: LocalDate? = null,
	override val pricingModel: PerTokenPricingModel? = null,
	/* No default value is specified in the doc https://docs.perplexity.ai/docs/agent-api/models.
	* Hence, reused the value from openai models.*/
	val maxTokens: Int = 16384,
	val temperature: Double = 1.0,
	val topP: Double? = null,
) : LlmAutoConfigMetadata

class PerplexityAiModelLoader(
	resourceLoader: ResourceLoader = DefaultResourceLoader(),
	configPath: String = DEFAULT_CONFIG_PATH
) : AbstractYamlModelLoader<PerplexityAiModelDefinitions>(resourceLoader, configPath) {

	override fun getProviderClass() = PerplexityAiModelDefinitions::class

	override fun createEmptyProvider() = PerplexityAiModelDefinitions()

	override fun getProviderName() = "Perplexity AI"

	override fun validateModels(provider: PerplexityAiModelDefinitions) {
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
		 * Default path to the Perplexity models YAML configuration file.
		 */
		private const val DEFAULT_CONFIG_PATH = "classpath:models/perplexity-ai-models.yml"
	}
}
