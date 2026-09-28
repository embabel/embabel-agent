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

import com.embabel.common.ai.decision.ChoiceQuestionSpec
import com.embabel.common.ai.decision.DecisionCapabilities
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.PropositionQuestionSpec
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.Question
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.RatingQuestionSpec
import com.embabel.common.ai.decision.RatingResult
import com.embabel.common.ai.decision.UnsupportedDecisionException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.EnumSet

class DecisionExecutionPlanTest {

    private val sentinelInput = "SENTINEL-INPUT-7f3a"
    private val sentinelInstruction = "SENTINEL-INSTRUCTION-91bc"
    private val sentinelOption = "SENTINEL-OPTION-44de"

    private fun proposition(name: String = "urgent"): PropositionQuestionSpec =
        Questions.named(name).proposition("$sentinelInstruction Is this urgent?").build()

    private fun choice(name: String = "team"): ChoiceQuestionSpec = Questions.named(name)
        .choice("$sentinelInstruction Which team?")
        .option("billing", "$sentinelOption payments")
        .option("support", "$sentinelOption help")
        .build()

    private fun rating(name: String = "anger"): RatingQuestionSpec = Questions.named(name)
        .rating("$sentinelInstruction How angry?")
        .level("calm", "$sentinelOption calm")
        .level("angry", "$sentinelOption angry")
        .build()

    private fun request(vararg questions: Question<*>, input: String = sentinelInput): DecisionRequest =
        DecisionRequest.of(input, *questions)

    private val legacy = DecisionExecution.LEGACY_CAPABILITIES

    private val allKinds = EnumSet.allOf(QuestionKind::class.java)

    private val everyKind = DecisionCapabilities.of(allKinds)

    private val noHooks = object {}

    private val propositionsOnly = DecisionCapabilities.of(EnumSet.of(QuestionKind.PROPOSITION))

    private val ratingOnly = object : RatingAssessment {
        override fun rate(input: String, question: RatingQuestionSpec): RatingResult = error("not called")
    }

    private val nativeSource = object : NativeQuestionSetExecution {
        override fun askNative(request: DecisionRequest): DecisionResponse = error("not called")
    }

    private val propositionHook = object : PropositionAssessment {
        override fun assess(input: String, question: PropositionQuestionSpec): PropositionResult = error("not called")
    }

    private fun plan(
        capabilities: DecisionCapabilities,
        request: DecisionRequest,
        hookSource: Any = noHooks,
        service: Any = hookSource,
    ): Boolean = DecisionExecution.plan("svc-under-test", capabilities, hookSource, request, service)

    private fun unsupported(block: () -> Unit): UnsupportedDecisionException {
        val error = assertThrows(UnsupportedDecisionException::class.java) { block() }
        assertInstanceOf(UnsupportedOperationException::class.java, error)
        return error
    }

    private fun assertContains(message: String, vararg fragments: String) {
        for (fragment in fragments) {
            assertTrue(message.contains(fragment)) { "Expected '$fragment' in: $message" }
        }
    }

    private fun assertNoContent(message: String) {
        for (sentinel in listOf(sentinelInput, sentinelInstruction, sentinelOption)) {
            assertFalse(message.contains(sentinel)) { "Unexpected '$sentinel' in: $message" }
        }
    }

