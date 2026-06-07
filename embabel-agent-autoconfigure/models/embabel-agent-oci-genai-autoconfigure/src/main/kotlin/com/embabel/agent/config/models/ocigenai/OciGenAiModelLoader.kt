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
package com.embabel.agent.config.models.ocigenai

import com.embabel.common.ai.autoconfig.AbstractYamlModelLoader
import com.embabel.common.ai.autoconfig.LlmAutoConfigMetadata
import com.embabel.common.ai.autoconfig.LlmAutoConfigProvider
import com.embabel.common.ai.model.PerTokenPricingModel
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.ResourceLoader
import java.time.LocalDate

data class OciGenAiModelDefinitions(
    override val models: List<OciGenAiModelDefinition> = emptyList(),
    val embeddingModels: List<OciGenAiEmbeddingModelDefinition> = emptyList(),
) : LlmAutoConfigProvider<OciGenAiModelDefinition>

data class OciGenAiModelDefinition(
    override val name: String,
    override val modelId: String,
    override val displayName: String? = null,
    override val knowledgeCutoffDate: LocalDate? = null,
    override val pricingModel: PerTokenPricingModel? = null,
    val apiFormat: OciGenAiApiFormat = OciGenAiApiFormat.GENERIC,
    val maxTokens: Int? = null,
    val temperature: Double? = 0.7,
    val topP: Double? = null,
    val topK: Int? = null,
) : LlmAutoConfigMetadata

data class OciGenAiEmbeddingModelDefinition(
    val name: String,
    val modelId: String,
    val displayName: String? = null,
    val dimensions: Int? = null,
    val truncate: OciGenAiEmbeddingTruncate = OciGenAiEmbeddingTruncate.NONE,
    val inputType: OciGenAiEmbeddingInputType? = null,
    val pricingModel: OciGenAiEmbeddingPricingModel? = null,
)

data class OciGenAiEmbeddingPricingModel(
    val usdPer1mTokens: Double,
)

class OciGenAiModelLoader(
    resourceLoader: ResourceLoader = DefaultResourceLoader(),
    configPath: String = DEFAULT_CONFIG_PATH,
) : AbstractYamlModelLoader<OciGenAiModelDefinitions>(resourceLoader, configPath) {

    override fun getProviderClass() = OciGenAiModelDefinitions::class

    override fun createEmptyProvider() = OciGenAiModelDefinitions()

    override fun getProviderName() = "OCI GenAI"

    override fun validateModels(provider: OciGenAiModelDefinitions) {
        provider.models.forEach { model ->
            validateCommonFields(model)
            model.maxTokens?.let {
                require(it > 0) { "Max tokens must be positive for model ${model.name}" }
            }
            model.temperature?.let {
                require(it in 0.0..2.0) { "Temperature must be between 0 and 2 for model ${model.name}" }
            }
            model.topP?.let {
                require(it in 0.0..1.0) { "Top P must be between 0 and 1 for model ${model.name}" }
            }
            model.topK?.let {
                require(it > 0) { "Top K must be positive for model ${model.name}" }
            }
        }

        provider.embeddingModels.forEach { model ->
            require(model.name.isNotBlank()) { "Embedding model name cannot be blank" }
            require(model.modelId.isNotBlank()) { "Embedding model ID cannot be blank" }
            model.dimensions?.let {
                require(it > 0) { "Dimensions must be positive for embedding model ${model.name}" }
            }
            model.pricingModel?.let {
                require(it.usdPer1mTokens >= 0.0) { "Pricing must be non-negative for embedding model ${model.name}" }
            }
        }
    }

    companion object {
        private const val DEFAULT_CONFIG_PATH = "classpath:models/oci-genai-models.yml"
    }
}
