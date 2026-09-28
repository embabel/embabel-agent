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
package com.embabel.common.ai.decision.spi

import com.embabel.common.ai.classification.Category
import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.classificationSpec
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.ChoiceQuestionSpec
import com.embabel.common.ai.decision.DecisionCapabilities
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.PropositionQuestionSpec
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.RatingQuestionSpec
import com.embabel.common.ai.decision.RatingResult
import com.embabel.common.ai.decision.UnsupportedDecisionException
import com.embabel.common.ai.model.DecisionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.EnumSet

class DecisionServiceAskTest {

    private val provenance = ModelProvenance("model-a", "provider-a")

    private val urgent: PropositionQuestionSpec =
        Questions.named("urgent").proposition("Does this convey urgency?").build()

    private val team: ChoiceQuestionSpec = Questions.named("team")
        .choice("Which team should handle this?")
        .option("billing", "Payments and refunds")
        .option("support", "Product help")
        .build()

    private val anger: RatingQuestionSpec = Questions.named("anger")
        .rating("How angry is the writer?")
        .level("calm", "Calm")
        .level("angry", "Angry")
        .build()

    /** A service written against the base API: name, provider, classify and assess only. */
    private class LegacyService(
        private val propositionResult: PropositionResult,
    ) : DecisionService {
        val calls = mutableListOf<String>()
        val propositions = mutableListOf<PropositionRequest>()
        val classified = mutableListOf<ClassificationRequest>()

        override val name = "legacy"
        override val provider = "test"

        override fun classify(request: ClassificationRequest): ClassificationResult {
            calls += "classify"
            classified += request
            return ClassificationResult.NoMatch(ModelProvenance("legacy", "test"))
        }

        override fun assess(request: PropositionRequest): PropositionResult {
            calls += "assess"
            propositions += request
            return propositionResult
        }
    }

    /** A service with the rating hook, answering from scripted functions. */
    private class HookedService(
        private val onAssess: (PropositionRequest) -> PropositionResult,
        private val onClassify: (ClassificationRequest) -> ClassificationResult,
        private val onRate: (RatingQuestionSpec) -> RatingResult,
    ) : DecisionService, RatingAssessment {
        val calls = mutableListOf<String>()
        val classified = mutableListOf<ClassificationRequest>()

        override val name = "hooked"
        override val provider = "test"

        override fun classify(request: ClassificationRequest): ClassificationResult {
            calls += "classify"
            classified += request
            return onClassify(request)
        }

        override fun assess(request: PropositionRequest): PropositionResult {
            calls += "assess"
            return onAssess(request)
        }

        override fun rate(input: String, question: RatingQuestionSpec): RatingResult {
            calls += "rate"
            return onRate(question)
        }
    }

    private class NativeService(private val answer: (DecisionRequest) -> DecisionResponse) :
        DecisionService, NativeQuestionSetExecution {
        var nativeCalls = 0

        override val name = "native"
        override val provider = "test"

        override fun capabilities(): DecisionCapabilities =
            DecisionCapabilities.of(EnumSet.allOf(QuestionKind::class.java))

        override fun classify(request: ClassificationRequest): ClassificationResult = error("not used")

        override fun assess(request: PropositionRequest): PropositionResult = error("not used")

        override fun askNative(request: DecisionRequest): DecisionResponse {
            nativeCalls++
            return answer(request)
        }
    }

    private fun hooked(
        onAssess: (PropositionRequest) -> PropositionResult = { PropositionResult.Answered(true, provenance) },
        onClassify: (ClassificationRequest) -> ClassificationResult = {
            ClassificationResult.Selected("billing", provenance)
        },
        onRate: (RatingQuestionSpec) -> RatingResult = { RatingResult.Answered(provenance, selectedLevelId = "calm") },
    ) = HookedService(onAssess, onClassify, onRate)

