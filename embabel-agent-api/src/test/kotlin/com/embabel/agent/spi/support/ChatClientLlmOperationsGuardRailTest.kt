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
import com.embabel.agent.api.common.ToolsStats
import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.api.tool.ToolObject
import com.embabel.agent.api.validation.guardrails.AssistantMessageGuardRail
import com.embabel.agent.api.validation.guardrails.GuardRailViolationException
import com.embabel.agent.api.validation.guardrails.UserInputGuardRail
import com.embabel.agent.core.*
import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.core.support.safelyGetToolsFrom
import com.embabel.agent.spi.support.springai.ChatClientLlmOperations
import com.embabel.agent.spi.support.springai.MaybeReturn
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.agent.spi.validation.DefaultValidationPromptGenerator
import com.embabel.agent.support.SimpleTestAgent
import com.embabel.agent.test.common.EventSavingAgenticEventListener
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.DefaultOptionsConverter
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.ai.model.ModelSelectionCriteria
import com.embabel.common.core.validation.ValidationError
import com.embabel.common.core.validation.ValidationLocation
import com.embabel.common.core.validation.ValidationResult
import com.embabel.common.core.validation.ValidationSeverity
import com.embabel.common.textio.template.JinjavaTemplateRenderer
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import jakarta.validation.Validation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.DefaultChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.chat.messages.AssistantMessage as SpringAssistantMessage

/**
 * Fake ChatModel for guardrail testing
 */
class GuardRailTestFakeChatModel(
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
                Generation(SpringAssistantMessage(responses[index])).also {
                    // If we have more than one response, step through them
                    if (responses.size > 1) ++index
                }
            )
        )
    }
}

data class Dog(val name: String)

/**
 * Tests for guardrail validation in ChatClientLlmOperations
 */
class ChatClientLlmOperationsGuardRailTest {

    data class Setup(
        val llmOperations: LlmOperations,
        val mockAgentProcess: AgentProcess,
        val mutableLlmInvocationHistory: GuardRailTestMutableLlmInvocationHistory,
    )

