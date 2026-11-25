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

package com.embabel.agent.spi.support

import com.embabel.agent.api.common.InteractionId
import com.embabel.agent.api.event.observation.InternalObservabilityApi
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.Usage
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.loop.LlmMessageResponse
import com.embabel.agent.spi.loop.LlmMessageSender
import com.embabel.agent.support.SimpleTestAgent
import com.embabel.agent.test.common.EventSavingAgenticEventListener
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.textio.template.JinjavaTemplateRenderer
import io.mockk.every
import io.mockk.mockk
import jakarta.validation.Validation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.Executors

/**
 * The agent path through [ToolLoopLlmOperations] with an [LlmService] that is not backed by Spring AI:
 * the call goes through the service's own [LlmMessageSender].
 */
class ToolLoopLlmOperationsNonSpringAiTest {

    private val invocationHistory = MutableLlmInvocationHistory()

    private var attempts = 0

    private val agentProcess: AgentProcess = mockk<AgentProcess>().also { process ->
        val processContext = mockk<ProcessContext>()
        val eventListener = EventSavingAgenticEventListener()
        every { processContext.platformServices } returns mockk()
        every { processContext.platformServices.agentPlatform } returns mockk()
        every { processContext.platformServices.agentPlatform.toolGroupResolver } returns
            RegistryToolGroupResolver("test", emptyList())
        every { processContext.platformServices.eventListener } returns eventListener
        every { processContext.processOptions } returns ProcessOptions()
        every { processContext.onProcessEvent(any()) } answers { eventListener.onProcessEvent(firstArg()) }
        every { processContext.agentProcess } returns process
        every { process.recordLlmInvocation(any()) } answers { invocationHistory.invocations.add(firstArg()) }
        every { process.agent } returns SimpleTestAgent
        every { process.processContext } returns processContext
        every { process.blackboard } returns mockk(relaxed = true)
    }

    private fun operationsFor(maxAttempts: Int = 1, sender: LlmMessageSender): ToolLoopLlmOperations {
        val llm = mockk<LlmService<*>>(relaxed = true)
        every { llm.createMessageSender(any()) } answers {
            attempts++
            sender
        }
        val modelProvider = mockk<ModelProvider>()
        every { modelProvider.getLlm(any()) } returns llm
        return ToolLoopLlmOperations(
            modelProvider = modelProvider,
            toolDecorator = DefaultToolDecorator(),
            validator = Validation.buildDefaultValidatorFactory().validator,
            dataBindingProperties = LlmDataBindingProperties(maxAttempts = maxAttempts),
            asyncer = ExecutorAsyncer(Executors.newCachedThreadPool()),
            templateRenderer = JinjavaTemplateRenderer(),
        )
    }

    private fun <O> createObject(operations: ToolLoopLlmOperations, outputClass: Class<O>): O =
        operations.createObject(
            messages = listOf(UserMessage("Name a dog")),
            interaction = LlmInteraction(id = InteractionId("id"), llm = LlmOptions()),
            outputClass = outputClass,
            action = SimpleTestAgent.actions.first(),
            agentProcess = agentProcess,
        )

    @Test
    fun `sends through the service's own message sender`() {
        val sent = mutableListOf<List<Message>>()
        val operations = operationsFor { messages, _ ->
            sent += messages
            LlmMessageResponse(AssistantMessage("Duke"), "Duke")
        }

        assertEquals("Duke", createObject(operations, String::class.java))
        assertTrue(sent.single().any { it.content.contains("Name a dog") })
    }

    @Test
    fun `records the usage its sender reports`() {
        val usage = Usage(promptTokens = 7, completionTokens = 3, nativeUsage = null)
        val operations = operationsFor { _, _ -> LlmMessageResponse(AssistantMessage("Duke"), "Duke", usage) }

        createObject(operations, String::class.java)

        assertEquals(usage, invocationHistory.invocations.single().usage)
    }

    data class Dog(val name: String)

    @Test
    fun `typed output fails with a clear message until output converters are vendor-neutral`() {
        val operations = operationsFor { _, _ ->
            LlmMessageResponse(AssistantMessage("""{"name":"Duke"}"""), """{"name":"Duke"}""")
        }

        val thrown = assertThrows<Exception> { createObject(operations, Dog::class.java) }

        val messages = generateSequence<Throwable>(thrown) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue(messages.any { "output converter" in it }, "Unexpected failure: $messages")
    }

    @Test
    fun `a missing output converter is not retried, as no attempt can succeed`() {
        val operations = operationsFor(maxAttempts = 3) { _, _ ->
            LlmMessageResponse(AssistantMessage("""{"name":"Duke"}"""), """{"name":"Duke"}""")
        }

        assertThrows<Exception> { createObject(operations, Dog::class.java) }

        assertEquals(1, attempts)
    }

    @Test
    fun `if-possible output fails with a clear message, even for String`() {
        val operations = operationsFor { _, _ -> LlmMessageResponse(AssistantMessage("Duke"), "Duke") }

        val failure = runCatching {
            operations.createObjectIfPossible(
                messages = listOf(UserMessage("Name a dog")),
                interaction = LlmInteraction(id = InteractionId("id"), llm = LlmOptions()),
                outputClass = String::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = agentProcess,
            ).getOrThrow()
        }.exceptionOrNull()

        val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue(messages.any { "output converter" in it }, "Unexpected outcome: $messages")
    }

    @Test
    fun `typed output with thinking fails with a clear message`() {
        val operations = operationsFor { _, _ ->
            LlmMessageResponse(AssistantMessage("""{"name":"Duke"}"""), """{"name":"Duke"}""")
        }

        val thrown = assertThrows<Exception> {
            operations.doTransformWithThinking(
                messages = listOf(UserMessage("Name a dog")),
                interaction = LlmInteraction(id = InteractionId("id"), llm = LlmOptions()),
                outputClass = Dog::class.java,
                llmRequestEvent = null,
            )
        }

        val messages = generateSequence<Throwable>(thrown) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue(messages.any { "output converter" in it }, "Unexpected failure: $messages")
    }

    @Test
    fun `if-possible output with thinking fails with a clear message, even for String`() {
        val operations = operationsFor { _, _ -> LlmMessageResponse(AssistantMessage("Duke"), "Duke") }

        val failure = runCatching {
            operations.doTransformWithThinkingIfPossible(
                messages = listOf(UserMessage("Name a dog")),
                interaction = LlmInteraction(id = InteractionId("id"), llm = LlmOptions()),
                outputClass = String::class.java,
                llmRequestEvent = null,
            ).getOrThrow()
        }.exceptionOrNull()

        val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue(messages.any { "output converter" in it }, "Unexpected outcome: $messages")
    }
}