    @Nested
    inner class LegacyImplementor {

        @Test
        fun `a single proposition runs with one assess call`() {
            val service = LegacyService(PropositionResult.Answered(false, provenance))
            val response = service.ask("An email.", DecisionSpec.of(urgent))
            assertEquals(PropositionResult.Answered(false, provenance), response.answer(urgent))
            assertEquals(listOf("assess"), service.calls)
            assertEquals("Does this convey urgency?", service.propositions.single().proposition)
            assertEquals("An email.", service.propositions.single().input)
        }

        @Test
        fun `a single choice runs with one classify call built from the question`() {
            val service = LegacyService(PropositionResult.Answered(true, provenance))
            val response = service.ask("An email.", DecisionSpec.of(team))
            assertEquals(ClassificationResult.NoMatch(ModelProvenance("legacy", "test")), response.answer(team))
            assertEquals(listOf("classify"), service.calls)
            val received = service.classified.single()
            assertEquals("An email.", received.input)
            assertSame(team, received.spec.question)
            assertEquals("Which team should handle this?", received.instructions)
            assertEquals(listOf("billing", "support"), received.categories.map { it.id })
        }

        @Test
        fun `a single rating is unsupported with no calls, and classify still works directly`() {
            val service = LegacyService(PropositionResult.Answered(true, provenance))
            val error = assertThrows(UnsupportedDecisionException::class.java) {
                service.ask("An email.", DecisionSpec.of(anger))
            }
            assertTrue(error.message!!.contains("'anger'"))
            assertEquals(emptyList<String>(), service.calls)
            val direct = service.classify(ClassificationRequest.of("An email.", classificationSpec { asking("Which category fits?"); category("billing", "Payments") }))
            assertInstanceOf(ClassificationResult.NoMatch::class.java, direct)
            assertEquals(listOf("classify"), service.calls)
        }

        @Test
        fun `two propositions run one assess call each, in spec order`() {
            val service = LegacyService(PropositionResult.Answered(true, provenance))
            val second = Questions.named("second").proposition("Is it a refund?").build()
            val response = service.ask(DecisionRequest.of("An email.", urgent, second))
            assertEquals(listOf("assess", "assess"), service.calls)
            assertEquals(
                listOf("Does this convey urgency?", "Is it a refund?"),
                service.propositions.map { it.proposition },
            )
            assertEquals(PropositionResult.Answered(true, provenance), response.answer(second))
        }

        @Test
        fun `capabilities are the legacy capabilities`() {
            assertEquals(
                DecisionExecution.LEGACY_CAPABILITIES,
                LegacyService(PropositionResult.Answered(true, provenance)).capabilities(),
            )
        }
    }

    @Nested
    inner class HookedImplementor {

        @Test
        fun `the rating hook adds RATING`() {
            assertEquals(EnumSet.allOf(QuestionKind::class.java), hooked().capabilities().questionKinds)
        }

        @Test
        fun `a single rating runs with one rate call`() {
            val service = hooked()
            val response = service.ask("An email.", DecisionSpec.of(anger))
            assertEquals(listOf("rate"), service.calls)
            assertEquals(RatingResult.Answered(provenance, selectedLevelId = "calm"), response.answer(anger))
        }

        @Test
        fun `per-question execution calls assess, classify and rate in spec order with the question text`() {
            val service = hooked()
            val response = service.ask(
                DecisionRequest.of("An email.", urgent, team, anger))
            assertEquals(listOf("assess", "classify", "rate"), service.calls)
            val received = service.classified.single()
            assertSame(team, received.spec.question)
            assertEquals("Which team should handle this?", received.instructions)
            assertEquals(listOf("billing", "support"), received.categories.map { it.id })
            assertEquals(ClassificationResult.Selected("billing", provenance), response.answer(team))
        }

        @Test
        fun `a failure from the first call and an answer from the second both appear`() {
            val service = hooked(onAssess = { PropositionResult.Failure(FailureReason.UNAVAILABLE) })
            val response = service.ask(DecisionRequest.of("An email.", urgent, team))
            assertEquals(PropositionResult.Failure(FailureReason.UNAVAILABLE), response.answer(urgent))
            assertEquals(ClassificationResult.Selected("billing", provenance), response.answer(team))
            assertEquals(null, response.requestFailure)
        }

        @Test
        fun `a thrown exception from the second call propagates and the third is not called`() {
            val boom = RuntimeException("boom")
            val service = hooked(onClassify = { throw boom })
            val thrown = assertThrows(RuntimeException::class.java) {
                service.ask(DecisionRequest.of("An email.", urgent, team, anger))
            }
            assertSame(boom, thrown)
            assertEquals(listOf("assess", "classify"), service.calls)
        }

        @Test
        fun `an interruption propagates with the thread flag set`() {
            val service = hooked(onClassify = {
                Thread.currentThread().interrupt()
                throw InterruptedException("stopped")
            })
            try {
                assertThrows(InterruptedException::class.java) {
                    service.ask(DecisionRequest.of("An email.", urgent, team, anger))
                }
                assertTrue(Thread.currentThread().isInterrupted)
                assertEquals(listOf("assess", "classify"), service.calls)
            } finally {
                Thread.interrupted()
            }
        }

        @Test
        fun `a selection outside the options becomes an invalid response failure`() {
            val service = hooked(onClassify = { ClassificationResult.Selected("elsewhere", provenance) })
            val response = service.ask("An email.", DecisionSpec.of(team))
            assertEquals(ClassificationResult.Failure(FailureReason.INVALID_RESPONSE), response.answer(team))
        }

        @Test
        fun `an IllegalArgumentException from a hook fails only that question`() {
            val service = hooked(
                onClassify = { throw IllegalArgumentException("Question 'team': not one of its options") },
                onRate = { throw IllegalArgumentException("Question 'anger': not one of its levels") },
            )
            val second = Questions.named("second").proposition("Is it a refund?").build()
            val response = service.ask(
                DecisionRequest.of("An email.", urgent, team, anger, second))
            assertEquals(listOf("assess", "classify", "rate", "assess"), service.calls)
            assertEquals(PropositionResult.Answered(true, provenance), response.answer(urgent))
            assertEquals(ClassificationResult.Failure(FailureReason.INVALID_RESPONSE), response.answer(team))
            assertEquals(RatingResult.Failure(FailureReason.INVALID_RESPONSE), response.answer(anger))
            assertEquals(PropositionResult.Answered(true, provenance), response.answer(second))
            assertEquals(null, response.requestFailure)
        }

        @Test
        fun `an IllegalArgumentException from assess propagates`() {
            val service = hooked(onAssess = { throw IllegalArgumentException("bad") })
            assertThrows(IllegalArgumentException::class.java) {
                service.ask(DecisionRequest.of("An email.", urgent, team))
            }
            assertEquals(listOf("assess"), service.calls)
        }

        @Test
        fun `a rating level outside the levels becomes an invalid response failure`() {
            val service = hooked(onRate = { RatingResult.Answered(provenance, selectedLevelId = "furious") })
            val response = service.ask(DecisionRequest.of("An email.", anger))
            assertEquals(RatingResult.Failure(FailureReason.INVALID_RESPONSE), response.answer(anger))
        }
    }

