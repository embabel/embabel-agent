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

package com.embabel.agent.spi.support.decision

import com.embabel.agent.api.event.observation.InternalObservabilityApi
import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.core.support.InvalidLlmReturnFormatException
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.agent.spi.support.DefaultToolDecorator
import com.embabel.agent.spi.support.ExecutorAsyncer
import com.embabel.agent.spi.support.FakeChatModel
import com.embabel.agent.spi.support.springai.ChatClientLlmOperations
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.chat.Message
import com.embabel.common.ai.classification.Category
import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationSpec
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.model.DefaultOptionsConverter
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.ai.model.ModelType
import com.embabel.common.ai.model.PreResolvedModelSelectionCriteria
import com.embabel.common.textio.template.JinjavaTemplateRenderer
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.validation.Validation
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.chat.messages.MessageType
import org.springframework.ai.retry.TransientAiException
import org.springframework.retry.backoff.ExponentialBackOffPolicy
import org.springframework.retry.support.RetryTemplate
import java.net.SocketTimeoutException
import java.nio.channels.ClosedByInterruptException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

class LlmDecisionServiceTest {

    private class TestRetryProperties : RetryProperties {
        override val maxAttempts = 3
        override val backoffMillis = 1L
        override val backoffMultiplier = 2.0
        override val backoffMaxInterval = 4L
        override val propertyPrefix = "embabel.agent.platform.decisions.test"
        val templateNames = mutableListOf<String>()

        override fun retryTemplate(name: String) = super.retryTemplate(name).also { templateNames += name }
    }

    private val categories = listOf(
        Category("billing", "Payments, invoices and refunds"),
        Category("technical", "Errors, outages and bugs"),
    )

    private val classification = ClassificationRequest.of(
        "My card was charged twice",
        ClassificationSpec.of(
            Questions.named("department").choice("Which team should handle this?")
                .apply { categories.forEach { option(it.id, it.description) } }
                .build(),
        ),
    )

    private val proposition = PropositionRequest("My card was charged twice", "The customer wants a refund")

    private val provenance = ModelProvenance("gpt-test", "TestProvider")

    private val llm = mockk<LlmService<*>> {
        every { name } returns "gpt-test"
        every { provider } returns "TestProvider"
    }

    private val options = LlmOptions(modelSelectionCriteria = PreResolvedModelSelectionCriteria(llm)).withTemperature(0.0)

    private val retry = TestRetryProperties()

    private val llmOperations = mockk<LlmOperations>()

    private val interactions = mutableListOf<LlmInteraction>()

    private val service = LlmDecisionService(llmOperations, llm, options, retry)

    private fun <A> whenAsked(answerType: Class<A>) =
        every {
            llmOperations.doTransform(any<List<Message>>(), capture(interactions), answerType, null)
        }

    @AfterEach
    fun `every call runs without tools and with the configured options`() {
        interactions.forEach {
            assertTrue(it.tools.isEmpty(), "tools")
            assertTrue(it.toolGroups.isEmpty(), "tool groups")
            assertEquals(false, it.generateExamples)
            assertEquals(options, it.llm)
        }
    }

    @Nested
    inner class Classify {

        @Test
        fun `selected answer becomes a selection with the model's provenance`() {
            whenAsked(ClassificationAnswer::class.java) returns ClassificationAnswer(ClassificationVerdict.SELECTED, "billing")
            assertEquals(ClassificationResult.Selected("billing", provenance), service.classify(classification))
            assertEquals("classify", interactions.single().id.value)
            assertEquals(listOf("decision-gpt-test"), retry.templateNames)
        }

        @Test
        fun `no match answer becomes no match`() {
            whenAsked(ClassificationAnswer::class.java) returns ClassificationAnswer(ClassificationVerdict.NO_MATCH, null)
            assertEquals(ClassificationResult.NoMatch(provenance), service.classify(classification))
        }

        @Test
        fun `inconclusive answer becomes inconclusive`() {
            whenAsked(ClassificationAnswer::class.java) returns ClassificationAnswer(ClassificationVerdict.INCONCLUSIVE, null)
            assertEquals(ClassificationResult.Inconclusive(provenance), service.classify(classification))
        }

        @Test
        fun `user message is the input envelope`() {
            val messages = mutableListOf<List<Message>>()
            every {
                llmOperations.doTransform(capture(messages), capture(interactions), ClassificationAnswer::class.java, null)
            } returns ClassificationAnswer(ClassificationVerdict.NO_MATCH, null)
            service.classify(classification)
            val expected = PromptedClassification.messages(classification)
            assertEquals(expected.map { it.javaClass to it.content }, messages.single().map { it.javaClass to it.content })
            assertEquals(inputEnvelope(classification.input), messages.single().last().content)
        }
    }