    @Nested
    inner class Routing {

        @Test
        fun `a legacy service answers one proposition per question`() {
            assertFalse(plan(legacy, request(proposition())))
        }

        @Test
        fun `a legacy service answers several propositions per question`() {
            assertFalse(plan(legacy, request(proposition("a"), proposition("b"))))
        }

        @Test
        fun `a proposition hook source answers per question`() {
            assertFalse(plan(legacy, request(proposition("a"), proposition("b")), propositionHook))
        }

        @Test
        fun `a legacy service answers a choice per question through classify`() {
            assertFalse(plan(legacy, request(proposition(), choice())))
        }

        @Test
        fun `one rating on a legacy service names the question, RATING and RatingAssessment`() {
            val error = unsupported { plan(legacy, request(rating())) }
            assertContains(error.message!!, "'anger' (RATING) needs RatingAssessment")
        }

        @Test
        fun `a proposition on capabilities without PROPOSITION blames the capabilities`() {
            val choicesOnly = DecisionCapabilities.of(EnumSet.of(QuestionKind.CHOICE))
            val message = unsupported { plan(choicesOnly, request(proposition())) }.message!!
            assertContains(
                message,
                "capabilities leave out PROPOSITION questions",
                "'urgent' (PROPOSITION), which the service backs with assess",
                "Report PROPOSITION in the service's capabilities()",
            )
            assertFalse(message.contains("needs")) { "No missing hook in: $message" }
        }

        @Test
        fun `all three kinds with every hook answer per question`() {
            assertFalse(plan(everyKind, request(proposition(), choice(), rating()), ratingOnly))
        }

        @Test
        fun `a native service answers one and three questions in one call`() {
            assertTrue(plan(everyKind, request(proposition()), nativeSource))
            assertTrue(plan(everyKind, request(proposition(), choice(), rating()), nativeSource))
        }

        @Test
        fun `a native service still needs the kind in its capabilities`() {
            val message = unsupported { plan(propositionsOnly, request(choice()), nativeSource) }.message!!
            assertContains(
                message,
                "capabilities leave out CHOICE questions: 'team' (CHOICE)",
                "Report CHOICE in the service's capabilities()",
            )
            assertFalse(message.contains("needs")) { "No missing hook in: $message" }
        }

        @Test
        fun `more questions than maxQuestions is rejected with the count`() {
            val capabilities = everyKind.withMaxQuestions(2)
            val error = unsupported {
                plan(capabilities, request(proposition("a"), proposition("b"), proposition("c")), nativeSource)
            }
            assertContains(error.message!!, "3", "maxQuestions 2")
        }

        @Test
        fun `maxQuestions also binds per-question execution`() {
            val error = unsupported { plan(legacy.withMaxQuestions(1), request(proposition("a"), proposition("b"))) }
            assertContains(error.message!!, "2 questions", "maxQuestions 1")
        }

        @Test
        fun `input longer than maxInputCharacters is rejected with the count`() {
            val capabilities = everyKind.withMaxInputCharacters(5)
            val error = unsupported { plan(capabilities, request(proposition()), nativeSource) }
            assertContains(error.message!!, "${sentinelInput.length} characters", "maxInputCharacters 5")
        }
    }

    @Nested
    inner class DescriptorAndHooks {

        @Test
        fun `CHOICE claimed with no hooks is backed by classify`() {
            assertFalse(plan(everyKind, request(proposition(), choice()), noHooks))
        }

        @Test
        fun `RATING claimed without the rating hook names RatingAssessment`() {
            val error = assertThrows(IllegalStateException::class.java) {
                plan(everyKind, request(proposition(), rating()), noHooks)
            }
            assertContains(error.message!!, "svc-under-test", "RatingAssessment", "NativeQuestionSetExecution")
        }

        @Test
        fun `a claimed kind with no backing is checked before any other question runs`() {
            val error = assertThrows(IllegalStateException::class.java) {
                plan(everyKind, request(rating()), noHooks)
            }
            assertContains(error.message!!, "claims rating questions")
            assertNoContent(error.message!!)
        }

        @Test
        fun `native execution backs every claimed kind`() {
            assertTrue(plan(everyKind, request(choice(), rating()), nativeSource))
        }
    }

    @Nested
    inner class Decorators {

        private val bareForwarder = object {
            override fun toString() = "bareForwarder"
        }

        @Test
        fun `a decorator without the native hook of its hook source fails preflight`() {
            val error = assertThrows(IllegalStateException::class.java) {
                plan(everyKind, request(choice()), nativeSource, service = bareForwarder)
            }
            assertContains(
                error.message!!,
                "svc-under-test",
                bareForwarder.javaClass.name,
                "NativeQuestionSetExecution",
                "Implement NativeQuestionSetExecution on ${bareForwarder.javaClass.name}",
            )
        }

        @Test
        fun `a decorator without a per-question hook of its hook source fails preflight`() {
            val error = assertThrows(IllegalStateException::class.java) {
                plan(everyKind, request(choice(), rating()), ratingOnly, service = bareForwarder)
            }
            assertContains(error.message!!, "svc-under-test", "does not implement RatingAssessment", "Implement RatingAssessment")
            assertFalse(error.message!!.contains("classify")) { "Only the missing hook is named: ${error.message}" }
            assertNoContent(error.message!!)
        }

        @Test
        fun `a decorator without the proposition hook of its hook source fails preflight`() {
            val error = assertThrows(IllegalStateException::class.java) {
                plan(legacy, request(proposition()), propositionHook, service = noHooks)
            }
            assertContains(error.message!!, "does not implement PropositionAssessment")
        }

        @Test
        fun `a decorator only needs the hooks the request uses`() {
            assertFalse(plan(everyKind, request(proposition(), choice()), ratingOnly, service = bareForwarder))
        }

        @Test
        fun `a decorator with every hook of its hook source passes`() {
            val full = object : NativeQuestionSetExecution {
                override fun askNative(request: DecisionRequest): DecisionResponse = error("not called")
            }
            assertTrue(plan(everyKind, request(choice(), rating()), nativeSource, service = full))
        }
    }

