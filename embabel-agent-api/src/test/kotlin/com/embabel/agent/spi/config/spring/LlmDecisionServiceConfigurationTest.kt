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
import com.embabel.agent.spi.support.decision.PropositionAnswer
import com.embabel.agent.spi.support.decision.PropositionVerdict
import com.embabel.chat.Message
import com.embabel.common.ai.classification.Category
import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.model.ByNameModelSelectionCriteria
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.ModelMetadata
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.ai.model.ModelSelectionCriteria
import com.embabel.common.ai.model.NoSuitableModelException
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.ai.retry.TransientAiException
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
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

    private val quickRetry = arrayOf(
        "embabel.agent.platform.decisions.llm.max-attempts=2",
        "embabel.agent.platform.decisions.llm.backoff-millis=1",
        "embabel.agent.platform.decisions.llm.backoff-max-interval=2",
    )

    private fun AssertableApplicationContext.failureChain(): List<Throwable> =
        generateSequence(startupFailure) { it.cause }.toList()

    private fun failEveryCall() {
        every {
            llmOperations.doTransform(any<List<Message>>(), any(), PropositionAnswer::class.java, null)
        } throws TransientAiException("provider busy")
        every {
            llmOperations.doTransform(any<List<Message>>(), any(), ClassificationAnswer::class.java, null)
        } throws TransientAiException("provider busy")
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
    }

    @Nested
    inner class RetrySettings {

        @Test
        fun `retry settings take the platform defaults`() {
            runner.run { context ->
                assertEquals(LlmDecisionRetryProperties(), context.getBean(LlmDecisionRetryProperties::class.java))
            }
        }

        @Test
        fun `retry settings bind from the decisions prefix`() {
            runner.withPropertyValues(
                "embabel.agent.platform.decisions.llm.max-attempts=2",
                "embabel.agent.platform.decisions.llm.backoff-millis=7",
                "embabel.agent.platform.decisions.llm.backoff-multiplier=1.5",
                "embabel.agent.platform.decisions.llm.backoff-max-interval=40",
            ).run { context ->
                assertEquals(
                    LlmDecisionRetryProperties(maxAttempts = 2, backoffMillis = 7L, backoffMultiplier = 1.5, backoffMaxInterval = 40L),
                    context.getBean(LlmDecisionRetryProperties::class.java),
                )
            }
        }

        @Test
        fun `declared services beside the retry settings do not fail binding`() {
            runner.withPropertyValues(
                "embabel.agent.platform.decisions.llm.max-attempts=3",
                "embabel.agent.platform.decisions.llm.services.triage.llm=gpt-test",
            ).run { context ->
                context.startupFailure?.let { throw it }
                assertEquals(3, context.getBean(LlmDecisionRetryProperties::class.java).maxAttempts)
            }
        }

        @Test
        fun `an out of range setting fails startup and names the prefix`() {
            runner.withPropertyValues("embabel.agent.platform.decisions.llm.max-attempts=0").run { context ->
                val bind = context.failureChain().filterIsInstance<BindException>().single()
                assertEquals("embabel.agent.platform.decisions.llm", bind.name.toString())
                val invalid = context.failureChain().filterIsInstance<IllegalArgumentException>().last()
                assertEquals("max-attempts must be at least 1", invalid.message)
            }
        }

        @Test
        fun `bound max attempts limits the calls a service makes`() {
            failEveryCall()
            runner.withPropertyValues(*quickRetry).run { context ->
                val service = context.getBean(LlmDecisionServiceFactory::class.java).decisionService("gpt-test")
                assertEquals(PropositionResult.Failure(FailureReason.UNAVAILABLE), service.assess(proposition))
                verify(exactly = 2) {
                    llmOperations.doTransform(any<List<Message>>(), any(), PropositionAnswer::class.java, null)
                }
            }
        }

        @Test
        fun `the platform factory names the retry property when retries run out`() {
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

        private fun capturingWarnings(block: () -> Unit): List<String> {
            val logger = LoggerFactory.getLogger(RetryProperties::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                block()
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }
            return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        }
    }

    /**
     * Builds the model provider from the LLM beans in the context, the same way the platform does.
     */
    class ModelProviderFromLlmBeans {
        @Bean
        fun modelProvider(context: ApplicationContext): ModelProvider =
            StubModelProvider(context.getBeansOfType(LlmService::class.java).values.toList())
    }

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
