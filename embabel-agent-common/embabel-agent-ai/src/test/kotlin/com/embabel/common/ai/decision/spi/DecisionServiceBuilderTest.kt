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
import com.embabel.common.ai.decision.PropositionQuestionSpec
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.TextDecisionInput
import com.embabel.common.ai.decision.UnsupportedDecisionException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.EnumSet

class DecisionServiceBuilderTest {

    private val provenance = ModelProvenance("model-a", "provider-a")

    private val metadata = DecisionServiceMetadata.create("configured", "test")

    private val urgent: PropositionQuestionSpec = Questions.named("urgent").proposition("Is it urgent?").build()

    private val spec = DecisionSpec.of(urgent)

    private val endUser: DecisionOption<String> = DecisionOption.of("test", "end-user", String::class.java)

    private val effort: DecisionOption<String> = DecisionOption.of("test", "effort", String::class.java)

    private val capabilities = DecisionCapabilities.of(EnumSet.of(QuestionKind.PROPOSITION))
        .acceptingOption(endUser)
        .acceptingOption(effort)
        .acceptingOption(DecisionOptions.TIMEOUT)

    /** Answers yes to everything and remembers each call it gets. */
    private class RecordingExecutor(private val provenance: ModelProvenance) : DecisionExecutor {
        val calls = mutableListOf<DecisionCall>()

        override fun execute(call: DecisionCall): DecisionResponse {
            calls += call
            return ExecutorDecisionServiceTest.answerAll(call.spec, provenance)
        }
    }

    /** A clock the test moves by hand, read as nanoseconds. */
    private class ManualClock {
        var nanos = 1_000L

        fun advance(by: Duration) {
            nanos += by.toNanos()
        }
    }

    private fun call(): DecisionCall = DecisionCall.of(TextDecisionInput("the printer is on fire"), spec)

    @Test
    fun `E6 a default fills an unset option and a call's own value wins`() {
        val executor = RecordingExecutor(provenance)
        val service = DecisionService.builder(metadata, capabilities, executor)
            .withDefaultOption(endUser, "default-user")
            .withDefaultOption(effort, "low")
            .build()

        service.ask(call())
        service.ask(call().withOption(endUser, "caller-user"))

        assertEquals("default-user", executor.calls[0].option(endUser))
        assertEquals("low", executor.calls[0].option(effort))
        assertEquals("caller-user", executor.calls[1].option(endUser))
        assertEquals("low", executor.calls[1].option(effort))
    }

    @Test
    fun `E6 a default the capabilities do not list fails at build time in both styles`() {
        val unlisted = DecisionOption.of("test", "unlisted", String::class.java)
        val executor = RecordingExecutor(provenance)

        assertThrows<IllegalArgumentException> {
            DecisionService.builder(metadata, capabilities, executor).withDefaultOption(unlisted, "x").build()
        }
        assertThrows<IllegalArgumentException> {
            DecisionService(metadata, capabilities, executor) { defaultOption(unlisted, "x") }
        }
        assertThrows<IllegalArgumentException> {
            DecisionService(metadata, DecisionCapabilities.of(EnumSet.of(QuestionKind.PROPOSITION)), executor) {
                defaultOption(endUser, "x")
            }
        }
    }

    @Test
    fun `E7 the preflight sees the effective call after the checks and can stop the ask`() {
        val executor = RecordingExecutor(provenance)
        val seen = mutableListOf<DecisionCall>()
        val service = DecisionService(metadata, capabilities, executor) {
            defaultOption(effort, "low")
            preflight { call ->
                seen += call
                if (call.option(endUser) == "blocked") throw IllegalStateException("blocked by the provider")
            }
        }

        service.ask(call())
        assertEquals("low", seen.single().option(effort))
        assertEquals(1, executor.calls.size)

        assertThrows<IllegalStateException> { service.ask(call().withOption(endUser, "blocked")) }
        assertEquals(1, executor.calls.size)

        // A call that fails a capability check never reaches the preflight.
        val rating = Questions.named("anger").rating("How angry?").level("calm", "Calm").level("angry", "Angry").build()
        assertThrows<UnsupportedDecisionException> {
            service.ask(DecisionCall.of(TextDecisionInput("x"), DecisionSpec.of(rating)))
        }
        assertEquals(2, seen.size)
        assertEquals(1, executor.calls.size)
    }

    @Test
    fun `E8 the executor gets the time left since the ask began`() {
        val clock = ManualClock()
        val executor = RecordingExecutor(provenance)
        val service = ExecutorDecisionService(
            metadata,
            capabilities,
            executor,
            preflight = { clock.advance(Duration.ofSeconds(3)) },
            nanoTime = { clock.nanos },
        )

        service.ask(call().withOption(DecisionOptions.TIMEOUT, Duration.ofSeconds(5)))
        assertEquals(Duration.ofSeconds(2), executor.calls.single().option(DecisionOptions.TIMEOUT))

        service.ask(call())
        assertNull(executor.calls[1].option(DecisionOptions.TIMEOUT))
    }

