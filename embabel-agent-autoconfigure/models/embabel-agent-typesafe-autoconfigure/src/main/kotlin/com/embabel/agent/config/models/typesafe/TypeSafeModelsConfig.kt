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
import com.embabel.common.ai.model.DecisionService
import io.micrometer.observation.ObservationRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
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
 * application transport selection and the default named decision-service bean.
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

    /** The configured default remains replaceable without suppressing other decision providers. */
    @Bean("typeSafeDecisionService")
    fun typeSafeDecisionService(): DecisionService = build()

    companion object {
        private const val API_KEY_ENVIRONMENT_VARIABLE = "TYPESAFE_API_KEY"
        private const val AI_MODEL_REST_CLIENT_BUILDER = "aiModelRestClientBuilder"

        private val logger = org.slf4j.LoggerFactory.getLogger(TypeSafeModelsConfig::class.java)

        /** Keep property conversion at the configuration edge and native options immutable. */
        private fun options(properties: TypeSafeProperties): TypeSafeClientOptions {
            val defaults = TypeSafeClientOptions.defaults()
            return TypeSafeClientOptions(
                parseBaseUri(properties.baseUrl()),
                defaults.connectTimeout(),
                defaults.readTimeout(),
                properties.maxResponseBytes(),
            )
        }

        /** Translate parsing failures without retaining an endpoint that may contain credentials. */
        private fun parseBaseUri(value: String): URI = try {
            URI.create(value)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("TypeSafe base URL is invalid")
        }

        /** Select and clone the same application transport hierarchy used by other providers. */
        private fun selectedBuilder(
            platformBuilders: ObjectProvider<RestClient.Builder>,
            builders: ObjectProvider<RestClient.Builder>,
            registries: ObjectProvider<ObservationRegistry>,
        ): RestClient.Builder? {
            val selected = platformBuilders.ifUnique ?: builders.ifUnique ?: return null
            val registry = registries.ifUnique ?: return selected
            return selected.clone().observationRegistry(registry)
        }

        /** Resolve the environment key first and never include credential contents in failures. */
        private fun requireApiKey(properties: TypeSafeProperties, environment: Environment): String {
            val environmentKey = environment.getProperty(API_KEY_ENVIRONMENT_VARIABLE)
            return environmentKey.takeUnless { it.isNullOrBlank() }
                ?: properties.apiKey().takeUnless { it.isNullOrBlank() }
                ?: error("TypeSafe API key is required")
        }
    }
}