    @Nested
    inner class DefaultCapabilities {

        @Test
        fun `legacy capabilities are propositions and choices with no limits`() {
            assertEquals(setOf(QuestionKind.PROPOSITION, QuestionKind.CHOICE), legacy.questionKinds)
            assertEquals(null, legacy.maxQuestions)
            assertEquals(null, legacy.maxInputCharacters)
        }

        @Test
        fun `a source with no hooks gets the legacy capabilities`() {
            assertEquals(legacy, DecisionExecution.defaultCapabilities(noHooks))
        }

        @Test
        fun `the proposition hook keeps PROPOSITION and CHOICE only`() {
            assertEquals(legacy, DecisionExecution.defaultCapabilities(propositionHook))
        }

        @Test
        fun `the rating hook adds RATING`() {
            assertEquals(allKinds, DecisionExecution.defaultCapabilities(ratingOnly).questionKinds)
        }

        @Test
        fun `a native hook source gets the legacy capabilities`() {
            assertEquals(legacy, DecisionExecution.defaultCapabilities(nativeSource))
        }
    }

    @Nested
    inner class Messages {

        @Test
        fun `a kind miss names service, questions, kinds, hooks, capabilities and remedy`() {
            val message = unsupported { plan(legacy, request(proposition(), rating())) }.message!!
            assertContains(
                message,
                "svc-under-test",
                "'anger' (RATING) needs RatingAssessment",
                "capabilities leave out RATING questions",
                "kinds [PROPOSITION, CHOICE]",
                "Use a service that implements RatingAssessment, or remove these questions.",
            )
            assertFalse(message.contains("'urgent'")) { "Only unsupported questions are listed: $message" }
            assertFalse(message.contains("mode", ignoreCase = true)) { "No mode in: $message" }
            assertNoContent(message)
        }

        @Test
        fun `a choice kind miss never asks for a hook`() {
            val message = unsupported { plan(propositionsOnly, request(choice())) }.message!!
            assertFalse(message.contains("needs")) { "No missing hook in: $message" }
            assertFalse(message.contains("Assessment")) { "No hook named in: $message" }
            assertNoContent(message)
        }

        @Test
        fun `a kind the service has the hook for names the capabilities as the fix`() {
            val message = unsupported { plan(propositionsOnly, request(choice())) }.message!!
            assertContains(
                message,
                "capabilities leave out CHOICE questions",
                "'team' (CHOICE), which the service backs with classify",
                "Report CHOICE in the service's capabilities(), use a service whose capabilities include CHOICE, " +
                    "or remove these questions.",
            )
            assertFalse(message.contains("needs")) { "No missing hook in: $message" }
            assertFalse(message.contains("Use a service that implements")) { "No hook remedy in: $message" }
            assertNoContent(message)
        }

        @Test
        fun `a present hook and a missing hook each get their own remedy`() {
            val message = unsupported { plan(propositionsOnly, request(choice(), rating())) }.message!!
            assertContains(
                message,
                "'team' (CHOICE), which the service backs with classify",
                "'anger' (RATING) needs RatingAssessment",
                "Report CHOICE in the service's capabilities(), use a service that implements RatingAssessment, " +
                    "or remove these questions.",
            )
        }

        @Test
        fun `a limit miss names the limit, the count and a remedy`() {
            val message = unsupported {
                plan(everyKind.withMaxQuestions(1), request(proposition("a"), choice()), nativeSource)
            }.message!!
            assertContains(message, "svc-under-test", "'a' (PROPOSITION)", "'team' (CHOICE)", "2 questions", "maxQuestions 1", "Split")
            assertNoContent(message)
        }

        @Test
        fun `an input limit miss gives the length and never the input`() {
            val message = unsupported {
                plan(everyKind.withMaxInputCharacters(3), request(proposition()), nativeSource)
            }.message!!
            assertContains(message, "svc-under-test", "maxInputCharacters 3", "Shorten")
            assertNoContent(message)
        }
    }
}
