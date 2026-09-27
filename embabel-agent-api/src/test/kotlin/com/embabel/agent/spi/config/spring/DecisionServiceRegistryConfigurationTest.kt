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
import com.embabel.agent.spi.support.SpringContextPlatformServices
import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.decision.spi.DecisionContentCapture
import com.embabel.common.ai.decision.support.StubDecisionService
import com.embabel.common.ai.model.ClassificationService
import com.embabel.common.ai.model.ConfigurableModelProviderProperties
import com.embabel.common.ai.model.DecisionService
import com.embabel.common.ai.model.DecisionServiceRegistry
import com.embabel.common.ai.model.ModelType
import com.embabel.common.ai.model.ServiceSelectionException
import com.embabel.common.util.EmbabelObjectMapperHolder
import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Scope
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

/**
 * The helper classes here carry no configuration annotation, so the API test application's scan of
 * `com.embabel.agent` does not pick them up.
 */
class DecisionServiceRegistryConfigurationTest {

    private val llm = mockk<LlmService<*>> {
        every { name } returns "gpt-test"
        every { provider } returns "TestProvider"
    }

    private val stub = StubDecisionService.builder("stub-model").build()

    private val classifier = FakeClassifier("clf-model")

    private val runner = ApplicationContextRunner()
        .withBean("gptTest", LlmService::class.java, Supplier { llm })
        .withBean(LlmOperations::class.java, Supplier { mockk<LlmOperations>() })
        .withBean("triage-stub", DecisionService::class.java, Supplier { stub })
        .withUserConfiguration(
            LlmDecisionServiceConfigurationTest.ModelProviderFromLlmBeans::class.java,
            LlmDecisionServiceConfiguration::class.java,
            DecisionServiceRegistryConfiguration::class.java,
        )
        .withPropertyValues("embabel.agent.platform.decisions.llm.services.llm-review.llm=gpt-test")

    private val withClassifier = runner.withBean("router", ClassificationService::class.java, Supplier { classifier })

    private fun AssertableApplicationContext.registry(): DecisionServiceRegistry {
        startupFailure?.let { throw it }
        return getBean(DecisionServiceRegistry::class.java)
    }

    private fun AssertableApplicationContext.failure(): IllegalStateException =
        generateSequence(startupFailure) { it.cause }
            .filterIsInstance<IllegalStateException>()
            .first { it.message?.startsWith("Invalid decision service configuration") == true }

    private fun capturing(loggerClass: Class<*>, level: Level, block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(loggerClass) as Logger
        val previous = logger.level
        if (level == Level.DEBUG) logger.level = Level.DEBUG
        try {
            return capturingAtLevel(logger, level, block)
        } finally {
            logger.level = previous
        }
    }

    private fun capturingAtLevel(logger: Logger, level: Level, block: () -> Unit): List<String> {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
        return appender.list.filter { it.level == level }.map { it.formattedMessage }
    }

