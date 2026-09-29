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
package com.embabel.agent.autoconfigure.platform

import com.embabel.common.ai.classification.ClassificationService
import com.embabel.common.ai.decision.spi.DecisionContentCapture
import com.embabel.common.ai.model.DecisionServiceRegistry
import io.micrometer.observation.ObservationRegistry
import org.jetbrains.annotations.ApiStatus
import org.slf4j.LoggerFactory
import org.springframework.aop.scope.ScopedProxyUtils
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy
import org.springframework.core.env.Environment
import java.util.IdentityHashMap

/**
 * Builds the application's [DecisionServiceRegistry] from the decision and classification service
 * beans in the context, and applies the decision content capture property.
 *
 * Every `ClassificationService` bean registers under its bean name. That includes the services the
 * prompted and Jev registrars define from configuration. The family defaults and roles come from
 * these properties:
 *
 * ```yaml
 * embabel:
 *   models:
 *     decision:
 *       default: jev
 *       roles:
 *         support-triage: jev
 *     classification:
 *       roles:
 *         dice-revision: llm-review
 * ```
 *
 * Every `DecisionServiceRegistry.DefaultCandidate` bean is passed to the registry as a default
 * candidate, and the context's `ObservationRegistry` becomes the registry's observation registry.
 *
 * One service instance exposed under several bean names, for example through a `@Primary` bean
 * that returns another bean, registers once. It takes the name of its `@Primary` bean, or else its
 * first bean name. A default, role or default candidate that names one of the other bean names
 * resolves to that registration. The other names are logged at DEBUG.
 *
 * A service bean marked `@Lazy`, or one that is not a singleton, such as a prototype, is not created
 * here and is not registered. One INFO line names it. Pass it to `using(service)` to call it.
 *
 * The registry is a snapshot taken when its bean is created. It holds the service bean definitions
 * and the singletons registered before that point. A service registered later is not visible to
 * lookups; pass it to `using(service)`.
 *
 * Startup fails when a default or role names a missing service, or when a decision default or role
 * names a classification-only service. The error names the property. A missing or ambiguous family
 * default fails at lookup, when `defaultService()` is called.
 *
 * An application `DecisionServiceRegistry` bean replaces the one built here.
 */
@ApiStatus.Internal
@AutoConfiguration(after = [AgentPlatformAutoConfiguration::class, LlmDecisionServicesAutoConfiguration::class])
class DecisionServiceRegistryAutoConfiguration {

    /**
     * The registry of decision and classification services in the context.
     *
     * @throws IllegalStateException when a default or role property names a missing or ineligible
     * service, naming the property
     */
    @Bean
    @ConditionalOnMissingBean
    fun decisionServiceRegistry(
        beanFactory: ListableBeanFactory,
        candidates: ObjectProvider<DecisionServiceRegistry.DefaultCandidate>,
        observationRegistry: ObjectProvider<ObservationRegistry>,
        environment: Environment,
    ): DecisionServiceRegistry {
        val binder = Binder.get(environment)
        val decision = bindFamily(binder, DECISION_PREFIX)
        val classification = bindFamily(binder, CLASSIFICATION_PREFIX)
        val builder = DecisionServiceRegistry.builder()
            .observationRegistry(observationRegistry(beanFactory, observationRegistry))
        val services = serviceBeans(beanFactory)
        val registrationNames = registrationNames(beanFactory, services)
        services.forEach { (name, service) -> if (registrationNames[name] == name) builder.register(name, service) }
        val resolve = { name: String -> registrationNames[name] ?: name }
        decision.default?.let { builder.decisionDefault(resolve(it)) }
        decision.roles.forEach { (role, name) -> builder.decisionRole(role, resolve(name)) }
        classification.default?.let { builder.classificationDefault(resolve(it)) }
        classification.roles.forEach { (role, name) -> builder.classificationRole(role, resolve(name)) }
        candidates.orderedStream().forEach { builder.defaultCandidate(resolve(it.name)) }
        return try {
            builder.build()
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("Invalid decision service configuration: ${e.message}", e)
        }
    }

    /**
     * Applies `embabel.agent.platform.decisions.capture-content`. The bean exists whether or not the
     * registry above is created.
     */
    @Bean
    @Lazy(false)
    fun decisionContentCaptureProperty(environment: Environment): DecisionContentCaptureProperty =
        DecisionContentCaptureProperty(
            Binder.get(environment).bind(CAPTURE_CONTENT_PROPERTY, Boolean::class.javaObjectType).orElse(false),
        )

    /**
     * The default and role bindings of one service family.
     *
     * @property default registration name of the family default
     * @property roles registration name bound to each role
     */
    data class FamilyProperties(
        val default: String? = null,
        val roles: Map<String, String> = emptyMap(),
    )

    /**
     * Turns [DecisionContentCapture] on while the context runs when [enabled] is true, and off
     * again when the context closes.
     */
    class DecisionContentCaptureProperty(val enabled: Boolean) : DisposableBean {

        init {
            if (enabled) {
                DecisionContentCapture.enable()
                LoggerFactory.getLogger(DecisionServiceRegistryAutoConfiguration::class.java).warn(
                    "{} is true: decision requests and provider responses are logged at TRACE. " +
                        "They can hold personal or confidential text. Do not enable this in production.",
                    CAPTURE_CONTENT_PROPERTY,
                )
            }
        }