    @Test
    fun `E8 a default timeout is the budget when the call sets none`() {
        val clock = ManualClock()
        val executor = RecordingExecutor(provenance)
        val service = ExecutorDecisionService(
            metadata,
            capabilities,
            executor,
            preflight = { clock.advance(Duration.ofMillis(250)) },
            defaults = mapOf(DecisionOptions.TIMEOUT to Duration.ofSeconds(1)),
            nanoTime = { clock.nanos },
        )

        service.ask(call())
        assertEquals(Duration.ofMillis(750), executor.calls.single().option(DecisionOptions.TIMEOUT))
    }

    @Test
    fun `E8 no time left gives an unavailable failure without calling the executor`() {
        val clock = ManualClock()
        val executor = RecordingExecutor(provenance)
        val service = ExecutorDecisionService(
            metadata,
            capabilities,
            executor,
            preflight = { clock.advance(Duration.ofSeconds(5)) },
            nanoTime = { clock.nanos },
        )

        val response = service.ask(call().withOption(DecisionOptions.TIMEOUT, Duration.ofSeconds(5)))

        assertEquals(FailureReason.UNAVAILABLE, response.requestFailure)
        assertTrue(executor.calls.isEmpty())
    }

    @Test
    fun `E8 a zero or negative timeout is rejected before the executor runs`() {
        val executor = RecordingExecutor(provenance)
        var preflights = 0
        val service = DecisionService(metadata, capabilities, executor) { preflight { preflights++ } }

        assertThrows<UnsupportedDecisionException> {
            service.ask(call().withOption(DecisionOptions.TIMEOUT, Duration.ZERO))
        }
        assertThrows<UnsupportedDecisionException> {
            service.ask(call().withOption(DecisionOptions.TIMEOUT, Duration.ofSeconds(-1)))
        }
        assertTrue(executor.calls.isEmpty())
        assertEquals(0, preflights)
    }

    @Test
    fun `B1 each builder method returns a new builder and leaves the receiver as it was`() {
        val executor = RecordingExecutor(provenance)
        var preflights = 0
        val plain = DecisionService.builder(metadata, capabilities, executor)

        val withDefault = plain.withDefaultOption(effort, "low")
        val withPreflight = plain.withPreflight { preflights++ }

        assertTrue(withDefault !== plain)
        assertTrue(withPreflight !== plain)

        plain.build().ask(call())
        assertNull(executor.calls.single().option(effort))
        assertEquals(0, preflights)

        withDefault.build().ask(call())
        assertEquals("low", executor.calls[1].option(effort))
        assertEquals(0, preflights)

        withPreflight.build().ask(call())
        assertNull(executor.calls[2].option(effort))
        assertEquals(1, preflights)
    }

    @Test
    fun `B2 the block and the chain give services that behave the same`() {
        fun check(call: DecisionCall) {
            if (call.option(endUser) == "blocked") throw IllegalStateException("blocked")
        }
        val fromBlock = RecordingExecutor(provenance)
        val fromChain = RecordingExecutor(provenance)

        val block = DecisionService(metadata, capabilities, fromBlock) {
            defaultOption(effort, "high")
            preflight(::check)
        }
        val chain = DecisionService.builder(metadata, capabilities, fromChain)
            .withDefaultOption(effort, "high")
            .withPreflight(::check)
            .build()

        val asked = call().withOption(endUser, "someone")
        assertEquals(chain.ask(asked).answer(urgent), block.ask(asked).answer(urgent))
        assertEquals(fromChain.calls, fromBlock.calls)

        val blocked = call().withOption(endUser, "blocked")
        assertThrows<IllegalStateException> { block.ask(blocked) }
        assertThrows<IllegalStateException> { chain.ask(blocked) }
        assertEquals(fromChain.calls, fromBlock.calls)
        assertEquals(capabilities, block.capabilities())
        assertEquals(chain.name, block.name)
        assertEquals(chain.provider, block.provider)
    }

    @Test
    fun `B3 from behaves as a builder with no settings`() {
        val viaFrom = RecordingExecutor(provenance)
        val viaBuilder = RecordingExecutor(provenance)
        val fromService = DecisionService.from(metadata, capabilities, viaFrom)
        val builderService = DecisionService.builder(metadata, capabilities, viaBuilder).build()

        val asked = call().withOption(endUser, "someone")
        assertEquals(builderService.ask(asked).answer(urgent), fromService.ask(asked).answer(urgent))
        assertEquals(PropositionResult.Answered(true, provenance), fromService.ask(asked).answer(urgent))
        assertEquals(viaBuilder.calls.single(), viaFrom.calls.first())

        val unlisted = call().withOption(DecisionOption.of("test", "unlisted", String::class.java), "x")
        assertThrows<UnsupportedDecisionException> { fromService.ask(unlisted) }
        assertThrows<UnsupportedDecisionException> { builderService.ask(unlisted) }

        assertEquals(builderService.capabilities(), fromService.capabilities())
        assertEquals(builderService.name, fromService.name)
        assertEquals(builderService.provider, fromService.provider)
    }
}