    @Nested
    inner class Registration {

        @Test
        fun `stub and prompted services register under their bean names`() {
            runner.run { context ->
                val registry = context.registry()
                assertEquals(setOf("triage-stub", "llm-review"), registry.registrationNames().toSet())
                assertSame(stub, registry.decisions().named("triage-stub"))
                assertSame(context.getBean("llm-review"), registry.decisions().named("llm-review"))
                assertEquals("gpt-test", registry.decisions().named("llm-review").name)
            }
        }

        @Test
        fun `the registry observes with the context's observation registry`() {
            val observations = ObservationRegistry.create()
            runner.withBean(ObservationRegistry::class.java, Supplier { observations }).run { context ->
                assertSame(observations, context.registry().observationRegistry)
            }
        }

        @Test
        fun `a primary observation registry is used when there are several`() {
            val primary = ObservationRegistry.create()
            runner.withBean("primaryObservations", ObservationRegistry::class.java, Supplier { primary }, { it.isPrimary = true })
                .withBean("otherObservations", ObservationRegistry::class.java, Supplier { ObservationRegistry.create() })
                .run { context -> assertSame(primary, context.registry().observationRegistry) }
        }

        @Test
        fun `several observation registries and none primary log one warning naming them`() {
            val warnings = capturing(DecisionServiceRegistryConfiguration::class.java, Level.WARN) {
                runner.withBean("firstObservations", ObservationRegistry::class.java, Supplier { ObservationRegistry.create() })
                    .withBean("secondObservations", ObservationRegistry::class.java, Supplier { ObservationRegistry.create() })
                    .run { context -> assertSame(ObservationRegistry.NOOP, context.registry().observationRegistry) }
            }
            val warning = warnings.single { it.contains("ObservationRegistry") }
            assertTrue(warning.contains("firstObservations") && warning.contains("secondObservations"), warning)
            assertTrue(warning.contains("@Primary"), warning)
        }

        @Test
        fun `the context starts with the prompted configuration and beans lazy by default`() {
            runner.withInitializer { it.addBeanFactoryPostProcessor(LazyInitializationBeanFactoryPostProcessor()) }
                .run { context ->
                    assertEquals(setOf("triage-stub", "llm-review"), context.registry().registrationNames().toSet())
                }
        }

        @Test
        fun `platform services return the registry bean`() {
            runner.run { context ->
                val registry = context.registry()
                assertSame(registry, platformServices(context).decisionServices())
            }
        }

        @Test
        fun `platform services without a context return the empty registry`() {
            val registry = platformServices(null).decisionServices()
            assertTrue(registry.registrationNames().isEmpty())
            val error = assertThrows(ServiceSelectionException::class.java) { registry.decisions().defaultService() }
            assertEquals(ServiceSelectionException.Reason.NO_DEFAULT, error.reason)
        }

        @Test
        fun `platform services return the empty registry when the context has no registry bean`() {
            ApplicationContextRunner().run { context ->
                assertTrue(platformServices(context).decisionServices().registrationNames().isEmpty())
            }
        }

        @Test
        fun `an application registry bean replaces the configured one`() {
            val own = DecisionServiceRegistry.builder().register("own", StubDecisionService.builder("own").build()).build()
            ApplicationContextRunner()
                .withBean(DecisionServiceRegistry::class.java, Supplier { own })
                .withBean("triage-stub", DecisionService::class.java, Supplier { stub })
                .withUserConfiguration(DecisionServiceRegistryConfiguration::class.java)
                .run { context ->
                    assertSame(own, context.registry())
                    assertEquals(1, context.getBeansOfType(DecisionServiceRegistry::class.java).size)
                }
        }

        @Test
        fun `the summary is logged once at info without input or credentials`() {
            val lines = capturing(DecisionServiceRegistry::class.java, Level.INFO) {
                runner.withPropertyValues("embabel.agent.platform.models.typesafe.api-key=sk-sentinel-key")
                    .run { context -> context.registry() }
            }
            assertEquals(1, lines.size, lines.toString())
            assertTrue(lines.single().contains("triage-stub"), lines.single())
            assertTrue(lines.single().contains("llm-review (name gpt-test, provider TestProvider"), lines.single())
            assertFalse(lines.single().contains("sk-sentinel-key"), lines.single())
        }
    }

