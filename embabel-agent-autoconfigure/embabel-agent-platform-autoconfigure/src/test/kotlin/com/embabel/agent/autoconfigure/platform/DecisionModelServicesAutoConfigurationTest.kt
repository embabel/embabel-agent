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

import com.embabel.agent.spi.decision.LlmDecisionServiceFactory
import com.embabel.common.ai.classification.ClassificationService
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.DecisionCapabilities
import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.UnsupportedDecisionException
import com.embabel.common.ai.decision.support.StubDecisionService
import com.embabel.common.ai.model.DecisionModelDescriptor
import com.embabel.common.ai.model.DecisionModelProvider
import com.embabel.common.ai.model.DecisionServiceRegistry
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

class DecisionModelServicesAutoConfigurationTest {
    private val provider = CountingProvider()
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            DecisionModelServicesAutoConfiguration::class.java,
            DecisionServiceRegistryAutoConfiguration::class.java,
        ))
        .withBean(DecisionModelProvider::class.java, { provider })

    @Test
    fun `custom provider registers typed eager services without a chat model`() {
        // tag::generic-services[]
        runner.withPropertyValues(
            "embabel.agent.platform.decisions.services.triage.provider=custom",
            "embabel.agent.platform.decisions.services.triage.model=choice-model",
            "embabel.models.decision.default=triage",
            "embabel.models.decision.roles.support-triage=triage",
        ).run { context ->
            val service = context.getBean("triage", DecisionService::class.java)
            val registry = context.getBean(DecisionServiceRegistry::class.java)
            assertSame(service, registry.decisions().defaultService())
            assertSame(service, registry.decisions().byRole("support-triage"))
            assertSame(service, registry.classifications().defaultService())
            assertEquals("choice-model", service.name)
            assertEquals("stub", service.provider)
            val definition = context.beanFactory.getBeanDefinition("triage")
            assertEquals(DecisionService::class.java.name, definition.beanClassName)
            assertEquals(false, definition.isLazyInit)
            assertEquals("embabel.agent.platform.decisions.services.triage",
                definition.getAttribute(LlmDecisionServicesRegistrar.CONFIGURED_SERVICE_ATTRIBUTE))
            assertEquals(1, provider.described)
            assertEquals(1, provider.created)
        }
        // end::generic-services[]
    }

    @Test
    fun `capability rejection happens before an unsupported provider operation`() {
        configured().run { context ->
            val service = context.getBean("triage", DecisionService::class.java)
            val rating = Questions.named("rating").rating("PRIVATE_QUESTION")
                .level("low", "PRIVATE_LOW").level("high", "PRIVATE_HIGH").build()
            assertThrows<UnsupportedDecisionException> { service.ask("PRIVATE_INPUT", DecisionSpec.of(rating)) }
            val stub = service as StubDecisionService
            assertThat(stub.calls()).isEmpty()
            val choice = Questions.named("team").choice("Which team?").option("billing", "Billing").build()
            assertThat(service.ask("input", DecisionSpec.of(choice)).answer(choice))
                .isInstanceOf(ClassificationResult.Selected::class.java)
            assertEquals(listOf("classify"), stub.calls())
        }
    }

    @Test
    fun `global lazy defaults still validate an unknown provider eagerly`() {
        configured("unknown").withBean(LazyInitializationBeanFactoryPostProcessor::class.java).run { context ->
            assertThat(context).hasFailed()
            assertThat(messages(context.startupFailure)).contains("services.triage.provider", "unknown")
        }
    }

    @Test
    fun `no entries leave lazy provider and unrelated factory uncreated`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DecisionModelServicesAutoConfiguration::class.java))
            .withInitializer { context ->
                val definitions = context.beanFactory as BeanDefinitionRegistry
                definitions.registerBeanDefinition("unusedProvider", BeanDefinitionBuilder
                    .genericBeanDefinition(DecisionModelProvider::class.java) { error("provider must stay uncreated") }
                    .setLazyInit(true).beanDefinition)
                definitions.registerBeanDefinition("unusedService", BeanDefinitionBuilder
                    .genericBeanDefinition(DecisionService::class.java) { error("service must stay uncreated") }
                    .setLazyInit(true).beanDefinition)
            }.run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.beanFactory.containsSingleton("unusedProvider")).isFalse()
                assertThat(context.beanFactory.containsSingleton("unusedService")).isFalse()
            }
    }

    @Test
    fun `declared application classifier wins without resolving a throwing provider`() {
        val classifier = mockk<ClassificationService>()
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DecisionModelServicesAutoConfiguration::class.java))
            .withUserConfiguration(ApplicationOverride::class.java)
            .withBean("applicationClassifier", ClassificationService::class.java, { classifier })
            .withInitializer { context ->
                (context.beanFactory as BeanDefinitionRegistry).registerBeanDefinition("unneededProvider",
                    BeanDefinitionBuilder.genericBeanDefinition(DecisionModelProvider::class.java) {
                        error("must not resolve provider for override")
                    }.setLazyInit(true).beanDefinition)
            }.withPropertyValues(
                "embabel.agent.platform.decisions.services.triage.provider=missing",
                "embabel.agent.platform.decisions.services.triage.model= ",
            ).run { context ->
                assertThat(context).hasNotFailed()
                assertSame(classifier, context.getBean("triage"))
                assertThat(context.beanFactory.containsSingleton("unneededProvider")).isFalse()
            }
    }

    @Test
    fun `application alias remains the primary registration and resolves configured roles`() {
        configured().withUserConfiguration(Alias::class.java)
            .withPropertyValues("embabel.models.decision.roles.review=triage")
            .run { context ->
                assertThat(context).hasNotFailed()
                val registry = context.getBean(DecisionServiceRegistry::class.java)
                assertEquals(listOf("primaryAlias"), registry.registrationNames())
                assertSame(context.getBean("triage"), registry.decisions().byRole("review"))
                assertSame(context.getBean("triage"), registry.decisions().defaultService())
            }
    }

    @Test
    fun `blank fields and unknown entry keys fail with their property origins`() {
        listOf(
            "provider" to "", "model" to " ", "unknown" to "PRIVATE_VALUE",
        ).forEach { (field, value) ->
            configured().withPropertyValues("embabel.agent.platform.decisions.services.triage.$field=$value")
                .run { context ->
                    assertThat(context).hasFailed()
                    assertThat(messages(context.startupFailure)).contains("services.triage.$field")
                    assertThat(messages(context.startupFailure)).doesNotContain("PRIVATE_VALUE")
                }
        }
    }

    @Test
    fun `duplicate provider ids fail naming the configured provider property`() {
        configured().withBean("duplicateProvider", DecisionModelProvider::class.java, { CountingProvider() })
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(messages(context.startupFailure)).contains("services.triage.provider", "duplicate")
            }
    }

    @Test
    fun `unknown provider fails without calling model discovery or create`() {
        configured("missing").run { context ->
            assertThat(context).hasFailed()
            assertThat(messages(context.startupFailure)).contains("services.triage.provider")
            assertEquals(0, provider.described)
            assertEquals(0, provider.created)
        }
    }

    @Test
    fun `dishonest capability and model descriptions fail before the service is registered`() {
        listOf(CountingProvider(wrongModel = true), CountingProvider(wrongCapabilities = true)).forEach { liar ->
            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DecisionModelServicesAutoConfiguration::class.java))
                .withBean(DecisionModelProvider::class.java, { liar })
                .withPropertyValues(*properties())
                .run { context ->
                    assertThat(context).hasFailed()
                    assertThat(messages(context.startupFailure)).contains("services.triage.model", "descriptor")
                }
        }
    }

    @Test
    fun `provider failure diagnostics exclude model and exception payloads`() {
        val broken = object : DecisionModelProvider {
            override val id = "custom"
            override fun describe(modelName: String): DecisionModelDescriptor = error("PRIVATE_PROVIDER_RESPONSE")
            override fun create(modelName: String): DecisionService = error("not reached")
        }
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DecisionModelServicesAutoConfiguration::class.java))
            .withBean(DecisionModelProvider::class.java, { broken })
            .withPropertyValues(*properties(model = "PRIVATE_MODEL"))
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(messages(context.startupFailure)).contains("services.triage.model")
                    .doesNotContain("PRIVATE_PROVIDER_RESPONSE", "PRIVATE_MODEL")
            }
    }

    @Test
    fun `non service bean collision fails naming the configured origin`() {
        configured().withBean("triage", String::class.java, { "existing" }).run { context ->
            assertThat(context).hasFailed()
            assertThat(messages(context.startupFailure)).contains("services.triage", "decision or classification")
        }
    }

    @Test
    fun `actual prompted registrar collisions fail in either registration order`() {
        val legacyFactory = mockk<LlmDecisionServiceFactory>()
        listOf(true, false).forEach { genericFirst ->
            val environment = StandardEnvironment().apply {
                propertySources.addFirst(MapPropertySource("test", mapOf(
                    "embabel.agent.platform.decisions.services.triage.provider" to "custom",
                    "embabel.agent.platform.decisions.services.triage.model" to "choice-model",
                    "embabel.agent.platform.decisions.llm.services.triage.llm" to "llm-model",
                )))
            }
            val definitions = org.springframework.beans.factory.support.DefaultListableBeanFactory()
            definitions.registerSingleton("factory", legacyFactory)
            val generic = DecisionModelServicesRegistrar(DecisionModelServicesRegistrar.bind(environment), definitions)
            val legacy = LlmDecisionServicesRegistrar(LlmDecisionServicesRegistrar.bindServices(environment), definitions)
            val first = if (genericFirst) generic else legacy
            val second = if (genericFirst) legacy else generic
            first.postProcessBeanDefinitionRegistry(definitions)
            val error = assertThrows<IllegalStateException> { second.postProcessBeanDefinitionRegistry(definitions) }
            assertThat(error.message).contains("decisions.services.triage", "decisions.llm.services.triage")
        }
    }

    @Test
    fun `environment variables bind entries with the established unknown key exception`() {
        runner.withInitializer { context ->
            context.environment.propertySources.addFirst(SystemEnvironmentPropertySource("systemEnvironment", mapOf(
                "EMBABEL_AGENT_PLATFORM_DECISIONS_SERVICES_TRIAGE_PROVIDER" to "custom",
                "EMBABEL_AGENT_PLATFORM_DECISIONS_SERVICES_TRIAGE_MODEL" to "choice-model",
                "EMBABEL_AGENT_PLATFORM_DECISIONS_SERVICES_TRIAGE_UNKNOWN" to "ignored",
            )))
        }.run { context ->
            assertThat(context).hasNotFailed()
            assertEquals("choice-model", context.getBean("triage", DecisionService::class.java).name)
        }
    }

    private fun configured(providerId: String = "custom") = runner.withPropertyValues(*properties(providerId))
    private fun properties(providerId: String = "custom", model: String = "choice-model") = arrayOf(
        "embabel.agent.platform.decisions.services.triage.provider=$providerId",
        "embabel.agent.platform.decisions.services.triage.model=$model",
    )
    private fun messages(failure: Throwable?): String = generateSequence(failure) { it.cause }
        .joinToString("\n") { it.message ?: "" }

    class CountingProvider(
        private val wrongModel: Boolean = false,
        private val wrongCapabilities: Boolean = false,
    ) : DecisionModelProvider {
        override val id = "custom"
        var described = 0
        var created = 0
        private val capabilities = DecisionCapabilities.of(setOf(QuestionKind.CHOICE))
        override fun models(): List<DecisionModelDescriptor> = error("catalog must not be queried at construction")
        override fun describe(modelName: String): DecisionModelDescriptor {
            described++
            return DecisionModelDescriptor(if (wrongModel) "other" else modelName, capabilities)
        }
        override fun create(modelName: String): DecisionService {
            created++
            return StubDecisionService.builder(modelName)
                .capabilities(if (wrongCapabilities) DecisionCapabilities.of(setOf(QuestionKind.PROPOSITION)) else capabilities)
                .choice("team", ClassificationResult.Selected("billing", ModelProvenance(modelName, "stub")))
                .perQuestion().build()
        }
    }

    @Configuration(proxyBeanMethods = false)
    class ApplicationOverride {
        @Bean fun triage(@Qualifier("applicationClassifier") classifier: ClassificationService): ClassificationService = classifier
    }

    @Configuration(proxyBeanMethods = false)
    class Alias {
        @Bean @Primary fun primaryAlias(@Qualifier("triage") service: DecisionService): DecisionService = service
    }
}
