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
@file:OptIn(InternalObservabilityApi::class)

package com.embabel.agent.spi.decision

import com.embabel.agent.api.event.observation.InternalObservabilityApi
import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.agent.spi.support.DefaultToolDecorator
import com.embabel.agent.spi.support.ExecutorAsyncer
import com.embabel.agent.spi.support.FakeChatModel
import com.embabel.agent.spi.support.decision.ClassificationAnswer
import com.embabel.agent.spi.support.decision.ClassificationVerdict
import com.embabel.agent.spi.support.decision.PropositionAnswer
import com.embabel.agent.spi.support.decision.PropositionVerdict
import com.embabel.agent.spi.support.springai.ChatClientLlmOperations
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.chat.Message
import com.embabel.common.ai.classification.Category
import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.model.DefaultOptionsConverter
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.ai.model.ModelSelectionCriteria.Companion.byName
import com.embabel.common.ai.model.ModelType
import com.embabel.common.ai.model.NoSuitableModelException
import com.embabel.common.ai.model.PreResolvedModelSelectionCriteria
import com.embabel.common.ai.model.observation.ObservedClassificationService
import com.embabel.common.ai.model.observation.ObservedDecisionService
import com.embabel.common.textio.template.JinjavaTemplateRenderer
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.mockk.Called
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.validation.Validation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.retry.TransientAiException
import java.util.concurrent.Executors

class LlmDecisionServiceFactoryTest {

    private class Recorder : ObservationHandler<Observation.Context> {
        val stopped = mutableListOf<Observation.Context>()
        override fun supportsContext(context: Observation.Context) = true
        override fun onStop(context: Observation.Context) {
            stopped += context
        }
    }

    /**
     * A chat model that behaves like an instrumented one. Each call records its own observation
     * and the observation that was current when it started. The first calls fail with a
     * transient error and the call after them answers.
     */
    private class ObservedFlakyChatModel(
        private val registry: ObservationRegistry,
        private val failures: Int,
        private val answer: String,
    ) : ChatModel {
        val parents = mutableListOf<Observation?>()
        private var calls = 0

        override fun getOptions(): ChatOptions = ToolCallingChatOptions.builder().build()

        override fun call(prompt: Prompt): ChatResponse {
            parents += registry.currentObservation
            val observation = Observation.start("test.chat.model", registry)
            try {
                if (++calls <= failures) throw TransientAiException("provider busy")
                return ChatResponse(listOf(Generation(AssistantMessage(answer))))
            } finally {
                observation.stop()
            }
        }
    }

    private val recorder = Recorder()

    private val registry = ObservationRegistry.create().apply { observationConfig().observationHandler(recorder) }

    private val llm = mockk<LlmService<*>> {
        every { name } returns "gpt-test"
        every { provider } returns "TestProvider"
    }

    private val provenance = ModelProvenance("gpt-test", "TestProvider")

    private val classification = ClassificationRequest(
        "My card was charged twice",
        "Which team should handle this?",
        listOf(Category("billing", "Payments, invoices and refunds"), Category("technical", "Errors and outages")),
    )

    private val proposition = PropositionRequest("My card was charged twice", "The customer wants a refund")

    private val interactions = mutableListOf<LlmInteraction>()

    private val llmOperations = mockk<LlmOperations> {
        every {
            doTransform(any<List<Message>>(), capture(interactions), ClassificationAnswer::class.java, null)
        } returns ClassificationAnswer(ClassificationVerdict.SELECTED, "billing")
        every {
            doTransform(any<List<Message>>(), capture(interactions), PropositionAnswer::class.java, null)
        } returns PropositionAnswer(PropositionVerdict.FALSE)
    }

    private val modelProvider = mockk<ModelProvider> {
        every { getLlm(byName("gpt-test")) } returns llm
    }

    private val factory = LlmDecisionServiceFactory(llmOperations, modelProvider, QuickRetry(), registry)

    private fun observationNames() = recorder.stopped.map { it.name }