    @Nested
    inner class Selection {

        @Test
        fun `explicit defaults and roles resolve in each family`() {
            withClassifier.withPropertyValues(
                "embabel.models.decision.default=llm-review",
                "embabel.models.decision.roles.support-triage=triage-stub",
                "embabel.models.classification.default=router",
                "embabel.models.classification.roles.dice-revision=llm-review",
            ).run { context ->
                val registry = context.registry()
                assertSame(context.getBean("llm-review"), registry.decisions().defaultService())
                assertSame(stub, registry.decisions().byRole("support-triage"))
                assertSame(classifier, registry.classifications().defaultService())
                val revision = registry.classifications().byRole("dice-revision")
                assertSame(context.getBean("llm-review"), revision)
                assertEquals(ModelType.DECISION, revision.type)
            }
        }

        @Test
        fun `a role naming an unknown service fails startup naming the property`() {
            runner.withPropertyValues("embabel.models.decision.roles.support-triage=jevv").run { context ->
                val message = context.failure().message!!
                assertTrue(message.contains("embabel.models.decision.roles.support-triage"), message)
                assertTrue(message.contains("'jevv'"), message)
            }
        }

        @Test
        fun `a classification role naming an unknown service fails startup naming the property`() {
            runner.withPropertyValues("embabel.models.classification.roles.dice-revision=missing").run { context ->
                val message = context.failure().message!!
                assertTrue(message.contains("embabel.models.classification.roles.dice-revision"), message)
            }
        }

        @Test
        fun `a classification default naming a classifier resolves`() {
            withClassifier.withPropertyValues("embabel.models.classification.default=router").run { context ->
                assertSame(classifier, context.registry().classifications().defaultService())
            }
        }

        @Test
        fun `a decision default naming a classifier fails startup naming the property`() {
            withClassifier.withPropertyValues("embabel.models.decision.default=router").run { context ->
                val message = context.failure().message!!
                assertTrue(message.contains("embabel.models.decision.default"), message)
                assertTrue(message.contains("'router'"), message)
            }
        }

        @Test
        fun `a default candidate is the default of both families when no default is set`() {
            runner.withBean(
                DecisionServiceRegistry.DefaultCandidate::class.java,
                Supplier { DecisionServiceRegistry.DefaultCandidate("triage-stub") },
            ).run { context ->
                val registry = context.registry()
                assertSame(stub, registry.decisions().defaultService())
                assertSame(stub, registry.classifications().defaultService())
            }
        }

        @Test
        fun `an explicit default wins over the candidate`() {
            runner.withBean(
                DecisionServiceRegistry.DefaultCandidate::class.java,
                Supplier { DecisionServiceRegistry.DefaultCandidate("triage-stub") },
            ).withPropertyValues("embabel.models.decision.default=llm-review").run { context ->
                val registry = context.registry()
                assertSame(context.getBean("llm-review"), registry.decisions().defaultService())
                assertSame(stub, registry.classifications().defaultService())
            }
        }

        @Test
        fun `two services without a default start and fail at lookup naming the property`() {
            runner.run { context ->
                val registry = context.registry()
                val error = assertThrows(ServiceSelectionException::class.java) { registry.decisions().defaultService() }
                assertEquals(ServiceSelectionException.Reason.AMBIGUOUS_DEFAULT, error.reason)
                assertTrue(error.message!!.contains("embabel.models.decision.default"), error.message)
            }
        }
    }

