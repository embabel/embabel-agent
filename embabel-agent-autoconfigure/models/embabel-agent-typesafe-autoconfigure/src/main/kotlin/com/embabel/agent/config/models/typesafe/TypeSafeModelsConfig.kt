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
import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.model.DecisionServiceRegistry
import io.micrometer.observation.ObservationRegistry
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.web.client.RestClient
import java.net.URI
import java.util.function.Supplier

/**
 * Spring configuration for TypeSafe models.
 *
 * Extends [TypeSafeModelFactory] so native provider construction is shared with the BYOK path,
 * matching the Anthropic and OpenAI provider pattern. This class adds property resolution,
 * application transport selection, the default named decision-service bean, its default
 * candidate and the named services configured under `services`.
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
    properties.model(),
) {

    init {
        requireApiKey(properties, environment)
        logger.info("TypeSafe models are available: {}", properties)
    }

    /**
     * Defines the default TypeSafe decision service and offers it as the decision and
     * classification family default. An application bean named `typeSafeDecisionService` replaces
     * both. The named services under `services` still register.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(name = [DEFAULT_SERVICE])
    class DefaultServiceConfiguration {

        /** Builds the default service from the configured model. */
        @Bean(DEFAULT_SERVICE)
        fun typeSafeDecisionService(factory: TypeSafeModelsConfig): DecisionService = factory.build()

        /** Offers `typeSafeDecisionService` as the decision and classification family default. */
        @Bean
        fun typeSafeDefaultCandidate(): DecisionServiceRegistry.DefaultCandidate =
            DecisionServiceRegistry.DefaultCandidate(DEFAULT_SERVICE)
    }

    companion object {

        /**
         * Registers the services configured under `embabel.agent.platform.models.typesafe.services`.
         * Static, so Spring creates it before ordinary beans and the service definitions exist before
         * anything that injects them by name.
         */
        @JvmStatic
        @Bean
        fun typeSafeServicesRegistrar(
            environment: Environment,
            beanFactory: BeanFactory,
        ): BeanDefinitionRegistryPostProcessor =
            TypeSafeServicesRegistrar(TypeSafeServicesRegistrar.bind(environment), beanFactory)

        /** Bean name of the default TypeSafe decision service. */
        const val DEFAULT_SERVICE = "typeSafeDecisionService"

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
