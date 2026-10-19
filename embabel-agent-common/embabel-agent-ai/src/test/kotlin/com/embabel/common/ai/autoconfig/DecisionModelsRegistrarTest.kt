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
import com.embabel.common.ai.decision.support.StubDecisionService
import com.embabel.common.ai.model.DecisionServiceRegistry
import com.embabel.common.ai.model.PerTokenPricingModel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import java.time.LocalDate
import java.util.function.Function

class DecisionModelsRegistrarTest {

    private data class Model(
        override val name: String,
        override val modelId: String,
    ) : LlmAutoConfigMetadata {
        override val displayName: String? get() = null
        override val knowledgeCutoffDate: LocalDate? get() = null
        override val pricingModel: PerTokenPricingModel? get() = null
    }

    private val built = mutableListOf<String>()

    private fun registrar(vararg models: Model) = DecisionModelsRegistrar(
        provider = "Acme",
        models = models.toList(),
        defaultModel = "acme-default",
        defaultCandidate = "acmeDefaultCandidate",
        serviceFactory = Function { modelId ->
            built += modelId
            StubDecisionService.builder(modelId).build()
        },
    )

    private fun applicationService(): DecisionService = StubDecisionService.builder("application").build()

    @Nested
    inner class CatalogModels {

        @Test
        fun `defines one tagged bean per catalog model and builds it lazily`() {
            val factory = DefaultListableBeanFactory()
            registrar(Model("acme-default", "acme/default-1"), Model("acme-fast", "acme/fast-1"))
                .postProcessBeanDefinitionRegistry(factory)

            assertThat(built).isEmpty()
            assertThat(factory.getBeanDefinition("acme-fast").getAttribute(DecisionModelsRegistrar.CATALOG_MODEL_ATTRIBUTE))
                .isEqualTo("acme/fast-1")
            assertThat(factory.getBeanDefinition("acme-fast").getAttribute(DecisionModelsRegistrar.CATALOG_PROVIDER_ATTRIBUTE))
                .isEqualTo("Acme")
            assertThat(factory.getBean("acme-fast", DecisionService::class.java).name).isEqualTo("acme/fast-1")
            assertThat(built).containsExactly("acme/fast-1")
        }

        @Test
        fun `skips a model whose name the application defines`() {
            val factory = DefaultListableBeanFactory()
            factory.registerBeanDefinition(
                "acme-fast",
                BeanDefinitionBuilder.genericBeanDefinition(DecisionService::class.java) { applicationService() }.beanDefinition,
            )
            registrar(Model("acme-fast", "acme/fast-1")).postProcessBeanDefinitionRegistry(factory)

            assertThat(factory.getBeanDefinition("acme-fast").getAttribute(DecisionModelsRegistrar.CATALOG_MODEL_ATTRIBUTE))
                .isNull()
            assertThat(factory.getBean("acme-fast", DecisionService::class.java).name).isEqualTo("application")
        }

        @Test
        fun `skips a model whose name the application uses as an alias`() {
            val factory = DefaultListableBeanFactory()
            factory.registerBeanDefinition(
                "custom",
                BeanDefinitionBuilder.genericBeanDefinition(DecisionService::class.java) { applicationService() }.beanDefinition,
            )
            factory.registerAlias("custom", "acme-fast")
            registrar(Model("acme-fast", "acme/fast-1")).postProcessBeanDefinitionRegistry(factory)

            assertThat(factory.containsBeanDefinition("acme-fast")).isFalse()
            assertThat(factory.getBean("acme-fast", DecisionService::class.java).name).isEqualTo("application")
        }
    }

    @Nested
    inner class DefaultCandidate {

        @Test
        fun `is defined when the registrar defined the default model`() {
            val factory = DefaultListableBeanFactory()
            registrar(Model("acme-default", "acme/default-1")).postProcessBeanDefinitionRegistry(factory)

            assertThat(factory.getBean("acmeDefaultCandidate", DecisionServiceRegistry.DefaultCandidate::class.java))
                .isEqualTo(DecisionServiceRegistry.DefaultCandidate("acme-default"))
        }

        @Test
        fun `is not defined when the catalog lacks the default model`() {
            val factory = DefaultListableBeanFactory()
            registrar(Model("acme-fast", "acme/fast-1")).postProcessBeanDefinitionRegistry(factory)

            assertThat(factory.containsBeanDefinition("acmeDefaultCandidate")).isFalse()
        }

        @Test
        fun `is not defined when the application replaced the default model`() {
            val factory = DefaultListableBeanFactory()
            factory.registerBeanDefinition(
                "acme-default",
                BeanDefinitionBuilder.genericBeanDefinition(DecisionService::class.java) { applicationService() }.beanDefinition,
            )
            registrar(Model("acme-default", "acme/default-1")).postProcessBeanDefinitionRegistry(factory)

            assertThat(factory.containsBeanDefinition("acmeDefaultCandidate")).isFalse()
        }

        @Test
        fun `keeps a candidate the application defines`() {
            val factory = DefaultListableBeanFactory()
            factory.registerBeanDefinition(
                "acmeDefaultCandidate",
                BeanDefinitionBuilder.genericBeanDefinition(DecisionServiceRegistry.DefaultCandidate::class.java) {
                    DecisionServiceRegistry.DefaultCandidate("application")
                }.beanDefinition,
            )
            registrar(Model("acme-default", "acme/default-1")).postProcessBeanDefinitionRegistry(factory)

            assertThat(factory.getBean("acmeDefaultCandidate", DecisionServiceRegistry.DefaultCandidate::class.java))
                .isEqualTo(DecisionServiceRegistry.DefaultCandidate("application"))
        }
    }
}
