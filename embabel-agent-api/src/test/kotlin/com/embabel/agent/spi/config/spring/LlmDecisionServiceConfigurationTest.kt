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
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.decision.LlmDecisionRetryProperties
import com.embabel.agent.spi.support.decision.PropositionAnswer
import com.embabel.agent.spi.support.decision.PropositionVerdict
import com.embabel.chat.Message
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.model.ByNameModelSelectionCriteria
import com.embabel.common.ai.model.ClassificationService
import com.embabel.common.ai.model.DecisionService
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.ModelMetadata
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.ai.model.ModelSelectionCriteria
import com.embabel.common.ai.model.ModelType
import com.embabel.common.ai.model.NoSuitableModelException
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.retry.TransientAiException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import java.util.function.Supplier

/**
 * The helper classes here carry no configuration annotation on purpose. The API test application
 * scans `com.embabel.agent` for configuration classes, and a stub model provider found that way
 * would clash with the real one in unrelated tests.
 */
class LlmDecisionServiceConfigurationTest {

    private val llm = mockk<LlmService<*>> {
        every { name } returns "gpt-test"
        every { provider } returns "TestProvider"
    }

    private val proposition = PropositionRequest("My card was charged twice", "The customer wants a refund")

    private val llmOperations = mockk<LlmOperations> {
        every {
            doTransform(any<List<Message>>(), any(), PropositionAnswer::class.java, null)
        } returns PropositionAnswer(PropositionVerdict.TRUE)
    }

    private val runner = ApplicationContextRunner()
        .withBean("gptTest", LlmService::class.java, Supplier { llm })
        .withBean(LlmOperations::class.java, Supplier { llmOperations })
        .withUserConfiguration(ModelProviderFromLlmBeans::class.java, LlmDecisionServiceConfiguration::class.java)

    private val twoServices = arrayOf(
        "embabel.agent.platform.decisions.llm.services.triage.llm=gpt-test",
        "embabel.agent.platform.decisions.llm.services.routing.llm=gpt-test",
        "embabel.agent.platform.decisions.llm.services.routing.kind=classification",
    )

    private fun AssertableApplicationContext.failureChain(): List<Throwable> =
        generateSequence(startupFailure) { it.cause }.toList()

    @Nested
    inner class Registration {

        @Test
        fun `services resolve by name with the kind each declares`() {
            runner.withPropertyValues(*twoServices).run { context ->
                val triage = context.getBean("triage", DecisionService::class.java)
                assertEquals(ModelType.DECISION, triage.type)
                assertEquals("gpt-test", triage.name)
                assertEquals("TestProvider", triage.provider)

                val routing = context.getBean("routing", ClassificationService::class.java)
                assertEquals(ModelType.CLASSIFICATION, routing.type)
                assertFalse(routing is DecisionService)
                assertEquals("gpt-test", routing.name)
            }
        }

        @Test
        fun `services are not llms`() {
            runner.withPropertyValues(*twoServices).run { context ->
                assertEquals(setOf("gptTest"), context.getBeansOfType(LlmService::class.java).keys)
            }
        }

        @Test
        fun `definitions carry the service type and are eager`() {
            runner.withPropertyValues(*twoServices).run { context ->
                val triage = context.beanFactory.getBeanDefinition("triage")
                assertEquals(DecisionService::class.java.name, triage.beanClassName)
                assertFalse(triage.isLazyInit)
                val routing = context.beanFactory.getBeanDefinition("routing")
                assertEquals(ClassificationService::class.java.name, routing.beanClassName)
                assertFalse(routing.isLazyInit)
            }
        }

        @Test
        fun `a constructor can inject a service by qualifier`() {
            ApplicationContextRunner()
                .withBean(TriageConsumer::class.java)
                .withBean("gptTest", LlmService::class.java, Supplier { llm })
                .withBean(LlmOperations::class.java, Supplier { llmOperations })
                .withUserConfiguration(ModelProviderFromLlmBeans::class.java, LlmDecisionServiceConfiguration::class.java)
                .withPropertyValues(*twoServices)
                .run { context ->
                    context.startupFailure?.let { throw it }
                    assertSame(context.getBean("triage"), context.getBean(TriageConsumer::class.java).triage)
                }
        }

        @Test
        fun `no services are registered when none are declared`() {
            runner.run { context ->
                assertTrue(context.startupFailure == null)
                assertTrue(context.getBeansOfType(ClassificationService::class.java).isEmpty())
            }
        }

        @Test
        fun `services record observations in the application registry`() {
            val recorder = Recorder()
            val registry = ObservationRegistry.create().apply { observationConfig().observationHandler(recorder) }
            runner.withBean(ObservationRegistry::class.java, Supplier { registry })
                .withPropertyValues(*twoServices)
                .run { context ->
                    val result = context.getBean("triage", DecisionService::class.java).assess(proposition)
                    assertEquals(PropositionResult.Answered(true, ModelProvenance("gpt-test", "TestProvider")), result)
                    assertEquals(listOf("embabel.ai.decision"), recorder.stopped.map { it.name })
                }
        }
    }

