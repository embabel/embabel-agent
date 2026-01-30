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

import com.embabel.agent.api.annotation.support.Wumpus
import com.embabel.agent.api.common.InteractionId
import com.embabel.agent.api.common.ToolObject
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.Blackboard
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.core.support.InvalidLlmReturnFormatException
import com.embabel.agent.core.support.InvalidLlmReturnTypeException
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.core.support.safelyGetToolsFrom
import com.embabel.agent.spi.LlmOperations
import com.embabel.agent.spi.support.springai.ChatClientLlmOperations
import com.embabel.agent.spi.support.springai.MaybeReturn
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.agent.spi.validation.DefaultValidationPromptGenerator
import com.embabel.agent.support.SimpleTestAgent
import com.embabel.agent.test.common.EventSavingAgenticEventListener
import com.embabel.chat.SystemMessage
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.DefaultOptionsConverter
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.ai.model.ModelSelectionCriteria
import com.embabel.common.textio.template.JinjavaTemplateRenderer
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import jakarta.validation.Validation
import jakarta.validation.constraints.Pattern
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.DefaultChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import java.time.LocalDate
import kotlin.test.assertEquals

/**
 * Fake ChatModel with fixed response that captures prompts
 * and tools passed to it.
 * @param responses if > 1 element, they'll be returned in turn.
 * Otherwise, the single response will be returned on every request
 */
class FakeChatModel(
    val responses: List<String>,
    private val options: ChatOptions = DefaultChatOptions(),
) : ChatModel {

    constructor(
        response: String,
        options: ChatOptions = DefaultChatOptions(),
    ) : this(
        listOf(response), options
    )

    val response: String get() = responses.single()

    private var index = 0

    val promptsPassed = mutableListOf<Prompt>()
    val optionsPassed = mutableListOf<ToolCallingChatOptions>()

    override fun getDefaultOptions(): ChatOptions = options

    override fun call(prompt: Prompt): ChatResponse {
        promptsPassed.add(prompt)
        val options = prompt.options as? ToolCallingChatOptions
            ?: throw IllegalArgumentException("Expected ToolCallingChatOptions")
        optionsPassed.add(options)
        return ChatResponse(
            listOf(
                Generation(AssistantMessage(responses[index])).also {
                    // If we have more than one response, step through them
                    if (responses.size > 1) ++index
                }
            )
        )
    }
}


class ChatClientLlmOperationsTest {

    data class Setup(
        val llmOperations: LlmOperations,
        val mockAgentProcess: AgentProcess,
        val mutableLlmInvocationHistory: MutableLlmInvocationHistory,
    )