    private fun createChatClientLlmOperations(
        fakeChatModel: GuardRailTestFakeChatModel,
        dataBindingProperties: LlmDataBindingProperties = LlmDataBindingProperties(),
    ): Setup {
        val ese = EventSavingAgenticEventListener()
        val mutableLlmInvocationHistory = GuardRailTestMutableLlmInvocationHistory()
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

        // Add blackboard for guardrail testing
        val blackboard = mockk<Blackboard>(relaxed = true)
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
            asyncer = ExecutorAsyncer(java.util.concurrent.Executors.newCachedThreadPool()),
        )
        return Setup(cco, mockAgentProcess, mutableLlmInvocationHistory)
    }

    @Test
    fun `should validate user input with configured guardrails in doTransformWithSpringAi`() {
        val inputValidationCalled = mutableListOf<String>()
        val userInputGuard = object : UserInputGuardRail {
            override val name = "TestUserInputGuard"
            override val description = "Test user input validation"
            override fun validate(input: String, blackboard: Blackboard): ValidationResult {
                inputValidationCalled.add(input)
                return ValidationResult.VALID
            }
        }

        val setup = createChatClientLlmOperations(GuardRailTestFakeChatModel("Test response"))

        val interaction = LlmInteraction(
            id = InteractionId("test-interaction"),
            llm = LlmOptions(),
            tools = emptyList(),
            promptContributors = emptyList(),
            guardRails = listOf(userInputGuard),
            useEmbabelToolLoop = false
        )

        val llmRequestEvent = mockk<LlmRequestEvent<String>>(relaxed = true)
        every { llmRequestEvent.agentProcess } returns setup.mockAgentProcess

        val result = setup.llmOperations.doTransform(
            messages = listOf(UserMessage("Test input message")),
            interaction = interaction,
            outputClass = String::class.java,
            llmRequestEvent = llmRequestEvent
        )

        assertEquals("Test response", result)
        assertEquals(1, inputValidationCalled.size)
        assertTrue(inputValidationCalled[0].contains("Test input message"))
    }

    @Test
    fun `should validate assistant response with configured guardrails in doTransformWithSpringAi`() {
        val responseValidationCalled = mutableListOf<String>()
        val assistantGuard = object : AssistantMessageGuardRail {
            override val name = "TestAssistantGuard"
            override val description = "Test assistant response validation"
            override fun validate(input: String, blackboard: Blackboard): ValidationResult {
                responseValidationCalled.add(input)
                return ValidationResult.VALID
            }

            override fun validate(
                response: com.embabel.common.core.thinking.ThinkingResponse<*>,
                blackboard: Blackboard,
            ): ValidationResult {
                return ValidationResult.VALID
            }
        }

        val setup = createChatClientLlmOperations(GuardRailTestFakeChatModel("Assistant test response"))

        val llmRequestEvent = mockk<LlmRequestEvent<String>>(relaxed = true)
        every { llmRequestEvent.agentProcess } returns setup.mockAgentProcess

        val result = setup.llmOperations.doTransform(
            messages = listOf(UserMessage("Test input")),
            interaction = LlmInteraction(
                id = InteractionId("test-interaction"),
                llm = LlmOptions(),
                tools = emptyList(),
                promptContributors = emptyList(),
                guardRails = listOf(assistantGuard),
                useEmbabelToolLoop = false
            ),
            outputClass = String::class.java,
            llmRequestEvent = llmRequestEvent
        )

        assertEquals("Assistant test response", result)
        assertEquals(1, responseValidationCalled.size)
        assertEquals("Assistant test response", responseValidationCalled[0])
    }

    @Test
    fun `should throw exception when user input guardrail returns critical violation`() {
        val criticalUserGuard = object : UserInputGuardRail {
            override val name = "CriticalUserGuard"
            override val description = "Critical user validation"
            override fun validate(input: String, blackboard: Blackboard): ValidationResult {
                return ValidationResult(
                    isValid = false,
                    errors = listOf(
                        ValidationError(
                            code = "CRITICAL_USER_VIOLATION",
                            message = "Critical violation detected",
                            severity = ValidationSeverity.CRITICAL,
                            location = ValidationLocation(
                                type = "GuardRail",
                                name = "CriticalUserGuard",
                                agentName = "test-agent",
                                component = "ChatClientLlmOperations"
                            )
                        )
                    )
                )
            }
        }

        val setup = createChatClientLlmOperations(GuardRailTestFakeChatModel("Should not reach this"))

        val interaction = LlmInteraction(
            id = InteractionId("test-interaction"),
            llm = LlmOptions(),
            tools = emptyList(),
            promptContributors = emptyList(),
            guardRails = listOf(criticalUserGuard),
            useEmbabelToolLoop = false
        )

        val llmRequestEvent = mockk<LlmRequestEvent<String>>(relaxed = true)
        every { llmRequestEvent.agentProcess } returns setup.mockAgentProcess

        val exception = assertThrows(GuardRailViolationException::class.java) {
            setup.llmOperations.doTransform(
                messages = listOf(UserMessage("Violating input")),
                interaction = interaction,
                outputClass = String::class.java,
                llmRequestEvent = llmRequestEvent
            )
        }

        assertEquals("CriticalUserGuard", exception.guard)
        assertEquals("Critical violation detected", exception.violation)
        assertEquals(ValidationSeverity.CRITICAL, exception.severity)
    }

    @Test
    fun `should throw exception when assistant response guardrail returns critical violation`() {
        val criticalAssistantGuard = object : AssistantMessageGuardRail {
            override val name = "CriticalAssistantGuard"
            override val description = "Critical assistant validation"
            override fun validate(input: String, blackboard: Blackboard): ValidationResult {
                return ValidationResult(
                    isValid = false,
                    errors = listOf(
                        ValidationError(
                            code = "CRITICAL_ASSISTANT_VIOLATION",
                            message = "Critical response violation",
                            severity = ValidationSeverity.CRITICAL,
                            location = ValidationLocation(
                                type = "GuardRail",
                                name = "CriticalAssistantGuard",
                                agentName = "test-agent",
                                component = "ChatClientLlmOperations"
                            )
                        )
                    )
                )
            }

            override fun validate(
                response: com.embabel.common.core.thinking.ThinkingResponse<*>,
                blackboard: Blackboard,
            ): ValidationResult {
                return ValidationResult.VALID
            }
        }

        val setup = createChatClientLlmOperations(GuardRailTestFakeChatModel("Violating response"))

        val interaction = LlmInteraction(
            id = InteractionId("test-interaction"),
            llm = LlmOptions(),
            tools = emptyList(),
            promptContributors = emptyList(),
            guardRails = listOf(criticalAssistantGuard),
            useEmbabelToolLoop = false
        )

        val llmRequestEvent = mockk<LlmRequestEvent<String>>(relaxed = true)
        every { llmRequestEvent.agentProcess } returns setup.mockAgentProcess

        val exception = assertThrows(GuardRailViolationException::class.java) {
            setup.llmOperations.doTransform(
                messages = listOf(UserMessage("Input")),
                interaction = interaction,
                outputClass = String::class.java,
                llmRequestEvent = llmRequestEvent
            )
        }

        assertEquals("CriticalAssistantGuard", exception.guard)
        assertEquals("Critical response violation", exception.violation)
        assertEquals(ValidationSeverity.CRITICAL, exception.severity)
    }

    @Test
    fun `should validate both user input and assistant response in createObjectIfPossible`() {
        val inputValidationCalled = mutableListOf<String>()
        val responseValidationCalled = mutableListOf<String>()

        val userInputGuard = object : UserInputGuardRail {
            override val name = "TestUserInputGuard"
            override val description = "Test user input validation"
            override fun validate(input: String, blackboard: Blackboard): ValidationResult {
                inputValidationCalled.add(input)
                return ValidationResult.VALID
            }
        }

        val assistantGuard = object : AssistantMessageGuardRail {
            override val name = "TestAssistantGuard"
            override val description = "Test assistant response validation"
            override fun validate(input: String, blackboard: Blackboard): ValidationResult {
                responseValidationCalled.add(input)
                return ValidationResult.VALID
            }

            override fun validate(
                response: com.embabel.common.core.thinking.ThinkingResponse<*>,
                blackboard: Blackboard,
            ): ValidationResult {
                return ValidationResult.VALID
            }
        }

        val testResponse = jacksonObjectMapper().writeValueAsString(MaybeReturn(success = Dog("Test Dog")))
        val setup = createChatClientLlmOperations(GuardRailTestFakeChatModel(testResponse))

        val interaction = LlmInteraction(
            id = InteractionId("test-interaction"),
            llm = LlmOptions(),
            tools = emptyList(),
            promptContributors = emptyList(),
            guardRails = listOf(userInputGuard, assistantGuard),
            useEmbabelToolLoop = false
        )

        val result = setup.llmOperations.createObjectIfPossible(
            messages = listOf(UserMessage("Test input for createObjectIfPossible")),
            interaction = interaction,
            outputClass = Dog::class.java,
            action = null,
            agentProcess = setup.mockAgentProcess
        )

        assertTrue(result.isSuccess)
        assertEquals(Dog("Test Dog"), result.getOrThrow())
        assertEquals(1, inputValidationCalled.size)
        assertEquals(1, responseValidationCalled.size)
        assertTrue(inputValidationCalled[0].contains("Test input for createObjectIfPossible"))
        assertEquals(testResponse, responseValidationCalled[0])
    }

    @Test
    fun `should validate both user input and assistant response in doTransformWithEmbabelToolLoop`() {
        val inputValidationCalled = mutableListOf<String>()
        val responseValidationCalled = mutableListOf<String>()

        val userInputGuard = object : UserInputGuardRail {
            override val name = "TestUserInputGuard"
            override val description = "Test user input validation"
            override fun validate(input: String, blackboard: Blackboard): ValidationResult {
                inputValidationCalled.add(input)
                return ValidationResult.VALID
            }
        }

        val assistantGuard = object : AssistantMessageGuardRail {
            override val name = "TestAssistantGuard"
            override val description = "Test assistant response validation"
            override fun validate(input: String, blackboard: Blackboard): ValidationResult {
                responseValidationCalled.add(input)
                return ValidationResult.VALID
            }

            override fun validate(
                response: com.embabel.common.core.thinking.ThinkingResponse<*>,
                blackboard: Blackboard,
            ): ValidationResult {
                return ValidationResult.VALID
            }
        }

        val setup = createChatClientLlmOperations(GuardRailTestFakeChatModel("Tool loop response"))

        val tools = safelyGetToolsFrom(ToolObject(Wumpus("test-wumpus")))
        val interaction = LlmInteraction(
            id = InteractionId("test-interaction"),
            llm = LlmOptions(),
            tools = tools,
            promptContributors = emptyList(),
            guardRails = listOf(userInputGuard, assistantGuard),
            useEmbabelToolLoop = true
        )

        val llmRequestEvent = mockk<LlmRequestEvent<String>>(relaxed = true)
        every { llmRequestEvent.agentProcess } returns setup.mockAgentProcess

        val result = setup.llmOperations.doTransform(
            messages = listOf(UserMessage("Test input for tool loop")),
            interaction = interaction,
            outputClass = String::class.java,
            llmRequestEvent = llmRequestEvent
        )

        assertEquals("Tool loop response", result)
        assertEquals(1, inputValidationCalled.size)
        assertEquals(1, responseValidationCalled.size)
        assertTrue(inputValidationCalled[0].contains("Test input for tool loop"))
        assertEquals("Tool loop response", responseValidationCalled[0])
    }

    @Test
    fun `should use combineMessages when validating multiple user messages`() {
        val combinedInputReceived = mutableListOf<String>()
        val userInputGuard = object : UserInputGuardRail {
            override val name = "CombineTestGuard"
            override val description = "Tests combineMessages flow"
            override fun validate(input: String, blackboard: Blackboard): ValidationResult {
                combinedInputReceived.add(input)
                return ValidationResult.VALID
            }
        }

        val setup = createChatClientLlmOperations(GuardRailTestFakeChatModel("Test response"))

        val interaction = LlmInteraction(
            id = InteractionId("test-combine-messages"),
            llm = LlmOptions(),
            tools = emptyList(),
            promptContributors = emptyList(),
            guardRails = listOf(userInputGuard),
            useEmbabelToolLoop = false
        )

        val llmRequestEvent = mockk<LlmRequestEvent<String>>(relaxed = true)
        every { llmRequestEvent.agentProcess } returns setup.mockAgentProcess

        setup.llmOperations.doTransform(
            messages = listOf(
                UserMessage("First message"),
                UserMessage("Second message"),
                UserMessage("Third message")
            ),
            interaction = interaction,
            outputClass = String::class.java,
            llmRequestEvent = llmRequestEvent
        )

        assertEquals(1, combinedInputReceived.size)
        // Default combineMessages joins with newline
        assertEquals("First message\nSecond message\nThird message", combinedInputReceived[0])
    }

    @Test
    fun `should use custom combineMessages implementation when provided`() {
        val combinedInputReceived = mutableListOf<String>()
        val customCombineGuard = object : UserInputGuardRail {
            override val name = "CustomCombineGuard"
            override val description = "Tests custom combineMessages"

            override fun combineMessages(userMessages: List<UserMessage>): String {
                // Custom separator and transformation
                return userMessages.joinToString(" | ") { "[${it.content}]" }
            }

            override fun validate(input: String, blackboard: Blackboard): ValidationResult {
                combinedInputReceived.add(input)
                return ValidationResult.VALID
            }
        }

        val setup = createChatClientLlmOperations(GuardRailTestFakeChatModel("Test response"))

        val interaction = LlmInteraction(
            id = InteractionId("test-custom-combine"),
            llm = LlmOptions(),
            tools = emptyList(),
            promptContributors = emptyList(),
            guardRails = listOf(customCombineGuard),
            useEmbabelToolLoop = false
        )

        val llmRequestEvent = mockk<LlmRequestEvent<String>>(relaxed = true)
        every { llmRequestEvent.agentProcess } returns setup.mockAgentProcess

        setup.llmOperations.doTransform(
            messages = listOf(
                UserMessage("msg1"),
                UserMessage("msg2")
            ),
            interaction = interaction,
            outputClass = String::class.java,
            llmRequestEvent = llmRequestEvent
        )

        assertEquals(1, combinedInputReceived.size)
        assertEquals("[msg1] | [msg2]", combinedInputReceived[0])
    }

    @Test
    fun `should not throw exception for non-critical validation violations`() {
        val validationsCalled = mutableListOf<String>()

        // INFO level guardrail - should log but not throw
        val infoGuard = object : UserInputGuardRail {
            override val name = "InfoGuard"
            override val description = "Info level validation"
            override fun validate(input: String, blackboard: Blackboard): ValidationResult {
                validationsCalled.add("INFO: $input")
                return ValidationResult(
                    isValid = false,
                    errors = listOf(
                        ValidationError(
                            code = "INFO_VIOLATION",
                            message = "Info level issue detected",
                            severity = ValidationSeverity.INFO
                        )
                    )
                )
            }
        }

        // WARNING level guardrail - should warn but not throw
        val warningGuard = object : UserInputGuardRail {
            override val name = "WarningGuard"
            override val description = "Warning level validation"
            override fun validate(input: String, blackboard: Blackboard): ValidationResult {
                validationsCalled.add("WARNING: $input")
                return ValidationResult(
                    isValid = false,
                    errors = listOf(
                        ValidationError(
                            code = "WARNING_VIOLATION",
                            message = "Warning level issue detected",
                            severity = ValidationSeverity.WARNING
                        )
                    )
                )
            }
        }

        // ERROR level guardrail - should error but not throw
        val errorGuard = object : UserInputGuardRail {
            override val name = "ErrorGuard"
            override val description = "Error level validation"
            override fun validate(input: String, blackboard: Blackboard): ValidationResult {
                validationsCalled.add("ERROR: $input")
                return ValidationResult(
                    isValid = false,
                    errors = listOf(
                        ValidationError(
                            code = "ERROR_VIOLATION",
                            message = "Error level issue detected",
                            severity = ValidationSeverity.ERROR
                        )
                    )
                )
            }
        }

        val setup = createChatClientLlmOperations(GuardRailTestFakeChatModel("Success response"))
        val interaction = LlmInteraction(
            id = InteractionId("test-non-critical"),
            llm = LlmOptions(),
            tools = emptyList(),
            promptContributors = emptyList(),
            guardRails = listOf(infoGuard, warningGuard, errorGuard),
            useEmbabelToolLoop = false
        )

        val llmRequestEvent = mockk<LlmRequestEvent<String>>(relaxed = true)
        every { llmRequestEvent.agentProcess } returns setup.mockAgentProcess

        // Should NOT throw exception despite validation failures
        val result = setup.llmOperations.doTransform(
            messages = listOf(UserMessage("Test non-critical violations")),
            interaction = interaction,
            outputClass = String::class.java,
            llmRequestEvent = llmRequestEvent
        )

        // Verify operation completed successfully
        assertEquals("Success response", result)

        // Verify all guardrails were called
        assertEquals(3, validationsCalled.size)
        assertTrue(validationsCalled.any { it.contains("INFO:") })
        assertTrue(validationsCalled.any { it.contains("WARNING:") })
        assertTrue(validationsCalled.any { it.contains("ERROR:") })
    }


}

class GuardRailTestMutableLlmInvocationHistory : LlmInvocationHistory {
    val invocations = mutableListOf<LlmInvocation>()
    override val llmInvocations: List<LlmInvocation>
        get() = invocations

    override val toolsStats: ToolsStats
        get() = TODO("Not yet implemented")
}
