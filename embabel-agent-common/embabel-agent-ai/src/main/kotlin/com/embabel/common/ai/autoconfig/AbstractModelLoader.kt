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

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.ResourceLoader

/**
 * Abstract base loader for LLM model definitions from YAML configuration.
 *
 * Provides common logic for loading, parsing, and validating model metadata
 * from YAML files. Subclasses specify the provider type and validation rules.
 *
 * @param T the type of [LlmAutoConfigProvider] to load
 * @property resourceLoader Spring resource loader for accessing classpath resources
 * @property configPath path to the YAML configuration file
 */
abstract class AbstractYamlModelLoader<T : LlmAutoConfigProvider<*>>(
    private val resourceLoader: ResourceLoader = DefaultResourceLoader(),
    private val configPath: String
) : LlmAutoConfigMetadataLoader<T> {

    protected val logger = LoggerFactory.getLogger(this::class.java)

    protected val yamlMapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .findAndRegisterModules()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)

    /**
     * Loads model definitions from YAML configuration.
     *
     * Attempts to read from the configured path. Returns empty definitions
     * if the file is missing or parsing fails. Validates all loaded models.
     *
     * @return provider instance containing all loaded and validated models
     */
    override fun loadAutoConfigMetadata(): T {
        return try {
            val configResource = resourceLoader.getResource(configPath)

            val definitions = if (configResource.exists()) {
                logger.info("Loading {} models from {}", getProviderName(), configPath)
                yamlMapper.readValue(configResource.inputStream, getProviderClass().java)
                    .also { validateModels(it) }
            } else {
                logger.warn("Configuration file {} not found, using empty model list", configPath)
                createEmptyProvider()
            }

            definitions.also {
                logger.info("Loaded {} {} model definitions", it.models.size, getProviderName())
            }
        } catch (e: Exception) {
            logger.error("Failed to load {} models from {}", getProviderName(), configPath, e)
            createEmptyProvider()
        }
    }

    /**
     * Returns the provider class for deserialization.
     *
     * @return the Kotlin class reference for the provider type
     */
    protected abstract fun getProviderClass(): kotlin.reflect.KClass<T>

    /**
     * Creates an empty provider instance when loading fails.
     *
     * @return an empty provider instance
     */
    protected abstract fun createEmptyProvider(): T

    /**
     * Returns the human-readable name of the provider for logging.
     *
     * @return the provider name (e.g., "Anthropic", "OpenAI")
     */
    protected abstract fun getProviderName(): String

    /**
     * Validates the loaded model definitions.
     *
     * Ensures that all required fields are present and within acceptable ranges.
     * Subclasses should implement provider-specific validation logic.
     *
     * @param provider the provider instance to validate
     * @throws IllegalArgumentException if validation fails
     */
    protected abstract fun validateModels(provider: T)

    /**
     * Common validation for all model definitions.
     *
     * Checks basic requirements like non-blank names and IDs.
     *
     * @param model the model metadata to validate
     * @throws IllegalArgumentException if validation fails
     */
    protected fun validateCommonFields(model: LlmAutoConfigMetadata) {
        require(model.name.isNotBlank()) { "Model name cannot be blank" }
        require(model.modelId.isNotBlank()) { "Model ID cannot be blank" }
    }
}
