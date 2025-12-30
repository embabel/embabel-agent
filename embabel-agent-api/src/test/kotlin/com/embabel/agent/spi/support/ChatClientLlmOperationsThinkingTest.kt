/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.spi.LlmInteraction
import com.embabel.agent.spi.support.springai.ChatClientLlmOperations
import com.embabel.agent.spi.support.springai.DefaultToolDecorator
import com.embabel.agent.spi.validation.DefaultValidationPromptGenerator
import com.embabel.agent.support.SimpleTestAgent
import com.embabel.agent.test.common.EventSavingAgenticEventListener
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.DefaultOptionsConverter
import com.embabel.common.ai.model.Llm
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.ai.model.ModelSelectionCriteria
import com.embabel.common.textio.template.JinjavaTemplateRenderer
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import jakarta.validation.Validation
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for thinking functionality in ChatClientLlmOperations.
 *
 * Focuses on the new thinking-aware methods:
 * - doTransformWithThinking() for comprehensive thinking extraction
 * - doTransformWithThinkingIfPossible() for safe thinking extraction with MaybeReturn
 * - Integration with SuppressThinkingConverter and existing LlmOperations infrastructure
 *
 * NOTE: For comprehensive business scenario testing,
 * see [[com.embabel.agent.api.common.thinking.ThinkingPromptRunnerOperationsExtractionTest]].
 */
class ChatClientLlmOperationsThinkingTest {

    private data class Setup(
        val llmOperations: ChatClientLlmOperations,
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

    // Test data class
    data class SimpleResult(
        val status: String,
        val value: Int,
    )

    @Test
    fun `doTransform should strip thinking blocks and convert object`() {
        // Given: LlmOperations with response containing thinking blocks
        val rawLlmResponse = """
            <think>
            This is a test thinking block.
            </think>

            {
                "status": "success",
                "value": 42
            }
        """.trimIndent()

        val fakeChatModel = FakeChatModel(rawLlmResponse)
        val setup = createChatClientLlmOperations(fakeChatModel)

        // When: Call doTransform (public API)
        val result = setup.llmOperations.doTransform(
            messages = listOf(UserMessage("Test request")),
            interaction = LlmInteraction(InteractionId("test-id")),
            outputClass = SimpleResult::class.java,
            llmRequestEvent = null
        )

        // Then: Should return converted object (thinking blocks are stripped)
        assertNotNull(result)

        // Verify object conversion - thinking blocks are cleaned out
        assertEquals("success", result.status)
        assertEquals(42, result.value)
    }

    @Test
    fun `createObjectIfPossible should handle JSON with thinking blocks`() {
        // Given: LlmOperations with response containing thinking blocks and MaybeReturn success
        val result = SimpleResult("completed", 123)
        val rawLlmResponse = """
            <think>
            Let me analyze this request carefully.
            The user wants a successful result.
            </think>

            {
                "success": {
                    "status": "completed",
                    "value": 123
                }
            }
        """.trimIndent()

        val fakeChatModel = FakeChatModel(rawLlmResponse)
        val setup = createChatClientLlmOperations(fakeChatModel)

        // When: Call createObjectIfPossible (public API)
        val resultWrapper = setup.llmOperations.createObjectIfPossible(
            messages = listOf(UserMessage("Test request")),
            interaction = LlmInteraction(InteractionId("test-id")),
            outputClass = SimpleResult::class.java,
            agentProcess = setup.mockAgentProcess,
            action = SimpleTestAgent.actions.first()
        )

        // Then: Should return successful Result with object (thinking blocks cleaned)
        assertTrue(resultWrapper.isSuccess)
        val actualResult = resultWrapper.getOrThrow()

        assertEquals("completed", actualResult.status)
        assertEquals(123, actualResult.value)
    }

    @Test
    fun `createObjectIfPossible should return failure when LLM cannot create object but has thinking blocks`() {
        // Given: LLM response with thinking blocks but explicit failure in MaybeReturn
        val rawLlmResponse = """
            <think>
            I need to analyze this request carefully.
            The user wants pricing information but the text doesn't contain any prices.
            I cannot extract pricing data from this content.
            </think>

            {
                "success": null,
                "failure": "No pricing information found in the provided text"
            }
        """.trimIndent()

        val fakeChatModel = FakeChatModel(rawLlmResponse)
        val setup = createChatClientLlmOperations(fakeChatModel)

        // When: Call createObjectIfPossible
        val resultWrapper = setup.llmOperations.createObjectIfPossible(
            messages = listOf(UserMessage("Extract pricing from: 'The weather is nice today.'")),
            interaction = LlmInteraction(InteractionId("test-id")),
            outputClass = SimpleResult::class.java,
            agentProcess = setup.mockAgentProcess,
            action = SimpleTestAgent.actions.first()
        )

        // Then: Should return failure Result<SimpleResult> (LLM correctly determined task is not possible)
        assertTrue("Method should return Result<> type") { resultWrapper is Result<SimpleResult> }
        assertTrue("Result should be failure when LLM cannot create object") { resultWrapper.isFailure }

        // Verify the failure message contains the LLM's reasoning
        val exception = resultWrapper.exceptionOrNull()
        assertNotNull(exception, "Failure Result should contain exception")
        assertTrue("Should contain LLM's failure reason: ${exception?.message}") {
            exception?.message?.contains("No pricing information found") == true
        }
    }

    @Test
    fun `should throw exception for malformed JSON with thinking blocks`() {
        // Given: LlmOperations with malformed JSON after thinking blocks
        val rawLlmResponse = """
            <think>
            This will cause parsing issues.
            </think>

            { this is completely malformed JSON
        """.trimIndent()

        val fakeChatModel = FakeChatModel(rawLlmResponse)
        val setup = createChatClientLlmOperations(fakeChatModel)

        // When/Then: Should throw exception for malformed JSON
        try {
            setup.llmOperations.doTransform(
                messages = listOf(UserMessage("Test request")),
                interaction = LlmInteraction(InteractionId("test-id")),
                outputClass = SimpleResult::class.java,
                llmRequestEvent = null
            )
        } catch (e: Exception) {
            // Expected - malformed JSON should cause parsing exception
            assertTrue("Exception should be related to parsing: ${e.message}") {
                val message = e.message ?: ""
                message.contains("parsing", ignoreCase = true) ||
                        message.contains("format", ignoreCase = true) ||
                        message.contains("JsonParseException", ignoreCase = true)
            }
        }
    }
}