    @Nested
    inner class PropositionHook {

        /** A service that answers proposition questions through the question hook. */
        private inner class QuestionAssessingService : DecisionService, PropositionAssessment {
            val asked = mutableListOf<String>()
            var legacyCalls = 0

            override val name = "question-assessing"
            override val provider = "test"

            override fun classify(request: ClassificationRequest): ClassificationResult = error("not used")

            override fun assess(request: PropositionRequest): PropositionResult {
                legacyCalls++
                return PropositionResult.Failure(FailureReason.UNAVAILABLE)
            }

            override fun assess(input: String, question: PropositionQuestionSpec): PropositionResult {
                asked += "${question.name}: $input"
                return PropositionResult.Answered(question.name == "urgent", provenance)
            }
        }

        @Test
        fun `propositions go through the question hook by name, in spec order`() {
            val service = QuestionAssessingService()
            val second = Questions.named("second").proposition("Is it a refund?").build()
            val response = service.ask(DecisionRequest.of("An email.", urgent, second))
            assertEquals(listOf("urgent: An email.", "second: An email."), service.asked)
            assertEquals(0, service.legacyCalls)
            assertEquals(PropositionResult.Answered(true, provenance), response.answer(urgent))
            assertEquals(PropositionResult.Answered(false, provenance), response.answer(second))
        }

        @Test
        fun `capabilities from the question hook are propositions and choices`() {
            assertEquals(DecisionExecution.LEGACY_CAPABILITIES, QuestionAssessingService().capabilities())
        }
    }

    @Nested
    inner class Interruption {

        @Test
        fun `a per-question run stops before the next question when the thread is interrupted`() {
            val service = hooked(onClassify = {
                Thread.currentThread().interrupt()
                ClassificationResult.Failure(FailureReason.UNAVAILABLE)
            })
            try {
                val error = assertThrows(InterruptedException::class.java) {
                    service.ask(DecisionRequest.of("An email.", team, anger, urgent))
                }
                assertTrue(error.message!!.contains("'anger'"))
                assertTrue(Thread.currentThread().isInterrupted)
                assertEquals(listOf("classify"), service.calls)
            } finally {
                Thread.interrupted()
            }
        }
    }