        override fun destroy() {
            if (enabled) DecisionContentCapture.disable()
        }
    }

    companion object {

        const val DECISION_PREFIX = "embabel.models.decision"

        const val CLASSIFICATION_PREFIX = "embabel.models.classification"

        const val CAPTURE_CONTENT_PROPERTY = "embabel.agent.platform.decisions.capture-content"

        private val logger = LoggerFactory.getLogger(DecisionServiceRegistryAutoConfiguration::class.java)

        /**
         * Picks the unique or primary observation registry. With several and none primary,
         * bindings cannot pick one, so one WARN names them and the registry observes on NOOP.
         *
         * @param beanFactory bean factory to look up ObservationRegistry beans in
         * @param registries the ObservationRegistry beans in the context
         * @return the registry to use for decision observations
         */
        private fun observationRegistry(
            beanFactory: ListableBeanFactory,
            registries: ObjectProvider<ObservationRegistry>,
        ): ObservationRegistry {
            registries.ifUnique?.let { return it }
            val names = beanFactory.getBeanNamesForType(ObservationRegistry::class.java)
            if (names.size > 1) {
                logger.warn(
                    "Decision service registry found {} ObservationRegistry beans and none is primary: {}. " +
                        "Decision observations have no parent and are not recorded. Mark one ObservationRegistry " +
                        "bean @Primary.",
                    names.size, names.joinToString(),
                )
            }
            return ObservationRegistry.NOOP
        }

        /**
         * Collects the classification service beans in definition order. Lazy and non-singleton
         * beans are left uncreated unless something has already created them.
         *
         * @param beanFactory bean factory to look up ClassificationService beans in
         * @return each eligible bean name mapped to its service instance
         */
        private fun serviceBeans(beanFactory: ListableBeanFactory): Map<String, ClassificationService> {
            val services = LinkedHashMap<String, ClassificationService>()
            beanFactory.getBeanNamesForType(ClassificationService::class.java)
                .filterNot { ScopedProxyUtils.isScopedTarget(it) }
                .forEach { name ->
                    val deferred = (beanFactory as? ConfigurableListableBeanFactory)?.let { deferral(it, name) }
                    if (deferred != null) {
                        logger.info(
                            "Decision service bean '{}' is {}, so it is not created at startup or registered by name. " +
                                "Pass it to using(service) to call it.",
                            name,
                            deferred,
                        )
                    } else {
                        services[name] = beanFactory.getBean(name, ClassificationService::class.java)
                    }
                }
            return services
        }

        /**
         * Says why the bean must not be created now, or null when it can be.
         *
         * @param beanFactory bean factory holding the bean definition
         * @param name the bean name to check
         * @return the reason it must stay uncreated, or null if it can be created
         */
        private fun deferral(beanFactory: ConfigurableListableBeanFactory, name: String): String? {
            if (!beanFactory.containsBeanDefinition(name) || beanFactory.containsSingleton(name)) return null
            val merged = beanFactory.getMergedBeanDefinition(name)
            if (!merged.isSingleton) return "scoped '${merged.scope}'"
            // A context that makes every bean lazy by default also sets lazy-init, so only an
            // explicit @Lazy defers a singleton.
            val definition = beanFactory.getBeanDefinition(name)
            val lazyAnnotated = definition is AnnotatedBeanDefinition &&
                (
                    definition.factoryMethodMetadata?.isAnnotated(Lazy::class.java.name) == true ||
                        definition.metadata.isAnnotated(Lazy::class.java.name)
                    )
            return if (merged.isLazyInit && lazyAnnotated) "marked @Lazy" else null
        }

        /**
         * Maps every bean name to the name its instance registers under. An instance with several
         * bean names takes its @Primary name, or else its first name.
         *
         * @param beanFactory bean factory to check for @Primary bean definitions
         * @param services each bean name mapped to its service instance
         * @return every bean name mapped to its chosen registration name
         */
        private fun registrationNames(
            beanFactory: ListableBeanFactory,
            services: Map<String, ClassificationService>,
        ): Map<String, String> {
            val namesByInstance = IdentityHashMap<ClassificationService, MutableList<String>>()
            services.forEach { (name, service) -> namesByInstance.getOrPut(service) { mutableListOf() }.add(name) }
            val configurable = beanFactory as? ConfigurableListableBeanFactory
            val registrationNames = HashMap<String, String>()
            namesByInstance.values.forEach { names ->
                val chosen = names.firstOrNull { name ->
                    configurable != null && configurable.containsBeanDefinition(name) &&
                        configurable.getMergedBeanDefinition(name).isPrimary
                } ?: names.first()
                names.forEach { registrationNames[it] = chosen }
                if (names.size > 1) {
                    logger.debug(
                        "Decision service '{}' is also exposed as bean {}. It registers once, as '{}'.",
                        chosen,
                        names.filter { it != chosen },
                        chosen,
                    )
                }
            }
            return registrationNames
        }

        /**
         * Binds the default and role properties for one service family.
         *
         * @param binder the property binder for the environment
         * @param prefix the family's configuration prefix
         * @return the bound properties, or empty defaults if none are set
         */
        private fun bindFamily(binder: Binder, prefix: String): FamilyProperties =
            binder.bind(prefix, Bindable.of(FamilyProperties::class.java)).orElse(FamilyProperties())
    }
}
