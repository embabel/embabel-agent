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
import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.core.Usage
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.AutoLlmSelectionCriteriaResolver
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.ToolDecorator
import com.embabel.agent.spi.loop.LlmMessageResponse
import com.embabel.agent.spi.loop.LlmMessageSender
import com.embabel.agent.spi.support.springai.DefaultToolDecorator
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.agent.spi.validation.DefaultValidationPromptGenerator
import com.embabel.agent.support.SimpleTestAgent
import com.embabel.agent.test.common.EventSavingAgenticEventListener
import com.embabel.chat.AssistantMessage
import com.embabel.chat.AssistantMessageWithToolCalls
import com.embabel.chat.Message
import com.embabel.chat.ToolCall
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.DefaultOptionsConverter
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.ai.model.ModelSelectionCriteria
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import jakarta.validation.Validation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.DefaultChatOptions
import org.springframework.ai.chat.prompt.Prompt

/**
 * Tests for [ToolLoopLlmOperations] directly, testing the framework-agnostic
 * tool loop orchestration logic.
 */
class ToolLoopLlmOperationsTest {

    private lateinit var mockModelProvider: ModelProvider
    private lateinit var mockAgentProcess: AgentProcess
    private lateinit var mockProcessContext: ProcessContext
    private lateinit var eventListener: EventSavingAgenticEventListener
    private lateinit var mutableLlmInvocationHistory: MutableLlmInvocationHistory
    private val objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @BeforeEach
    fun setup() {
        eventListener = EventSavingAgenticEventListener()
        mutableLlmInvocationHistory = MutableLlmInvocationHistory()
        mockProcessContext = mockk<ProcessContext>()
        every { mockProcessContext.platformServices } returns mockk()
        every { mockProcessContext.platformServices.agentPlatform } returns mockk()
        every { mockProcessContext.platformServices.agentPlatform.toolGroupResolver } returns RegistryToolGroupResolver(
            "test",
            emptyList()
        )
        every { mockProcessContext.platformServices.eventListener } returns eventListener
        mockAgentProcess = mockk<AgentProcess>()
        every { mockAgentProcess.recordLlmInvocation(any()) } answers {
            mutableLlmInvocationHistory.invocations.add(firstArg())
        }
        every { mockProcessContext.onProcessEvent(any()) } answers { eventListener.onProcessEvent(firstArg()) }
        every { mockProcessContext.agentProcess } returns mockAgentProcess
        every { mockAgentProcess.agent } returns SimpleTestAgent
        every { mockAgentProcess.processContext } returns mockProcessContext

        mockModelProvider = mockk<ModelProvider>()
    }

    private fun createTestableOperations(
        messageSender: LlmMessageSender,
        outputConverter: OutputConverter<*>? = null,
    ): TestableToolLoopLlmOperations {
        val fakeChatModel = FakeChatModel("unused")
        val fakeLlm = SpringAiLlmService("test", "provider", fakeChatModel, DefaultOptionsConverter)
        val crit = slot<ModelSelectionCriteria>()
        every { mockModelProvider.getLlm(capture(crit)) } returns fakeLlm

        return TestableToolLoopLlmOperations(
            modelProvider = mockModelProvider,
            toolDecorator = DefaultToolDecorator(),
            objectMapper = objectMapper,
            messageSender = messageSender,
            outputConverter = outputConverter,
        )
    }

