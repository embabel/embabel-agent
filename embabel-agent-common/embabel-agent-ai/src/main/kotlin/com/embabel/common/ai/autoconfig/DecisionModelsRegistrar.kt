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
package com.embabel.common.ai.autoconfig

import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.model.DecisionServiceRegistry
import org.jetbrains.annotations.ApiStatus
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import java.util.function.Function

/**
 * Defines one `DecisionService` bean per catalog model, named after the model, and the provider's
 * default candidate. Decision model starters share it.
 *
 * The models are bean definitions, so applications can inject them by type or with
 * `@Qualifier("<model>")` like any other bean. A name the application already uses, as a bean name or
 * an alias, keeps its bean, and the catalog model is skipped with one INFO line. The default candidate
 * is defined only when this registrar defined [defaultModel], and an application bean already named
 * [defaultCandidate] is kept.
 *
 * @param provider the provider name, used in logs and to tag each definition
 * @param models the catalog to define beans for
 * @param defaultModel the catalog name of the model offered as the default candidate
 * @param defaultCandidate the bean name of the default candidate
 * @param serviceFactory builds a service from a model id; it runs only when the bean is created
 */
@ApiStatus.Internal
class DecisionModelsRegistrar(
    private val provider: String,
    private val models: List<LlmAutoConfigMetadata>,
    private val defaultModel: String,
    private val defaultCandidate: String,
    private val serviceFactory: Function<String, DecisionService>,
) : BeanDefinitionRegistryPostProcessor {

    override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
        val defined = models.filter { model ->
            if (registry.isBeanNameInUse(model.name)) {
                logger.info("{} model '{}' is defined by the application; the catalog model is skipped", provider, model.name)
                false
            } else {
                val definition = BeanDefinitionBuilder.genericBeanDefinition(DecisionService::class.java) {
                    serviceFactory.apply(model.modelId)
                }.beanDefinition
                definition.setAttribute(CATALOG_MODEL_ATTRIBUTE, model.modelId)
                definition.setAttribute(CATALOG_PROVIDER_ATTRIBUTE, provider)
                registry.registerBeanDefinition(model.name, definition)
                true
            }
        }
        if (defined.any { it.name == defaultModel }) {
            if (registry.isBeanNameInUse(defaultCandidate)) {
                logger.info("{} default candidate '{}' is defined by the application, so it is kept", provider, defaultCandidate)
                return
            }
            val candidate =
                BeanDefinitionBuilder.genericBeanDefinition(DecisionServiceRegistry.DefaultCandidate::class.java) {
                    DecisionServiceRegistry.DefaultCandidate(defaultModel)
                }.beanDefinition
            registry.registerBeanDefinition(defaultCandidate, candidate)
        }
    }

    companion object {

        /** Bean definition attribute holding the model id of a catalog model bean. */
        const val CATALOG_MODEL_ATTRIBUTE = "com.embabel.decision.catalogModel"

        /** Bean definition attribute holding the provider that defined a catalog model bean. */
        const val CATALOG_PROVIDER_ATTRIBUTE = "com.embabel.decision.catalogProvider"

        private val logger = LoggerFactory.getLogger(DecisionModelsRegistrar::class.java)

        /**
         * Reports the catalog models a provider's registrar defined, the way the chat model starters
         * report theirs. Models the application replaced, and other providers' models, are left out.
         *
         * @param provider the provider name the registrar was given
         * @param beanFactory the factory holding the catalog model definitions
         * @return the provider's initialization, with only decision models
         */
        @JvmStatic
        fun initialization(provider: String, beanFactory: ConfigurableListableBeanFactory): ProviderInitialization {
            val registered = beanFactory.beanDefinitionNames.mapNotNull { name ->
                val definition = beanFactory.getBeanDefinition(name)
                if (definition.getAttribute(CATALOG_PROVIDER_ATTRIBUTE) != provider) return@mapNotNull null
                val modelId = definition.getAttribute(CATALOG_MODEL_ATTRIBUTE) as? String ?: return@mapNotNull null
                logger.info("Registered {} decision model bean: {} -> {}", provider, name, modelId)
                RegisteredModel(beanName = name, modelId = modelId)
            }
            return ProviderInitialization(
                provider = provider,
                registeredLlms = emptyList(),
                registeredDecisionModels = registered,
            ).also { logger.info(it.summary()) }
        }
    }
}
