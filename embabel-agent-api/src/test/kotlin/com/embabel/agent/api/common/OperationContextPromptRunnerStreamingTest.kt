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
package com.embabel.agent.api.common

import com.embabel.agent.api.common.streaming.StreamingPromptRunner
import com.embabel.agent.api.common.streaming.StreamingPromptRunnerOperations
import com.embabel.agent.api.common.support.OperationContextPromptRunner
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.Operation
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.LlmOperations
import com.embabel.agent.spi.streaming.StreamingLlmOperations
import com.embabel.agent.spi.support.springai.ChatClientLlmOperations
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.agent.test.integration.DummyObjectCreatingLlmOperations
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.model.StreamingChatModel
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for OperationContextPromptRunner streaming functionality.
 *
 * Tests the new streaming capability architecture with explicit failure policy:
 * - Three-level capability detection in supportsStreaming()
 * - Explicit UnsupportedOperationException on stream() when not supported
 * - Clean streaming operations creation when supported
 * - StreamingCapability tag interface polymorphic access
 *
 * Focus: Capability detection, explicit failure behavior, streaming object creation.
 */
class OperationContextPromptRunnerStreamingTest {

    @Test
    fun `should create streaming operations when llmOperations is ChatClientLlmOperations`() {
        // Given: Mock with ChatClientLlmOperations that implements StreamingLlmOperations AND has StreamingChatModel
        val mockStreamingChatModel = mockk<ChatModel>(moreInterfaces = arrayOf(StreamingChatModel::class)) {
            every { stream(any<Prompt>()) } returns Flux.just(
                ChatResponse(
                    listOf(Generation(AssistantMessage("streaming response")))
                )
            )
        }

        val mockLlm = mockk<SpringAiLlmService> {
            every { chatModel } returns mockStreamingChatModel
        }

        val mockChatClientLlmOperations =
            mockk<ChatClientLlmOperations>(moreInterfaces = arrayOf(StreamingLlmOperations::class), relaxed = true) {
                every { getLlm(any<LlmInteraction>()) } returns mockLlm
            }

        val mockAgentPlatform = mockk<AgentPlatform>()
        val mockAgentProcess = mockk<AgentProcess>()
        val mockOperation = mockk<Operation>()

        val mockOperationContext = mockk<OperationContext> {
            every { agentPlatform() } returns mockAgentPlatform
            every { processContext.agentProcess } returns mockAgentProcess
            every { operation } returns mockOperation
        }

        val mockPlatformServices = mockk<PlatformServices> {
            every { llmOperations } returns mockChatClientLlmOperations  // This enables all capability levels
        }

        every { mockAgentPlatform.platformServices } returns mockPlatformServices
        every { mockOperation.name } returns "testOperation"

        // Create with real value objects where possible
        val promptRunner = OperationContextPromptRunner(
            context = mockOperationContext,
            llm = LlmOptions.withModel("test-model"),
            messages = listOf(UserMessage("Test message")),
            toolGroups = emptySet(),
            toolObjects = emptyList(),
            promptContributors = emptyList(),
            contextualPromptContributors = emptyList(),
            generateExamples = false
        )

        // When: Call the stream() method
        val result = promptRunner.stream()

        // Then: Verify object creation and types
        assertNotNull(result, "Stream method should return non-null result")
        assertTrue(result is StreamingPromptRunnerOperations, "Should return StreamingPromptRunnerOperations")
        assertTrue(
            promptRunner is StreamingPromptRunner,
            "OperationContextPromptRunner should implement StreamingPromptRunner"
        )

        // Verify fluent API works
        val withPromptResult = result.withPrompt("New prompt")
        assertNotNull(withPromptResult, "Fluent API withPrompt should work")

        // Verify method chaining works
        val chainedResult = result
            .withPrompt("test prompt")
            .withMessages(listOf(UserMessage("additional message")))
        assertNotNull(chainedResult, "Method chaining should work")
    }

