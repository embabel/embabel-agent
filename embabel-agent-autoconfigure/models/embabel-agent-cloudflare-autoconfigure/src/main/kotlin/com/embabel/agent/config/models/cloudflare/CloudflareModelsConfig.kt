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
package com.embabel.agent.config.models.cloudflare

import com.embabel.agent.cloudflare.CloudflareModelFactory
import com.embabel.common.ai.autoconfig.DecisionModelsRegistrar
import com.embabel.common.ai.autoconfig.ProviderInitialization
import com.embabel.common.ai.autoconfig.RegisteredModel
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
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
import java.util.function.Function
import java.util.function.Supplier

/**
 * Spring configuration for the decision models on Cloudflare Workers AI.
 *
 * Extends [CloudflareModelFactory], the way the TypeSafe, Anthropic and OpenAI configurations
 * extend their factories. It defines one decision service bean per catalog model, named after the
 * model, and offers `clef` as the decision and classification default candidate when it defines it.
 * `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID` take precedence over the properties.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CloudflareProperties::class)
class CloudflareModelsConfig(
    properties: CloudflareProperties,
    environment: Environment,
    @Qualifier(AI_MODEL_REST_CLIENT_BUILDER)
    platformBuilders: ObjectProvider<RestClient.Builder>,
    builders: ObjectProvider<RestClient.Builder>,
    registries: ObjectProvider<ObservationRegistry>,
) : CloudflareModelFactory(
    requireAccountId(properties, environment),
    Supplier { requireApiKey(environment) },
    selectedBuilder(platformBuilders, builders, registries),
    registries.getIfUnique { ObservationRegistry.NOOP },
    CLEF,
    properties.maxResponseBytes,
) {

    init {
        requireApiKey(environment)
        logger.info("Cloudflare models are available: {}", properties)
    }

    /**
     * Reports the catalog models [cloudflareModelsRegistrar] defined, the way the chat model
     * starters report theirs. Models the application replaced are left out.
     *
     * @param beanFactory the factory holding the catalog model definitions
     * @return the models registered
     */
    @Bean
    fun cloudflareModelsInitializer(beanFactory: ConfigurableListableBeanFactory): ProviderInitialization {
        val registered = beanFactory.beanDefinitionNames.mapNotNull { name ->
            val definition = beanFactory.getBeanDefinition(name)
            if (definition.getAttribute(DecisionModelsRegistrar.CATALOG_PROVIDER_ATTRIBUTE) != PROVIDER) return@mapNotNull null
            val modelId = definition.getAttribute(DecisionModelsRegistrar.CATALOG_MODEL_ATTRIBUTE) as? String
                ?: return@mapNotNull null
            logger.info("Registered Cloudflare decision model bean: {} -> {}", name, modelId)
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
        fun cloudflareModelsRegistrar(beanFactory: BeanFactory): BeanDefinitionRegistryPostProcessor =
            registrar(catalog(), beanFactory)

        /**
         * Builds the registrar for a catalog. Each model's service is built by the `CloudflareModelFactory`
         * bean when the model bean is created.
         *
         * @param models the catalog to define beans for
         * @param beanFactory the factory the `CloudflareModelFactory` bean is read from
         * @return the registrar
         */
        internal fun registrar(models: List<CloudflareModelDefinition>, beanFactory: BeanFactory): DecisionModelsRegistrar =
            DecisionModelsRegistrar(
                provider = PROVIDER,
                models = models,
                defaultModel = DEFAULT_MODEL,
                defaultCandidate = DEFAULT_CANDIDATE,
                serviceFactory = Function { modelId -> beanFactory.getBean(CloudflareModelFactory::class.java).build(modelId) },
            )

        /**
         * Loads the starter's model catalog.
         *
         * @return the catalog models, in file order
         */
        private fun catalog(): List<CloudflareModelDefinition> = CloudflareModelLoader().loadAutoConfigMetadata().models

        private const val API_KEY_ENVIRONMENT_VARIABLE = "CLOUDFLARE_API_TOKEN"
        private const val ACCOUNT_ID_ENVIRONMENT_VARIABLE = "CLOUDFLARE_ACCOUNT_ID"
        private const val API_KEY_PROPERTY = CloudflareProperties.PREFIX + ".api-key"
        private const val AI_MODEL_REST_CLIENT_BUILDER = "aiModelRestClientBuilder"
        private const val DEFAULT_MODEL = "clef"
        private const val DEFAULT_CANDIDATE = "cloudflareDefaultCandidate"

        private val logger = LoggerFactory.getLogger(CloudflareModelsConfig::class.java)

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
         * Reads the API key, a Cloudflare API token, preferring `CLOUDFLARE_API_TOKEN` over the
         * configured property. Both are read through the environment on every call, so a rotated key
         * takes effect. The error never includes the key.
         *
         * @param environment the Spring environment
         * @return the API key
         * @throws IllegalStateException if neither source has a key
         */
        private fun requireApiKey(environment: Environment): String =
            environment.getProperty(API_KEY_ENVIRONMENT_VARIABLE).takeUnless { it.isNullOrBlank() }
                ?: environment.getProperty(API_KEY_PROPERTY).takeUnless { it.isNullOrBlank() }
                ?: error("Cloudflare API key is required")

        /**
         * Reads the account id, preferring `CLOUDFLARE_ACCOUNT_ID` over the configured property. It is
         * read once, since the account is part of every model's address.
         *
         * @param properties the Cloudflare configuration properties
         * @param environment the Spring environment
         * @return the account id
         * @throws IllegalStateException if neither source has an account id
         */
        private fun requireAccountId(properties: CloudflareProperties, environment: Environment): String =
            environment.getProperty(ACCOUNT_ID_ENVIRONMENT_VARIABLE).takeUnless { it.isNullOrBlank() }
                ?: properties.accountId.takeUnless { it.isNullOrBlank() }
                ?: error("Cloudflare account id is required")
    }
}
