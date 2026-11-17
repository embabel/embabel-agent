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

import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.DecisionCall
import com.embabel.common.ai.decision.DecisionCapabilities
import com.embabel.common.ai.decision.DecisionOption
import com.embabel.common.ai.decision.DecisionOptions
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.decision.DecisionServiceMetadata
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.DecisionUsage
import com.embabel.common.ai.decision.PropositionQuestionSpec
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.TextDecisionInput
import com.embabel.common.ai.decision.UnsupportedDecisionException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import java.lang.reflect.Proxy
import java.util.EnumSet

/** Tests for the fixes an independent review asked for, one per clause id. */
class ExecutorReviewFixesTest {

    private val provenance = ModelProvenance("model-a", "provider-a")

    private val metadata = DecisionServiceMetadata.create("one-function", "test")

    private val urgent: PropositionQuestionSpec =
        Questions.named("urgent").proposition("Is this urgent?").build()

    private val other: PropositionQuestionSpec =
        Questions.named("other").proposition("Is this something else?").build()

    private val all: EnumSet<QuestionKind> = EnumSet.allOf(QuestionKind::class.java)

    private val budget: DecisionOption<Long> = DecisionOption.of("test", "budget", Long::class.javaObjectType)

    /** Answers every question of whatever call it gets, and remembers the calls. */
    private class Recording(private val answer: (DecisionCall) -> DecisionResponse) : DecisionExecutor {
        val calls = mutableListOf<DecisionCall>()

        override fun execute(call: DecisionCall): DecisionResponse {
            calls += call
            return answer(call)
        }
    }

    private fun answering() = Recording { ExecutorDecisionServiceTest.answerAll(it.spec, provenance) }

    private fun call(spec: DecisionSpec = DecisionSpec.of(urgent)) = DecisionCall.of(TextDecisionInput("text"), spec)

    @Test
    fun `R1 a call key with the timeout id and another type is rejected before the executor runs`() {
        val executor = answering()
        val service = DecisionService.from(metadata, DecisionCapabilities.of(all).acceptingOption(DecisionOptions.TIMEOUT), executor)
        val wrongTimeout = DecisionOption.of("embabel", "timeout", String::class.java)

        assertThrows<UnsupportedDecisionException> { service.ask(call().withOption(wrongTimeout, "PT5S")) }
        assertEquals(0, executor.calls.size)
    }

    @Test
    fun `R1 a call key with a default's id and another type is rejected`() {
        val executor = answering()
        val service = DecisionService.builder(metadata, DecisionCapabilities.of(all).acceptingOption(budget), executor)
            .withDefaultOption(budget, 100L)
            .build()
        val wrongBudget = DecisionOption.of("test", "budget", String::class.java)

        assertThrows<UnsupportedDecisionException> { service.ask(call().withOption(wrongBudget, "lots")) }
        assertEquals(0, executor.calls.size)
    }

    @Test
    fun `R1 two keys in one call that share an id with different types are rejected`() {
        val executor = answering()
        val asText = DecisionOption.of("test", "end-user", String::class.java)
        val asNumber = DecisionOption.of("test", "end-user", Int::class.javaObjectType)
        val service = DecisionService.from(metadata, DecisionCapabilities.of(all).acceptingOption(asText), executor)

        assertThrows<UnsupportedDecisionException> {
            service.ask(call().withOption(asText, "alice").withOption(asNumber, 7))
        }
        assertEquals(0, executor.calls.size)
    }

    @Test
    fun `R2 a key made from a primitive class token holds the boxed type and its values`() {
        val tokens: List<Pair<Class<*>, Any>> = listOf(
            Boolean::class.javaPrimitiveType!! to true,
            Byte::class.javaPrimitiveType!! to 1.toByte(),
            Short::class.javaPrimitiveType!! to 2.toShort(),
            Char::class.javaPrimitiveType!! to 'c',
            Int::class.javaPrimitiveType!! to 3,
            Long::class.javaPrimitiveType!! to 4L,
            Float::class.javaPrimitiveType!! to 5.0f,
            Double::class.javaPrimitiveType!! to 6.0,
        )
        tokens.forEach { (primitive, value) ->
            @Suppress("UNCHECKED_CAST")
            val key = DecisionOption.of("test", "number", primitive as Class<Any>)
            assertSame(primitive.kotlin.javaObjectType, key.type)
            assertEquals(DecisionOption.of("test", "number", primitive.kotlin.javaObjectType), key)
            assertEquals(value, call().withOption(key, value).option(key))
        }
        val fromIntToken = DecisionOption.of("test", "count", Int::class.java)
        assertEquals(1, call().withOption(fromIntToken, 1).option(fromIntToken))
        assertEquals(DecisionOption.of("test", "count", Integer::class.java), fromIntToken)
    }

    @Test
    fun `R3 an invalid response from the executor keeps the usage it reported`() {
        val usage = DecisionUsage(inputTokens = 120, outputTokens = 30, totalTokens = 150)
        val wrongSpec = DecisionSpec.of(other)
        val executor = Recording {
            DecisionResponse.builder(wrongSpec)
                .answer(other, PropositionResult.Answered(true, provenance))
                .usage(usage)
                .build()
        }
        val response = DecisionService.from(metadata, DecisionCapabilities.of(all), executor).ask(call())

        assertEquals(FailureReason.INVALID_RESPONSE, response.requestFailure)
        assertEquals(usage, response.usage)

        // A Java executor can return null, which a Kotlin lambda can't, so a proxy stands in for one.
        val returnsNull = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DecisionExecutor::class.java)) { _, _, _ -> null }
            as DecisionExecutor
        val nothing = DecisionService.from(metadata, DecisionCapabilities.of(all), returnsNull).ask(call())
        assertEquals(FailureReason.INVALID_RESPONSE, nothing.requestFailure)
        assertNull(nothing.usage)
    }

    @Test
    fun `R4 a response with many refused questions reads back with the same refused order`() {
        val questions = (1..60).map { Questions.named("q$it").proposition("Question $it?").build() }
        val spec = DecisionSpec.of(*questions.toTypedArray())
        val builder = DecisionResponse.builder(spec)
        questions.forEachIndexed { index, question ->
            if (index % 3 == 0) {
                builder.answer(question, PropositionResult.Answered(true, provenance))
            } else {
                builder.answer(question, PropositionResult.Inconclusive(provenance))
                builder.refused(question)
            }
        }
        val written = builder.build()
        val mapper = JsonMapper.builder().build()

        val read = mapper.readValue(mapper.writeValueAsString(written), DecisionResponse::class.java)

        assertEquals(written.refusedQuestions.toList(), read.refusedQuestions.toList())
        assertEquals(40, read.refusedQuestions.size)
        assertEquals(written, read)
    }
}
