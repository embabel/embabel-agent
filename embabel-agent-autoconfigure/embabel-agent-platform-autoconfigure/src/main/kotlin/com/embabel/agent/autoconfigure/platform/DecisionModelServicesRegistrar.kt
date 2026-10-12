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
import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.model.DecisionModelProvider
import org.jetbrains.annotations.ApiStatus
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.BindHandler
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler
import org.springframework.boot.context.properties.source.UnboundElementsSourceFilter
import org.springframework.core.env.Environment
import org.springframework.util.ClassUtils

/**
 * Registers `embabel.agent.platform.decisions.services.<name>.provider/model` as eager services.
 * Application service definitions override entries; configured entries never override one another.
 */
@ApiStatus.Internal
class DecisionModelServicesRegistrar internal constructor(
    private val services: Map<String, ServiceProperties>,
    private val beanFactory: ListableBeanFactory,
) : BeanDefinitionRegistryPostProcessor {
    class ServiceProperties {
        var provider: String? = null
        var model: String? = null
    }

    override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
        services.forEach { (name, properties) ->
            val property = "$PREFIX.$name"
            if (registry.containsBeanDefinition(name)) {
                requireApplicationService(registry, name, property)
                logger.info("Decision service '{}' is defined by the application; its configured definition is skipped", name)
            } else {
                val provider = properties.provider?.takeIf { it.isNotBlank() }
                    ?: error("$property.provider must name a decision model provider")
                val model = properties.model?.takeIf { it.isNotBlank() }
                    ?: error("$property.model must name a decision model")
                val definition = BeanDefinitionBuilder.genericBeanDefinition(DecisionService::class.java) {
                    create(property, provider, model)
                }.setLazyInit(false).beanDefinition
                definition.setAttribute(LlmDecisionServicesRegistrar.CONFIGURED_SERVICE_ATTRIBUTE, property)
                registry.registerBeanDefinition(name, definition)
            }
        }
    }

    private fun create(property: String, providerId: String, model: String): DecisionService {
        // Only provider beans are resolved. Never use an untyped getBeansOfType scan, which can
        // instantiate the very decision services this registrar is defining.
        val matches = beanFactory.getBeanNamesForType(DecisionModelProvider::class.java, false, false)
            .map { name -> providerBean(name, property) }
            .groupBy { provider -> bounded("$property.provider could not read its decision model provider id") { provider.id } }
        if (matches.any { (id, providers) -> id.isBlank() || providers.size > 1 }) {
            error("$property.provider has duplicate or blank decision model provider ids")
        }
        val provider = matches[providerId]?.singleOrNull()
            ?: error("$property.provider names an unknown decision model provider")
        val descriptor = bounded("$property.model could not be described by its decision model provider") {
            provider.describe(model)
        }
        check(descriptor.modelName == model) { "$property.model has a descriptor for a different model" }
        val service = bounded("$property.model could not be created by its decision model provider") {
            provider.create(model)
        }
        val capabilities = bounded("$property.model could not read the constructed decision model capabilities") {
            service.capabilities()
        }
        check(descriptor.capabilities == capabilities) { "$property.model descriptor capabilities differ from the constructed service" }
        return service
    }

    private fun providerBean(name: String, property: String): DecisionModelProvider =
        bounded("$property.provider could not resolve its decision model provider") {
            beanFactory.getBean(name, DecisionModelProvider::class.java)
        }

    private fun <T> bounded(message: String, operation: () -> T): T = try {
        operation()
    } catch (_: Exception) {
        // Provider exceptions can carry credentials or responses; configuration diagnostics expose
        // only the property that the operator can fix.
        throw IllegalStateException(message)
    }

    private fun requireApplicationService(registry: BeanDefinitionRegistry, name: String, property: String) {
        val existing = registry.getBeanDefinition(name)
        (existing.getAttribute(LlmDecisionServicesRegistrar.CONFIGURED_SERVICE_ATTRIBUTE) as? String)?.let { other ->
            error("$property and $other both configure a decision service named '$name'. Rename one of the keys.")
        }
        val type = definedType(registry, existing)
        check(type != null && ClassificationService::class.java.isAssignableFrom(type)) {
            "$property is configured, and the context already has a bean named '$name'. " +
                "A configured key can only share its name with a decision or classification service bean. Rename the key under $PREFIX."
        }
    }

    private fun definedType(registry: BeanDefinitionRegistry, definition: BeanDefinition): Class<*>? {
        val classLoader = (registry as? ConfigurableBeanFactory)?.beanClassLoader ?: ClassUtils.getDefaultClassLoader()
        val typeName = (definition as? AnnotatedBeanDefinition)?.factoryMethodMetadata?.returnTypeName
            ?: definition.resolvableType.resolve()?.name
            ?: definition.beanClassName.takeIf { definition.factoryMethodName == null }
        return typeName?.let { runCatching { ClassUtils.forName(it, classLoader) }.getOrNull() }
    }

    companion object {
        const val PREFIX = "embabel.agent.platform.decisions.services"
        private val logger = LoggerFactory.getLogger(DecisionModelServicesRegistrar::class.java)

        fun bind(environment: Environment): Map<String, ServiceProperties> = try {
            Binder.get(environment).bind(
                PREFIX, Bindable.mapOf(String::class.java, ServiceProperties::class.java),
                NoUnboundElementsBindHandler(BindHandler.DEFAULT, UnboundElementsSourceFilter()),
            ).orElse(emptyMap())
        } catch (failure: BindException) {
            val unbound = generateSequence<Throwable>(failure) { it.cause }
                .filterIsInstance<UnboundConfigurationPropertiesException>().firstOrNull()
            val names = unbound?.unboundProperties?.map { it.name.toString() }?.sorted()?.take(10)
            throw IllegalStateException(if (names.isNullOrEmpty()) "$PREFIX could not bind decision service properties"
                else "Unknown decision service properties: ${names.joinToString()}")
        }
    }
}