    private fun createChatClientLlmOperations(
        fakeChatModel: FakeChatModel,
        dataBindingProperties: LlmDataBindingProperties = LlmDataBindingProperties(),
    ): Setup {
        val ese = EventSavingAgenticEventListener()
        val mutableLlmInvocationHistory = MutableLlmInvocationHistory()
        val mockProcessContext = mockk<ProcessContext>()
        every { mockProcessContext.platformServices } returns mockk()
        every { mockProcessContext.platformServices.agentPlatform } returns mockk()
        every { mockProcessContext.platformServices.agentPlatform.toolGroupResolver } returns RegistryToolGroupResolver(
            "mt",
            emptyList()
        )
        every { mockProcessContext.platformServices.eventListener } returns ese
        val mockAgentProcess = mockk<AgentProcess>()
        every { mockAgentProcess.recordLlmInvocation(any()) } answers {
            mutableLlmInvocationHistory.invocations.add(firstArg())
        }
        every { mockProcessContext.onProcessEvent(any()) } answers { ese.onProcessEvent(firstArg()) }
        every { mockProcessContext.agentProcess } returns mockAgentProcess

        every { mockAgentProcess.agent } returns SimpleTestAgent
        every { mockAgentProcess.processContext } returns mockProcessContext

        // Add blackboard for guardrail validation (defensive - returns null if not needed)
        val blackboard = mockk<com.embabel.agent.core.Blackboard>(relaxed = true)
        every { mockAgentProcess.blackboard } returns blackboard

        val mockModelProvider = mockk<ModelProvider>()
        val crit = slot<ModelSelectionCriteria>()
        val fakeLlm = SpringAiLlmService("fake", "provider", fakeChatModel, DefaultOptionsConverter)
        every { mockModelProvider.getLlm(capture(crit)) } returns fakeLlm
        val cco = ChatClientLlmOperations(
            modelProvider = mockModelProvider,
            toolDecorator = DefaultToolDecorator(),
            validator = Validation.buildDefaultValidatorFactory().validator,
            validationPromptGenerator = DefaultValidationPromptGenerator(),
            templateRenderer = JinjavaTemplateRenderer(),
            objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule()),
            dataBindingProperties = dataBindingProperties,
        )
        return Setup(cco, mockAgentProcess, mutableLlmInvocationHistory)
    }

    data class Dog(val name: String)

    data class TemporalDog(
        val name: String,
        val birthDate: LocalDate,
    )

    @Nested
    inner class CreateObject {

        @Test
        fun `passes correct prompt`() {
            val duke = Dog("Duke")

            val fakeChatModel = FakeChatModel(jacksonObjectMapper().writeValueAsString(duke))

            val prompt =
                "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua."
            val setup = createChatClientLlmOperations(fakeChatModel)
            setup.llmOperations.createObject(
                messages = listOf(UserMessage(prompt)),
                interaction = LlmInteraction(
                    id = InteractionId("id"), llm = LlmOptions()
                ),
                outputClass = Dog::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = setup.mockAgentProcess,
            )

            val promptText = fakeChatModel.promptsPassed[0].toString()
            assertTrue(promptText.contains("\$schema"), "Prompt contains JSON schema")
            assertTrue(promptText.contains(promptText), "Prompt contains user prompt:\n$promptText")
        }

        @Test
        fun `returns string`() {
            val fakeChatModel = FakeChatModel("fake response")

            val setup = createChatClientLlmOperations(fakeChatModel)
            val result = setup.llmOperations.createObject(
                messages = listOf(UserMessage("prompt")),
                interaction = LlmInteraction(
                    id = InteractionId("id"), llm = LlmOptions()
                ),
                outputClass = String::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = setup.mockAgentProcess,
            )
            assertEquals(fakeChatModel.response, result)
        }

        @Test
        fun `handles ill formed JSON when returning data class`() {
            val fakeChatModel = FakeChatModel("This ain't no JSON")

            val setup = createChatClientLlmOperations(fakeChatModel)
            try {
                setup.llmOperations.createObject(
                    messages = listOf(UserMessage("prompt")),
                    interaction = LlmInteraction(
                        id = InteractionId("id"), llm = LlmOptions()
                    ),
                    outputClass = Dog::class.java,
                    action = SimpleTestAgent.actions.first(),
                    agentProcess = setup.mockAgentProcess,
                )
                fail("Should have thrown exception")
            } catch (e: InvalidLlmReturnFormatException) {
                assertEquals(fakeChatModel.response, e.llmReturn)
                assertTrue(e.infoString(verbose = true).contains(fakeChatModel.response))
            }
        }

        @Test
        fun `returns data class`() {
            val duke = Dog("Duke")

            val fakeChatModel = FakeChatModel(jacksonObjectMapper().writeValueAsString(duke))

            val setup = createChatClientLlmOperations(fakeChatModel)
            val result = setup.llmOperations.createObject(
                messages = listOf(UserMessage("prompt")),
                interaction = LlmInteraction(
                    id = InteractionId("id"), llm = LlmOptions()
                ),
                outputClass = Dog::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = setup.mockAgentProcess,
            )
            assertEquals(duke, result)
        }

        @Test
        fun `passes JSON few shot example`() {
            val duke = Dog("Duke")

            val fakeChatModel = FakeChatModel(jacksonObjectMapper().writeValueAsString(duke))

            val setup = createChatClientLlmOperations(fakeChatModel)
            val result = setup.llmOperations.createObject(
                messages = listOf(
                    UserMessage(
                        """
                    Return a dog. Dogs look like this:
                {
                    "name": "Duke",
                    "type": "Dog"
                }
                """.trimIndent()
                    )
                ),
                interaction = LlmInteraction(
                    id = InteractionId("id"), llm = LlmOptions()
                ),
                outputClass = Dog::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = setup.mockAgentProcess,
            )
            assertEquals(duke, result)
        }

        @Test
        fun `presents no tools to ChatModel`() {
            val duke = Dog("Duke")

            val fakeChatModel = FakeChatModel(jacksonObjectMapper().writeValueAsString(duke))

            val setup = createChatClientLlmOperations(fakeChatModel)
            val result = setup.llmOperations.createObject(
                messages = listOf(UserMessage("prompt")),
                interaction = LlmInteraction(
                    id = InteractionId("id"), llm = LlmOptions()
                ),
                outputClass = Dog::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = setup.mockAgentProcess,
            )
            assertEquals(duke, result)
            assertEquals(1, fakeChatModel.promptsPassed.size)
            val tools = fakeChatModel.optionsPassed[0].toolCallbacks
            assertEquals(0, tools.size)
        }

        @Test
        fun `presents tools to ChatModel via doTransform`() {
            val duke = Dog("Duke")

            val fakeChatModel = FakeChatModel(jacksonObjectMapper().writeValueAsString(duke))

            // Wumpus's have tools
            val tools = safelyGetToolsFrom(ToolObject(Wumpus("wumpy")))
            val setup = createChatClientLlmOperations(fakeChatModel)
            val result = setup.llmOperations.doTransform(
                messages = listOf(
                    SystemMessage("do whatever"),
                    UserMessage("prompt"),
                ),
                interaction = LlmInteraction(
                    id = InteractionId("id"),
                    llm = LlmOptions(),
                    tools = tools,
                ),
                outputClass = Dog::class.java,
                llmRequestEvent = null,
            )
            assertEquals(duke, result)
            assertEquals(1, fakeChatModel.promptsPassed.size)
            val passedTools = fakeChatModel.optionsPassed[0].toolCallbacks
            assertEquals(tools.size, passedTools.size, "Must have passed same number of tools")
            assertEquals(
                tools.map { it.definition.name }.toSet(),
                passedTools.map { it.toolDefinition.name() }.toSet(),
            )
        }

        @Test
        fun `presents tools to ChatModel when given multiple messages`() {
            val duke = Dog("Duke")

            val fakeChatModel = FakeChatModel(jacksonObjectMapper().writeValueAsString(duke))

            // Wumpus's have tools - use native Tool interface
            val tools = safelyGetToolsFrom(ToolObject(Wumpus("wumpy")))
            val setup = createChatClientLlmOperations(fakeChatModel)
            val result = setup.llmOperations.createObject(
                messages = listOf(UserMessage("prompt")),
                interaction = LlmInteraction(
                    id = InteractionId("id"),
                    llm = LlmOptions(),
                    tools = tools,
                ),
                outputClass = Dog::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = setup.mockAgentProcess,
            )
            assertEquals(duke, result)
            assertEquals(1, fakeChatModel.promptsPassed.size)
            val passedTools = fakeChatModel.optionsPassed[0].toolCallbacks
            assertEquals(tools.size, passedTools.size, "Must have passed same number of tools")
            assertEquals(
                tools.map { it.definition.name }.sorted(),
                passedTools.map { it.toolDefinition.name() })
        }

        @Test
        fun `handles reasoning model return`() {
            val duke = Dog("Duke")

            val fakeChatModel = FakeChatModel(
                "<think>Deep thoughts</think>\n" + jacksonObjectMapper().writeValueAsString(duke)
            )

            val setup = createChatClientLlmOperations(fakeChatModel)
            val result = setup.llmOperations.createObject(
                messages = listOf(UserMessage("prompt")),
                interaction = LlmInteraction(
                    id = InteractionId("id"), llm = LlmOptions()
                ),
                outputClass = Dog::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = setup.mockAgentProcess,
            )
            assertEquals(duke, result)
        }

        @Test
        fun `handles LocalDate return`() {
            val duke = TemporalDog("Duke", birthDate = LocalDate.of(2021, 2, 26))

            val fakeChatModel = FakeChatModel(
                jacksonObjectMapper().registerModule(JavaTimeModule()).writeValueAsString(duke)
            )

            val setup = createChatClientLlmOperations(fakeChatModel)
            val result = setup.llmOperations.createObject(
                messages = listOf(UserMessage("prompt")),
                interaction = LlmInteraction(
                    id = InteractionId("id"), llm = LlmOptions()
                ),
                outputClass = TemporalDog::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = setup.mockAgentProcess,
            )
            assertEquals(duke, result)
        }
    }

    @Nested
    inner class CreateObjectIfPossible {

        @Test
        fun `should have correct prompt with success and failure`() {
            val fakeChatModel =
                FakeChatModel(
                    jacksonObjectMapper().writeValueAsString(
                        MaybeReturn<Dog>(
                            failure = "didn't work"
                        )
                    )
                )

            val prompt = "The quick brown fox jumped over the lazy dog"
            val setup = createChatClientLlmOperations(fakeChatModel)
            val result = setup.llmOperations.createObjectIfPossible(
                messages = listOf(UserMessage(prompt)),
                interaction = LlmInteraction(
                    id = InteractionId("id"), llm = LlmOptions()
                ),
                outputClass = Dog::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = setup.mockAgentProcess,
            )
            assertTrue(result.isFailure)
            val promptText = fakeChatModel.promptsPassed[0].toString()
            assertTrue(promptText.contains("\$schema"), "Prompt contains JSON schema")
            assertTrue(promptText.contains(promptText), "Prompt contains user prompt:\n$promptText")

            assertTrue(promptText.contains("possible"), "Prompt mentions possible")
            assertTrue(promptText.contains("success"), "Prompt mentions success")
            assertTrue(promptText.contains("failure"), "Prompt mentions failure")
        }

        @Test
        fun `returns data class - success`() {
            val duke = Dog("Duke")

            val fakeChatModel = FakeChatModel(
                jacksonObjectMapper().writeValueAsString(
                    MaybeReturn(
                        success = duke
                    )
                )
            )

            val setup = createChatClientLlmOperations(fakeChatModel)
            val result = setup.llmOperations.createObjectIfPossible(
                messages = listOf(UserMessage("prompt")),
                interaction = LlmInteraction(
                    id = InteractionId("id"), llm = LlmOptions()
                ),
                outputClass = Dog::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = setup.mockAgentProcess,
            )
            assertEquals(duke, result.getOrThrow())
        }

        @Test
        fun `handles reasoning model success return`() {
            val duke = Dog("Duke")

            val fakeChatModel = FakeChatModel(
                "<think>More deep thoughts</think>\n" + jacksonObjectMapper().writeValueAsString(
                    MaybeReturn(
                        success = duke
                    )
                )
            )

            val setup = createChatClientLlmOperations(fakeChatModel)
            val result = setup.llmOperations.createObjectIfPossible(
                messages = listOf(UserMessage("prompt")),
                interaction = LlmInteraction(
                    id = InteractionId("id"), llm = LlmOptions()
                ),
                outputClass = Dog::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = setup.mockAgentProcess,
            )
            assertEquals(duke, result.getOrThrow())
        }

        @Test
        fun `handles LocalDate return`() {
            val duke = TemporalDog("Duke", birthDate = LocalDate.of(2021, 2, 26))

            val fakeChatModel = FakeChatModel(
                jacksonObjectMapper().registerModule(JavaTimeModule()).writeValueAsString(
                    MaybeReturn(duke)
                )
            )

            val setup = createChatClientLlmOperations(fakeChatModel)
            val result = setup.llmOperations.createObjectIfPossible(
                messages = listOf(UserMessage("prompt")),
                interaction = LlmInteraction(
                    id = InteractionId("id"), llm = LlmOptions()
                ),
                outputClass = TemporalDog::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = setup.mockAgentProcess,
            )
            assertEquals(duke, result.getOrThrow())
        }

        @Test
        fun `handles ill formed JSON when returning data class`() {
            val fakeChatModel = FakeChatModel("This ain't no JSON")

            val setup = createChatClientLlmOperations(fakeChatModel)
            try {
                setup.llmOperations.createObjectIfPossible(
                    messages = listOf(UserMessage("prompt")),
                    interaction = LlmInteraction(
                        id = InteractionId("id"), llm = LlmOptions()
                    ),
                    outputClass = Dog::class.java,
                    action = SimpleTestAgent.actions.first(),
                    agentProcess = setup.mockAgentProcess,
                )
                fail("Should have thrown exception")
            } catch (e: InvalidLlmReturnFormatException) {
                assertEquals(fakeChatModel.response, e.llmReturn)
                assertTrue(e.infoString(verbose = true).contains(fakeChatModel.response))
            }
        }

        @Test
        fun `returns data class - failure`() {
            val fakeChatModel =
                FakeChatModel(
                    jacksonObjectMapper().writeValueAsString(
                        MaybeReturn<Dog>(
                            failure = "didn't work"
                        )
                    )
                )

            val setup = createChatClientLlmOperations(fakeChatModel)
            val result = setup.llmOperations.createObjectIfPossible(
                messages = listOf(UserMessage("prompt")),
                interaction = LlmInteraction(
                    id = InteractionId("id"), llm = LlmOptions()
                ),
                outputClass = Dog::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = setup.mockAgentProcess,
            )
            assertTrue(result.isFailure)
        }

        @Test
        fun `presents tools to ChatModel`() {
            val duke = Dog("Duke")

            val fakeChatModel = FakeChatModel(
                jacksonObjectMapper().writeValueAsString(
                    MaybeReturn(duke)
                )
            )

            // Wumpus's have tools - use native Tool interface
            val tools = safelyGetToolsFrom(ToolObject(Wumpus("wumpy")))
            val setup = createChatClientLlmOperations(fakeChatModel)
            setup.llmOperations.createObjectIfPossible(
                messages = listOf(UserMessage("prompt")),
                interaction = LlmInteraction(
                    id = InteractionId("id"),
                    llm = LlmOptions(),
                    tools = tools,
                ),
                outputClass = Dog::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = setup.mockAgentProcess,
            )
            assertEquals(1, fakeChatModel.promptsPassed.size)
            val passedTools = fakeChatModel.optionsPassed[0].toolCallbacks
            assertEquals(tools.size, passedTools.size, "Must have passed same number of tools")
            assertEquals(
                tools.map { it.definition.name }.sorted(),
                passedTools.map { it.toolDefinition.name() })
        }
    }

    @Nested
    inner class TimeoutBehavior {

        /**
         * Fake ChatModel that introduces a delay before returning.
         * Used to test timeout behavior.
         */
        inner class DelayingFakeChatModel(
            private val response: String,
            private val delayMillis: Long,
            options: ChatOptions = DefaultChatOptions(),
        ) : ChatModel {
            private val defaultOptions = options
            val callCount = java.util.concurrent.atomic.AtomicInteger(0)

            override fun getDefaultOptions(): ChatOptions = defaultOptions

            override fun call(prompt: Prompt): ChatResponse {
                callCount.incrementAndGet()
                Thread.sleep(delayMillis)
                val options = prompt.options as? ToolCallingChatOptions
                    ?: throw IllegalArgumentException("Expected ToolCallingChatOptions")
                return ChatResponse(listOf(Generation(AssistantMessage(response))))
            }
        }

        @Test
        fun `Spring AI path should timeout when LLM call exceeds timeout`() {
            // LLM takes 2000ms, but timeout is 200ms - should definitely timeout
            val duke = Dog("Duke")
            val delayingChatModel = DelayingFakeChatModel(
                response = jacksonObjectMapper().writeValueAsString(duke),
                delayMillis = 2000,
            )

            val setup = createChatClientLlmOperationsWithDelayingModel(delayingChatModel)

            // Spring AI path (useEmbabelToolLoop=false) has timeout - should fail
            val exception = assertThrows(RuntimeException::class.java) {
                setup.llmOperations.createObject(
                    messages = listOf(UserMessage("Give me a dog")),
                    interaction = LlmInteraction(
                        id = InteractionId("timeout-test-springai"),
                        llm = LlmOptions().withTimeout(java.time.Duration.ofMillis(200)),
                        useEmbabelToolLoop = false,
                    ),
                    outputClass = Dog::class.java,
                    action = SimpleTestAgent.actions.first(),
                    agentProcess = setup.mockAgentProcess,
                )
            }

            assertTrue(
                exception.message?.contains("timed out") == true ||
                        exception.cause is java.util.concurrent.TimeoutException,
                "Should have timed out, but got: ${exception.message}"
            )
        }

        @Test
        fun `Embabel tool loop path should timeout when LLM call exceeds timeout`() {
            // LLM takes 500ms, but timeout is 100ms - should timeout
            // THIS TEST CURRENTLY FAILS because Embabel tool loop has no timeout!
            val duke = Dog("Duke")
            val delayingChatModel = DelayingFakeChatModel(
                response = jacksonObjectMapper().writeValueAsString(duke),
                delayMillis = 500,
            )

            val setup = createChatClientLlmOperationsWithDelayingModel(delayingChatModel)

            // Embabel tool loop path (useEmbabelToolLoop=true) should also timeout
            val exception = assertThrows(RuntimeException::class.java) {
                setup.llmOperations.createObject(
                    messages = listOf(UserMessage("Give me a dog")),
                    interaction = LlmInteraction(
                        id = InteractionId("timeout-test-embabel"),
                        llm = LlmOptions().withTimeout(java.time.Duration.ofMillis(100)),
                        useEmbabelToolLoop = true,
                    ),
                    outputClass = Dog::class.java,
                    action = SimpleTestAgent.actions.first(),
                    agentProcess = setup.mockAgentProcess,
                )
            }

            assertTrue(
                exception.message?.contains("timed out") == true ||
                        exception.cause is java.util.concurrent.TimeoutException,
                "Should have timed out, but got: ${exception.message}"
            )
        }

        private fun createChatClientLlmOperationsWithDelayingModel(
            delayingChatModel: DelayingFakeChatModel,
        ): Setup {
            val ese = EventSavingAgenticEventListener()
            val mutableLlmInvocationHistory = MutableLlmInvocationHistory()
            val mockProcessContext = mockk<ProcessContext>()
            every { mockProcessContext.platformServices } returns mockk()
            every { mockProcessContext.platformServices.agentPlatform } returns mockk()
            every { mockProcessContext.platformServices.agentPlatform.toolGroupResolver } returns RegistryToolGroupResolver(
                "mt",
                emptyList()
            )
            every { mockProcessContext.platformServices.eventListener } returns ese
            val mockAgentProcess = mockk<AgentProcess>()
            every { mockAgentProcess.recordLlmInvocation(any()) } answers {
                mutableLlmInvocationHistory.invocations.add(firstArg())
            }
            every { mockProcessContext.onProcessEvent(any()) } answers { ese.onProcessEvent(firstArg()) }
            every { mockProcessContext.agentProcess } returns mockAgentProcess

            every { mockAgentProcess.agent } returns SimpleTestAgent
            every { mockAgentProcess.processContext } returns mockProcessContext

            // Add blackboard for guardrail validation
            val blackboard = mockk<Blackboard>(relaxed = true)
            every { mockAgentProcess.blackboard } returns blackboard

            val mockModelProvider = mockk<ModelProvider>()
            val crit = slot<ModelSelectionCriteria>()
            val fakeLlm = SpringAiLlmService("fake", "provider", delayingChatModel, DefaultOptionsConverter)
            every { mockModelProvider.getLlm(capture(crit)) } returns fakeLlm
            val promptsProperties = LlmOperationsPromptsProperties().apply {
                defaultTimeout = java.time.Duration.ofMillis(100)  // Short default timeout
            }
            val cco = ChatClientLlmOperations(
                modelProvider = mockModelProvider,
                toolDecorator = DefaultToolDecorator(),
                validator = Validation.buildDefaultValidatorFactory().validator,
                validationPromptGenerator = DefaultValidationPromptGenerator(),
                templateRenderer = JinjavaTemplateRenderer(),
                objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule()),
                dataBindingProperties = LlmDataBindingProperties(maxAttempts = 1),  // No retries for timeout tests
                llmOperationsPromptsProperties = promptsProperties,
            )
            return Setup(cco, mockAgentProcess, mutableLlmInvocationHistory)
        }
    }

    @Nested
    inner class RetryOnInvalidJson {

        @Test
        fun `should retry on invalid JSON and succeed with Embabel tool loop`() {
            // This test demonstrates the bug: when useEmbabelToolLoop=true (default),
            // InvalidLlmReturnFormatException is NOT retried, causing the operation to fail
            // even when a subsequent attempt would succeed.
            val duke = Dog("Duke")

            // First response is invalid JSON, second is valid
            val fakeChatModel = FakeChatModel(
                responses = listOf(
                    "This ain't no JSON - malformed response",
                    jacksonObjectMapper().writeValueAsString(duke)
                )
            )

            val setup = createChatClientLlmOperations(
                fakeChatModel,
                LlmDataBindingProperties(maxAttempts = 3)
            )

            // With useEmbabelToolLoop=true (default), this should retry and succeed
            // Currently it fails because the Embabel tool loop path has no retry wrapper
            val result = setup.llmOperations.createObject(
                messages = listOf(UserMessage("Give me a dog")),
                interaction = LlmInteraction(
                    id = InteractionId("retry-test"),
                    llm = LlmOptions(),
                    useEmbabelToolLoop = true,  // This is the default, making it explicit
                ),
                outputClass = Dog::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = setup.mockAgentProcess,
            )

            assertEquals(duke, result, "Should have retried and got valid response")
            assertEquals(2, fakeChatModel.promptsPassed.size, "Should have made 2 attempts")
        }

        @Test
        fun `should retry on invalid JSON and succeed with Spring AI tool loop`() {
            // This test shows the Spring AI path DOES have retry logic
            val duke = Dog("Duke")

            // First response is invalid JSON, second is valid
            val fakeChatModel = FakeChatModel(
                responses = listOf(
                    "This ain't no JSON - malformed response",
                    jacksonObjectMapper().writeValueAsString(duke)
                )
            )

            val setup = createChatClientLlmOperations(
                fakeChatModel,
                LlmDataBindingProperties(maxAttempts = 3)
            )

            // With useEmbabelToolLoop=false, this uses Spring AI's path which has retry
            val result = setup.llmOperations.createObject(
                messages = listOf(UserMessage("Give me a dog")),
                interaction = LlmInteraction(
                    id = InteractionId("retry-test-springai"),
                    llm = LlmOptions(),
                    useEmbabelToolLoop = false,  // Use Spring AI path which has retry
                ),
                outputClass = Dog::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = setup.mockAgentProcess,
            )

            assertEquals(duke, result, "Should have retried and got valid response")
            assertEquals(2, fakeChatModel.promptsPassed.size, "Should have made 2 attempts")
        }
    }

    /**
     * Tests for proper system message ordering.
     * Validates fix for GitHub issue #1295: System messages should be consolidated
     * at the beginning of the conversation, not scattered throughout.
     * This is required for:
     * - OpenAI best practices (prevents instruction drift)
     * - DeepSeek compatibility (strict message ordering requirements)
     * - General cross-model reliability
     */
    @Nested
    inner class SystemMessageOrdering {

        @Test
        fun `system message appears only at the beginning of prompt`() {
            val duke = Dog("Duke")
            val fakeChatModel = FakeChatModel(jacksonObjectMapper().writeValueAsString(duke))

            val setup = createChatClientLlmOperations(fakeChatModel)
            setup.llmOperations.createObject(
                messages = listOf(UserMessage("Give me a dog named Duke")),
                interaction = LlmInteraction(
                    id = InteractionId("system-ordering-test"),
                    llm = LlmOptions()
                ),
                outputClass = Dog::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = setup.mockAgentProcess,
            )

            assertEquals(1, fakeChatModel.promptsPassed.size)
            val prompt = fakeChatModel.promptsPassed[0]
            val messages = prompt.instructions

            // Count system messages
            val systemMessages = messages.filterIsInstance<org.springframework.ai.chat.messages.SystemMessage>()
            assertTrue(
                systemMessages.size <= 1,
                "Should have at most one system message, but found ${systemMessages.size}"
            )

            // If there's a system message, it should be first
            if (systemMessages.isNotEmpty()) {
                assertTrue(
                    messages.first() is org.springframework.ai.chat.messages.SystemMessage,
                    "System message should be at the beginning of the prompt"
                )
            }
        }

        @Test
        fun `schema format is included in system message not appended after`() {
            val duke = Dog("Duke")
            val fakeChatModel = FakeChatModel(jacksonObjectMapper().writeValueAsString(duke))

            val setup = createChatClientLlmOperations(fakeChatModel)
            setup.llmOperations.createObject(
                messages = listOf(UserMessage("Give me a dog")),
                interaction = LlmInteraction(
                    id = InteractionId("schema-in-system-test"),
                    llm = LlmOptions()
                ),
                outputClass = Dog::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = setup.mockAgentProcess,
            )

            val prompt = fakeChatModel.promptsPassed[0]
            val messages = prompt.instructions

            // The schema format (containing $schema) should be in the first system message
            val systemMessages = messages.filterIsInstance<org.springframework.ai.chat.messages.SystemMessage>()
            assertTrue(systemMessages.isNotEmpty(), "Should have a system message")

            val firstSystemMessage = systemMessages.first()
            assertTrue(
                firstSystemMessage.text.contains("\$schema") || firstSystemMessage.text.contains("\"type\""),
                "Schema format should be in the system message"
            )

            // Verify no system message appears after user messages
            val userMessageIndex = messages.indexOfFirst { it is org.springframework.ai.chat.messages.UserMessage }
            if (userMessageIndex >= 0) {
                val messagesAfterUser = messages.drop(userMessageIndex + 1)
                val systemMessagesAfterUser =
                    messagesAfterUser.filterIsInstance<org.springframework.ai.chat.messages.SystemMessage>()
                assertTrue(
                    systemMessagesAfterUser.isEmpty(),
                    "No system messages should appear after user messages, but found ${systemMessagesAfterUser.size}"
                )
            }
        }

        @Test
        fun `createObjectIfPossible consolidates system messages`() {
            val duke = Dog("Duke")
            val fakeChatModel = FakeChatModel(
                jacksonObjectMapper().writeValueAsString(
                    MaybeReturn(success = duke)
                )
            )

            val setup = createChatClientLlmOperations(fakeChatModel)
            setup.llmOperations.createObjectIfPossible(
                messages = listOf(UserMessage("Give me a dog if possible")),
                interaction = LlmInteraction(
                    id = InteractionId("maybe-return-system-test"),
                    llm = LlmOptions()
                ),
                outputClass = Dog::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = setup.mockAgentProcess,
            )

            val prompt = fakeChatModel.promptsPassed[0]
            val messages = prompt.instructions

            // Count system messages - should be exactly one at the start
            val systemMessages = messages.filterIsInstance<org.springframework.ai.chat.messages.SystemMessage>()
            assertTrue(
                systemMessages.size <= 1,
                "createObjectIfPossible should consolidate to at most one system message, found ${systemMessages.size}"
            )

            // System message should be first
            if (systemMessages.isNotEmpty()) {
                assertTrue(
                    messages.first() is org.springframework.ai.chat.messages.SystemMessage,
                    "System message should be at the beginning"
                )
            }

            // No system messages after user messages
            val firstNonSystemIndex =
                messages.indexOfFirst { it !is org.springframework.ai.chat.messages.SystemMessage }
            if (firstNonSystemIndex >= 0) {
                val messagesAfterFirst = messages.drop(firstNonSystemIndex)
                val lateSystemMessages =
                    messagesAfterFirst.filterIsInstance<org.springframework.ai.chat.messages.SystemMessage>()
                assertTrue(
                    lateSystemMessages.isEmpty(),
                    "No system messages should appear after non-system messages"
                )
            }
        }

        @Test
        fun `prompt contributions and schema are merged into single system message`() {
            val duke = Dog("Duke")
            val fakeChatModel = FakeChatModel(jacksonObjectMapper().writeValueAsString(duke))

            val setup = createChatClientLlmOperations(fakeChatModel)
            setup.llmOperations.createObject(
                messages = listOf(
                    SystemMessage("You are a helpful assistant that creates dogs."),
                    UserMessage("Give me a dog named Duke"),
                ),
                interaction = LlmInteraction(
                    id = InteractionId("merged-system-test"),
                    llm = LlmOptions()
                ),
                outputClass = Dog::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = setup.mockAgentProcess,
            )

            val prompt = fakeChatModel.promptsPassed[0]
            val messages = prompt.instructions

            // Should have exactly one system message
            val systemMessages = messages.filterIsInstance<org.springframework.ai.chat.messages.SystemMessage>()

            // The single system message should contain the schema
            if (systemMessages.isNotEmpty()) {
                val systemContent = systemMessages.first().text
                assertTrue(
                    systemContent.contains("\$schema") || systemContent.contains("\"type\""),
                    "System message should contain schema format"
                )
            }

            // Verify proper ordering: system first, then user/assistant
            var foundNonSystem = false
            for (message in messages) {
                if (message !is org.springframework.ai.chat.messages.SystemMessage) {
                    foundNonSystem = true
                } else if (foundNonSystem) {
                    fail<Unit>("System message found after non-system message - violates message ordering")
                }
            }
        }
    }

    @Nested
    inner class ReturnValidation {

        @Test
        fun `validates with no rules`() {
            val duke = Dog("Duke")
            val fakeChatModel = FakeChatModel(jacksonObjectMapper().writeValueAsString(duke))
            val prompt =
                "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua."
            val setup = createChatClientLlmOperations(fakeChatModel)
            val createdDog = setup.llmOperations.createObject(
                messages = listOf(UserMessage(prompt)),
                interaction = LlmInteraction(
                    id = InteractionId("id"), llm = LlmOptions()
                ),
                outputClass = Dog::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = setup.mockAgentProcess,
            )

            assertEquals(duke, createdDog)
        }

        @Test
        fun `validated field with no violation`() {
            // Picky eater
            data class BorderCollie(
                val name: String,
                @field:Pattern(regexp = "^mince$", message = "eats field must be 'mince'")
                val eats: String,
            )

            // This is OK
            val husky = BorderCollie("Husky", eats = "mince")
            val fakeChatModel = FakeChatModel(jacksonObjectMapper().writeValueAsString(husky))
            val prompt =
                "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua."
            val setup = createChatClientLlmOperations(fakeChatModel)
            val createdDog = setup.llmOperations.createObject(
                messages = listOf(UserMessage(prompt)),
                interaction = LlmInteraction(
                    id = InteractionId("id"), llm = LlmOptions()
                ),
                outputClass = BorderCollie::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = setup.mockAgentProcess,
            )
            assertEquals(husky, createdDog)
        }

        @Test
        fun `corrects validated field with violation`() {
            // Picky eater
            data class BorderCollie(
                val name: String,
                @field:Pattern(regexp = "^mince$", message = "eats field must be 'mince'")
                val eats: String,
            )

            val invalidHusky = BorderCollie("Husky", eats = "kibble")
            val validHusky = BorderCollie("Husky", eats = "mince")
            val fakeChatModel = FakeChatModel(
                responses = listOf(
                    jacksonObjectMapper().writeValueAsString(invalidHusky),
                    jacksonObjectMapper().writeValueAsString(validHusky),
                )
            )
            val prompt =
                "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua."
            val setup = createChatClientLlmOperations(fakeChatModel)
            val createdDog = setup.llmOperations.createObject(
                messages = listOf(UserMessage(prompt)),
                interaction = LlmInteraction(
                    id = InteractionId("id"), llm = LlmOptions()
                ),
                outputClass = BorderCollie::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = setup.mockAgentProcess,
            )

            assertEquals(validHusky, createdDog, "Invalid response should have been corrected")
        }

        @Test
        fun `fails to correct validated field with violation`() {
            // Picky eater
            data class BorderCollie(
                val name: String,
                @field:Pattern(regexp = "^mince$", message = "eats field must be 'mince'")
                val eats: String,
            )

            val invalidHusky = BorderCollie("Husky", eats = "kibble")
            val fakeChatModel = FakeChatModel(
                response = jacksonObjectMapper().writeValueAsString(invalidHusky)
            )

            val prompt =
                "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua."
            val setup = createChatClientLlmOperations(fakeChatModel)
            try {
                setup.llmOperations.createObject(
                    messages = listOf(UserMessage(prompt)),
                    interaction = LlmInteraction(
                        id = InteractionId("id"), llm = LlmOptions()
                    ),
                    outputClass = BorderCollie::class.java,
                    action = SimpleTestAgent.actions.first(),
                    agentProcess = setup.mockAgentProcess,
                )
                fail("Should have thrown an exception on invalid object")
            } catch (e: InvalidLlmReturnTypeException) {
                assertEquals(invalidHusky, e.returnedObject, "Invalid response should have been corrected")
                assertTrue(e.constraintViolations.isNotEmpty())
            }
        }

        @Test
        fun `passes correct description of violation to LLM`() {
            // Picky eater
            data class BorderCollie(
                val name: String,
                @field:Pattern(regexp = "^mince$", message = "eats field must be 'mince'")
                val eats: String,
            )

            val invalidHusky = BorderCollie("Husky", eats = "kibble")
            val validHusky = BorderCollie("Husky", eats = "mince")
            val fakeChatModel = FakeChatModel(
                responses = listOf(
                    jacksonObjectMapper().writeValueAsString(invalidHusky),
                    jacksonObjectMapper().writeValueAsString(validHusky),
                )
            )
            val prompt =
                "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua."
            val setup = createChatClientLlmOperations(fakeChatModel)
            val createdDog = setup.llmOperations.createObject(
                messages = listOf(UserMessage(prompt)),
                interaction = LlmInteraction(
                    id = InteractionId("id"), llm = LlmOptions()
                ),
                outputClass = BorderCollie::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = setup.mockAgentProcess,
            )
            val secondPrompt = fakeChatModel.promptsPassed[1].toString()
            assertTrue(secondPrompt.contains("eats field must be 'mince'"), "Prompt mentions validation violation")

            assertEquals(validHusky, createdDog, "Invalid response should have been corrected")
        }

        @Test
        fun `does not pass description of validation rules to LLM if so configured`() {
            // Picky eater
            data class BorderCollie(
                val name: String,
                @field:Pattern(regexp = "^mince$", message = "eats field must be 'mince'")
                val eats: String,
            )

            val validHusky = BorderCollie("Husky", eats = "mince")
            val fakeChatModel = FakeChatModel(
                jacksonObjectMapper().writeValueAsString(validHusky)
            )
            val prompt =
                "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua."
            val setup = createChatClientLlmOperations(
                fakeChatModel,
                LlmDataBindingProperties(sendValidationInfo = false)
            )
            val createdDog = setup.llmOperations.createObject(
                messages = listOf(UserMessage(prompt)),
                interaction = LlmInteraction(
                    id = InteractionId("id"), llm = LlmOptions()
                ),
                outputClass = BorderCollie::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = setup.mockAgentProcess,
            )
            val firstPrompt = fakeChatModel.promptsPassed[0].toString()
            assertFalse(firstPrompt.contains("eats field must be 'mince'"), "Prompt mentions validation violation")
            assertEquals(validHusky, createdDog, "Invalid response should have been corrected")
        }

        @Test
        fun `passes correct description of validation rules to LLM if so configured`() {
            // Picky eater
            data class BorderCollie(
                val name: String,
                @field:Pattern(regexp = "^mince$", message = "eats field must be 'mince'")
                val eats: String,
            )

            val validHusky = BorderCollie("Husky", eats = "mince")
            val fakeChatModel = FakeChatModel(
                jacksonObjectMapper().writeValueAsString(validHusky)
            )
            val prompt =
                "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua."
            val setup = createChatClientLlmOperations(
                fakeChatModel = fakeChatModel,
                dataBindingProperties = LlmDataBindingProperties(
                    sendValidationInfo = true,
                )
            )
            val createdDog = setup.llmOperations.createObject(
                messages = listOf(UserMessage(prompt)),
                interaction = LlmInteraction(
                    id = InteractionId("id"), llm = LlmOptions()
                ),
                outputClass = BorderCollie::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = setup.mockAgentProcess,
            )
            val firstPrompt = fakeChatModel.promptsPassed[0].toString()
            assertTrue(firstPrompt.contains("eats field must be 'mince'"), "Prompt mentions validation violation")

            assertEquals(validHusky, createdDog, "Invalid response should have been corrected")
        }

        @Test
        fun `does not validate if interaction validation is set to false`() {
            // Picky eater
            data class BorderCollie(
                val name: String,
                @field:Pattern(regexp = "^mince$", message = "eats field must be 'mince'")
                val eats: String,
            )

            val invalidHusky = BorderCollie("Husky", eats = "kibble")
            val fakeChatModel = FakeChatModel(
                jacksonObjectMapper().writeValueAsString(invalidHusky)
            )
            val prompt =
                "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua."
            val setup = createChatClientLlmOperations(
                fakeChatModel = fakeChatModel,
                dataBindingProperties = LlmDataBindingProperties(
                    sendValidationInfo = true,
                )
            )
            val createdDog = setup.llmOperations.createObject(
                messages = listOf(UserMessage(prompt)),
                interaction = LlmInteraction(
                    id = InteractionId("id"), llm = LlmOptions(),
                    validation = false
                ),
                outputClass = BorderCollie::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = setup.mockAgentProcess,
            )
            assertEquals(invalidHusky, createdDog, "Invalid response should have been corrected")
        }
    }

}
