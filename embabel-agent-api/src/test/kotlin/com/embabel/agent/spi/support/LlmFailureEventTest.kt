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
package com.embabel.agent.spi.support

import com.embabel.agent.api.common.InteractionId
import com.embabel.agent.api.event.LlmCallFailedEvent
import com.embabel.agent.api.event.LlmFailureEvent
import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.api.event.LlmResponseEvent
import com.embabel.agent.api.event.LlmRetryEvent
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.ReplanRequestedException
import com.embabel.agent.core.support.InvalidLlmReturnTypeException
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.AutoLlmSelectionCriteriaResolver
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.validation.DefaultValidationPromptGenerator
import com.embabel.agent.support.SimpleTestAgent
import com.embabel.agent.test.common.EventSavingAgenticEventListener
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.core.thinking.ThinkingResponse
import io.mockk.every
import io.mockk.mockk
import jakarta.validation.Validation
import jakarta.validation.constraints.NotBlank
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import java.util.concurrent.Executors

/**
 * Failures of an LLM call must be visible to listeners, not only to the log.
 */
class LlmFailureEventTest {

    private lateinit var eventListener: EventSavingAgenticEventListener
    private lateinit var agentProcess: AgentProcess

    @BeforeEach
    fun setup() {
        eventListener = EventSavingAgenticEventListener()
        val processContext = mockk<ProcessContext>()
        every { processContext.platformServices } returns mockk()
        every { processContext.platformServices.agentPlatform } returns mockk()
        every { processContext.platformServices.agentPlatform.toolGroupResolver } returns
            RegistryToolGroupResolver("test", emptyList())
        every { processContext.platformServices.eventListener } returns eventListener
        every { processContext.onProcessEvent(any()) } answers { eventListener.onProcessEvent(firstArg()) }
        every { processContext.processOptions } returns ProcessOptions()
        agentProcess = mockk<AgentProcess>()
        every { agentProcess.agent } returns SimpleTestAgent
        every { agentProcess.processContext } returns processContext
        every { processContext.agentProcess } returns agentProcess
    }

    private fun failuresBeforeSuccess(
        failures: Int,
        maxAttempts: Int,
        backoffMillis: Long = 1L,
        exception: () -> Throwable = { IllegalStateException("LLM is down") },
    ): TestLlmOperations {
        var attempt = 0
        return TestLlmOperations(maxAttempts, backoffMillis) {
            if (attempt++ < failures) throw exception()
            "success"
        }
    }

    private fun interaction(llm: LlmOptions = LlmOptions()) =
        LlmInteraction(id = InteractionId("test-interaction"), llm = llm)

    private fun <O> createObject(
        operations: TestLlmOperations,
        outputClass: Class<O>,
        interaction: LlmInteraction = interaction(),
    ): O =
        operations.createObject(
            messages = listOf(UserMessage("go")),
            interaction = interaction,
            outputClass = outputClass,
            agentProcess = agentProcess,
            action = null,
        )

    private fun createObject(operations: TestLlmOperations): String =
        createObject(operations, String::class.java)

    private fun failureEvents(): List<LlmFailureEvent> =
        eventListener.processEvents.filterIsInstance<LlmFailureEvent>()

    private fun retryEvents(): List<LlmRetryEvent> =
        eventListener.processEvents.filterIsInstance<LlmRetryEvent>()

    private fun failedEvent(): LlmCallFailedEvent =
        eventListener.processEvents.filterIsInstance<LlmCallFailedEvent>().single()

