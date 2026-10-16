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
package com.embabel.agent.config.models.typesafe

import com.embabel.common.ai.autoconfig.AbstractYamlModelLoader
import com.embabel.common.ai.autoconfig.LlmAutoConfigMetadata
import com.embabel.common.ai.autoconfig.LlmAutoConfigProvider
import com.embabel.common.ai.model.PerTokenPricingModel
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.ResourceLoader
import java.time.LocalDate

/**
 * TypeSafe decision models loaded from YAML.
 *
 * @property models the catalog entries, in file order
 */
data class TypeSafeModelDefinitions(
    override val models: List<TypeSafeModelDefinition> = emptyList(),
) : LlmAutoConfigProvider<TypeSafeModelDefinition>

/**
 * One TypeSafe decision model. Decision models have no knowledge cutoff or token pricing, so those
 * fields stay null.
 *
 * @property name the bean and registry name
 * @property modelId the TypeSafe model identifier or alias sent to the provider
 * @property displayName optional human-readable name
 */
data class TypeSafeModelDefinition(
    override val name: String,
    override val modelId: String,
    override val displayName: String? = null,
) : LlmAutoConfigMetadata {
    override val knowledgeCutoffDate: LocalDate? get() = null
    override val pricingModel: PerTokenPricingModel? get() = null
}

/**
 * Loads the TypeSafe model catalog, `classpath:models/typesafe-models.yml` by default.
 *
 * The catalog is the only source of TypeSafe model beans, so a missing, unreadable, invalid or empty
 * catalog fails startup with the resource path in the message. The chat model loaders log and fall
 * back to an empty catalog.
 *
 * @param resourceLoader loader for the catalog resource
 * @param configPath location of the catalog
 */
class TypeSafeModelLoader @JvmOverloads constructor(
    private val resourceLoader: ResourceLoader = DefaultResourceLoader(),
    private val configPath: String = DEFAULT_CONFIG_PATH,
) : AbstractYamlModelLoader<TypeSafeModelDefinitions>(resourceLoader, configPath) {

    /**
     * Reads and validates the catalog.
     *
     * @return the catalog
     * @throws IllegalStateException when the catalog is missing, unreadable, invalid or empty
     */
    override fun loadAutoConfigMetadata(): TypeSafeModelDefinitions {
        val resource = resourceLoader.getResource(configPath)
        check(resource.exists()) { "TypeSafe model catalog $configPath not found" }
        return try {
            resource.inputStream.use { yamlMapper.readValue(it, TypeSafeModelDefinitions::class.java) }
                .also { validateModels(it) }
        } catch (e: Exception) {
            throw IllegalStateException("TypeSafe model catalog $configPath is invalid: ${e.message}", e)
        }
    }

    override fun getProviderClass() = TypeSafeModelDefinitions::class

    override fun createEmptyProvider() = TypeSafeModelDefinitions()

    override fun getProviderName() = "TypeSafe"

    override fun validateModels(provider: TypeSafeModelDefinitions) {
        require(provider.models.isNotEmpty()) { "the catalog defines no models" }
        provider.models.forEach(::validateCommonFields)
        val repeated = provider.models.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        require(repeated.isEmpty()) { "TypeSafe model names must be unique. Repeated names: $repeated" }
    }

    companion object {
        private const val DEFAULT_CONFIG_PATH = "classpath:models/typesafe-models.yml"
    }
}
