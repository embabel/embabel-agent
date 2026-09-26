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
package com.embabel.agent.spi.config.spring

import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.spi.decision.LlmDecisionRetryProperties
import com.embabel.agent.spi.decision.LlmDecisionServiceFactory
import com.embabel.common.ai.model.ClassificationService
import com.embabel.common.ai.model.DecisionService
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.ai.model.NoSuitableModelException
import io.micrometer.observation.ObservationRegistry
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * Registers a decision or classification service bean for each entry under
 * `embabel.agent.platform.decisions.llm.services`. The entry's key is the bean name.
 *
 * ```yaml
 * embabel:
 *   agent:
 *     platform:
 *       decisions:
 *         llm:
 *           services:
 *             triage:
 *               llm: gpt-4.1-mini
 *             routing:
 *               llm: gpt-4.1-nano
 *               kind: classification
 *               max-attempts: 3
 * ```
 *
 * Startup fails if an entry names no LLM or one the model provider doesn't know.
 */
@Configuration(proxyBeanMethods = false)
internal class LlmDecisionServiceConfiguration {

    /**
     * One declared service.
     *
     * @property llm name of the model that answers
     * @property kind whether the bean is a decision service or only classifies
     */
    data class ServiceProperties(
        val llm: String? = null,
        val kind: Kind = Kind.DECISION,
        val maxAttempts: Int = DEFAULT_RETRY.maxAttempts,
        val backoffMillis: Long = DEFAULT_RETRY.backoffMillis,
        val backoffMultiplier: Double = DEFAULT_RETRY.backoffMultiplier,
        val backoffMaxInterval: Long = DEFAULT_RETRY.backoffMaxInterval,
    ) {
        fun retry(key: String) = LlmDecisionRetryProperties(
            maxAttempts = maxAttempts,
            backoffMillis = backoffMillis,
            backoffMultiplier = backoffMultiplier,
            backoffMaxInterval = backoffMaxInterval,
            propertyPrefix = "$PREFIX.$key",
        )
    }

    enum class Kind { DECISION, CLASSIFICATION }

    companion object {

        const val PREFIX = "embabel.agent.platform.decisions.llm.services"

        private val DEFAULT_RETRY = LlmDecisionRetryProperties()

        /**
         * Static, so Spring creates it before any ordinary bean and the service definitions exist
         * before anything that injects them by name.
         */
        @JvmStatic
        @Bean
        fun llmDecisionServiceRegistrar(
            environment: Environment,
            beanFactory: BeanFactory,
        ): BeanDefinitionRegistryPostProcessor = Registrar(bindServices(environment), beanFactory)

        fun bindServices(environment: Environment): Map<String, ServiceProperties> =
            Binder.get(environment)
                .bind(PREFIX, Bindable.mapOf(String::class.java, ServiceProperties::class.java))
                .orElse(emptyMap())
    }

    private class Registrar(
        private val services: Map<String, ServiceProperties>,
        private val beanFactory: BeanFactory,
    ) : BeanDefinitionRegistryPostProcessor {

        override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
            services.forEach { (key, service) -> registry.registerBeanDefinition(key, definition(key, service)) }
        }

        // The definition names the service type so the model provider's search for LLM beans can
        // skip it without creating it. It is eager, so an unknown LLM stops startup even when the
        // application makes beans lazy by default.
        private fun definition(key: String, service: ServiceProperties): BeanDefinition {
            val llm = service.llm?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("$PREFIX.$key.llm must name an LLM")
            val builder = when (service.kind) {
                Kind.DECISION -> BeanDefinitionBuilder.genericBeanDefinition(DecisionService::class.java) {
                    build(key, llm, service) { it.decisionService(llm) }
                }

                Kind.CLASSIFICATION -> BeanDefinitionBuilder.genericBeanDefinition(ClassificationService::class.java) {
                    build(key, llm, service) { it.classificationService(llm) }
                }
            }
            return builder.setLazyInit(false).beanDefinition
        }

        private fun <T> build(
            key: String,
            llm: String,
            service: ServiceProperties,
            create: (LlmDecisionServiceFactory) -> T,
        ): T {
            val factory = LlmDecisionServiceFactory(
                llmOperations = beanFactory.getBean(LlmOperations::class.java),
                modelProvider = beanFactory.getBean(ModelProvider::class.java),
                observationRegistry = beanFactory.getBeanProvider(ObservationRegistry::class.java)
                    .getIfUnique { ObservationRegistry.NOOP },
                retry = service.retry(key),
            )
            return try {
                create(factory)
            } catch (e: NoSuitableModelException) {
                throw IllegalStateException("$PREFIX.$key.llm names unknown LLM '$llm'", e)
            }
        }
    }
}