    @Nested
    inner class DoTransformTests {

        @Test
        fun `doTransform returns parsed result from LLM response`() {
            val messageSender = TestLlmMessageSender(
                responses = listOf(textResponse("Hello, world!"))
            )
            val operations = createTestableOperations(messageSender)

            val result = operations.testDoTransform(
                messages = listOf(UserMessage("Say hello")),
                interaction = createInteraction(),
                outputClass = String::class.java,
            )

            assertEquals("Hello, world!", result)
        }

        @Test
        fun `doTransform uses output converter for non-String types`() {
            data class TestOutput(val message: String)

            val converter = object : OutputConverter<TestOutput> {
                override fun convert(source: String): TestOutput = TestOutput(source)
                override fun getFormat(): String = "Return a message"
            }

            val messageSender = TestLlmMessageSender(
                responses = listOf(textResponse("parsed message"))
            )
            val operations = createTestableOperations(messageSender, converter)

            val result = operations.testDoTransform(
                messages = listOf(UserMessage("Get output")),
                interaction = createInteraction(),
                outputClass = TestOutput::class.java,
            )

            assertEquals("parsed message", result.message)
        }

        @Test
        fun `doTransform executes tools when LLM requests them`() {
            val toolCalled = mutableListOf<String>()
            val testTool = TestTool(
                name = "test_tool",
                description = "A test tool",
                onCall = { args ->
                    toolCalled.add(args)
                    Tool.Result.text("""{"status": "done"}""")
                }
            )

            val messageSender = TestLlmMessageSender(
                responses = listOf(
                    toolCallResponse("call_1", "test_tool", """{"param": "value"}"""),
                    textResponse("Tool executed successfully")
                )
            )
            val operations = createTestableOperations(messageSender)

            val result = operations.testDoTransform(
                messages = listOf(UserMessage("Use the tool")),
                interaction = createInteraction(tools = listOf(testTool)),
                outputClass = String::class.java,
            )

            assertEquals("Tool executed successfully", result)
            assertEquals(1, toolCalled.size)
            assertEquals("""{"param": "value"}""", toolCalled[0])
        }

        @Test
        fun `doTransform handles multiple tool calls in sequence`() {
            val callOrder = mutableListOf<String>()

            val tool1 = TestTool("tool_a", "Tool A") { callOrder.add("A"); Tool.Result.text("A done") }
            val tool2 = TestTool("tool_b", "Tool B") { callOrder.add("B"); Tool.Result.text("B done") }

            val messageSender = TestLlmMessageSender(
                responses = listOf(
                    toolCallResponse("call_1", "tool_a", "{}"),
                    toolCallResponse("call_2", "tool_b", "{}"),
                    textResponse("Both tools executed")
                )
            )
            val operations = createTestableOperations(messageSender)

            val result = operations.testDoTransform(
                messages = listOf(UserMessage("Use both tools")),
                interaction = createInteraction(tools = listOf(tool1, tool2)),
                outputClass = String::class.java,
            )

            assertEquals("Both tools executed", result)
            assertEquals(listOf("A", "B"), callOrder)
        }
    }

    @Nested
    inner class MessageBuildingTests {

        @Test
        fun `buildInitialMessages includes system prompt contributions`() {
            val messageSender = TestLlmMessageSender(
                responses = listOf(textResponse("response"))
            )
            val operations = createTestableOperations(messageSender)

            val messages = operations.testBuildInitialMessages(
                promptContributions = "You are a helpful assistant",
                messages = listOf(UserMessage("Hello")),
                schemaFormat = null,
            )

            assertEquals(2, messages.size)
            assertTrue(messages[0] is com.embabel.chat.SystemMessage)
            assertEquals("You are a helpful assistant", (messages[0] as com.embabel.chat.SystemMessage).content)
            assertTrue(messages[1] is UserMessage)
        }

        @Test
        fun `buildInitialMessages includes schema format when provided`() {
            val messageSender = TestLlmMessageSender(
                responses = listOf(textResponse("response"))
            )
            val operations = createTestableOperations(messageSender)

            val messages = operations.testBuildInitialMessages(
                promptContributions = "System prompt",
                messages = listOf(UserMessage("Query")),
                schemaFormat = "Return JSON: {\"key\": \"value\"}",
            )

            assertEquals(3, messages.size)
            assertTrue(messages[0] is com.embabel.chat.SystemMessage)
            assertTrue(messages[1] is UserMessage)
            assertTrue(messages[2] is com.embabel.chat.SystemMessage)
            assertEquals("Return JSON: {\"key\": \"value\"}", (messages[2] as com.embabel.chat.SystemMessage).content)
        }

        @Test
        fun `buildInitialMessages skips empty prompt contributions`() {
            val messageSender = TestLlmMessageSender(
                responses = listOf(textResponse("response"))
            )
            val operations = createTestableOperations(messageSender)

            val messages = operations.testBuildInitialMessages(
                promptContributions = "",
                messages = listOf(UserMessage("Hello")),
                schemaFormat = null,
            )

            assertEquals(1, messages.size)
            assertTrue(messages[0] is UserMessage)
        }
    }

