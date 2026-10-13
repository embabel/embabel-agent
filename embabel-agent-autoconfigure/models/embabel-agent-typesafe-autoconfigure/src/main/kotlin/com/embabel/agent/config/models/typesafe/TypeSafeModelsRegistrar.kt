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

import com.embabel.agent.typesafe.TypeSafeModelFactory
import com.embabel.common.ai.decision.DecisionService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor

/**
 * Defines one `DecisionService` bean per catalog model, named after the model.
 *
 * The models are bean definitions, so applications can inject them by type or with
 * `@Qualifier("<model>")` like any other bean. A bean the application already defines under a model's
 * name keeps that name, and the catalog model is skipped with one INFO line.
 *
 * @param models the catalog to define beans for
 * @param beanFactory the factory the `TypeSafeModelFactory` bean is read from when a model is created
 */
internal class TypeSafeModelsRegistrar(
    private val models: List<TypeSafeModelDefinition>,
    private val beanFactory: BeanFactory,
) : BeanDefinitionRegistryPostProcessor {

    override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
        models.forEach { model ->
            if (registry.containsBeanDefinition(model.name)) {
                logger.info("TypeSafe model '{}' is defined by the application; the catalog model is skipped", model.name)
                return@forEach
            }
            val definition = BeanDefinitionBuilder.genericBeanDefinition(DecisionService::class.java) {
                beanFactory.getBean(TypeSafeModelFactory::class.java).build(model.modelId)
            }.beanDefinition
            registry.registerBeanDefinition(model.name, definition)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(TypeSafeModelsRegistrar::class.java)
    }
}