    @Nested
    inner class SharedAndDeferredBeans {

        @Test
        fun `one instance under two bean names registers once under the first name`() {
            val shared = StubDecisionService.builder("shared-model").build()
            val lines = capturing(DecisionServiceRegistryConfiguration::class.java, Level.DEBUG) {
                runner.withBean("first", DecisionService::class.java, Supplier { shared })
                    .withBean("second", DecisionService::class.java, Supplier { shared })
                    .withPropertyValues("embabel.models.decision.roles.support-triage=second")
                    .run { context ->
                        val registry = context.registry()
                        assertEquals(setOf("triage-stub", "llm-review", "first"), registry.registrationNames().toSet())
                        assertSame(shared, registry.decisions().named("first"))
                        assertSame(shared, registry.decisions().byRole("support-triage"))
                    }
            }
            assertEquals(1, lines.size, lines.toString())
            assertTrue(lines.single().contains("'first'"), lines.single())
            assertTrue(lines.single().contains("second"), lines.single())
        }

        @Test
        fun `a primary bean returning another service bean registers under the primary name`() {
            runner.withUserConfiguration(PrimaryDelegate::class.java)
                .withPropertyValues(
                    "embabel.models.decision.default=decisionService",
                    "embabel.models.decision.roles.support-triage=typeSafeDecisionService",
                )
                .withBean(
                    DecisionServiceRegistry.DefaultCandidate::class.java,
                    Supplier { DecisionServiceRegistry.DefaultCandidate("typeSafeDecisionService") },
                )
                .run { context ->
                    val registry = context.registry()
                    assertEquals(
                        setOf("triage-stub", "llm-review", "decisionService"),
                        registry.registrationNames().toSet(),
                    )
                    assertSame(PrimaryDelegate.JEV, registry.decisions().defaultService())
                    assertSame(PrimaryDelegate.JEV, registry.decisions().byRole("support-triage"))
                    assertSame(PrimaryDelegate.JEV, registry.classifications().defaultService())
                }
        }

        @Test
        fun `a lazy service bean is not created and is named at info`() {
            LazyAndPrototype.created.set(0)
            val lines = capturing(DecisionServiceRegistryConfiguration::class.java, Level.INFO) {
                runner.withUserConfiguration(LazyAndPrototype::class.java).run { context ->
                    val registry = context.registry()
                    assertFalse("lazyService" in registry.registrationNames())
                    assertFalse("prototypeService" in registry.registrationNames())
                    assertEquals(0, LazyAndPrototype.created.get())
                }
            }
            val lazy = lines.single { it.contains("'lazyService'") }
            assertTrue(lazy.contains("@Lazy"), lazy)
            assertTrue(lazy.contains("using(service)"), lazy)
        }

        @Test
        fun `a prototype service bean is not created and is named at info`() {
            LazyAndPrototype.created.set(0)
            val lines = capturing(DecisionServiceRegistryConfiguration::class.java, Level.INFO) {
                runner.withUserConfiguration(LazyAndPrototype::class.java).run { context ->
                    context.registry()
                    assertEquals(0, LazyAndPrototype.created.get())
                    val service = context.getBean("prototypeService", DecisionService::class.java)
                    assertSame(service, context.registry().decisions().using(service))
                }
            }
            val prototype = lines.single { it.contains("'prototypeService'") }
            assertTrue(prototype.contains("prototype"), prototype)
            assertTrue(prototype.contains("using(service)"), prototype)
        }

        @Test
        fun `a context that makes every bean lazy still registers its singletons`() {
            runner.withUserConfiguration(PrimaryDelegate::class.java)
                .withInitializer { it.addBeanFactoryPostProcessor(LazyInitializationBeanFactoryPostProcessor()) }
                .run { context ->
                    assertTrue("decisionService" in context.registry().registrationNames())
                }
        }
    }

    @Nested
    inner class Snapshot {

        @Test
        fun `a singleton registered by a bean factory post-processor is included`() {
            val early = StubDecisionService.builder("early-model").build()
            runner.withBean(
                "earlyRegistrar",
                BeanFactoryPostProcessor::class.java,
                Supplier { BeanFactoryPostProcessor { it.registerSingleton("early", early) } },
            ).run { context ->
                assertSame(early, context.registry().decisions().named("early"))
            }
        }

        @Test
        fun `a singleton registered after the registry is built is only reachable through using`() {
            runner.run { context ->
                val registry = context.registry()
                val late = StubDecisionService.builder("late-model").build()
                context.sourceApplicationContext.beanFactory.registerSingleton("late", late)
                val error = assertThrows(ServiceSelectionException::class.java) { registry.decisions().named("late") }
                assertEquals(ServiceSelectionException.Reason.UNKNOWN_NAME, error.reason)
                assertSame(late, registry.decisions().using(late))
            }
        }
    }

    @Nested
    inner class Coexistence {

        @Test
        fun `model provider and prompted service properties bind beside the family keys`() {
            runner.withUserConfiguration(ModelProviderProperties::class.java)
                .withPropertyValues(
                    "embabel.models.default-llm=gpt-test",
                    "embabel.models.llms.cheapest=gpt-test",
                    "embabel.models.decision.default=llm-review",
                    "embabel.models.decision.roles.support-triage=triage-stub",
                    "embabel.models.classification.roles.dice-revision=llm-review",
                    "embabel.agent.platform.decisions.llm.services.llm-review.max-attempts=2",
                )
                .run { context ->
                    val registry = context.registry()
                    val properties = context.getBean(ConfigurableModelProviderProperties::class.java)
                    assertEquals("gpt-test", properties.defaultLlm)
                    assertEquals(mapOf("cheapest" to "gpt-test"), properties.llms)
                    assertSame(context.getBean("llm-review"), registry.decisions().defaultService())
                    assertSame(stub, registry.decisions().byRole("support-triage"))
                    val services = LlmDecisionServiceConfiguration.bindServices(context.environment)
                    assertEquals("gpt-test", services.getValue("llm-review").llm)
                    assertEquals(2, services.getValue("llm-review").maxAttempts)
                }
        }
    }