    @Nested
    inner class Retrying {

        @Test
        fun `successful call raises no failure event`() {
            createObject(failuresBeforeSuccess(failures = 0, maxAttempts = 5))

            assertTrue(failureEvents().isEmpty(), "No failure expected: ${failureEvents()}")
            assertEquals(1, eventListener.processEvents.filterIsInstance<LlmResponseEvent<*>>().size)
        }

        @Test
        fun `each retry raises an event naming the attempt that failed`() {
            createObject(failuresBeforeSuccess(failures = 2, maxAttempts = 5))

            assertEquals(listOf(1, 2), retryEvents().map { it.attempts })
            assertTrue(retryEvents().all { it.maxAttempts == 5 })
            assertTrue(retryEvents().all { it.throwable.message == "LLM is down" })
            assertEquals(1, eventListener.processEvents.filterIsInstance<LlmResponseEvent<*>>().size)
        }

        @Test
        fun `exhausting attempts raises a failure event and no response event`() {
            val operations = failuresBeforeSuccess(failures = Int.MAX_VALUE, maxAttempts = 3)

            assertThrows<IllegalStateException> { createObject(operations) }

            assertEquals(2, retryEvents().size)
            assertEquals(3, failedEvent().attempts)
            assertEquals(3, failedEvent().maxAttempts)
            assertEquals("LLM is down", failedEvent().throwable.message)
            assertTrue(eventListener.processEvents.filterIsInstance<LlmResponseEvent<*>>().isEmpty())
        }

        @Test
        fun `a retry reports the time the failed attempt took, not the backoff wait`() {
            val backoffMillis = 500L
            createObject(failuresBeforeSuccess(failures = 1, maxAttempts = 2, backoffMillis = backoffMillis))

            val retry = retryEvents().single()
            assertTrue(
                retry.runningTime.toMillis() < backoffMillis / 2,
                "The attempt returns at once; only the wait that follows it is slow: ${retry.runningTime}",
            )
        }

        @Test
        fun `the failure event points back to its request`() {
            val operations = failuresBeforeSuccess(failures = Int.MAX_VALUE, maxAttempts = 1)

            assertThrows<IllegalStateException> { createObject(operations) }

            val request = eventListener.processEvents.filterIsInstance<LlmRequestEvent<*>>().single()
            assertEquals(request, failureEvents().single().request)
        }

        @Test
        fun `a control flow signal is not a failure`() {
            val operations = failuresBeforeSuccess(
                failures = Int.MAX_VALUE,
                maxAttempts = 3,
                exception = { ReplanRequestedException("need to replan") },
            )

            assertThrows<ReplanRequestedException> { createObject(operations) }

            assertTrue(failureEvents().isEmpty(), "Control flow signal is not a failure: ${failureEvents()}")
        }
    }

    @Nested
    inner class Validating {

        @Test
        fun `a response that never validates raises a failure event counting every round trip`() {
            val operations = TestLlmOperations(maxAttempts = 3) { ValidatedPerson(name = "") }

            assertThrows<InvalidLlmReturnTypeException> { createObject(operations, ValidatedPerson::class.java) }

            assertTrue(
                failedEvent().throwable is InvalidLlmReturnTypeException,
                "Expected the validation failure to be reported: ${failedEvent().throwable}",
            )
            // The binding call and the relance that follows it are both round trips to the model
            assertEquals(2, operations.calls)
            assertEquals(operations.calls, failedEvent().attempts)
            // The relance runs its own retry sequence, so it brings its own budget with it
            assertEquals(6, failedEvent().maxAttempts)
            assertTrue(retryEvents().isEmpty())
            assertTrue(eventListener.processEvents.filterIsInstance<LlmResponseEvent<*>>().isEmpty())
        }

        @Test
        fun `a retry inside the validation relance keeps counting up`() {
            var call = 0
            // Round trip 1 binds but does not validate; round trip 2 throws, so the relance retries
            val operations = TestLlmOperations(maxAttempts = 3) {
                if (call++ == 1) throw IllegalStateException("LLM is down")
                ValidatedPerson(name = "")
            }

            assertThrows<InvalidLlmReturnTypeException> { createObject(operations, ValidatedPerson::class.java) }

            assertEquals(3, operations.calls)
            assertEquals(operations.calls, failedEvent().attempts)
            assertEquals(6, failedEvent().maxAttempts)
            // The retry is the third round trip, so two had been made when it was reported
            assertEquals(listOf(2), retryEvents().map { it.attempts })
            assertEquals(listOf(6), retryEvents().map { it.maxAttempts })
        }
    }