    @Nested
    inner class StartupFailures {

        @Test
        fun `an unknown llm fails startup and names the property`() {
            runner.withPropertyValues("embabel.agent.platform.decisions.llm.services.triage.llm=missing").run { context ->
                val failure = context.failureChain().filterIsInstance<IllegalStateException>().single()
                assertEquals(
                    "embabel.agent.platform.decisions.llm.services.triage.llm names unknown LLM 'missing'",
                    failure.message,
                )
                assertTrue(failure.cause is NoSuitableModelException)
            }
        }

        @Test
        fun `an unknown llm fails startup even when beans are lazy by default`() {
            runner.withBean(LazyInitializationBeanFactoryPostProcessor::class.java)
                .withPropertyValues(
                    "embabel.agent.platform.decisions.llm.services.routing.llm=missing",
                    "embabel.agent.platform.decisions.llm.services.routing.kind=classification",
                )
                .run { context ->
                    val failure = context.failureChain().filterIsInstance<IllegalStateException>().single()
                    assertEquals(
                        "embabel.agent.platform.decisions.llm.services.routing.llm names unknown LLM 'missing'",
                        failure.message,
                    )
                }
        }

        @Test
        fun `a service without an llm fails startup and names the property`() {
            runner.withPropertyValues(
                "embabel.agent.platform.decisions.llm.services.triage.kind=decision",
                "embabel.agent.platform.decisions.llm.services.triage.role=best",
            ).run { context ->
                val failure = context.failureChain().filterIsInstance<IllegalStateException>().single()
                assertEquals("embabel.agent.platform.decisions.llm.services.triage.llm must name an LLM", failure.message)
            }
        }
    }

    @Nested
    inner class Properties {

        private fun environment(vararg properties: Pair<String, Any>) = StandardEnvironment().apply {
            propertySources.addFirst(MapPropertySource("test", mapOf(*properties)))
        }

        @Test
        fun `retry fields bind for each service`() {
            val services = LlmDecisionServiceConfiguration.bindServices(
                environment(
                    "embabel.agent.platform.decisions.llm.services.triage.llm" to "gpt-test",
                    "embabel.agent.platform.decisions.llm.services.triage.max-attempts" to "2",
                    "embabel.agent.platform.decisions.llm.services.triage.backoff-millis" to "7",
                    "embabel.agent.platform.decisions.llm.services.triage.backoff-multiplier" to "1.5",
                    "embabel.agent.platform.decisions.llm.services.triage.backoff-max-interval" to "40",
                ),
            )
            assertEquals(
                LlmDecisionRetryProperties(
                    maxAttempts = 2,
                    backoffMillis = 7L,
                    backoffMultiplier = 1.5,
                    backoffMaxInterval = 40L,
                    propertyPrefix = "embabel.agent.platform.decisions.llm.services.triage",
                ),
                services.getValue("triage").retry("triage"),
            )
        }

        @Test
        fun `unset fields take the platform defaults`() {
            val services = LlmDecisionServiceConfiguration.bindServices(
                environment("embabel.agent.platform.decisions.llm.services.triage.llm" to "gpt-test"),
            )
            val triage = services.getValue("triage")
            assertEquals(LlmDecisionServiceConfiguration.Kind.DECISION, triage.kind)
            assertEquals(
                LlmDecisionRetryProperties(propertyPrefix = "embabel.agent.platform.decisions.llm.services.triage"),
                triage.retry("triage"),
            )
        }

        @Test
        fun `bound max attempts limits the calls a service makes`() {
            every {
                llmOperations.doTransform(any<List<Message>>(), any(), PropositionAnswer::class.java, null)
            } throws TransientAiException("provider busy")
            runner.withPropertyValues(
                "embabel.agent.platform.decisions.llm.services.triage.llm=gpt-test",
                "embabel.agent.platform.decisions.llm.services.triage.max-attempts=2",
                "embabel.agent.platform.decisions.llm.services.triage.backoff-millis=1",
                "embabel.agent.platform.decisions.llm.services.triage.backoff-max-interval=2",
            ).run { context ->
                val result = context.getBean("triage", DecisionService::class.java).assess(proposition)
                assertEquals(PropositionResult.Failure(FailureReason.UNAVAILABLE), result)
                verify(exactly = 2) {
                    llmOperations.doTransform(any<List<Message>>(), any(), PropositionAnswer::class.java, null)
                }
            }
        }

        @Test
        fun `no model or role properties are added`() {
            runner.withPropertyValues(*twoServices).run { context ->
                val names = context.environment.propertySources
                    .filterIsInstance<EnumerablePropertySource<*>>()
                    .flatMap { it.propertyNames.toList() }
                assertEquals(
                    twoServices.map { it.substringBefore('=') }.toSet(),
                    names.filter { it.startsWith("embabel.") }.toSet(),
                )
            }
        }
    }