    @Nested
    inner class Assess {

        @Test
        fun `false answer is a successful answer`() {
            whenAsked(PropositionAnswer::class.java) returns PropositionAnswer(PropositionVerdict.FALSE)
            assertEquals(PropositionResult.Answered(false, provenance), service.assess(proposition))
            assertEquals("assess", interactions.single().id.value)
        }

        @Test
        fun `true answer is a successful answer`() {
            whenAsked(PropositionAnswer::class.java) returns PropositionAnswer(PropositionVerdict.TRUE)
            assertEquals(PropositionResult.Answered(true, provenance), service.assess(proposition))
        }

        @Test
        fun `inconclusive answer becomes inconclusive`() {
            whenAsked(PropositionAnswer::class.java) returns PropositionAnswer(PropositionVerdict.INCONCLUSIVE)
            assertEquals(PropositionResult.Inconclusive(provenance), service.assess(proposition))
        }

        @Test
        fun `missing verdict is an invalid response without a retry`() {
            whenAsked(PropositionAnswer::class.java) returns PropositionAnswer(null)
            assertEquals(PropositionResult.Failure(FailureReason.INVALID_RESPONSE), service.assess(proposition))
            assertEquals(1, interactions.size)
        }

        @Test
        fun `provider failure is unavailable`() {
            whenAsked(PropositionAnswer::class.java) throws IllegalStateException("provider rejected the request")
            assertEquals(PropositionResult.Failure(FailureReason.UNAVAILABLE), service.assess(proposition))
            assertEquals(1, interactions.size)
        }
    }

    @Nested
    inner class Failures {

        @Test
        fun `invalid format on every attempt is an invalid response after three calls`() {
            whenAsked(ClassificationAnswer::class.java) throws
                InvalidLlmReturnFormatException("not json", ClassificationAnswer::class.java, RuntimeException("parse"))
            assertEquals(ClassificationResult.Failure(FailureReason.INVALID_RESPONSE), service.classify(classification))
            assertEquals(3, interactions.size)
        }

        @Test
        fun `contradictory answer is an invalid response after one call`() {
            whenAsked(ClassificationAnswer::class.java) returns ClassificationAnswer(ClassificationVerdict.NO_MATCH, "billing")
            assertEquals(ClassificationResult.Failure(FailureReason.INVALID_RESPONSE), service.classify(classification))
            assertEquals(1, interactions.size)
        }

        @Test
        fun `transient failures that clear up give the answer after three calls`() {
            whenAsked(ClassificationAnswer::class.java) throws
                TransientAiException("busy") andThenThrows
                TransientAiException("still busy") andThen
                ClassificationAnswer(ClassificationVerdict.SELECTED, "technical")
            assertEquals(ClassificationResult.Selected("technical", provenance), service.classify(classification))
            assertEquals(3, interactions.size)
        }

        @Test
        fun `persistent transient failure is unavailable`() {
            whenAsked(ClassificationAnswer::class.java) throws TransientAiException("down")
            assertEquals(ClassificationResult.Failure(FailureReason.UNAVAILABLE), service.classify(classification))
            assertEquals(3, interactions.size)
        }

        @Test
        fun `socket timeout with the flag clear is retried and ends unavailable`() {
            whenAsked(ClassificationAnswer::class.java) throws SocketTimeoutException("read timed out")
            assertEquals(ClassificationResult.Failure(FailureReason.UNAVAILABLE), service.classify(classification))
            assertEquals(retry.maxAttempts, interactions.size)
            assertFalse(Thread.currentThread().isInterrupted)
        }

        @Test
        fun `provider illegal state is unavailable after one call`() {
            whenAsked(ClassificationAnswer::class.java) throws IllegalStateException("provider rejected the request")
            assertEquals(ClassificationResult.Failure(FailureReason.UNAVAILABLE), service.classify(classification))
            assertEquals(1, interactions.size)
        }
    }

