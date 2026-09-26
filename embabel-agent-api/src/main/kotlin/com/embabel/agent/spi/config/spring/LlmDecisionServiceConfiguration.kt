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
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.bind.BindHandler
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler
import org.springframework.boot.context.properties.source.UnboundElementsSourceFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * Supplies the `LlmDecisionServiceFactory` bean and registers a decision or classification service
 * bean for each entry under `embabel.agent.platform.decisions.llm.services`. The entry's key is the
 * bean name.
 *
 * ```yaml
 * embabel:
 *   agent:
 *     platform:
 *       decisions:
 *         llm:
 *           max-attempts: 5
 *           services:
 *             triage:
 *               llm: small-chat-model
 *             routing:
 *               llm: fast-chat-model
 *               kind: classification
 *               max-attempts: 3
 * ```
 *
 * The retry settings `max-attempts`, `backoff-millis`, `backoff-multiplier` and
 * `backoff-max-interval` apply to the factory bean at the top level. An entry takes each one it
 * leaves unset from the top level.
 *
 * Startup fails if an entry names no LLM or one the model provider doesn't know, if an entry has a
 * key this class doesn't know, or if a retry setting is out of range. The error names the property.
 * Unknown keys are only caught in configuration files and other property sources. Environment
 * variables and system properties are not checked for them.
 */
@Configuration(proxyBeanMethods = false)
internal class LlmDecisionServiceConfiguration {

    /**
     * The factory applications inject. Configured services are built by this bean too, so an
     * application that supplies its own factory changes them as well.
     */
    @Bean
    @ConditionalOnMissingBean
    fun llmDecisionServiceFactory(
        llmOperations: LlmOperations,
        modelProvider: ModelProvider,
        observationRegistry: ObjectProvider<ObservationRegistry>,
        environment: Environment,
    ): LlmDecisionServiceFactory = LlmDecisionServiceFactory(
        llmOperations = llmOperations,
        modelProvider = modelProvider,
        observationRegistry = observationRegistry.getIfUnique { ObservationRegistry.NOOP },
        retry = bindRetry(environment),
    )

    /**
     * One declared service. A retry field left unset takes the top-level value.
     *
     * @property llm name of the model that answers
     * @property kind whether the bean is a decision service or only classifies
     */
    data class ServiceProperties(
        val llm: String? = null,
        val kind: Kind = Kind.DECISION,
        val maxAttempts: Int? = null,
        val backoffMillis: Long? = null,
        val backoffMultiplier: Double? = null,
        val backoffMaxInterval: Long? = null,
    ) {

        /**
         * The retry settings for the entry with this key, filling unset fields from [defaults].
         *
         * @throws IllegalStateException if a setting is out of range, naming the entry
         */
        fun retry(key: String, defaults: LlmDecisionRetryProperties): LlmDecisionRetryProperties {
            val prefix = "$SERVICES_PREFIX.$key"
            return try {
                LlmDecisionRetryProperties(
                    maxAttempts = maxAttempts ?: defaults.maxAttempts,
                    backoffMillis = backoffMillis ?: defaults.backoffMillis,
                    backoffMultiplier = backoffMultiplier ?: defaults.backoffMultiplier,
                    backoffMaxInterval = backoffMaxInterval ?: defaults.backoffMaxInterval,
                    propertyPrefix = prefix,
                )
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("$prefix: ${e.message}", e)
            }
        }
    }

    enum class Kind { DECISION, CLASSIFICATION }

    companion object {

        const val PREFIX = "embabel.agent.platform.decisions.llm"

        const val SERVICES_PREFIX = "$PREFIX.services"

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
        ): BeanDefinitionRegistryPostProcessor = Registrar(bindServices(environment), bindRetry(environment), beanFactory)

        /**
         * Binds the declared services. A key that no service field matches fails the binding,
         * unless it comes from an environment variable or a system property.
         */
        fun bindServices(environment: Environment): Map<String, ServiceProperties> =
            Binder.get(environment)
                .bind(
                    SERVICES_PREFIX,
                    Bindable.mapOf(String::class.java, ServiceProperties::class.java),
                    NoUnboundElementsBindHandler(BindHandler.DEFAULT, UnboundElementsSourceFilter()),
                )
                .orElse(emptyMap())

        /**
         * Binds the top-level retry settings. Each field is bound on its own so the services under
         * the same prefix are left to [bindServices].
         *
         * @throws IllegalStateException if a setting is out of range, naming the prefix
         */
        fun bindRetry(environment: Environment): LlmDecisionRetryProperties {
            val binder = Binder.get(environment)
            fun <T : Any> field(name: String, type: Class<T>, default: T): T =
                binder.bind("$PREFIX.$name", type).orElse(default)
            return try {
                LlmDecisionRetryProperties(
                    maxAttempts = field("max-attempts", Int::class.javaObjectType, DEFAULT_RETRY.maxAttempts),
                    backoffMillis = field("backoff-millis", Long::class.javaObjectType, DEFAULT_RETRY.backoffMillis),
                    backoffMultiplier = field(
                        "backoff-multiplier",
                        Double::class.javaObjectType,
                        DEFAULT_RETRY.backoffMultiplier,
                    ),
                    backoffMaxInterval = field(
                        "backoff-max-interval",
                        Long::class.javaObjectType,
                        DEFAULT_RETRY.backoffMaxInterval,
                    ),
                    propertyPrefix = PREFIX,
                )
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("$PREFIX: ${e.message}", e)
            }
        }
    }

    private class Registrar(
        private val services: Map<String, ServiceProperties>,
        private val defaults: LlmDecisionRetryProperties,
        private val beanFactory: BeanFactory,
    ) : BeanDefinitionRegistryPostProcessor {

        override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
            services.forEach { (key, service) -> registry.registerBeanDefinition(key, definition(key, service)) }
        }

        // The definition names the service type so the model provider's search for LLM beans can
        // skip it without creating it. It is eager, so an unknown LLM stops startup even when the
        // application makes beans lazy by default. The retry settings are checked here, before any
        // bean is created.
        private fun definition(key: String, service: ServiceProperties): BeanDefinition {
            val llm = service.llm?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("$SERVICES_PREFIX.$key.llm must name an LLM")
            val retry = service.retry(key, defaults)
            val builder = when (service.kind) {
                Kind.DECISION -> BeanDefinitionBuilder.genericBeanDefinition(DecisionService::class.java) {
                    build(key, llm) { it.decisionService(llm, retry, "decision-$key") }
                }

                Kind.CLASSIFICATION -> BeanDefinitionBuilder.genericBeanDefinition(ClassificationService::class.java) {
                    build(key, llm) { it.classificationService(llm, retry, "classification-$key") }
                }
            }
            return builder.setLazyInit(false).beanDefinition
        }

        // The factory bean builds every configured service, so a factory the application supplies
        // builds them as well.
        private fun <T> build(key: String, llm: String, create: (LlmDecisionServiceFactory) -> T): T {
            val factory = beanFactory.getBean(LlmDecisionServiceFactory::class.java)
            return try {
                create(factory)
            } catch (e: NoSuitableModelException) {
                throw IllegalStateException("$SERVICES_PREFIX.$key.llm names unknown LLM '$llm'", e)
            }
        }
    }
}