    @Nested
    inner class Metadata {

        @Test
        fun `decision services are observed decision services from the model`() {
            val service = factory.decisionService(llm)
            assertTrue(service is ObservedDecisionService)
            assertEquals(ModelType.DECISION, service.type)
            assertEquals("gpt-test", service.name)
            assertEquals("TestProvider", service.provider)
        }

        @Test
        fun `classification services are observed classification services and not decision services`() {
            val service = factory.classificationService(llm)
            assertTrue(service is ObservedClassificationService)
            assertFalse(service is DecisionService)
            assertEquals(ModelType.CLASSIFICATION, service.type)
            assertEquals("gpt-test", service.name)
            assertEquals("TestProvider", service.provider)
        }

        @Test
        fun `no service is itself an llm`() {
            assertFalse((factory.decisionService(llm) as Any) is LlmService<*>)
            assertFalse((factory.classificationService(llm) as Any) is LlmService<*>)
        }
    }

    @Nested
    inner class NamedModel {

        @Test
        fun `decision service looks the model up once at build and never again`() {
            val service = factory.decisionService("gpt-test")
            verify(exactly = 1) { modelProvider.getLlm(byName("gpt-test")) }
            service.assess(proposition)
            service.classify(classification)
            verify(exactly = 1) { modelProvider.getLlm(byName("gpt-test")) }
            confirmVerified(modelProvider)
            assertEquals("gpt-test", service.name)
        }

        @Test
        fun `classification service looks the model up once at build and never again`() {
            val service = factory.classificationService("gpt-test")
            service.classify(classification)
            verify(exactly = 1) { modelProvider.getLlm(byName("gpt-test")) }
            confirmVerified(modelProvider)
            assertEquals(ModelType.CLASSIFICATION, service.type)
        }

        @Test
        fun `every call selects the resolved model directly`() {
            factory.decisionService("gpt-test").assess(proposition)
            factory.classificationService("gpt-test").classify(classification)
            assertEquals(2, interactions.size)
            interactions.forEach { assertEquals(PreResolvedModelSelectionCriteria(llm), it.llm.criteria) }
        }

        @Test
        fun `unknown model name fails the build`() {
            every { modelProvider.getLlm(byName("missing")) } throws NoSuitableModelException(byName("missing"), listOf("gpt-test"))
            assertThrows<NoSuitableModelException> { factory.decisionService("missing") }
            assertThrows<NoSuitableModelException> { factory.classificationService("missing") }
        }

        @Test
        fun `blank model name is rejected before any lookup`() {
            listOf("", " ", "\t").forEach { name ->
                assertThrows<IllegalArgumentException> { factory.decisionService(name) }
                assertThrows<IllegalArgumentException> { factory.classificationService(name) }
            }
            verify { modelProvider wasNot Called }
        }

        @Test
        fun `named model runs through real operations without another lookup`() {
            val chatModel = FakeChatModel("""{"verdict":"TRUE"}""")
            val fake = SpringAiLlmService("fake", "provider", chatModel, DefaultOptionsConverter)
            val provider = mockk<ModelProvider> { every { getLlm(byName("fake")) } returns fake }
            val operations = ChatClientLlmOperations(
                modelProvider = provider,
                toolDecorator = DefaultToolDecorator(),
                validator = Validation.buildDefaultValidatorFactory().validator,
                templateRenderer = JinjavaTemplateRenderer(),
                asyncer = ExecutorAsyncer(Executors.newCachedThreadPool()),
            )
            val service = LlmDecisionServiceFactory(operations, provider, QuickRetry()).decisionService("fake")
            assertEquals(PropositionResult.Answered(true, ModelProvenance("fake", "provider")), service.assess(proposition))
            assertEquals(1, chatModel.promptsPassed.size)
            verify(exactly = 1) { provider.getLlm(byName("fake")) }
            confirmVerified(provider)
        }
    }

    @Nested
    inner class SuppliedModel {

        @Test
        fun `supplied model is used without the model provider`() {
            val service = factory.decisionService(llm)
            assertEquals(PropositionResult.Answered(false, provenance), service.assess(proposition))
            assertSame(llm, (interactions.single().llm.criteria as PreResolvedModelSelectionCriteria<*>).resolved)
            verify { modelProvider wasNot Called }
        }
    }

