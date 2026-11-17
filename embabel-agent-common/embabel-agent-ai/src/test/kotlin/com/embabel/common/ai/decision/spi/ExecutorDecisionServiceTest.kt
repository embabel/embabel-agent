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

import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.ClassificationSpec
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.ChoiceQuestionSpec
import com.embabel.common.ai.decision.DecisionCall
import com.embabel.common.ai.decision.DecisionCapabilities
import com.embabel.common.ai.decision.DecisionInput
import com.embabel.common.ai.decision.DecisionOption
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.decision.DecisionServiceMetadata
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.JsonDecisionInput
import com.embabel.common.ai.decision.PropositionQuestionSpec
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.RatingQuestionSpec
import com.embabel.common.ai.decision.RatingResult
import com.embabel.common.ai.decision.TextDecisionInput
import com.embabel.common.ai.decision.UnsupportedDecisionException
import com.embabel.common.ai.model.ModelName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.EnumSet

class ExecutorDecisionServiceTest {

    private val provenance = ModelProvenance("model-a", "provider-a")

    private val metadata = DecisionServiceMetadata.create("one-function", "test")

    private val urgent: PropositionQuestionSpec =
        Questions.named("urgent").proposition("Secret instructions about urgency").build()

    private val team: ChoiceQuestionSpec = Questions.named("team")
        .choice("Which team should handle this?")
        .option("billing", "Secret billing label")
        .option("support", "Product help")
        .build()

    private val anger: RatingQuestionSpec = Questions.named("anger")
        .rating("How angry is the writer?")
        .level("calm", "Secret calm label")
        .level("angry", "Angry")
        .build()

    private val endUser: DecisionOption<String> = DecisionOption.of("test", "end-user", String::class.java)

    private val json = JsonDecisionInput(jacksonObjectMapper().readTree("""{"ticket":"printer on fire"}"""))

    private val all: EnumSet<QuestionKind> = EnumSet.allOf(QuestionKind::class.java)

    /** Answers every question of whatever call it gets, and remembers the calls. */
    private class CountingExecutor(private val provenance: ModelProvenance) : DecisionExecutor {
        val calls = mutableListOf<DecisionCall>()

        override fun execute(call: DecisionCall): DecisionResponse {
            calls += call
            return answerAll(call.spec, provenance)
        }
    }

    private fun counting() = CountingExecutor(provenance)

    private fun service(capabilities: DecisionCapabilities, executor: DecisionExecutor): DecisionService =
        DecisionService.from(metadata, capabilities, executor)

    private fun spec(vararg questions: com.embabel.common.ai.decision.Question<*>) = DecisionSpec.of(*questions)

    /** A service written against the base API, with no idea that calls exist. */
    private class LegacyService : DecisionService {
        val assessed = mutableListOf<PropositionRequest>()

        override val name = "legacy"
        override val provider = "test"

        override fun classify(request: ClassificationRequest): ClassificationResult =
            ClassificationResult.NoMatch(ModelProvenance("legacy", "test"))

        override fun assess(request: PropositionRequest): PropositionResult {
            assessed += request
            return PropositionResult.Answered(true, ModelProvenance("legacy", "test"))
        }
    }

    /** A decorator that forwards the base members only, so it lacks the call hook. */
    private open class Forwarding(override val hookSource: DecisionService) : DecisionService, DelegatingDecisionService {
        override val name: String get() = hookSource.name
        override val provider: String get() = hookSource.provider
        override fun capabilities(): DecisionCapabilities = hookSource.capabilities()
        override fun classify(request: ClassificationRequest): ClassificationResult = hookSource.classify(request)
        override fun assess(request: PropositionRequest): PropositionResult = hookSource.assess(request)
    }

    /** An input form that neither the framework nor most services know. */
    private class ImageInput : DecisionInput {
        override val kind: String = "image"
    }

