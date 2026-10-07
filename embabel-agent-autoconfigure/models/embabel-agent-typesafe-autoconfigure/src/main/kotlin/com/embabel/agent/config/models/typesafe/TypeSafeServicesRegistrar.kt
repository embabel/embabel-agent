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

import com.embabel.common.ai.classification.ClassificationService
import com.embabel.common.ai.decision.DecisionService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.boot.context.properties.bind.BindHandler
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler
import org.springframework.boot.context.properties.source.UnboundElementsSourceFilter
import org.springframework.core.env.Environment
import org.springframework.util.ClassUtils

/**
 * Registers a TypeSafe decision service bean for each entry under
 * `embabel.agent.platform.models.typesafe.services`. The entry's key is the bean name.
 *
 * ```yaml
 * embabel:
 *   agent:
 *     platform:
 *       models:
 *         typesafe:
 *           services:
 *             jev:
 *               model: jev-latest
 *             jev-fast:
 *               model: jev-fast
 * ```
 *
 * Every service shares the credential, base URL and response limit of [TypeSafeProperties] and is
 * built by the [TypeSafeModelsConfig] bean. A decision or classification service bean the
 * application defines under an entry's key replaces that entry. Startup fails when the key names a
 * bean of another type, or an entry under `embabel.agent.platform.decisions.llm.services` with the
 * same key. The error names both and asks for one key to be renamed. Startup also fails when an
 * entry has a blank model or a key this class does not know. The error names the property. Unknown keys are detected in configuration files and other
 * property sources, and not in environment variables or system properties.
 */
internal class TypeSafeServicesRegistrar(
    private val services: Map<String, ServiceProperties>,
    private val beanFactory: BeanFactory,
) : BeanDefinitionRegistryPostProcessor {

    /**
     * One configured TypeSafe service.
     *
     * @property model the model identifier or alias the service calls
     */
    class ServiceProperties {
        var model: String? = null

        override fun toString(): String = "ServiceProperties(model=$model)"
    }

    // The prompted-service registrar can run before or after this one. Each marks its definitions
    // with the property path, so the one that runs second finds the clash.
    override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
        services.forEach { (key, service) ->
            val property = "$PREFIX.$key"
            if (registry.containsBeanDefinition(key)) {
                requireApplicationService(registry, key, property)
                logger.info("TypeSafe service '{}' is defined by the application; {}.{}.model is not used", key, PREFIX, key)
            } else {
                val definition = definition(key, service)
                definition.setAttribute(CONFIGURED_SERVICE_ATTRIBUTE, property)
                registry.registerBeanDefinition(key, definition)
            }
        }
    }

    /**
     * Fails unless the existing definition is an application decision or classification service.
     * The type comes from the definition, so no bean is created.
     *
     * @param registry the registry that holds the existing definition
     * @param key the configured key the definition shares
     * @param property the configuration property path, used in the error message
     * @throws IllegalStateException if another configured entry uses the key, or the bean is not a service
     */
    private fun requireApplicationService(registry: BeanDefinitionRegistry, key: String, property: String) {
        val existing = registry.getBeanDefinition(key)
        (existing.getAttribute(CONFIGURED_SERVICE_ATTRIBUTE) as? String)?.let { other ->
            throw IllegalStateException(
                "$property and $other both configure a decision service named '$key'. Rename one of the keys.",
            )
        }
        val type = definedType(registry, existing)
        if (type == null || !ClassificationService::class.java.isAssignableFrom(type)) {
            val described = type?.let { "of type ${it.name}" } ?: "of a type that cannot be read from its definition"
            throw IllegalStateException(
                "$property is configured, and the context already has a bean named '$key' $described. " +
                    "A configured key can only share its name with a decision or classification service bean. " +
                    "Rename the key under $PREFIX.",
            )
        }
    }

    /**
     * Reads the type a definition declares: the `@Bean` method's return type, or else the bean
     * class.
     *
     * For a definition from `@Bean fun jev(): DecisionService`, this returns
     * `DecisionService::class.java` without invoking `jev()`. For a definition registered with
     * a bean class, it returns that class instead.
     *
     * @param registry registry the definition is held in, used to resolve the class loader
     * @param definition the bean definition to read
     * @return the declared type, or null if it can't be resolved
     */
    private fun definedType(registry: BeanDefinitionRegistry, definition: BeanDefinition): Class<*>? {
        val classLoader = (registry as? ConfigurableBeanFactory)?.beanClassLoader ?: ClassUtils.getDefaultClassLoader()
        val typeName = (definition as? AnnotatedBeanDefinition)?.factoryMethodMetadata?.returnTypeName
            ?: definition.resolvableType.resolve()?.name
            ?: definition.beanClassName.takeIf { definition.factoryMethodName == null }
        return typeName?.let { runCatching { ClassUtils.forName(it, classLoader) }.getOrNull() }
    }

    /**
     * Builds the bean definition for one configured service. The definition names the service type
     * so type lookups can skip it without creating it. It is eager, so a failing service stops
     * startup even when the application makes beans lazy.
     *
     * @param key the configured key, used as the bean name
     * @param service the configured service properties
     * @return the bean definition to register
     * @throws IllegalStateException if the service has no model configured
     */
    private fun definition(key: String, service: ServiceProperties): BeanDefinition {
        val model = service.model?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("$PREFIX.$key.model must name a TypeSafe model")
        return BeanDefinitionBuilder.genericBeanDefinition(DecisionService::class.java) {
            beanFactory.getBean(TypeSafeModelsConfig::class.java).build(model)
        }.setLazyInit(false).beanDefinition
    }

    companion object {
        /** Property prefix of the configured TypeSafe services. */
        const val PREFIX = "${TypeSafeProperties.PREFIX}.services"

        /**
         * Bean definition attribute holding the property path of a configured decision service.
         * The prompted decision service registrar uses the same attribute name.
         */
        const val CONFIGURED_SERVICE_ATTRIBUTE = "com.embabel.decision.configuredServiceProperty"

        private val logger = LoggerFactory.getLogger(TypeSafeServicesRegistrar::class.java)

        /**
         * Binds the configured services. A key that no service field matches fails the binding,
         * unless it comes from an environment variable or a system property.
         */
        fun bind(environment: Environment): Map<String, ServiceProperties> =
            Binder.get(environment)
                .bind(
                    PREFIX,
                    Bindable.mapOf(String::class.java, ServiceProperties::class.java),
                    NoUnboundElementsBindHandler(BindHandler.DEFAULT, UnboundElementsSourceFilter()),
                )
                .orElse(emptyMap())
    }
}
