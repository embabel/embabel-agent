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

import com.embabel.agent.typesafe.TypeSafeClientOptions
import com.embabel.agent.typesafe.TypeSafeModelFactory
import com.embabel.common.ai.autoconfig.ProviderInitialization
import com.embabel.common.ai.autoconfig.RegisteredModel
import io.micrometer.observation.ObservationRegistry
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.web.client.RestClient
import java.net.URI
import java.util.function.Supplier

/**
 * Spring configuration for TypeSafe decision models.
 *
 * Extends [TypeSafeModelFactory] so native provider construction is shared with the BYOK path, as the
 * Anthropic and OpenAI configurations do. It defines one decision service bean per catalog model,
 * named after the model, and offers [TypeSafeModelFactory.DEFAULT_MODEL] as the decision and
 * classification default candidate when it defines that model. `TYPESAFE_API_KEY` takes precedence
 * over the configured key.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TypeSafeProperties::class)
class TypeSafeModelsConfig(
    private val properties: TypeSafeProperties,
    private val environment: Environment,
    @Qualifier(AI_MODEL_REST_CLIENT_BUILDER)
    platformBuilders: ObjectProvider<RestClient.Builder>,
    builders: ObjectProvider<RestClient.Builder>,
    registries: ObjectProvider<ObservationRegistry>,
) : TypeSafeModelFactory(
    options(properties),
    Supplier { requireApiKey(properties, environment) },
    selectedBuilder(platformBuilders, builders, registries),
    registries.getIfUnique { ObservationRegistry.NOOP },
) {

    init {
        requireApiKey(properties, environment)
        logger.info("TypeSafe models are available: {}", properties)
    }

    /**
     * Reports the catalog models [typeSafeModelsRegistrar] defined, the way the chat model starters
     * report theirs. Models the application replaced are left out.
     *
     * @param beanFactory the factory holding the catalog model definitions
     * @return the models registered
     */
    @Bean
    fun typeSafeModelsInitializer(beanFactory: ConfigurableListableBeanFactory): ProviderInitialization {
        val registered = beanFactory.beanDefinitionNames.mapNotNull { name ->
            val modelId = beanFactory.getBeanDefinition(name)
                .getAttribute(TypeSafeModelsRegistrar.CATALOG_MODEL_ATTRIBUTE) as? String ?: return@mapNotNull null
            logger.info("Registered TypeSafe decision model bean: {} -> {}", name, modelId)
            RegisteredModel(beanName = name, modelId = modelId)
        }
        return ProviderInitialization(
            provider = PROVIDER,
            registeredLlms = emptyList(),
            registeredDecisionModels = registered,
        ).also { logger.info(it.summary()) }
    }

    companion object {

        /**
         * Defines the catalog model beans. Static, so the definitions exist before anything that
         * injects a model by type or name.
         */
        @JvmStatic
        @Bean
        fun typeSafeModelsRegistrar(beanFactory: BeanFactory): BeanDefinitionRegistryPostProcessor =
            TypeSafeModelsRegistrar(catalog(), beanFactory)

        /**
         * Loads the starter's model catalog.
         *
         * @return the catalog models, in file order
         */
        private fun catalog(): List<TypeSafeModelDefinition> = TypeSafeModelLoader().loadAutoConfigMetadata().models

        private const val API_KEY_ENVIRONMENT_VARIABLE = "TYPESAFE_API_KEY"
        private const val AI_MODEL_REST_CLIENT_BUILDER = "aiModelRestClientBuilder"

        private val logger = org.slf4j.LoggerFactory.getLogger(TypeSafeModelsConfig::class.java)

        /**
         * Builds the client options from configuration, keeping the default timeouts.
         *
         * @param properties the TypeSafe configuration properties
         * @return the client options
         */
        private fun options(properties: TypeSafeProperties): TypeSafeClientOptions {
            val defaults = TypeSafeClientOptions.defaults()
            return TypeSafeClientOptions(
                parseBaseUri(properties.baseUrl()),
                defaults.connectTimeout(),
                defaults.readTimeout(),
                properties.maxResponseBytes(),
            )
        }

        /**
         * Parses the base URL. The error leaves the URL out, since it may hold credentials.
         *
         * @param value the configured base URL
         * @return the parsed URI
         * @throws IllegalArgumentException if the URL is invalid
         */
        private fun parseBaseUri(value: String): URI = try {
            URI.create(value)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("TypeSafe base URL is invalid")
        }

        /**
         * Picks the same REST client builder the other model providers use, and adds the observation
         * registry to a copy of it when there is exactly one.
         *
         * @param platformBuilders the platform's model REST client builder
         * @param builders any other REST client builders in the context
         * @param registries the observation registries in the context
         * @return the builder to use, or null to use the client's own
         */
        private fun selectedBuilder(
            platformBuilders: ObjectProvider<RestClient.Builder>,
            builders: ObjectProvider<RestClient.Builder>,
            registries: ObjectProvider<ObservationRegistry>,
        ): RestClient.Builder? {
            val selected = platformBuilders.ifUnique ?: builders.ifUnique ?: return null
            val registry = registries.ifUnique ?: return selected
            return selected.clone().observationRegistry(registry)
        }

        /**
         * Reads the API key, preferring the environment variable over the configured property. The
         * error never includes the key.
         *
         * @param properties the TypeSafe configuration properties
         * @param environment the Spring environment
         * @return the API key
         * @throws IllegalStateException if neither source has a key
         */
        private fun requireApiKey(properties: TypeSafeProperties, environment: Environment): String {
            val environmentKey = environment.getProperty(API_KEY_ENVIRONMENT_VARIABLE)
            return environmentKey.takeUnless { it.isNullOrBlank() }
                ?: properties.apiKey().takeUnless { it.isNullOrBlank() }
                ?: error("TypeSafe API key is required")
        }
    }
}