    @Nested
    inner class LegacyRouting {

        @Test
        fun `D1 a decorator over a call-executing service must forward askCall`() {
            val executor = counting()
            val decorator = Forwarding(service(DecisionCapabilities.of(all), executor))
            assertThrows<IllegalStateException> { decorator.ask(DecisionCall.of(TextDecisionInput("x"), spec(urgent))) }
            assertTrue(executor.calls.isEmpty())
        }

        @Test
        fun `D1 a call-executing source gets the call through askCall on the service`() {
            val executor = counting()
            val inner = service(DecisionCapabilities.of(all).acceptingInput(DecisionInput.JSON).acceptingOption(endUser), executor)
            val forwarded = mutableListOf<DecisionCall>()
            val decorator = object : Forwarding(inner), DecisionCallExecution {
                override fun askCall(call: DecisionCall): DecisionResponse {
                    forwarded += call
                    return (inner as DecisionCallExecution).askCall(call)
                }
            }
            val call = DecisionCall.of(json, spec(urgent)).withOption(endUser, "u-1")
            decorator.ask(call)
            assertEquals(listOf(call), forwarded)
            assertEquals(1, executor.calls.size)
        }

        @Test
        fun `D2 a legacy service rejects a call with options and never drops them`() {
            val legacy = LegacyService()
            val call = DecisionCall.of(TextDecisionInput("x"), spec(urgent)).withOption(endUser, "u-1")
            assertThrows<UnsupportedDecisionException> { legacy.ask(call) }
            assertTrue(legacy.assessed.isEmpty())
        }

        @Test
        fun `D3 a legacy service rejects a call that requires native JSON`() {
            val legacy = LegacyService()
            val call = DecisionCall.of(json, spec(urgent)).requiringNativeJson()
            assertThrows<UnsupportedDecisionException> { legacy.ask(call) }
            assertTrue(legacy.assessed.isEmpty())
        }

        @Test
        fun `D4 a legacy service answers text and JSON as text and rejects other inputs`() {
            val legacy = LegacyService()
            val text = legacy.ask(DecisionCall.of(TextDecisionInput("hello"), spec(urgent)))
            assertEquals(PropositionResult.Answered(true, ModelProvenance("legacy", "test")), text.answer(urgent))
            legacy.ask(DecisionCall.of(json, spec(urgent)))
            assertEquals(listOf("hello", json.toText()), legacy.assessed.map { it.input })
            assertThrows<UnsupportedDecisionException> { legacy.ask(DecisionCall.of(ImageInput(), spec(urgent))) }
            assertEquals(2, legacy.assessed.size)
        }

        @Test
        fun `D4 toRequest turns a plain text call into the same request`() {
            val request = DecisionCalls.toRequest("legacy", DecisionCall.of(TextDecisionInput("hello"), spec(urgent)))
            assertEquals(DecisionRequest.of("hello", spec(urgent)), request)
        }
    }

    @Nested
    inner class Checks {

        @Test
        fun `E1 a question kind outside the capabilities is rejected before the executor runs`() {
            val executor = counting()
            val service = service(DecisionCapabilities.of(EnumSet.of(QuestionKind.PROPOSITION)), executor)
            assertThrows<UnsupportedDecisionException> {
                service.ask(DecisionCall.of(TextDecisionInput("x"), spec(urgent, team)))
            }
            assertThrows<UnsupportedDecisionException> { service.ask(DecisionRequest.of("x", spec(team))) }
            assertTrue(executor.calls.isEmpty())
        }

        @Test
        fun `E2 a call with more questions than the stated maximum is rejected and never split`() {
            val executor = counting()
            val service = service(DecisionCapabilities.of(all).withMaxQuestions(2), executor)
            assertThrows<UnsupportedDecisionException> {
                service.ask(DecisionCall.of(TextDecisionInput("x"), spec(urgent, team, anger)))
            }
            assertTrue(executor.calls.isEmpty())
            service.ask(DecisionCall.of(TextDecisionInput("x"), spec(urgent, team)))
            assertEquals(1, executor.calls.size)
        }

        @Test
        fun `E3 option and level counts outside the stated arity are rejected`() {
            val executor = counting()
            val choices = service(DecisionCapabilities.of(all).withArity(QuestionKind.CHOICE, 3, 5), executor)
            assertThrows<UnsupportedDecisionException> { choices.ask(DecisionCall.of(TextDecisionInput("x"), spec(team))) }
            val ratings = service(DecisionCapabilities.of(all).withArity(QuestionKind.RATING, 1, 1), executor)
            assertThrows<UnsupportedDecisionException> { ratings.ask(DecisionCall.of(TextDecisionInput("x"), spec(anger))) }
            assertTrue(executor.calls.isEmpty())
            val fits = service(DecisionCapabilities.of(all).withArity(QuestionKind.CHOICE, 2, 2), executor)
            fits.ask(DecisionCall.of(TextDecisionInput("x"), spec(team, anger)))
            assertEquals(1, executor.calls.size)
        }

        @Test
        fun `E4 JSON passes through when native, becomes text otherwise, and other kinds must be declared`() {
            val executor = counting()
            service(DecisionCapabilities.of(all).acceptingInput(DecisionInput.JSON), executor)
                .ask(DecisionCall.of(json, spec(urgent)))
            assertSame(json, executor.calls.single().input)

            val textOnly = service(DecisionCapabilities.of(all), executor)
            textOnly.ask(DecisionCall.of(json, spec(urgent)))
            assertEquals(TextDecisionInput(json.toText()), executor.calls[1].input)

            assertThrows<UnsupportedDecisionException> {
                textOnly.ask(DecisionCall.of(json, spec(urgent)).requiringNativeJson())
            }
            assertThrows<UnsupportedDecisionException> { textOnly.ask(DecisionCall.of(ImageInput(), spec(urgent))) }
            assertEquals(2, executor.calls.size)

            val image = ImageInput()
            service(DecisionCapabilities.of(all).acceptingInput("image"), executor).ask(DecisionCall.of(image, spec(urgent)))
            assertSame(image, executor.calls[2].input)
        }

        @Test
        fun `E5 every call option must be supported, and no stated set supports none`() {
            val executor = counting()
            val call = DecisionCall.of(TextDecisionInput("x"), spec(urgent)).withOption(endUser, "u-1")
            assertThrows<UnsupportedDecisionException> { service(DecisionCapabilities.of(all), executor).ask(call) }
            val other = DecisionOption.of("test", "other", String::class.java)
            assertThrows<UnsupportedDecisionException> {
                service(DecisionCapabilities.of(all).acceptingOption(other), executor).ask(call)
            }
            assertTrue(executor.calls.isEmpty())
            service(DecisionCapabilities.of(all).acceptingOption(endUser), executor).ask(call)
            assertEquals("u-1", executor.calls.single().option(endUser))
        }

        @Test
        fun `rejections name questions and kinds and leave out content`() {
            val service = service(
                DecisionCapabilities.of(EnumSet.of(QuestionKind.PROPOSITION, QuestionKind.CHOICE))
                    .withArity(QuestionKind.CHOICE, 3, 4),
                counting(),
            )
            val thrown = assertThrows<UnsupportedDecisionException> {
                service.ask(DecisionCall.of(TextDecisionInput("private ticket text"), spec(team)).withOption(endUser, "u-secret"))
            }
            val message = thrown.message!!
            assertTrue(message.contains("one-function"))
            assertTrue(message.contains("team"))
            listOf("private ticket text", "u-secret", "Which team", "Secret billing label").forEach {
                assertFalse(message.contains(it)) { "message leaks '$it': $message" }
            }
        }
    }