    @Test
    fun `should fail when llmOperations is not ChatClientLlmOperations`() {
        // Given: Mock with different LlmOperations implementation (capability detection failure scenario)
        val mockOtherLlmOperations = mockk<LlmOperations>() // NOT ChatClientLlmOperations
        val mockAgentPlatform = mockk<AgentPlatform>()
        val mockAgentProcess = mockk<AgentProcess>()
        val mockOperation = mockk<Operation>()

        val mockOperationContext = mockk<OperationContext> {
            every { agentPlatform() } returns mockAgentPlatform
            every { processContext.agentProcess } returns mockAgentProcess
            every { operation } returns mockOperation
        }

        val mockPlatformServices = mockk<PlatformServices> {
            every { llmOperations } returns mockOtherLlmOperations  // This will fail capability detection
        }

        every { mockAgentPlatform.platformServices } returns mockPlatformServices
        every { mockOperation.name } returns "testOperation"

        val promptRunner = OperationContextPromptRunner(
            context = mockOperationContext,
            llm = LlmOptions.withModel("test-model"),
            messages = listOf(UserMessage("Test message")),
            toolGroups = emptySet(),
            toolObjects = emptyList(),
            promptContributors = emptyList(),
            contextualPromptContributors = emptyList(),
            generateExamples = false
        )

        // When & Then: Expect UnsupportedOperationException due to explicit failure policy
        assertThrows<UnsupportedOperationException>("Should fail when streaming not supported") {
            promptRunner.stream()
        }
    }

    @Test
    fun `supportsStreaming should return false when using DummyObjectCreatingLlmOperations`() {
        // Given: DummyObjectCreatingLlmOperations that is NOT ChatClientLlmOperations
        val dummyLlmOperations = DummyObjectCreatingLlmOperations.LoremIpsum
        val mockAgentPlatform = mockk<AgentPlatform>()
        val mockOperationContext = mockk<OperationContext> {
            every { agentPlatform() } returns mockAgentPlatform
        }

        val mockPlatformServices = mockk<PlatformServices> {
            every { llmOperations } returns dummyLlmOperations
        }

        every { mockAgentPlatform.platformServices } returns mockPlatformServices

        val promptRunner = OperationContextPromptRunner(
            context = mockOperationContext,
            llm = LlmOptions.withModel("test-model"),
            messages = emptyList(),
            toolGroups = emptySet(),
            toolObjects = emptyList(),
            promptContributors = emptyList(),
            contextualPromptContributors = emptyList(),
            generateExamples = false
        )

        // When
        val result = promptRunner.supportsStreaming()

        // Then
        assertFalse(result, "Should not support streaming when LlmOperations is not ChatClientLlmOperations")
    }

    @Test
    fun `should fail when using DummyObjectCreatingLlmOperations on stream call`() {
        // Given: DummyObjectCreatingLlmOperations that is NOT ChatClientLlmOperations
        val dummyLlmOperations = DummyObjectCreatingLlmOperations.LoremIpsum
        val mockAgentPlatform = mockk<AgentPlatform>()
        val mockAgentProcess = mockk<AgentProcess>()
        val mockOperation = mockk<Operation>()

        val mockOperationContext = mockk<OperationContext> {
            every { agentPlatform() } returns mockAgentPlatform
            every { processContext.agentProcess } returns mockAgentProcess
            every { operation } returns mockOperation
        }

        val mockPlatformServices = mockk<PlatformServices> {
            every { llmOperations } returns dummyLlmOperations
        }

        every { mockAgentPlatform.platformServices } returns mockPlatformServices
        every { mockOperation.name } returns "testOperation"

        val promptRunner = OperationContextPromptRunner(
            context = mockOperationContext,
            llm = LlmOptions.withModel("test-model"),
            messages = listOf(UserMessage("Test message")),
            toolGroups = emptySet(),
            toolObjects = emptyList(),
            promptContributors = emptyList(),
            contextualPromptContributors = emptyList(),
            generateExamples = false
        )

        // When & Then: Expect UnsupportedOperationException when LlmOperations is not ChatClientLlmOperations
        assertThrows<UnsupportedOperationException>("Should fail when LlmOperations is not ChatClientLlmOperations") {
            promptRunner.stream()
        }
    }
}