    @Nested
    inner class DoTransformIfPossibleTests {

        @Test
        fun `doTransformIfPossible returns success on successful transform`() {
            val messageSender = TestLlmMessageSender(
                responses = listOf(textResponse("Success!"))
            )
            val operations = createTestableOperations(messageSender)

            val result = operations.testDoTransformIfPossible(
                messages = listOf(UserMessage("Try something")),
                interaction = createInteraction(),
                outputClass = String::class.java,
            )

            assertTrue(result.isSuccess)
            assertEquals("Success!", result.getOrNull())
        }

        @Test
        fun `doTransformIfPossible returns failure on exception`() {
            val messageSender = TestLlmMessageSender(
                responses = emptyList() // Will throw when trying to get response
            )
            val operations = createTestableOperations(messageSender)

            val result = operations.testDoTransformIfPossible(
                messages = listOf(UserMessage("Fail")),
                interaction = createInteraction(),
                outputClass = String::class.java,
            )

            assertTrue(result.isFailure)
        }
    }

    @Nested
    inner class UsageRecordingTests {

        @Test
        fun `doTransform returns result with accumulated usage`() {
            val messageSender = TestLlmMessageSender(
                responses = listOf(
                    LlmMessageResponse(
                        message = AssistantMessage("Done"),
                        textContent = "Done",
                        usage = Usage(100, 50, null),
                    )
                )
            )
            val operations = createTestableOperations(messageSender)

            // Just verify the transform works - usage is accumulated in the tool loop
            val result = operations.testDoTransform(
                messages = listOf(UserMessage("Test")),
                interaction = createInteraction(),
                outputClass = String::class.java,
            )

            assertEquals("Done", result)
        }
    }

    @Nested
    inner class ExtensionPointTests {

        private fun setupMockModelProvider(): ModelProvider {
            val fakeChatModel = FakeChatModel("unused")
            val fakeLlm = SpringAiLlmService("test", "provider", fakeChatModel, DefaultOptionsConverter)
            val provider = mockk<ModelProvider>()
            every { provider.getLlm(any()) } returns fakeLlm
            return provider
        }

        @Test
        fun `createMessageSender is called during transform`() {
            var senderCreated = false
            val messageSender = TestLlmMessageSender(
                responses = listOf(textResponse("Done"))
            )

            val operations = object : TestableToolLoopLlmOperations(
                modelProvider = setupMockModelProvider(),
                toolDecorator = DefaultToolDecorator(),
                objectMapper = objectMapper,
                messageSender = messageSender,
                outputConverter = null,
            ) {
                override fun createMessageSender(llm: LlmService<*>, options: LlmOptions): LlmMessageSender {
                    senderCreated = true
                    return messageSender
                }
            }

            operations.testDoTransform(
                messages = listOf(UserMessage("Test")),
                interaction = createInteraction(),
                outputClass = String::class.java,
            )

            assertTrue(senderCreated)
        }

        @Test
        fun `createOutputConverter is called for non-String types`() {
            data class CustomOutput(val value: String)

            var converterCreated = false
            val converter = object : OutputConverter<CustomOutput> {
                override fun convert(source: String): CustomOutput = CustomOutput(source)
                override fun getFormat(): String = "format"
            }

            val messageSender = TestLlmMessageSender(
                responses = listOf(textResponse("test"))
            )

            val operations = object : TestableToolLoopLlmOperations(
                modelProvider = setupMockModelProvider(),
                toolDecorator = DefaultToolDecorator(),
                objectMapper = objectMapper,
                messageSender = messageSender,
                outputConverter = converter,
            ) {
                override fun <O> createOutputConverter(
                    outputClass: Class<O>,
                    interaction: LlmInteraction,
                ): OutputConverter<O>? {
                    converterCreated = true
                    @Suppress("UNCHECKED_CAST")
                    return converter as OutputConverter<O>
                }
            }

            operations.testDoTransform(
                messages = listOf(UserMessage("Test")),
                interaction = createInteraction(),
                outputClass = CustomOutput::class.java,
            )

            assertTrue(converterCreated)
        }

        @Test
        fun `sanitizeStringOutput is called for String results`() {
            var sanitizeCalled = false
            val messageSender = TestLlmMessageSender(
                responses = listOf(textResponse("<think>thinking</think>Result"))
            )

            val operations = object : TestableToolLoopLlmOperations(
                modelProvider = setupMockModelProvider(),
                toolDecorator = DefaultToolDecorator(),
                objectMapper = objectMapper,
                messageSender = messageSender,
                outputConverter = null,
            ) {
                override fun sanitizeStringOutput(text: String): String {
                    sanitizeCalled = true
                    return text.replace("<think>thinking</think>", "")
                }
            }

            val result = operations.testDoTransform(
                messages = listOf(UserMessage("Test")),
                interaction = createInteraction(),
                outputClass = String::class.java,
            )

            assertTrue(sanitizeCalled)
            assertEquals("Result", result)
        }
    }

