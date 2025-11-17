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
package com.embabel.agent.config.models.bedrock

import com.embabel.common.ai.autoconfig.AbstractYamlModelLoader
import com.embabel.common.ai.autoconfig.LlmAutoConfigMetadata
import com.embabel.common.ai.autoconfig.LlmAutoConfigProvider
import com.embabel.common.ai.model.PerTokenPricingModel
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.ResourceLoader
import java.time.LocalDate

/**
 * Container for Bedrock model definitions loaded from YAML.
 *
 * Implements [LlmAutoConfigProvider] to supply Bedrock-specific model metadata
 * for auto-configuration purposes.
 *
 * @property models list of Bedrock LLM model definitions
 * @property embeddingModels list of Bedrock embedding model definitions
 */
data class BedrockModelDefinitions(
    override val models: List<BedrockModelDefinition> = emptyList(),
    val embeddingModels: List<BedrockEmbeddingModelDefinition> = emptyList()
) : LlmAutoConfigProvider<BedrockModelDefinition>

/**
 * Bedrock-specific LLM model definition.
 *
 * Implements [LlmAutoConfigMetadata] with Bedrock-specific features
 * like regional deployment.
 *
 * @property name the unique name of the model
 * @property modelId the Bedrock model identifier (e.g., us.anthropic.claude-3-5-sonnet-20241022-v2:0)
 * @property displayName optional human-readable name
 * @property region the AWS region prefix (us, eu, apac)
 * @property knowledgeCutoffDate optional knowledge cutoff date
 * @property pricingModel optional per-token pricing information
 */
data class BedrockModelDefinition(
    override val name: String,
    override val modelId: String,
    override val displayName: String? = null,
    val region: String,
    override val knowledgeCutoffDate: LocalDate? = null,
    override val pricingModel: PerTokenPricingModel? = null,
) : LlmAutoConfigMetadata

/**
 * Bedrock embedding model definition.
 *
 * @property name the unique name of the model
 * @property modelId the model identifier
 * @property displayName optional human-readable name
 * @property modelType the type of embedding model (titan or cohere)
 */
data class BedrockEmbeddingModelDefinition(
    val name: String,
    val modelId: String,
    val displayName: String? = null,
    val modelType: String
)

/**
 * Loader for Bedrock model definitions from YAML configuration.
 *
 * Reads model metadata from the configured resource path (default: `classpath:models/bedrock-models.yml`)
 * and deserializes it into [BedrockModelDefinitions]. Validates loaded models to ensure data integrity.
 *
 * @property resourceLoader Spring resource loader for accessing classpath resources
 * @property configPath path to the YAML configuration file
 */
class BedrockModelLoader(
    resourceLoader: ResourceLoader = DefaultResourceLoader(),
    configPath: String = DEFAULT_CONFIG_PATH
) : AbstractYamlModelLoader<BedrockModelDefinitions>(resourceLoader, configPath) {

    override fun getProviderClass() = BedrockModelDefinitions::class

    override fun createEmptyProvider() = BedrockModelDefinitions()

    override fun getProviderName() = "Bedrock"

    override fun validateModels(provider: BedrockModelDefinitions) {
        // Validate LLM models
        provider.models.forEach { model ->
            validateCommonFields(model)
            require(model.region in VALID_REGIONS) {
                "Region must be one of ${VALID_REGIONS.joinToString(", ")} for model ${model.name}, got: ${model.region}"
            }
            require(model.modelId.startsWith("${model.region}.")) {
                "Model ID must start with region prefix '${model.region}.' for model ${model.name}"
            }
        }

        // Validate embedding models
        provider.embeddingModels.forEach { model ->
            require(model.name.isNotBlank()) { "Embedding model name cannot be blank" }
            require(model.modelId.isNotBlank()) { "Embedding model ID cannot be blank" }
            require(model.modelType in VALID_EMBEDDING_TYPES) {
                "Model type must be one of ${VALID_EMBEDDING_TYPES.joinToString(", ")} for model ${model.name}, got: ${model.modelType}"
            }
        }
    }

    companion object {
        /**
         * Default path to the Bedrock models YAML configuration file.
         */
        private const val DEFAULT_CONFIG_PATH = "classpath:models/bedrock-models.yml"

        /**
         * Valid AWS region prefixes for Bedrock models.
         */
        private val VALID_REGIONS = setOf("us", "eu", "apac")

        /**
         * Valid embedding model types.
         */
        private val VALID_EMBEDDING_TYPES = setOf("titan", "cohere")
    }
}