    @Nested
    inner class NativeImplementor {

        @Test
        fun `a native service is called once`() {
            val service = NativeService { request ->
                DecisionResponse.builder(request.spec)
                    .answer(urgent, PropositionResult.Answered(true, provenance))
                    .answer(team, ClassificationResult.NoMatch(provenance))
                    .build()
            }
            val response = service.ask(DecisionRequest.of("An email.", urgent, team))
            assertEquals(1, service.nativeCalls)
            assertEquals(ClassificationResult.NoMatch(provenance), response.answer(team))
        }

        @Test
        fun `a native service returning another spec's response is an illegal state`() {
            val service = NativeService {
                DecisionResponse.failed(DecisionSpec.of(anger), FailureReason.UNAVAILABLE)
            }
            val e = assertThrows(IllegalStateException::class.java) {
                service.ask(DecisionRequest.of("An email.", urgent, team))
            }
            assertTrue(e.message!!.contains("'native'"), e.message)
            assertTrue(e.message!!.contains("Missing: 'urgent', 'team'."), e.message)
        }

        @Test
        fun `a native service answering a question with other options is an illegal state`() {
            val otherTeam = Questions.named("team")
                .choice("Which team should handle this?")
                .option("billing", "Payments and refunds")
                .option("sales", "New customers")
                .build()
            val service = NativeService {
                DecisionResponse.failed(DecisionSpec.of(urgent, otherTeam), FailureReason.UNAVAILABLE)
            }
            val e = assertThrows(IllegalStateException::class.java) {
                service.ask(DecisionRequest.of("An email.", urgent, team))
            }
            assertTrue(e.message!!.contains("Answer 'team'"), e.message)
            assertInstanceOf(IllegalArgumentException::class.java, e.cause)
        }

        @Test
        fun `a native service answers a choice in its native call and leaves classify alone`() {
            var classifyCalls = 0
            val service = object : DecisionService, NativeQuestionSetExecution {
                override val name = "native-and-classify"
                override val provider = "test"

                override fun capabilities(): DecisionCapabilities =
                    DecisionCapabilities.of(EnumSet.of(QuestionKind.PROPOSITION, QuestionKind.CHOICE))

                override fun classify(request: ClassificationRequest): ClassificationResult {
                    classifyCalls++
                    return ClassificationResult.NoMatch(provenance)
                }

                override fun assess(request: PropositionRequest): PropositionResult = error("not used")

                override fun askNative(request: DecisionRequest): DecisionResponse =
                    DecisionResponse.builder(request.spec)
                        .answer(team, ClassificationResult.Selected("support", provenance))
                        .build()
            }
            val response = service.ask("An email.", DecisionSpec.of(team))
            assertEquals(ClassificationResult.Selected("support", provenance), response.answer(team))
            assertEquals(0, classifyCalls)
        }
    }

    @Nested
    inner class HookSource {

        @Test
        fun `a wrapper claiming RATING over a delegate without the hook fails before any call`() {
            val delegate = LegacyService(PropositionResult.Answered(true, provenance))
            var wrapperRateCalls = 0
            val wrapper = object : DecisionService by delegate, RatingAssessment {
                override fun capabilities(): DecisionCapabilities =
                    DecisionCapabilities.of(EnumSet.of(QuestionKind.PROPOSITION, QuestionKind.RATING))

                override fun rate(input: String, question: RatingQuestionSpec): RatingResult {
                    wrapperRateCalls++
                    return RatingResult.Inconclusive(provenance)
                }
            }
            val error = assertThrows(IllegalStateException::class.java) {
                DecisionExecution.execute(
                    wrapper,
                    DecisionRequest.of("An email.", anger),
                    hookSource = delegate,
                )
            }
            assertTrue(error.message!!.contains("RatingAssessment"))
            assertEquals(0, wrapperRateCalls)
            assertEquals(emptyList<String>(), delegate.calls)
        }

        @Test
        fun `a decorator without the native hook of its hook source fails before the native call`() {
            val inner = NativeService { error("askNative is not reached") }
            val decorator = PartialDecorator(inner)
            val error = assertThrows(IllegalStateException::class.java) {
                decorator.ask(DecisionRequest.of("An email.", urgent, team))
            }
            val message = error.message!!
            assertTrue(message.contains("'audited'"), message)
            assertTrue(message.contains(PartialDecorator::class.java.name), message)
            assertTrue(message.contains("hook source 'native'"), message)
            assertTrue(message.contains("Implement NativeQuestionSetExecution on ${PartialDecorator::class.java.name}"), message)
            assertEquals(0, inner.nativeCalls)
        }

        @Test
        fun `a decorator missing a later question's hook fails before the earlier question is asked`() {
            val inner = hooked()
            val decorator = PartialDecorator(inner)
            val error = assertThrows(IllegalStateException::class.java) {
                decorator.ask(DecisionRequest.of("An email.", team, anger))
            }
            assertTrue(error.message!!.contains("does not implement RatingAssessment"), error.message)
            assertEquals(emptyList<String>(), inner.calls)
        }

        @Test
        fun `a decorator without hooks answers a choice through its own classify`() {
            val inner = hooked()
            val decorator = PartialDecorator(inner)
            val response = decorator.ask(DecisionRequest.of("An email.", team))
            assertEquals(ClassificationResult.Selected("billing", provenance), response.answer(team))
            assertEquals(listOf("classify"), inner.calls)
        }
    }

    /**
     * A decorator that forwards the base calls and reports its delegate as hook source, but
     * implements none of the hook interfaces. It keeps the default ask.
     */
    private open class PartialDecorator(private val inner: DecisionService) : DecisionService, DelegatingDecisionService {
        override val hookSource: DecisionService = inner
        override val name = "audited"
        override val provider = "test"

        override fun capabilities(): DecisionCapabilities = inner.capabilities()

        override fun classify(request: ClassificationRequest): ClassificationResult = inner.classify(request)

        override fun assess(request: PropositionRequest): PropositionResult = inner.assess(request)
    }
}
