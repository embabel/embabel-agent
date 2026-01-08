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
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.core.support.safelyGetToolCallbacksFrom
import com.embabel.agent.spi.InvalidLlmReturnFormatException
import com.embabel.agent.spi.InvalidLlmReturnTypeException
import com.embabel.agent.spi.LlmInteraction
import com.embabel.agent.spi.LlmOperations
import com.embabel.agent.spi.support.springai.ChatClientLlmOperations
import com.embabel.agent.spi.support.springai.DefaultToolDecorator
import com.embabel.agent.spi.support.springai.MaybeReturn
import com.embabel.agent.spi.validation.DefaultValidationPromptGenerator
import com.embabel.agent.support.SimpleTestAgent
import com.embabel.agent.test.common.EventSavingAgenticEventListener
import com.embabel.chat.SystemMessage
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.*
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

        val mockModelProvider = mockk<ModelProvider>()
        val crit = slot<ModelSelectionCriteria>()
        val fakeLlm = Llm("fake", "provider", fakeChatModel, DefaultOptionsConverter)
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
            val toolCallbacks = safelyGetToolCallbacksFrom(ToolObject(Wumpus("wumpy")))
            val setup = createChatClientLlmOperations(fakeChatModel)
            val result = setup.llmOperations.doTransform(
                messages = listOf(
                    SystemMessage("do whatever"),
                    UserMessage("prompt"),
                ),
                interaction = LlmInteraction(
                    id = InteractionId("id"),
                    llm = LlmOptions(),
                    toolCallbacks = toolCallbacks,
                ),
                outputClass = Dog::class.java,
                llmRequestEvent = null,
            )
            assertEquals(duke, result)
            assertEquals(1, fakeChatModel.promptsPassed.size)
            val tools = fakeChatModel.optionsPassed[0].toolCallbacks
            assertEquals(toolCallbacks.size, tools.size, "Must have passed same number of tools")
            assertEquals(
                toolCallbacks.map { it.toolDefinition.name() }.toSet(),
                tools.map { it.toolDefinition.name() }.toSet(),
            )
        }

        @Test
        fun `presents tools to ChatModel when given multiple messages`() {
            val duke = Dog("Duke")

            val fakeChatModel = FakeChatModel(jacksonObjectMapper().writeValueAsString(duke))

            // Wumpus's have tools
            val toolCallbacks = safelyGetToolCallbacksFrom(ToolObject(Wumpus("wumpy")))
            val setup = createChatClientLlmOperations(fakeChatModel)
            val result = setup.llmOperations.createObject(
                messages = listOf(UserMessage("prompt")),
                interaction = LlmInteraction(
                    id = InteractionId("id"),
                    llm = LlmOptions(),
                    toolCallbacks = toolCallbacks,
                ),
                outputClass = Dog::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = setup.mockAgentProcess,
            )
            assertEquals(duke, result)
            assertEquals(1, fakeChatModel.promptsPassed.size)
            val tools = fakeChatModel.optionsPassed[0].toolCallbacks
            assertEquals(toolCallbacks.size, tools.size, "Must have passed same number of tools")
            assertEquals(
                toolCallbacks.map { it.toolDefinition.name() }.sorted(),
                tools.map { it.toolDefinition.name() })
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

            // Wumpus's have tools
            val toolCallbacks = safelyGetToolCallbacksFrom(ToolObject(Wumpus("wumpy")))
            val setup = createChatClientLlmOperations(fakeChatModel)
            setup.llmOperations.createObjectIfPossible(
                messages = listOf(UserMessage("prompt")),
                interaction = LlmInteraction(
                    id = InteractionId("id"),
                    llm = LlmOptions(),
                    toolCallbacks = toolCallbacks,
                ),
                outputClass = Dog::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = setup.mockAgentProcess,
            )
            assertEquals(1, fakeChatModel.promptsPassed.size)
            val tools = fakeChatModel.optionsPassed[0].toolCallbacks
            assertEquals(toolCallbacks.size, tools.size, "Must have passed same number of tools")
            assertEquals(
                toolCallbacks.map { it.toolDefinition.name() }.sorted(),
                tools.map { it.toolDefinition.name() })
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