    @Nested
    @ResourceLock("DecisionContentCapture")
    inner class ContentCapture {

        @Test
        fun `capture stays off without the property`() {
            val warnings = capturing(DecisionServiceRegistryConfiguration::class.java, Level.WARN) {
                runner.run { context ->
                    context.registry()
                    assertFalse(DecisionContentCapture.isEnabled())
                }
            }
            assertTrue(warnings.isEmpty(), warnings.toString())
        }

        @Test
        fun `the property turns capture on with one warning and off at close`() {
            try {
                val warnings = capturing(DecisionServiceRegistryConfiguration::class.java, Level.WARN) {
                    runner.withPropertyValues("embabel.agent.platform.decisions.capture-content=true").run { context ->
                        context.registry()
                        assertTrue(DecisionContentCapture.isEnabled())
                    }
                }
                assertFalse(DecisionContentCapture.isEnabled())
                assertEquals(1, warnings.size, warnings.toString())
                assertTrue(warnings.single().contains("embabel.agent.platform.decisions.capture-content"))
                assertTrue(warnings.single().contains("logged at TRACE"))
                assertTrue(warnings.single().contains("production"))
            } finally {
                DecisionContentCapture.disable()
            }
        }

        @Test
        fun `the property applies when the application supplies its own registry`() {
            try {
                ApplicationContextRunner()
                    .withBean(DecisionServiceRegistry::class.java, Supplier { DecisionServiceRegistry.empty() })
                    .withUserConfiguration(DecisionServiceRegistryConfiguration::class.java)
                    .withPropertyValues("embabel.agent.platform.decisions.capture-content=true")
                    .run { context ->
                        assertNull(context.startupFailure)
                        assertTrue(DecisionContentCapture.isEnabled())
                    }
                assertFalse(DecisionContentCapture.isEnabled())
            } finally {
                DecisionContentCapture.disable()
            }
        }
    }

    private fun platformServices(context: ApplicationContext?) =
        SpringContextPlatformServices(
            agentPlatform = mockk(),
            llmOperations = mockk(),
            eventListener = mockk(),
            operationScheduler = mockk(),
            agentProcessRepository = mockk(),
            asyncer = mockk(),
            embabelObjectMapperHolder = EmbabelObjectMapperHolder.createDefault(),
            outputChannel = mockk(),
            templateRenderer = mockk(),
            customLogicalExpressionParser = mockk(),
            applicationContext = context,
        )

    /** Binds the model provider properties the way the platform configuration does. */
    @EnableConfigurationProperties(ConfigurableModelProviderProperties::class)
    class ModelProviderProperties

    /** Exposes one service under a second name through a `@Primary` bean. */
    class PrimaryDelegate {
        @Bean
        fun typeSafeDecisionService(): DecisionService = JEV

        @Bean
        @Primary
        fun decisionService(@Qualifier("typeSafeDecisionService") service: DecisionService): DecisionService = service

        companion object {
            val JEV: DecisionService = StubDecisionService.builder("jev-model").build()
        }
    }

    /** Declares a lazy and a prototype service and counts their creation. */
    class LazyAndPrototype {
        @Bean
        @Lazy
        fun lazyService(): DecisionService {
            created.incrementAndGet()
            return StubDecisionService.builder("lazy-model").build()
        }

        @Bean
        @Scope("prototype")
        fun prototypeService(): DecisionService {
            created.incrementAndGet()
            return StubDecisionService.builder("prototype-model").build()
        }

        companion object {
            val created = AtomicInteger()
        }
    }

    private class FakeClassifier(override val name: String) : ClassificationService {
        override val provider: String = "Fake"
        override fun classify(request: ClassificationRequest): ClassificationResult = error("not called")
    }
}
