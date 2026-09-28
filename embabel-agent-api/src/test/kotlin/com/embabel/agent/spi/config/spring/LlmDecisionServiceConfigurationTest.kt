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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.agent.spi.decision.LlmDecisionRetryProperties
import com.embabel.agent.spi.decision.LlmDecisionServiceFactory
import com.embabel.agent.spi.support.decision.ClassificationAnswer
import com.embabel.agent.spi.support.decision.ClassificationVerdict
import com.embabel.agent.spi.support.decision.PropositionAnswer
import com.embabel.agent.spi.support.decision.PropositionVerdict
import com.embabel.chat.Message
import com.embabel.common.ai.classification.Category
import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
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
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.ai.retry.TransientAiException
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
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

    private val classification = ClassificationRequest(
        "My card was charged twice",
        "Which team should handle this?",
        listOf(Category("billing", "Payments, invoices and refunds"), Category("technical", "Errors and outages")),
    )

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
            runner.withPropertyValues("embabel.agent.platform.decisions.llm.services.triage.kind=decision").run { context ->
                val failure = context.failureChain().filterIsInstance<IllegalStateException>().single()
                assertEquals("embabel.agent.platform.decisions.llm.services.triage.llm must name an LLM", failure.message)
            }
        }

        @Test
        fun `an invalid service backoff multiplier fails startup and names the property`() {
            runner.withPropertyValues(
                "embabel.agent.platform.decisions.llm.services.triage.llm=gpt-test",
                "embabel.agent.platform.decisions.llm.services.triage.backoff-multiplier=1",
            ).run { context ->
                assertFailureStartsWith(
                    context,
                    "embabel.agent.platform.decisions.llm.services.triage: backoff-multiplier must be greater than 1",
                )
            }
        }

        @Test
        fun `a service max attempts below one fails startup and names the property`() {
            runner.withPropertyValues(
                "embabel.agent.platform.decisions.llm.services.triage.llm=gpt-test",
                "embabel.agent.platform.decisions.llm.services.triage.max-attempts=0",
            ).run { context ->
                assertFailureStartsWith(
                    context,
                    "embabel.agent.platform.decisions.llm.services.triage: max-attempts must be at least 1",
                )
            }
        }

        @Test
        fun `a service backoff below one millisecond fails startup and names the property`() {
            runner.withPropertyValues(
                "embabel.agent.platform.decisions.llm.services.triage.llm=gpt-test",
                "embabel.agent.platform.decisions.llm.services.triage.backoff-millis=0",
            ).run { context ->
                assertFailureStartsWith(
                    context,
                    "embabel.agent.platform.decisions.llm.services.triage: backoff-millis must be at least 1",
                )
            }
        }

        @Test
        fun `a service max interval no longer than the first wait fails startup and names the property`() {
            runner.withPropertyValues(
                "embabel.agent.platform.decisions.llm.services.triage.llm=gpt-test",
                "embabel.agent.platform.decisions.llm.services.triage.backoff-millis=50",
                "embabel.agent.platform.decisions.llm.services.triage.backoff-max-interval=50",
            ).run { context ->
                assertFailureStartsWith(
                    context,
                    "embabel.agent.platform.decisions.llm.services.triage: backoff-max-interval must be greater than backoff-millis",
                )
            }
        }

        @Test
        fun `a top-level max attempts below one fails startup and names the property`() {
            runner.withPropertyValues("embabel.agent.platform.decisions.llm.max-attempts=0").run { context ->
                assertFailureStartsWith(context, "embabel.agent.platform.decisions.llm: max-attempts must be at least 1")
            }
        }

        @Test
        fun `a top-level max attempts below one fails startup when services are declared`() {
            runner.withPropertyValues("embabel.agent.platform.decisions.llm.max-attempts=0", *twoServices).run { context ->
                assertFailureStartsWith(context, "embabel.agent.platform.decisions.llm: max-attempts must be at least 1")
            }
        }

        @Test
        fun `an unknown key under a service fails startup`() {
            runner.withPropertyValues(
                "embabel.agent.platform.decisions.llm.services.triage.llm=gpt-test",
                "embabel.agent.platform.decisions.llm.services.triage.role=fast",
            ).run { context ->
                val unbound = context.failureChain().filterIsInstance<UnboundConfigurationPropertiesException>().single()
                assertEquals(
                    listOf("embabel.agent.platform.decisions.llm.services.triage.role"),
                    unbound.unboundProperties.map { it.name.toString() },
                )
            }
        }

        @Test
        fun `top-level retry settings beside the services are not unknown keys`() {
            runner.withPropertyValues(
                "embabel.agent.platform.decisions.llm.max-attempts=3",
                "embabel.agent.platform.decisions.llm.backoff-millis=10",
                "embabel.agent.platform.decisions.llm.backoff-multiplier=2",
                "embabel.agent.platform.decisions.llm.backoff-max-interval=100",
                *twoServices,
            ).run { context ->
                context.startupFailure?.let { throw it }
                assertEquals(ModelType.DECISION, context.getBean("triage", DecisionService::class.java).type)
            }
        }

        private fun assertFailureStartsWith(context: AssertableApplicationContext, prefix: String) {
            val messages = context.failureChain().map { it.message.orEmpty() }
            assertTrue(messages.any { it.startsWith(prefix) }) { "expected a failure starting '$prefix' in $messages" }
            val failure = context.failureChain().filterIsInstance<IllegalStateException>().first { it.message!!.startsWith(prefix) }
            assertTrue(failure.cause is IllegalArgumentException)
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
                services.getValue("triage").retry("triage", LlmDecisionRetryProperties()),
            )
        }

        @Test
        fun `unset fields take the platform defaults`() {
            val environment = environment("embabel.agent.platform.decisions.llm.services.triage.llm" to "gpt-test")
            val triage = LlmDecisionServiceConfiguration.bindServices(environment).getValue("triage")
            assertEquals(LlmDecisionServiceConfiguration.Kind.DECISION, triage.kind)
            assertEquals(
                LlmDecisionRetryProperties(propertyPrefix = "embabel.agent.platform.decisions.llm.services.triage"),
                triage.retry("triage", LlmDecisionServiceConfiguration.bindRetry(environment)),
            )
        }

        @Test
        fun `unset service fields take the top-level values`() {
            val environment = environment(
                "embabel.agent.platform.decisions.llm.max-attempts" to "2",
                "embabel.agent.platform.decisions.llm.backoff-millis" to "7",
                "embabel.agent.platform.decisions.llm.backoff-multiplier" to "1.5",
                "embabel.agent.platform.decisions.llm.backoff-max-interval" to "40",
                "embabel.agent.platform.decisions.llm.services.triage.llm" to "gpt-test",
                "embabel.agent.platform.decisions.llm.services.triage.backoff-millis" to "3",
            )
            val triage = LlmDecisionServiceConfiguration.bindServices(environment).getValue("triage")
            assertEquals(
                LlmDecisionRetryProperties(
                    maxAttempts = 2,
                    backoffMillis = 3L,
                    backoffMultiplier = 1.5,
                    backoffMaxInterval = 40L,
                    propertyPrefix = "embabel.agent.platform.decisions.llm.services.triage",
                ),
                triage.retry("triage", LlmDecisionServiceConfiguration.bindRetry(environment)),
            )
        }

        @Test
        fun `top-level retry fields bind with the platform defaults`() {
            assertEquals(LlmDecisionRetryProperties(), LlmDecisionServiceConfiguration.bindRetry(environment()))
            assertEquals(
                LlmDecisionRetryProperties(maxAttempts = 2, backoffMillis = 7L, backoffMultiplier = 1.5, backoffMaxInterval = 40L),
                LlmDecisionServiceConfiguration.bindRetry(
                    environment(
                        "embabel.agent.platform.decisions.llm.max-attempts" to "2",
                        "embabel.agent.platform.decisions.llm.backoff-millis" to "7",
                        "embabel.agent.platform.decisions.llm.backoff-multiplier" to "1.5",
                        "embabel.agent.platform.decisions.llm.backoff-max-interval" to "40",
                    ),
                ),
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
        fun `a service without retry fields makes the top-level number of calls`() {
            every {
                llmOperations.doTransform(any<List<Message>>(), any(), PropositionAnswer::class.java, null)
            } throws TransientAiException("provider busy")
            runner.withPropertyValues(
                "embabel.agent.platform.decisions.llm.max-attempts=2",
                "embabel.agent.platform.decisions.llm.backoff-millis=1",
                "embabel.agent.platform.decisions.llm.backoff-max-interval=2",
                "embabel.agent.platform.decisions.llm.services.triage.llm=gpt-test",
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

    /**
     * Environment variables reach the binder through a source named `systemEnvironment`, which maps
     * `MAX_ATTEMPTS` to `max.attempts`. These tests put such a source in place of the real one.
     */
    @Nested
    inner class EnvironmentVariables {

        private fun ApplicationContextRunner.withEnvironmentVariables(vararg variables: Pair<String, String>) =
            withInitializer { context ->
                context.environment.propertySources.replace(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        mapOf<String, Any>(*variables),
                    ),
                )
            }

        private fun failEveryAssess() {
            every {
                llmOperations.doTransform(any<List<Message>>(), any(), PropositionAnswer::class.java, null)
            } throws TransientAiException("provider busy")
        }

        private fun assertAssessCalls(context: AssertableApplicationContext, calls: Int) {
            context.startupFailure?.let { throw it }
            val result = context.getBean("triage", DecisionService::class.java).assess(proposition)
            assertEquals(PropositionResult.Failure(FailureReason.UNAVAILABLE), result)
            verify(exactly = calls) {
                llmOperations.doTransform(any<List<Message>>(), any(), PropositionAnswer::class.java, null)
            }
        }

        @Test
        fun `service retry fields set by environment variables start and apply`() {
            failEveryAssess()
            runner.withEnvironmentVariables(
                "EMBABEL_AGENT_PLATFORM_DECISIONS_LLM_SERVICES_TRIAGE_LLM" to "gpt-test",
                "EMBABEL_AGENT_PLATFORM_DECISIONS_LLM_SERVICES_TRIAGE_MAX_ATTEMPTS" to "3",
                "EMBABEL_AGENT_PLATFORM_DECISIONS_LLM_SERVICES_TRIAGE_BACKOFF_MILLIS" to "1",
                "EMBABEL_AGENT_PLATFORM_DECISIONS_LLM_SERVICES_TRIAGE_BACKOFF_MULTIPLIER" to "2",
                "EMBABEL_AGENT_PLATFORM_DECISIONS_LLM_SERVICES_TRIAGE_BACKOFF_MAX_INTERVAL" to "2",
            ).run { context -> assertAssessCalls(context, 3) }
        }

        @Test
        fun `an environment variable and a property can set different fields of one service`() {
            failEveryAssess()
            runner.withEnvironmentVariables("EMBABEL_AGENT_PLATFORM_DECISIONS_LLM_SERVICES_TRIAGE_MAX_ATTEMPTS" to "3")
                .withPropertyValues(
                    "embabel.agent.platform.decisions.llm.services.triage.llm=gpt-test",
                    "embabel.agent.platform.decisions.llm.services.triage.backoff-millis=1",
                    "embabel.agent.platform.decisions.llm.services.triage.backoff-max-interval=2",
                )
                .run { context -> assertAssessCalls(context, 3) }
        }

        @Test
        fun `a property overrides an environment variable for the same field`() {
            failEveryAssess()
            runner.withEnvironmentVariables("EMBABEL_AGENT_PLATFORM_DECISIONS_LLM_SERVICES_TRIAGE_MAX_ATTEMPTS" to "4")
                .withPropertyValues(
                    "embabel.agent.platform.decisions.llm.services.triage.llm=gpt-test",
                    "embabel.agent.platform.decisions.llm.services.triage.max-attempts=2",
                    "embabel.agent.platform.decisions.llm.services.triage.backoff-millis=1",
                    "embabel.agent.platform.decisions.llm.services.triage.backoff-max-interval=2",
                )
                .run { context -> assertAssessCalls(context, 2) }
        }

        @Test
        fun `an unknown key in a property still fails beside environment variables`() {
            runner.withEnvironmentVariables("EMBABEL_AGENT_PLATFORM_DECISIONS_LLM_SERVICES_TRIAGE_MAX_ATTEMPTS" to "3")
                .withPropertyValues(
                    "embabel.agent.platform.decisions.llm.services.triage.llm=gpt-test",
                    "embabel.agent.platform.decisions.llm.services.triage.role=fast",
                )
                .run { context ->
                    val unbound = context.failureChain().filterIsInstance<UnboundConfigurationPropertiesException>().single()
                    assertEquals(
                        listOf("embabel.agent.platform.decisions.llm.services.triage.role"),
                        unbound.unboundProperties.map { it.name.toString() },
                    )
                }
        }

        @Test
        fun `bind services reads a multi-word field from the system environment`() {
            val environment = StandardEnvironment().apply {
                propertySources.replace(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        mapOf<String, Any>(
                            "EMBABEL_AGENT_PLATFORM_DECISIONS_LLM_SERVICES_TRIAGE_LLM" to "gpt-test",
                            "EMBABEL_AGENT_PLATFORM_DECISIONS_LLM_SERVICES_TRIAGE_BACKOFF_MAX_INTERVAL" to "900",
                        ),
                    ),
                )
            }
            val triage = LlmDecisionServiceConfiguration.bindServices(environment).getValue("triage")
            assertEquals("gpt-test", triage.llm)
            assertEquals(900L, triage.backoffMaxInterval)
        }
    }

    @Nested
    inner class PlatformFactory {

        @Test
        fun `the configuration supplies the factory bean`() {
            runner.run { context ->
                context.startupFailure?.let { throw it }
                val factory = context.getBean(LlmDecisionServiceFactory::class.java)
                assertEquals(
                    PropositionResult.Answered(true, ModelProvenance("gpt-test", "TestProvider")),
                    factory.decisionService("gpt-test").assess(proposition),
                )
            }
        }

        @Test
        fun `platform factory records observations in the application registry`() {
            val recorder = Recorder()
            val registry = ObservationRegistry.create().apply { observationConfig().observationHandler(recorder) }
            runner.withBean(ObservationRegistry::class.java, Supplier { registry }).run { context ->
                context.getBean(LlmDecisionServiceFactory::class.java).decisionService("gpt-test").assess(proposition)
                assertEquals(listOf("embabel.ai.decision"), recorder.stopped.map { it.name })
            }
        }

        @Test
        fun `an application factory bean replaces the platform one`() {
            val own = LlmDecisionServiceFactory(llmOperations, StubModelProvider(listOf(llm)))
            runner.withBean(LlmDecisionServiceFactory::class.java, Supplier { own }).run { context ->
                assertSame(own, context.getBean(LlmDecisionServiceFactory::class.java))
            }
        }

        @Test
        fun `configured services come from the factory bean`() {
            val ownLlm = mockk<LlmService<*>> {
                every { name } returns "gpt-test"
                every { provider } returns "OwnProvider"
            }
            val ownOperations = mockk<LlmOperations> {
                every {
                    doTransform(any<List<Message>>(), any(), PropositionAnswer::class.java, null)
                } returns PropositionAnswer(PropositionVerdict.FALSE)
                every {
                    doTransform(any<List<Message>>(), any(), ClassificationAnswer::class.java, null)
                } returns ClassificationAnswer(ClassificationVerdict.SELECTED, "billing")
            }
            val own = LlmDecisionServiceFactory(ownOperations, StubModelProvider(listOf(ownLlm)))
            runner.withBean(LlmDecisionServiceFactory::class.java, Supplier { own })
                .withPropertyValues(*twoServices)
                .run { context ->
                    context.startupFailure?.let { throw it }
                    val ownProvenance = ModelProvenance("gpt-test", "OwnProvider")
                    assertEquals(
                        PropositionResult.Answered(false, ownProvenance),
                        context.getBean("triage", DecisionService::class.java).assess(proposition),
                    )
                    assertEquals(
                        ClassificationResult.Selected("billing", ownProvenance),
                        context.getBean("routing", ClassificationService::class.java).classify(classification),
                    )
                    verify { llmOperations wasNot Called }
                }
        }
    }

    @Nested
    inner class RetryLogs {

        private lateinit var logger: Logger
        private lateinit var appender: ListAppender<ILoggingEvent>

        private fun capturingWarnings(block: () -> Unit): List<String> {
            logger = LoggerFactory.getLogger(RetryProperties::class.java) as Logger
            appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                block()
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }
            return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        }

        private fun failEveryCall() {
            every {
                llmOperations.doTransform(any<List<Message>>(), any(), PropositionAnswer::class.java, null)
            } throws TransientAiException("provider busy")
            every {
                llmOperations.doTransform(any<List<Message>>(), any(), ClassificationAnswer::class.java, null)
            } throws TransientAiException("provider busy")
        }

        private val quickRetry = arrayOf(
            "embabel.agent.platform.decisions.llm.max-attempts=2",
            "embabel.agent.platform.decisions.llm.backoff-millis=1",
            "embabel.agent.platform.decisions.llm.backoff-max-interval=2",
        )

        @Test
        fun `the platform factory names a top-level property when retries run out`() {
            failEveryCall()
            runner.withPropertyValues(*quickRetry).run { context ->
                val factory = context.getBean(LlmDecisionServiceFactory::class.java)
                val warnings = capturingWarnings {
                    factory.decisionService("gpt-test").assess(proposition)
                    factory.classificationService("gpt-test").classify(classification)
                }
                assertEquals(2, warnings.size, warnings.toString())
                assertTrue(warnings[0].startsWith("LLM invocation decision-gpt-test:"), warnings[0])
                assertTrue(warnings[1].startsWith("LLM invocation classification-gpt-test:"), warnings[1])
                warnings.forEach {
                    assertTrue(it.endsWith("using property embabel.agent.platform.decisions.llm.max-attempts"), it)
                }
            }
        }

        @Test
        fun `configured services name their own entry when retries run out`() {
            failEveryCall()
            runner.withPropertyValues(*quickRetry, *twoServices).run { context ->
                val warnings = capturingWarnings {
                    context.getBean("triage", DecisionService::class.java).assess(proposition)
                    context.getBean("routing", ClassificationService::class.java).classify(classification)
                }
                assertEquals(2, warnings.size, warnings.toString())
                assertTrue(warnings[0].startsWith("LLM invocation decision-triage:"), warnings[0])
                assertTrue(
                    warnings[0].endsWith("using property embabel.agent.platform.decisions.llm.services.triage.max-attempts"),
                    warnings[0],
                )
                assertTrue(warnings[1].startsWith("LLM invocation classification-routing:"), warnings[1])
                assertTrue(
                    warnings[1].endsWith("using property embabel.agent.platform.decisions.llm.services.routing.max-attempts"),
                    warnings[1],
                )
            }
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
