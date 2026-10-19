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
import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.model.DecisionServiceRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor

/**
 * Defines one `DecisionService` bean per catalog model, named after the model, and the Cloudflare
 * default candidate.
 *
 * The models are bean definitions, so applications can inject them by type or with
 * `@Qualifier("<model>")` like any other bean. A name the application already uses, as a bean name or
 * an alias, keeps its bean, and the catalog model is skipped with one INFO line. The default candidate
 * is defined only when this registrar defined [DEFAULT_MODEL], and an application bean already named
 * [DEFAULT_CANDIDATE] is kept.
 *
 * @param models the catalog to define beans for
 * @param beanFactory the factory the `CloudflareModelFactory` bean is read from when a model is created
 */
internal class CloudflareModelsRegistrar(
    private val models: List<CloudflareModelDefinition>,
    private val beanFactory: BeanFactory,
) : BeanDefinitionRegistryPostProcessor {

    override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
        val defined = models.filter { model ->
            if (registry.isBeanNameInUse(model.name)) {
                logger.info("Cloudflare model '{}' is defined by the application; the catalog model is skipped", model.name)
                false
            } else {
                val definition = BeanDefinitionBuilder.genericBeanDefinition(DecisionService::class.java) {
                    beanFactory.getBean(CloudflareModelFactory::class.java).build(model.modelId)
                }.beanDefinition
                definition.setAttribute(CATALOG_MODEL_ATTRIBUTE, model.modelId)
                registry.registerBeanDefinition(model.name, definition)
                true
            }
        }
        if (defined.any { it.name == DEFAULT_MODEL }) {
            if (registry.isBeanNameInUse(DEFAULT_CANDIDATE)) {
                logger.info("Cloudflare default candidate '{}' is defined by the application, so it is kept", DEFAULT_CANDIDATE)
                return
            }
            val candidate =
                BeanDefinitionBuilder.genericBeanDefinition(DecisionServiceRegistry.DefaultCandidate::class.java) {
                    DecisionServiceRegistry.DefaultCandidate(DEFAULT_MODEL)
                }.beanDefinition
            registry.registerBeanDefinition(DEFAULT_CANDIDATE, candidate)
        }
    }

    companion object {

        /** Bean definition attribute holding the model id of a catalog model bean. */
        const val CATALOG_MODEL_ATTRIBUTE = "com.embabel.cloudflare.catalogModel"

        /** Catalog name of the model offered as the default candidate. */
        const val DEFAULT_MODEL = "clef"

        /** Bean name of the Cloudflare default candidate. */
        const val DEFAULT_CANDIDATE = "cloudflareDefaultCandidate"

        private val logger = LoggerFactory.getLogger(CloudflareModelsRegistrar::class.java)
    }
}
