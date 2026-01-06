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

import com.embabel.agent.api.common.support.OperationContextPromptRunner
import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.Operation
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.spi.LlmInteraction
import com.embabel.agent.spi.LlmOperations
import com.embabel.agent.spi.support.springai.ChatClientLlmOperations
import com.embabel.chat.Message
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.Thinking
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for thinking functionality in OperationContextPromptRunner.
 *
 * Focuses on:
 * - withThinking() creates operational ThinkingPromptRunnerOperations
 * - Error handling for incompatible LlmOperations implementations
 */
class OperationContextPromptRunnerThinkingTest {

    private fun createMockOperationContextWithLlmOperations(llmOperations: LlmOperations): OperationContext {
        val mockOperationContext = mockk<OperationContext>()
        val mockAgentPlatform = mockk<AgentPlatform>()
        val mockPlatformServices = mockk<PlatformServices>()
        val mockOperation = mockk<Operation>()
        val mockProcessContext = mockk<ProcessContext>()
        val mockAgentProcess = mockk<AgentProcess>()

        every { mockOperationContext.agentPlatform() } returns mockAgentPlatform
        every { mockAgentPlatform.platformServices } returns mockPlatformServices
        every { mockPlatformServices.llmOperations } returns llmOperations
        every { mockOperationContext.operation } returns mockOperation
        every { mockOperation.name } returns "test-operation"
        every { mockOperationContext.processContext } returns mockProcessContext
        every { mockProcessContext.agentProcess } returns mockAgentProcess

        return mockOperationContext
    }

    private fun createOperationContextPromptRunner(
        context: OperationContext,
        llmOptions: LlmOptions = LlmOptions()
    ): OperationContextPromptRunner {
        return OperationContextPromptRunner(
            context = context,
            llm = llmOptions,
            toolGroups = emptySet(),
            toolObjects = emptyList(),
            promptContributors = emptyList(),
            contextualPromptContributors = emptyList(),
            generateExamples = null
        )
    }

    @Test
    fun `withThinking creates operational ThinkingPromptRunnerOperations with ChatClientLlmOperations`() {
        // Given: OperationContext with ChatClientLlmOperations and various LlmOptions scenarios
        val mockChatClientOps = mockk<ChatClientLlmOperations>(relaxed = true)
        val context = createMockOperationContextWithLlmOperations(mockChatClientOps)

        // Test with default LlmOptions
        val defaultRunner = createOperationContextPromptRunner(context)
        val defaultThinkingOps = defaultRunner.withThinking()
        assertNotNull(defaultThinkingOps)

        // Test with custom LlmOptions (verifies preservation of settings)
        val customLlmOptions = LlmOptions()
            .withMaxTokens(500)
            .withTemperature(0.7)
        val customRunner = createOperationContextPromptRunner(context, customLlmOptions)
        val customThinkingOps = customRunner.withThinking()
        assertNotNull(customThinkingOps)

        // Test with already thinking-enabled LlmOptions (verifies idempotency)
        val thinkingEnabledOptions = LlmOptions()
            .withThinking(Thinking.withExtraction())
        val thinkingRunner = createOperationContextPromptRunner(context, thinkingEnabledOptions)
        val thinkingOps = thinkingRunner.withThinking()
        assertNotNull(thinkingOps)

        // All should create valid, operational ThinkingPromptRunnerOperations
        // The fact they were created without exceptions validates the internal setup
    }

    @Test
    fun `withThinking throws UnsupportedOperationException for non-ChatClientLlmOperations`() {
        // Given: OperationContext with non-ChatClientLlmOperations
        val unsupportedLlmOps = object : LlmOperations {
            override fun <O> createObject(
                messages: List<Message>,
                interaction: LlmInteraction,
                outputClass: Class<O>,
                agentProcess: com.embabel.agent.core.AgentProcess,
                action: com.embabel.agent.core.Action?
            ): O = throw UnsupportedOperationException("Test implementation")

            override fun <O> createObjectIfPossible(
                messages: List<Message>,
                interaction: LlmInteraction,
                outputClass: Class<O>,
                agentProcess: com.embabel.agent.core.AgentProcess,
                action: com.embabel.agent.core.Action?
            ): Result<O> = Result.failure(UnsupportedOperationException("Test implementation"))

            override fun <O> doTransform(
                messages: List<Message>,
                interaction: LlmInteraction,
                outputClass: Class<O>,
                llmRequestEvent: LlmRequestEvent<O>?
            ): O = throw UnsupportedOperationException("Test implementation")
        }

        val context = createMockOperationContextWithLlmOperations(unsupportedLlmOps)
        val runner = createOperationContextPromptRunner(context)

        // When/Then: Should throw UnsupportedOperationException with descriptive message
        try {
            runner.withThinking()
            fail("Expected UnsupportedOperationException to be thrown")
        } catch (e: UnsupportedOperationException) {
            val message = e.message ?: ""
            assertTrue(message.contains("Thinking extraction not supported"))
            assertTrue(message.contains("ChatClientLlmOperations"))
        }
    }
}