    @Nested
    inner class PlatformFactory {

        @Test
        fun `platform factory records observations in the application registry`() {
            val recorder = Recorder()
            val registry = ObservationRegistry.create().apply { observationConfig().observationHandler(recorder) }
            val registryProvider = mockk<ObjectProvider<ObservationRegistry>> {
                every { getIfUnique(any()) } returns registry
            }
            val factory = AgentPlatformConfiguration().llmDecisionServiceFactory(
                llmOperations,
                StubModelProvider(listOf(llm)),
                registryProvider,
            )
            factory.decisionService("gpt-test").assess(proposition)
            assertEquals(listOf("embabel.ai.decision"), recorder.stopped.map { it.name })
        }

        @Test
        fun `platform factory works without a registry`() {
            val registryProvider = mockk<ObjectProvider<ObservationRegistry>> {
                every { getIfUnique(any()) } answers { firstArg<Supplier<ObservationRegistry>>().get() }
            }
            val factory = AgentPlatformConfiguration().llmDecisionServiceFactory(
                llmOperations,
                StubModelProvider(listOf(llm)),
                registryProvider,
            )
            assertEquals(
                PropositionResult.Answered(true, ModelProvenance("gpt-test", "TestProvider")),
                factory.decisionService("gpt-test").assess(proposition),
            )
        }
    }

    /**
     * Builds the model provider from the LLM beans in the context, the same way the platform does,
     * so a service definition the lookup could not type-match would be created too early and fail.
     */
    class ModelProviderFromLlmBeans {
        @Bean
        fun modelProvider(context: ApplicationContext): ModelProvider =
            StubModelProvider(context.getBeansOfType(LlmService::class.java).values.toList())
    }

    class TriageConsumer(@Qualifier("triage") val triage: DecisionService)

    private class StubModelProvider(private val llms: List<LlmService<*>>) : ModelProvider {
        override fun getLlm(criteria: ModelSelectionCriteria): LlmService<*> =
            llms.firstOrNull { it.name == (criteria as? ByNameModelSelectionCriteria)?.name }
                ?: throw NoSuitableModelException(criteria, llms.map { it.name })

        override fun getEmbeddingService(criteria: ModelSelectionCriteria): EmbeddingService =
            throw NoSuitableModelException(criteria, emptyList())

        override fun listRoles(modelClass: Class<*>): List<String> = emptyList()
        override fun listModelNames(modelClass: Class<*>): List<String> = llms.map { it.name }
        override fun listModels(): List<ModelMetadata> = llms
        override fun infoString(verbose: Boolean?, indent: Int): String = "stub"
    }

    private class Recorder : ObservationHandler<Observation.Context> {
        val stopped = mutableListOf<Observation.Context>()
        override fun supportsContext(context: Observation.Context) = true
        override fun onStop(context: Observation.Context) {
            stopped += context
        }
    }
}
