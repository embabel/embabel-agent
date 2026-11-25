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
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.Usage
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.loop.LlmMessageRequest
import com.embabel.agent.spi.loop.LlmMessageResponse
import com.embabel.agent.spi.loop.LlmMessageSender
import com.embabel.agent.spi.loop.RequestAwareLlmMessageSender
import com.embabel.agent.support.SimpleTestAgent
import com.embabel.agent.test.common.EventSavingAgenticEventListener
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.ai.converters.SchemaGeneratorCustomizer
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

    private fun operationsFor(
        maxAttempts: Int = 1,
        schemaGeneratorCustomizers: List<SchemaGeneratorCustomizer> = emptyList(),
        sender: LlmMessageSender,
    ): ToolLoopLlmOperations {
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
            schemaGeneratorCustomizers = schemaGeneratorCustomizers,
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

    private val interaction = LlmInteraction(id = InteractionId("id"), llm = LlmOptions())

    private fun replying(json: String) = operationsFor { _, _ -> LlmMessageResponse(AssistantMessage(json), json) }

    @Test
    fun `typed output is converted without a Spring AI subclass`() {
        assertEquals(Dog("Duke"), createObject(replying("""{"name":"Duke"}"""), Dog::class.java))
    }

    @Test
    fun `typed output asks for the JSON schema of the output class`() {
        val sent = mutableListOf<List<Message>>()
        val operations = operationsFor { messages, _ ->
            sent += messages
            LlmMessageResponse(AssistantMessage("""{"name":"Duke"}"""), """{"name":"Duke"}""")
        }

        createObject(operations, Dog::class.java)

        assertTrue(sent.single().any { "\"name\"" in it.content && "schema" in it.content.lowercase() })
    }

    /** Captures the native structured-output request a service's sender is given. */
    private class RequestCapturingSender : RequestAwareLlmMessageSender {
        val requests = mutableListOf<LlmMessageRequest>()

        override fun call(request: LlmMessageRequest): LlmMessageResponse {
            requests += request
            return LlmMessageResponse(AssistantMessage("""{"name":"Duke"}"""), """{"name":"Duke"}""")
        }

        override fun call(messages: List<Message>, tools: List<Tool>) = call(LlmMessageRequest(messages, tools))
    }

    @Test
    fun `typed output offers the service the JSON schema for native structured output`() {
        val sender = RequestCapturingSender()

        createObject(operationsFor(sender = sender), Dog::class.java)

        val request = sender.requests.single().nativeStructuredOutputRequest!!.structuredOutputRequest
        assertEquals("Dog", request.name)
        assertTrue("\"name\"" in request.schema, request.schema)
    }

    @Test
    fun `typed output schema has the schema generator customizers applied`() {
        val sender = RequestCapturingSender()
        val customizer = SchemaGeneratorCustomizer { builder ->
            builder.forFields().withDescriptionResolver { "what the dog answers to" }
        }

        createObject(operationsFor(schemaGeneratorCustomizers = listOf(customizer), sender = sender), Dog::class.java)

        val schema = sender.requests.single().nativeStructuredOutputRequest!!.structuredOutputRequest.schema
        assertTrue("what the dog answers to" in schema, schema)
    }

    @Test
    fun `String output has think blocks removed`() {
        assertEquals("Duke", createObject(replying("<think>a dog</think>Duke"), String::class.java))
    }

    @Test
    fun `if-possible output is converted`() {
        val result = replying("""{"success":{"name":"Duke"}}""").createObjectIfPossible(
            messages = listOf(UserMessage("Name a dog")),
            interaction = interaction,
            outputClass = Dog::class.java,
            action = SimpleTestAgent.actions.first(),
            agentProcess = agentProcess,
        )

        assertEquals(Dog("Duke"), result.getOrThrow())
    }

    @Test
    fun `if-possible output reports the model's failure`() {
        val result = replying("""{"failure":"no dogs here"}""").createObjectIfPossible(
            messages = listOf(UserMessage("Name a dog")),
            interaction = interaction,
            outputClass = Dog::class.java,
            action = SimpleTestAgent.actions.first(),
            agentProcess = agentProcess,
        )

        assertEquals("no dogs here", result.exceptionOrNull()?.message)
    }

    @Test
    fun `typed output with thinking is converted`() {
        val response = replying("""<think>a dog</think>{"name":"Duke"}""").doTransformWithThinking(
            messages = listOf(UserMessage("Name a dog")),
            interaction = interaction,
            outputClass = Dog::class.java,
            llmRequestEvent = null,
        )

        assertEquals(Dog("Duke"), response.result)
        assertTrue(response.thinkingBlocks.isNotEmpty())
    }

    @Test
    fun `if-possible output with thinking is converted`() {
        val response = replying("""<think>a dog</think>{"success":{"name":"Duke"}}""")
            .doTransformWithThinkingIfPossible(
                messages = listOf(UserMessage("Name a dog")),
                interaction = interaction,
                outputClass = Dog::class.java,
                llmRequestEvent = null,
            ).getOrThrow()

        assertEquals(Dog("Duke"), response.result)
    }

    /** A subclass that returns no output converters, as a misconfigured one would. */
    private fun operationsWithoutConverters(): ToolLoopLlmOperations {
        val llm = mockk<LlmService<*>>(relaxed = true)
        every { llm.createMessageSender(any()) } answers {
            attempts++
            LlmMessageSender { _, _ -> LlmMessageResponse(AssistantMessage("{}"), "{}") }
        }
        val modelProvider = mockk<ModelProvider>()
        every { modelProvider.getLlm(any()) } returns llm
        return object : ToolLoopLlmOperations(
            modelProvider = modelProvider,
            toolDecorator = DefaultToolDecorator(),
            validator = Validation.buildDefaultValidatorFactory().validator,
            dataBindingProperties = LlmDataBindingProperties(maxAttempts = 3),
            asyncer = ExecutorAsyncer(Executors.newCachedThreadPool()),
            templateRenderer = JinjavaTemplateRenderer(),
        ) {
            override fun <O> createOutputConverter(outputClass: Class<O>, interaction: LlmInteraction) =
                null as OutputConverter<O>?

            override fun <O> createMaybeReturnOutputConverter(outputClass: Class<O>, interaction: LlmInteraction) =
                null as OutputConverter<MaybeReturn<O>>?
        }
    }

    @Test
    fun `a subclass that returns no output converter fails once, with a clear message`() {
        val thrown = assertThrows<Exception> { createObject(operationsWithoutConverters(), Dog::class.java) }

        val messages = generateSequence<Throwable>(thrown) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue(messages.any { "output converter" in it }, "Unexpected failure: $messages")
        assertEquals(1, attempts)
    }

    @Test
    fun `a subclass that returns no if-possible output converter fails with a clear message`() {
        val failure = runCatching {
            operationsWithoutConverters().createObjectIfPossible(
                messages = listOf(UserMessage("Name a dog")),
                interaction = interaction,
                outputClass = Dog::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = agentProcess,
            ).getOrThrow()
        }.exceptionOrNull()

        val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue(messages.any { "output converter" in it }, "Unexpected outcome: $messages")
    }
}