    /**
     * Every entry point runs its call through the same retry helper, so every one of them
     * has to report a failure. Only [AbstractLlmOperations.createObject] also validates.
     */
    @Nested
    inner class EveryEntryPoint {

        private fun assertReportsFailure(call: (TestLlmOperations, LlmInteraction) -> Unit) {
            val operations = failuresBeforeSuccess(failures = Int.MAX_VALUE, maxAttempts = 2)

            assertThrows<IllegalStateException> { call(operations, interaction()) }

            assertEquals(listOf(1), retryEvents().map { it.attempts })
            assertEquals(2, failedEvent().attempts)
            assertEquals(2, failedEvent().maxAttempts)
            assertEquals(operations.calls, failedEvent().attempts)
        }

        @Test
        fun `createObject reports a failure`() {
            assertReportsFailure { operations, interaction ->
                operations.createObject(listOf(UserMessage("go")), interaction, String::class.java, agentProcess, null)
            }
        }

        @Test
        fun `createObjectIfPossible reports a failure`() {
            assertReportsFailure { operations, interaction ->
                operations.createObjectIfPossible(
                    listOf(UserMessage("go")), interaction, String::class.java, agentProcess, null,
                )
            }
        }

        @Test
        fun `createObjectWithThinking reports a failure`() {
            assertReportsFailure { operations, interaction ->
                operations.createObjectWithThinking(
                    listOf(UserMessage("go")), interaction, String::class.java, agentProcess, null,
                )
            }
        }

        @Test
        fun `createObjectIfPossibleWithThinking reports a failure`() {
            assertReportsFailure { operations, interaction ->
                operations.createObjectIfPossibleWithThinking(
                    listOf(UserMessage("go")), interaction, String::class.java, agentProcess, null,
                )
            }
        }
    }

    /**
     * A timed out call abandons its worker thread, so the failure event is the only place
     * the timeout is reported to a listener.
     */
    @Nested
    inner class TimingOut {

        @Test
        fun `a call that times out raises a failure event`() {
            val operations = TestLlmOperations(maxAttempts = 1) {
                Thread.sleep(2_000)
                "too late"
            }

            val thrown = assertThrows<RuntimeException> {
                createObject(operations, String::class.java, interaction(LlmOptions().withTimeout(Duration.ofMillis(50))))
            }

            assertTrue(thrown.message!!.contains("timed out"), "Expected a timeout: ${thrown.message}")
            assertEquals(1, failedEvent().attempts)
            assertTrue(failedEvent().throwable.message!!.contains("timed out"))
            assertTrue(eventListener.processEvents.filterIsInstance<LlmResponseEvent<*>>().isEmpty())
        }
    }
}

/**
 * Output class the LLM can only get wrong, so validation never passes.
 */
internal class ValidatedPerson(
    @field:NotBlank
    val name: String,
)

/**
 * Minimal [AbstractLlmOperations] whose transforms are driven by [transform],
 * so a test can decide when the LLM fails.
 */
private class TestLlmOperations(
    maxAttempts: Int,
    backoffMillis: Long = 1L,
    private val transform: () -> Any,
) : AbstractLlmOperations(
    toolDecorator = DefaultToolDecorator(),
    modelProvider = mockk<ModelProvider>().apply {
        every { getLlm(any()) } returns mockk<LlmService<*>>(relaxed = true)
    },
    validator = Validation.buildDefaultValidatorFactory().validator,
    validationPromptGenerator = DefaultValidationPromptGenerator(),
    autoLlmSelectionCriteriaResolver = AutoLlmSelectionCriteriaResolver.DEFAULT,
    dataBindingProperties = LlmDataBindingProperties(maxAttempts = maxAttempts, fixedBackoffMillis = backoffMillis),
    promptsProperties = LlmOperationsPromptsProperties(),
    asyncer = ExecutorAsyncer(Executors.newCachedThreadPool()),
    objectMapper = jacksonObjectMapper(),
) {

    /** Round trips the LLM was actually asked to make, so a test can check the count reported. */
    var calls: Int = 0
        private set

    @Suppress("UNCHECKED_CAST")
    override fun <O> doTransform(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?,
    ): O {
        calls++
        return transform() as O
    }

    // "If possible" lets the model decline to answer; it does not hide a failed call.
    // The real implementation lets those propagate, so this one must too.
    override fun <O> doTransformIfPossible(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>,
    ): Result<O> = Result.success(doTransform(messages, interaction, outputClass, llmRequestEvent))

    override fun <O> doTransformWithThinking(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?,
    ): ThinkingResponse<O> = ThinkingResponse(
        result = doTransform(messages, interaction, outputClass, llmRequestEvent),
        thinkingBlocks = emptyList(),
    )

    override fun <O> doTransformWithThinkingIfPossible(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?,
    ): Result<ThinkingResponse<O>> =
        Result.success(doTransformWithThinking(messages, interaction, outputClass, llmRequestEvent))
}