    @Nested
    inner class Responses {

        @Test
        fun `E9 a response that does not answer the spec becomes an invalid response failure`() {
            val service = service(DecisionCapabilities.of(all)) { answerAll(spec(team), provenance) }
            val response = service.ask(DecisionCall.of(TextDecisionInput("x"), spec(urgent)))
            assertEquals(DecisionResponse.failed(spec(urgent), FailureReason.INVALID_RESPONSE), response)
        }

        @Test
        fun `E9 an exception from the executor propagates unchanged`() {
            val boom = IllegalArgumentException("provider broke")
            val service = service(DecisionCapabilities.of(all)) { throw boom }
            val thrown = assertThrows<IllegalArgumentException> {
                service.ask(DecisionCall.of(TextDecisionInput("x"), spec(urgent)))
            }
            assertSame(boom, thrown)
        }

        @Test
        fun `E10 every entry point reaches the executor as one call`() {
            val executor = counting()
            val capabilities = DecisionCapabilities.of(all).withMaxQuestions(3)
            val service = service(capabilities, executor)

            val asked = service.ask(DecisionRequest.of("text", spec(urgent, team, anger)))
            assertEquals(spec(urgent, team, anger), executor.calls.single().spec)
            asked.requireMatches(spec(urgent, team, anger))

            (service as QuestionSetExecution).askQuestionSet(DecisionRequest.of("text", spec(urgent)))
            assertEquals(2, executor.calls.size)

            val classified = service.classify(ClassificationRequest.of("text", ClassificationSpec.of(team)))
            assertEquals(ClassificationResult.Selected("billing", provenance), classified)
            assertEquals(TextDecisionInput("text"), executor.calls[2].input)
            assertEquals(listOf(team), executor.calls[2].spec.questions)

            val assessed = service.assess(PropositionRequest("text", "Is it urgent?"))
            assertEquals(PropositionResult.Answered(true, provenance), assessed)
            val proposition = executor.calls[3].spec.questions.single()
            assertEquals(QuestionKind.PROPOSITION, proposition.kind)
            assertEquals("Is it urgent?", proposition.instructions)
            assertEquals(4, executor.calls.size)

            assertSame(capabilities, service.capabilities())
            assertEquals("one-function", service.name)
            assertEquals("test", service.provider)
        }

        @Test
        fun `E10 a Kotlin lambda passed to from is a working service`() {
            val service = DecisionService.from(metadata, DecisionCapabilities.of(all)) { call ->
                answerAll(call.spec, provenance)
            }
            val response = service.ask("text", spec(urgent, anger))
            assertEquals(PropositionResult.Answered(true, provenance), response.answer(urgent))
            assertInstanceOf(RatingResult.Answered::class.java, response.answer(anger))
        }

        @Test
        fun `KIND names the decision model kind`() {
            val kind: com.embabel.common.ai.model.ModelKind<DecisionService, ModelName> = DecisionService.KIND
            assertEquals("decision", kind.name)
            assertEquals(DecisionService::class.java, kind.capability)
        }
    }

    companion object {

        /** A canned answer for every question in the spec. */
        fun answerAll(spec: DecisionSpec, provenance: ModelProvenance): DecisionResponse {
            val builder = DecisionResponse.builder(spec)
            spec.questions.forEach { question ->
                when (question) {
                    is PropositionQuestionSpec -> builder.answer(question, PropositionResult.Answered(true, provenance))
                    is ChoiceQuestionSpec -> builder.answer(question, ClassificationResult.Selected(question.options.first().id, provenance))
                    is RatingQuestionSpec -> builder.answer(question, RatingResult.Answered(provenance, question.levels.first().id))
                }
            }
            return builder.build()
        }
    }
}