    @Nested
    inner class Interruption {

        @Test
        fun `wrapped interruption is thrown as a cancellation after one call with the flag set`() {
            val interrupted = InterruptedException("stop")
            whenAsked(ClassificationAnswer::class.java) throws RuntimeException(interrupted)
            try {
                val thrown = assertThrows<CancellationException> { service.classify(classification) }
                assertSame(interrupted, thrown.cause)
                assertTrue(Thread.currentThread().isInterrupted)
                assertEquals(1, interactions.size)
            } finally {
                Thread.interrupted()
            }
        }

        @Test
        fun `failure with the flag set is stopped by the retry guard and thrown as a cancellation after one call`() {
            whenAsked(PropositionAnswer::class.java) answers {
                Thread.currentThread().interrupt()
                throw TransientAiException("busy")
            }
            try {
                val thrown = assertThrows<CancellationException> { service.assess(proposition) }
                assertTrue(thrown.cause is InterruptedException)
                assertTrue(Thread.currentThread().isInterrupted)
                assertEquals(1, interactions.size)
            } finally {
                Thread.interrupted()
            }
        }

        @Test
        fun `io failure with the flag set is thrown as a cancellation after one call`() {
            val closed = ClosedByInterruptException()
            whenAsked(ClassificationAnswer::class.java) answers {
                Thread.currentThread().interrupt()
                throw closed
            }
            try {
                val thrown = assertThrows<CancellationException> { service.classify(classification) }
                assertTrue(thrown.cause is InterruptedException)
                assertSame(closed, thrown.cause?.cause)
                assertTrue(Thread.currentThread().isInterrupted)
                assertEquals(1, interactions.size)
            } finally {
                Thread.interrupted()
            }
        }

        @Test
        fun `interrupt that lands while waiting to retry is thrown as a cancellation with the flag set`() {
            // The backoff's sleeper throws at once, just as Thread.sleep does when interrupted.
            val interrupted = InterruptedException("sleep interrupted")
            val interruptedBackoff = object : RetryProperties by retry {
                override fun retryTemplate(name: String): RetryTemplate =
                    RetryTemplate.builder()
                        .maxAttempts(retry.maxAttempts)
                        .customBackoff(ExponentialBackOffPolicy().withSleeper { throw interrupted })
                        .build()
            }
            val service = LlmDecisionService(llmOperations, llm, options, interruptedBackoff)
            whenAsked(ClassificationAnswer::class.java) throws TransientAiException("busy")
            try {
                val thrown = assertThrows<CancellationException> { service.classify(classification) }
                assertSame(interrupted, thrown.cause)
                assertTrue(Thread.currentThread().isInterrupted)
                assertEquals(1, interactions.size)
            } finally {
                Thread.interrupted()
            }
        }

        @Test
        fun `flag already set before a successful call leaves the result and the flag alone`() {
            whenAsked(ClassificationAnswer::class.java) returns ClassificationAnswer(ClassificationVerdict.SELECTED, "billing")
            Thread.currentThread().interrupt()
            try {
                assertEquals(ClassificationResult.Selected("billing", provenance), service.classify(classification))
                assertTrue(Thread.currentThread().isInterrupted)
                assertEquals(1, interactions.size)
            } finally {
                Thread.interrupted()
            }
        }

        @Test
        fun `flag already set before an answer that breaks the rules gives an invalid response and keeps the flag`() {
            whenAsked(ClassificationAnswer::class.java) returns ClassificationAnswer(ClassificationVerdict.NO_MATCH, "billing")
            Thread.currentThread().interrupt()
            try {
                assertEquals(ClassificationResult.Failure(FailureReason.INVALID_RESPONSE), service.classify(classification))
                assertTrue(Thread.currentThread().isInterrupted)
                assertEquals(1, interactions.size)
            } finally {
                Thread.interrupted()
            }
        }

        @Test
        fun `flag already set before a failing call is thrown as a cancellation caused by the failure`() {
            val busy = TransientAiException("busy")
            whenAsked(ClassificationAnswer::class.java) throws busy
            Thread.currentThread().interrupt()
            try {
                val thrown = assertThrows<CancellationException> { service.classify(classification) }
                assertTrue(thrown.cause is InterruptedException)
                assertSame(busy, thrown.cause?.cause)
                assertTrue(Thread.currentThread().isInterrupted)
                assertEquals(1, interactions.size)
            } finally {
                Thread.interrupted()
            }
        }
    }

