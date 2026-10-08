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

import com.embabel.agent.config.models.typesafe.TypeSafeServicesRegistrar.ServiceProperties
import com.embabel.agent.typesafe.TypeSafeClientOptions
import com.embabel.agent.typesafe.TypeSafeCredential
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

/**
 * Spring configuration for TypeSafe models.
 *
 * Extends [TypeSafeModelFactory] so native provider construction is shared with the BYOK path,
 * matching the Anthropic and OpenAI provider pattern. This class adds property resolution,
 * application transport selection, the default named decision-service bean, its default
 * candidate and the named services configured under `services`.
 *
 * The TypeSafe cloud always needs a credential. A compatible server at any other `base-url` can
 * run without one, and then requests go out with no `Authorization` header at all.
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
    options(properties.baseUrl(), properties.maxResponseBytes()),
    credential(properties, environment),
    selectedBuilder(platformBuilders, builders, registries),
    registries.getIfUnique { ObservationRegistry.NOOP },
    properties.model(),
    requireProvider(properties),
) {

    // Kept so the named services that override something can build factories of their own on
    // the same transport. Selecting again is cheap: it only clones a builder.
    private val transport: RestClient.Builder? = selectedBuilder(platformBuilders, builders, registries)
    private val registry: ObservationRegistry = registries.getIfUnique { ObservationRegistry.NOOP }
    private val sharedCredential: TypeSafeCredential = credential(properties, environment)

    init {
        if (sharedCredential.isAnonymous) {
            logger.info("TypeSafe decision services call {} without a credential", "[CONFIGURED]")
        }
        logger.info("TypeSafe models are available: {}", properties)
    }

    /**
     * Builds the decision service for one entry under `services`.
     *
     * An entry that only names a model shares this factory. One that sets its own `base-url`,
     * `api-key` or `provider` gets a factory of its own on the same transport. An entry with its
     * own `base-url` only ever sends its own `api-key`, so the shared credential never reaches
     * another server; without one it calls that server anonymously, unless the server is the
     * TypeSafe cloud.
     *
     * @param key the entry's key, used in error messages
     * @param service the entry's configured values
     * @return the decision service for the entry
     * @throws IllegalStateException if a value is blank, or the entry points at the cloud without its own key
     */
    internal fun build(key: String, service: ServiceProperties): DecisionService {
        val property = "${TypeSafeServicesRegistrar.PREFIX}.$key"
        val model = service.model?.takeIf { it.isNotBlank() }
            ?: error("$property.model must name a TypeSafe model")
        val baseUrl = service.baseUrl?.let { nonBlank(it, "$property.base-url") }
        val apiKey = service.apiKey?.let { nonBlank(it, "$property.api-key") }
        val provider = service.provider?.let { nonBlank(it, "$property.provider") }
        if (baseUrl == null && apiKey == null && provider == null) {
            return build(model)
        }
        val entryOptions = options(baseUrl ?: properties.baseUrl(), properties.maxResponseBytes())
        val entryCredential = when {
            apiKey != null -> TypeSafeCredential.of { apiKey }
            baseUrl == null -> sharedCredential
            isCloudEndpoint(entryOptions.baseUri()) ->
                error("$property.api-key is required when $property.base-url is the TypeSafe cloud")
            else -> TypeSafeCredential.none()
        }
        if (entryCredential.isAnonymous) {
            logger.info("TypeSafe service '{}' calls {} without a credential", key, "[CONFIGURED]")
        }
        return TypeSafeModelFactory(
            entryOptions,
            entryCredential,
            transport,
            registry,
            model,
            provider ?: providerName,
        ).build()
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
         * Builds the client options for one endpoint, keeping the default timeouts.
         *
         * @param baseUrl the configured base URL
         * @param maxResponseBytes the response size limit
         * @return the client options
         */
        private fun options(baseUrl: String, maxResponseBytes: Int): TypeSafeClientOptions {
            val defaults = TypeSafeClientOptions.defaults()
            return TypeSafeClientOptions(
                parseBaseUri(baseUrl),
                defaults.connectTimeout(),
                defaults.readTimeout(),
                maxResponseBytes,
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
         * Works out how the default services authenticate.
         *
         * With a key in the environment variable or the property, the credential reads the key
         * again for every request, preferring the environment, so a rotated key takes effect.
         * Without either, only an endpoint other than the TypeSafe cloud may run anonymously; the
         * cloud fails here instead of quietly sending unauthenticated calls. Errors never include
         * the key or the URL.
         *
         * @param properties the TypeSafe configuration properties
         * @param environment the Spring environment
         * @return the credential for the default endpoint
         * @throws IllegalStateException if there is no key and the endpoint is the TypeSafe cloud
         */
        private fun credential(properties: TypeSafeProperties, environment: Environment): TypeSafeCredential {
            val key = {
                environment.getProperty(API_KEY_ENVIRONMENT_VARIABLE).takeUnless { it.isNullOrBlank() }
                    ?: properties.apiKey().takeUnless { it.isNullOrBlank() }
            }
            return when {
                key() != null -> TypeSafeCredential.of { key() ?: error("TypeSafe API key is required") }
                !isCloudEndpoint(parseBaseUri(properties.baseUrl())) -> TypeSafeCredential.none()
                else -> error("TypeSafe API key is required")
            }
        }

        /**
         * Tells whether a base URI is the TypeSafe cloud. Scheme and host match regardless of case,
         * a missing port means the scheme's default, and an empty path is the same as `/`, so
         * `HTTPS://API.TYPESAFE.AI/` and `https://api.typesafe.ai:443` both count.
         *
         * @param uri the configured base URI
         * @return true if requests to it would reach the TypeSafe cloud
         */
        private fun isCloudEndpoint(uri: URI): Boolean {
            val cloud = TypeSafeClientOptions.defaults().baseUri()
            fun port(u: URI): Int = when {
                u.port != -1 -> u.port
                u.scheme.equals("https", ignoreCase = true) -> 443
                u.scheme.equals("http", ignoreCase = true) -> 80
                else -> -1
            }
            fun path(u: URI): String = u.rawPath.orEmpty().ifEmpty { "/" }
            return uri.scheme.equals(cloud.scheme, ignoreCase = true) &&
                uri.host.equals(cloud.host, ignoreCase = true) &&
                port(uri) == port(cloud) &&
                path(uri) == path(cloud)
        }

        /**
         * Reads the provider name the default services report.
         *
         * @param properties the TypeSafe configuration properties
         * @return the provider name
         * @throws IllegalStateException if it is blank
         */
        private fun requireProvider(properties: TypeSafeProperties): String =
            nonBlank(properties.provider().orEmpty(), "${TypeSafeProperties.PREFIX}.provider")

        /**
         * Returns the value, or fails naming the property when it is blank.
         *
         * @param value the configured value
         * @param property the full property path, used in the error message
         * @return the value
         * @throws IllegalStateException if the value is blank
         */
        private fun nonBlank(value: String, property: String): String =
            value.takeIf { it.isNotBlank() } ?: error("$property must not be blank")
    }
}
