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
package com.embabel.agent.spi.support.springai.streaming

import com.embabel.agent.api.common.InteractionId
import com.embabel.agent.api.common.PromptRunnerOptions
import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.api.validation.guardrails.GuardRailConfiguration
import com.embabel.agent.api.validation.guardrails.GuardRailViolationException
import com.embabel.agent.api.validation.guardrails.UserInputGuardRail
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.Blackboard
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.core.LlmInvocation
import com.embabel.agent.core.LlmInvocationHistory
import com.embabel.agent.spi.support.LlmDataBindingProperties
import com.embabel.agent.spi.support.RegistryToolGroupResolver
import com.embabel.agent.spi.support.springai.ChatClientLlmOperations
import com.embabel.agent.spi.support.springai.DefaultToolDecorator
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.agent.spi.validation.DefaultValidationPromptGenerator
import com.embabel.agent.support.SimpleTestAgent
import com.embabel.agent.test.common.EventSavingAgenticEventListener
import com.embabel.agent.api.common.ToolsStats
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
import reactor.core.publisher.Flux

/**
 * Fake ChatModel for streaming guardrail testing
 */
class StreamingGuardRailTestFakeChatModel(
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

    override fun stream(prompt: Prompt): Flux<ChatResponse> {
        promptsPassed.add(prompt)
        val options = prompt.options as? ToolCallingChatOptions
            ?: throw IllegalArgumentException("Expected ToolCallingChatOptions")
        optionsPassed.add(options)

        // Create streaming chunks from response
        val response = responses[index]
        if (responses.size > 1) ++index

        // Split response into chunks for streaming simulation
        val chunks = response.chunked(10) // Create 10-character chunks
        return Flux.fromIterable(chunks.map { chunk ->
            ChatResponse(listOf(Generation(SpringAssistantMessage(chunk))))
        })
    }
}

/**
 * Tests for guardrail validation in StreamingChatClientOperations
 */
class StreamingChatClientOperationsGuardRailTest {

    internal data class Setup(
        val streamingOperations: StreamingChatClientOperations,
        val mockAgentProcess: AgentProcess,
        val mutableLlmInvocationHistory: StreamingGuardRailTestMutableLlmInvocationHistory,
    )

    private fun createStreamingChatClientOperations(
        fakeChatModel: StreamingGuardRailTestFakeChatModel,
        dataBindingProperties: LlmDataBindingProperties = LlmDataBindingProperties(),
    ): Setup {
        val ese = EventSavingAgenticEventListener()
        val mutableLlmInvocationHistory = StreamingGuardRailTestMutableLlmInvocationHistory()
        val mockProcessContext = mockk<ProcessContext>()
        every { mockProcessContext.platformServices } returns mockk()
        every { mockProcessContext.platformServices.agentPlatform } returns mockk()
        every { mockProcessContext.platformServices.agentPlatform.toolGroupResolver } returns RegistryToolGroupResolver(
            "mt",
            emptyList()
        )
        every { mockProcessContext.platformServices.eventListener } returns ese
        val mockAgentProcess = mockk<AgentProcess>()
        every { mockAgentProcess.recordLlmInvocation(any<LlmInvocation>()) } answers {
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
        )
        val streamingOperations = StreamingChatClientOperations(cco)
        return Setup(streamingOperations, mockAgentProcess, mutableLlmInvocationHistory)
    }

    @Test
    fun `should validate user input with configured guardrails in doTransformStream`() {
        val inputValidationCalled = mutableListOf<String>()
        val userInputGuard = object : UserInputGuardRail {
            override val name = "TestUserInputGuard"
            override val description = "Test user input validation for streaming"
            override fun validate(input: String, blackboard: Blackboard): ValidationResult {
                inputValidationCalled.add(input)
                return ValidationResult.VALID
            }
        }

        val setup = createStreamingChatClientOperations(StreamingGuardRailTestFakeChatModel("Streaming response"))

        val guardrailConfig = GuardRailConfiguration(guards = listOf(userInputGuard))
        val options = PromptRunnerOptions(guardRailConfig = guardrailConfig)
        val interaction = LlmInteraction(
            id = InteractionId("test-streaming-interaction"),
            llm = LlmOptions(),
            tools = emptyList(),
            promptContributors = emptyList(),
            options = options
        )

        val llmRequestEvent = mockk<LlmRequestEvent<String>>(relaxed = true)
        every { llmRequestEvent.agentProcess } returns setup.mockAgentProcess

        // Note: Using collectList() to test that streaming completes without guardrail violations
        val result = setup.streamingOperations.doTransformStream(
            messages = listOf(UserMessage("Test streaming input message")),
            interaction = interaction,
            llmRequestEvent = llmRequestEvent
        ).collectList().block()

        // Verify guardrail validation was called
        assertEquals(1, inputValidationCalled.size)
        assertTrue(inputValidationCalled[0].contains("Test streaming input message"))
        assertNotNull(result) // Stream should complete successfully
    }

    @Test
    fun `should throw exception when user input guardrail returns critical violation in doTransformStream`() {
        val criticalUserGuard = object : UserInputGuardRail {
            override val name = "CriticalStreamingUserGuard"
            override val description = "Critical user validation for streaming"
            override fun validate(input: String, blackboard: Blackboard): ValidationResult {
                return ValidationResult(
                    isValid = false,
                    errors = listOf(
                        ValidationError(
                            code = "CRITICAL_STREAMING_VIOLATION",
                            message = "Critical streaming violation detected",
                            severity = ValidationSeverity.CRITICAL,
                            location = ValidationLocation(
                                type = "GuardRail",
                                name = "CriticalStreamingUserGuard",
                                agentName = "test-agent",
                                component = "StreamingChatClientOperations"
                            )
                        )
                    )
                )
            }
        }

        val setup = createStreamingChatClientOperations(StreamingGuardRailTestFakeChatModel("Should not reach this"))

        val guardrailConfig = GuardRailConfiguration(guards = listOf(criticalUserGuard))
        val options = PromptRunnerOptions(guardRailConfig = guardrailConfig)
        val interaction = LlmInteraction(
            id = InteractionId("test-streaming-critical"),
            llm = LlmOptions(),
            tools = emptyList(),
            promptContributors = emptyList(),
            options = options
        )

        val llmRequestEvent = mockk<LlmRequestEvent<String>>(relaxed = true)
        every { llmRequestEvent.agentProcess } returns setup.mockAgentProcess

        val exception = assertThrows(GuardRailViolationException::class.java) {
            setup.streamingOperations.doTransformStream(
                messages = listOf(UserMessage("Violating streaming input")),
                interaction = interaction,
                llmRequestEvent = llmRequestEvent
            ).blockFirst() // Try to get first element, should throw before streaming starts
        }

        assertEquals("CriticalStreamingUserGuard", exception.guard)
        assertEquals("Critical streaming violation detected", exception.violation)
        assertEquals(ValidationSeverity.CRITICAL, exception.severity)
    }

}

class StreamingGuardRailTestMutableLlmInvocationHistory : LlmInvocationHistory {
    val invocations = mutableListOf<LlmInvocation>()
    override val llmInvocations: List<LlmInvocation>
        get() = invocations

    override val toolsStats: ToolsStats
        get() = TODO("Not yet implemented")
}