    @Nested
    inner class Observations {

        @Test
        fun `each assess records one decision observation`() {
            val service = factory.decisionService(llm)
            service.assess(proposition)
            assertEquals(listOf("embabel.ai.decision"), observationNames())
            service.assess(proposition)
            assertEquals(listOf("embabel.ai.decision", "embabel.ai.decision"), observationNames())
        }

        @Test
        fun `each classify on a decision service records one classification observation`() {
            assertEquals(ClassificationResult.Selected("billing", provenance), factory.decisionService(llm).classify(classification))
            assertEquals(listOf("embabel.ai.classification"), observationNames())
        }

        @Test
        fun `each classify on a classification service records one classification observation`() {
            val service = factory.classificationService("gpt-test")
            assertEquals(ClassificationResult.Selected("billing", provenance), service.classify(classification))
            assertEquals(listOf("embabel.ai.classification"), observationNames())
        }

        @Test
        fun `a decision that retries twice records one decision observation and three child chat model observations`() {
            val chatModel = ObservedFlakyChatModel(registry, failures = 2, answer = """{"verdict":"TRUE"}""")
            val fake = SpringAiLlmService("fake", "provider", chatModel, DefaultOptionsConverter)
            val operations = ChatClientLlmOperations(
                modelProvider = modelProvider,
                toolDecorator = DefaultToolDecorator(),
                validator = Validation.buildDefaultValidatorFactory().validator,
                templateRenderer = JinjavaTemplateRenderer(),
                asyncer = ExecutorAsyncer(Executors.newCachedThreadPool()),
            )
            val service = LlmDecisionServiceFactory(operations, modelProvider, QuickRetry(maxAttempts = 3), registry)
                .decisionService(fake)

            assertEquals(PropositionResult.Answered(true, ModelProvenance("fake", "provider")), service.assess(proposition))

            val decision = recorder.stopped.single { it.name == "embabel.ai.decision" }
            val chatObservations = recorder.stopped.filter { it.name == "test.chat.model" }
            assertEquals(3, chatObservations.size)
            assertEquals(3, chatModel.parents.size)
            chatModel.parents.forEach { assertSame(decision, it?.contextView) }
            chatObservations.forEach { assertSame(decision, it.parentObservation?.contextView) }
        }

        @Test
        fun `nothing is observed without a registry`() {
            val service = LlmDecisionServiceFactory(llmOperations, modelProvider, QuickRetry()).decisionService(llm)
            service.assess(proposition)
            assertTrue(recorder.stopped.isEmpty())
        }
    }

    @Nested
    inner class SuppliedRetry {

        @Test
        fun `supplied retry properties bound the number of calls`() {
            every {
                llmOperations.doTransform(any<List<Message>>(), any(), PropositionAnswer::class.java, null)
            } throws TransientAiException("provider busy")
            val service = LlmDecisionServiceFactory(llmOperations, modelProvider, QuickRetry(maxAttempts = 2), registry)
                .decisionService(llm)
            assertEquals(PropositionResult.Failure(FailureReason.UNAVAILABLE), service.assess(proposition))
            verify(exactly = 2) { llmOperations.doTransform(any<List<Message>>(), any(), PropositionAnswer::class.java, null) }
        }
    }

    @Nested
    inner class RetryNames {

        private val retry = object : RetryProperties {
            override val maxAttempts = 1
            override val backoffMillis = 1L
            override val backoffMultiplier = 2.0
            override val backoffMaxInterval = 2L
            override val propertyPrefix = "embabel.agent.platform.decisions.test"
            val names = mutableListOf<String>()

            override fun retryTemplate(name: String) = super.retryTemplate(name).also { names += name }
        }

        private val named = LlmDecisionServiceFactory(llmOperations, modelProvider, retry, registry)

        @Test
        fun `decision services carry a decision retry name`() {
            named.decisionService("gpt-test")
            named.decisionService(llm)
            assertEquals(listOf("decision-gpt-test", "decision-gpt-test"), retry.names)
        }

        @Test
        fun `classification services carry a classification retry name`() {
            named.classificationService("gpt-test")
            named.classificationService(llm)
            assertEquals(listOf("classification-gpt-test", "classification-gpt-test"), retry.names)
        }
    }
}