    @Nested
    inner class RetryName {

        @Test
        fun `retry template takes the given name`() {
            val names = TestRetryProperties()
            LlmDecisionService(llmOperations, llm, options, names, retryName = "classification-support")
            assertEquals(listOf("classification-support"), names.templateNames)
        }
    }

    @Nested
    inner class Identity {

        @Test
        fun `decision service reports the model's identity as a decision family`() {
            assertEquals("gpt-test", service.name)
            assertEquals("TestProvider", service.provider)
            assertEquals(ModelType.DECISION, service.type)
            assertEquals(ModelType.DECISION, service.metadata().type)
            assertFalse(LlmService::class.isInstance(service))
        }

        @Test
        fun `classification service keeps the classification family and delegates`() {
            val classifier = LlmClassificationService(service)
            assertEquals("gpt-test", classifier.name)
            assertEquals("TestProvider", classifier.provider)
            assertEquals(ModelType.CLASSIFICATION, classifier.type)
            assertEquals(ModelType.CLASSIFICATION, classifier.metadata().type)
            assertFalse(LlmService::class.isInstance(classifier))
            assertFalse(DecisionService::class.isInstance(classifier))

            whenAsked(ClassificationAnswer::class.java) returns ClassificationAnswer(ClassificationVerdict.SELECTED, "billing")
            assertEquals(ClassificationResult.Selected("billing", provenance), classifier.classify(classification))
        }
    }

    @Nested
    inner class RealPath {

        private val modelProvider = mockk<ModelProvider>()

        private fun serviceAnswering(response: String): Pair<LlmDecisionService, FakeChatModel> {
            val chatModel = FakeChatModel(response)
            val llm = SpringAiLlmService("fake", "provider", chatModel, DefaultOptionsConverter)
            val ops = ChatClientLlmOperations(
                modelProvider = modelProvider,
                toolDecorator = DefaultToolDecorator(),
                validator = Validation.buildDefaultValidatorFactory().validator,
                templateRenderer = JinjavaTemplateRenderer(),
                asyncer = ExecutorAsyncer(Executors.newCachedThreadPool()),
            )
            val options = LlmOptions(modelSelectionCriteria = PreResolvedModelSelectionCriteria(llm))
            return LlmDecisionService(ops, llm, options, TestRetryProperties()) to chatModel
        }

        private fun userText(chatModel: FakeChatModel): String =
            chatModel.promptsPassed.single().instructions.single { it.messageType == MessageType.USER }.text!!

        @Test
        fun `valid answer selects and the user message is the envelope`() {
            val (service, chatModel) = serviceAnswering("""{"verdict":"SELECTED","categoryId":"billing"}""")
            assertEquals(
                ClassificationResult.Selected("billing", ModelProvenance("fake", "provider")),
                service.classify(classification),
            )
            assertEquals(inputEnvelope(classification.input), userText(chatModel))
            verify { modelProvider wasNot Called }
        }

        @Test
        fun `reply that is not json is an invalid response`() {
            val (service, chatModel) = serviceAnswering("This ain't no JSON")
            assertEquals(ClassificationResult.Failure(FailureReason.INVALID_RESPONSE), service.classify(classification))
            assertEquals(3, chatModel.promptsPassed.size)
        }

        @Test
        fun `empty input is sent to the model and the result follows its answer`() {
            val (service, chatModel) = serviceAnswering("""{"verdict":"TRUE"}""")
            val empty = PropositionRequest("", "The text says nothing")
            assertEquals(PropositionResult.Answered(true, ModelProvenance("fake", "provider")), service.assess(empty))
            assertEquals(1, chatModel.promptsPassed.size)
            assertEquals(inputEnvelope(""), userText(chatModel))
        }
    }
}
