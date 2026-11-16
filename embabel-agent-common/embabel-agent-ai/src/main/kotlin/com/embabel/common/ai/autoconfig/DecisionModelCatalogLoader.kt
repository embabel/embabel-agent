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
package com.embabel.common.ai.autoconfig

import org.springframework.core.io.ResourceLoader

/**
 * Loads a decision model catalog from YAML and refuses to start without a usable one.
 *
 * A decision provider gets its model beans only from its catalog, so unlike the chat model loaders,
 * which log and fall back to an empty catalog, this one fails when the catalog is missing,
 * unreadable, invalid or empty, or repeats a model name. Every failure message names the resource
 * path. Subclasses only say which provider they are and which definitions class to read.
 *
 * @param T the catalog type
 * @param resourceLoader loader for the catalog resource
 * @param configPath location of the catalog
 */
abstract class DecisionModelCatalogLoader<T : LlmAutoConfigProvider<*>>(
    private val resourceLoader: ResourceLoader,
    private val configPath: String,
) : AbstractYamlModelLoader<T>(resourceLoader, configPath) {

    /**
     * Reads and validates the catalog.
     *
     * @return the catalog
     * @throws IllegalStateException when the catalog is missing, unreadable, invalid or empty
     */
    final override fun loadAutoConfigMetadata(): T {
        val resource = resourceLoader.getResource(configPath)
        check(resource.exists()) { "${getProviderName()} model catalog $configPath not found" }
        return try {
            resource.inputStream.use { yamlMapper.readValue(it, getProviderClass().java) }
                .also { validateModels(it) }
        } catch (e: Exception) {
            throw IllegalStateException("${getProviderName()} model catalog $configPath is invalid: ${e.message}", e)
        }
    }

    final override fun validateModels(provider: T) {
        require(provider.models.isNotEmpty()) { "the catalog defines no models" }
        provider.models.forEach(::validateCommonFields)
        val dupModelNames = provider.models.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        require(dupModelNames.isEmpty()) { "${getProviderName()} model names must be unique. Repeated names: $dupModelNames" }
    }
}