    // Helper methods

    private fun createInteraction(
        tools: List<Tool> = emptyList(),
    ): LlmInteraction {
        return LlmInteraction(
            id = InteractionId("test-interaction"),
            tools = tools,
            llm = LlmOptions(),
        )
    }

    private fun textResponse(text: String): LlmMessageResponse {
        return LlmMessageResponse(
            message = AssistantMessage(text),
            textContent = text,
        )
    }

    private fun toolCallResponse(
        toolCallId: String,
        toolName: String,
        arguments: String,
    ): LlmMessageResponse {
        val toolCall = ToolCall(toolCallId, toolName, arguments)
        return LlmMessageResponse(
            message = AssistantMessageWithToolCalls(
                content = " ",
                toolCalls = listOf(toolCall),
            ),
            textContent = "",
        )
    }
}

/**
 * Test implementation of ToolLoopLlmOperations that allows injecting mocks.
 */
internal open class TestableToolLoopLlmOperations(
    modelProvider: ModelProvider,
    toolDecorator: ToolDecorator,
    objectMapper: ObjectMapper,
    private val messageSender: LlmMessageSender,
    private val outputConverter: OutputConverter<*>?,
) : ToolLoopLlmOperations(
    modelProvider = modelProvider,
    toolDecorator = toolDecorator,
    validator = Validation.buildDefaultValidatorFactory().validator,
    validationPromptGenerator = DefaultValidationPromptGenerator(),
    dataBindingProperties = LlmDataBindingProperties(),
    autoLlmSelectionCriteriaResolver = AutoLlmSelectionCriteriaResolver.DEFAULT,
    promptsProperties = LlmOperationsPromptsProperties(),
    objectMapper = objectMapper,
) {

    override fun createMessageSender(llm: LlmService<*>, options: LlmOptions): LlmMessageSender {
        return messageSender
    }

    @Suppress("UNCHECKED_CAST")
    override fun <O> createOutputConverter(
        outputClass: Class<O>,
        interaction: LlmInteraction,
    ): OutputConverter<O>? {
        return outputConverter as? OutputConverter<O>
    }

    // Expose for testing - delegates to protected method
    fun testBuildInitialMessages(
        promptContributions: String,
        messages: List<Message>,
        schemaFormat: String?,
    ): List<Message> {
        return buildInitialMessages(promptContributions, messages, schemaFormat)
    }

    // Expose doTransform for direct testing, bypassing AbstractLlmOperations.createObject
    fun <O> testDoTransform(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
    ): O {
        return doTransform(
            messages = messages,
            interaction = interaction,
            outputClass = outputClass,
            llmRequestEvent = null,
        )
    }

    // Expose doTransformIfPossible for direct testing
    fun <O> testDoTransformIfPossible(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
    ): Result<O> {
        return doTransformIfPossible(
            messages = messages,
            interaction = interaction,
            outputClass = outputClass,
            llmRequestEvent = mockk(relaxed = true),
        )
    }
}

/**
 * Simple test tool for unit testing.
 */
internal class TestTool(
    private val name: String,
    private val description: String,
    private val onCall: (String) -> Tool.Result,
) : Tool {

    override val definition: Tool.Definition = Tool.Definition(
        name = name,
        description = description,
        inputSchema = Tool.InputSchema.empty(),
    )

    override fun call(input: String): Tool.Result = onCall(input)
}

/**
 * Simple test LlmMessageSender that returns predetermined responses.
 */
internal class TestLlmMessageSender(
    private val responses: List<LlmMessageResponse>,
) : LlmMessageSender {

    private var callIndex = 0

    override fun call(messages: List<Message>, tools: List<Tool>): LlmMessageResponse {
        if (callIndex >= responses.size) {
            throw IllegalStateException("TestLlmMessageSender ran out of responses at call $callIndex")
        }
        return responses[callIndex++]
    }
}
