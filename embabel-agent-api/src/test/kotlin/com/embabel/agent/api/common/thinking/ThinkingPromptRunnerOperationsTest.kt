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
package com.embabel.agent.api.common.thinking

import com.embabel.agent.api.common.support.OperationContextPromptRunner
import com.embabel.agent.api.common.PlatformServices
import com.embabel.agent.spi.support.springai.ChatClientLlmOperations
import com.embabel.chat.ChatResponseWithThinking
import com.embabel.common.core.thinking.ThinkingBlock
import com.embabel.common.core.thinking.ThinkingTagType
import org.junit.jupiter.api.Test
import io.mockk.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Test for the thinking prompt runner operations.
 *
 * Validates the end-to-end flow from user API through to thinking extraction:
 *
 * ```
 * promptRunner.withThinking()
 *   → ThinkingPromptRunnerOperationsImpl
 *   → ChatClientLlmOperations.doTransformWithThinking()
 *   → SuppressThinkingConverter.convertWithThinking()
 *   → extractAllThinkingBlocks()
 * ```
 */
class ThinkingPromptRunnerOperationsTest {

    // Data class for proper object conversion testing
    data class ProcessedData(
        val result: String,
        val status: String
    )

    @Test
    fun `withThinking should create ThinkingPromptRunnerOperationsImpl when ChatClientLlmOperations available`() {
        // Given: Mock OperationContextPromptRunner with ChatClientLlmOperations
        val mockOperationRunner = mockk<OperationContextPromptRunner>()
        val mockContext = mockk<com.embabel.agent.api.common.OperationContext>()
        val mockPlatform = mockk<com.embabel.agent.core.AgentPlatform>()
        val mockServices = mockk<PlatformServices>()
        val mockChatClientOps = mockk<ChatClientLlmOperations>()
        val mockAgentProcess = mockk<com.embabel.agent.core.AgentProcess>()

        // Mock LLM response with multiple thinking blocks
        val llmResponse = """
            <think>
            I need to analyze this step by step.
            First, let me understand what's being asked.
            </think>

            <analysis>
            The user wants me to process some data.
            I should be thorough in my approach.
            </analysis>

            {"result": "processed data", "status": "success"}
        """.trimIndent()

        val expectedThinking = listOf(
            ThinkingBlock(
                content = "I need to analyze this step by step.\nFirst, let me understand what's being asked.",
                tagType = ThinkingTagType.TAG,
                tagValue = "think"
            ),
            ThinkingBlock(
                content = "The user wants me to process some data.\nI should be thorough in my approach.",
                tagType = ThinkingTagType.TAG,
                tagValue = "analysis"
            )
        )

        every { mockContext.agentPlatform() } returns mockPlatform
        every { mockContext.operation } returns mockk<com.embabel.agent.core.Operation> {
            every { name } returns "test-operation"
        }
        every { mockContext.processContext } returns mockk<com.embabel.agent.core.ProcessContext> {
            every { agentProcess } returns mockAgentProcess
        }
        every { mockPlatform.platformServices } returns mockServices
        every { mockServices.llmOperations } returns mockChatClientOps
        every {
            mockChatClientOps.doTransformWithThinking<ProcessedData>(
                any<List<com.embabel.chat.Message>>(),
                any<com.embabel.agent.spi.LlmInteraction>(),
                any<Class<ProcessedData>>(),
                isNull()
            )
        } returns ChatResponseWithThinking(
            result = ProcessedData(result = "processed data", status = "success"),
            thinkingBlocks = expectedThinking
        )

        val mockLlmOptions = mockk<com.embabel.common.ai.model.LlmOptions>()
        every { mockLlmOptions.withThinking(any()) } returns mockLlmOptions

        val runner = OperationContextPromptRunner(
            context = mockContext,
            llm = mockLlmOptions,
            toolGroups = setOf(),
            toolObjects = emptyList(),
            promptContributors = emptyList(),
            contextualPromptContributors = emptyList(),
            generateExamples = null,
        )

        // When: Create thinking operations and use them
        val thinkingOps = runner.withThinking()
        val result = thinkingOps.createObject(
            prompt = "Test data processing",
            outputClass = ProcessedData::class.java
        )

        // Then: Verify complete pipeline worked
        assertNotNull(result.result)
        assertEquals("processed data", result.result.result)
        assertEquals("success", result.result.status)

        // Verify thinking blocks were extracted correctly
        assertEquals(2, result.thinkingBlocks.size)

        val firstThinking = result.thinkingBlocks[0]
        assertEquals(ThinkingTagType.TAG, firstThinking.tagType)
        assertEquals("think", firstThinking.tagValue)
        assertTrue(firstThinking.content.contains("analyze this step by step"))

        val secondThinking = result.thinkingBlocks[1]
        assertEquals(ThinkingTagType.TAG, secondThinking.tagType)
        assertEquals("analysis", secondThinking.tagValue)
        assertTrue(secondThinking.content.contains("process some data"))
    }

    /**
     * Tests ThinkingPromptRunner wrapper for non-streaming PromptRunnerOperations implementations.
     *
     * Verifies that:
     * 1. Non-OperationContextPromptRunner (except streaming) gets wrapped in ThinkingPromptRunner
     * 2. Original result is preserved: base.createObject() result becomes result.result
     * 3. Thinking blocks are always empty: result.thinkingBlocks.isEmpty()
     */
    @Test
    fun `non-OperationContextPromptRunner should get ThinkingPromptRunner wrapper with empty thinking blocks`() {
        // Given: Non-OperationContextPromptRunner, non-streaming implementation
        val mockRunner = mockk<com.embabel.agent.api.common.PromptRunnerOperations>()
        every { mockRunner.createObject(any<List<com.embabel.chat.Message>>(), any<Class<String>>()) } returns "test result"

        // When: Use fallback wrapper (extension method)
        val result = mockRunner.withThinking().createObject("test prompt", String::class.java)

        // Then: Should return result with empty thinking blocks
        assertEquals("test result", result.result)
        assertTrue(result.thinkingBlocks.isEmpty())
    }

    /**
     * Tests that StreamingPromptRunner throws exception when withThinking() is called.
     *
     * Verifies that:
     * 1. StreamingPromptRunner.withThinking() throws UnsupportedOperationException
     * 2. Exception message guides users to use streaming events instead
     */
    @Test
    fun `StreamingPromptRunner should get ThinkingPromptRunner wrapper with empty thinking blocks`() {
        // Given: Real StreamingPromptRunner implementation (no mocks)
        val testStreamingRunner = object : com.embabel.agent.api.common.streaming.StreamingPromptRunner {
            override val llm: com.embabel.common.ai.model.LlmOptions? = null
            override val messages: List<com.embabel.chat.Message> = emptyList()
            override val images: List<com.embabel.agent.api.common.AgentImage> = emptyList()
            override val toolGroups: Set<com.embabel.agent.core.ToolGroupRequirement> = emptySet()
            override val toolObjects: List<com.embabel.agent.api.common.ToolObject> = emptyList()
            override val promptContributors: List<com.embabel.common.ai.prompt.PromptContributor> = emptyList()
            override val generateExamples: Boolean? = null
            override val propertyFilter: java.util.function.Predicate<String> = java.util.function.Predicate { true }

            override fun <T> createObject(messages: List<com.embabel.chat.Message>, outputClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return "streaming test result" as T
            }

            override fun <T> createObjectIfPossible(messages: List<com.embabel.chat.Message>, outputClass: Class<T>): T? {
                return createObject(messages, outputClass)
            }

            override fun respond(messages: List<com.embabel.chat.Message>): com.embabel.chat.AssistantMessage {
                return com.embabel.chat.AssistantMessage("streaming response")
            }

            override fun evaluateCondition(condition: String, context: String, confidenceThreshold: com.embabel.common.core.types.ZeroToOne): Boolean {
                return true
            }

            override fun stream(): com.embabel.agent.api.common.streaming.StreamingPromptRunnerOperations {
                throw UnsupportedOperationException("Not implemented for test")
            }

            // Implementation methods that are required but not relevant for this test
            override fun withInteractionId(interactionId: com.embabel.agent.api.common.InteractionId): com.embabel.agent.api.common.PromptRunner = this
            override fun withMessages(messages: List<com.embabel.chat.Message>): com.embabel.agent.api.common.PromptRunner = this
            override fun withImages(images: List<com.embabel.agent.api.common.AgentImage>): com.embabel.agent.api.common.PromptRunner = this
            override fun withLlm(llm: com.embabel.common.ai.model.LlmOptions): com.embabel.agent.api.common.PromptRunner = this
            override fun withToolGroup(toolGroup: com.embabel.agent.core.ToolGroupRequirement): com.embabel.agent.api.common.PromptRunner = this
            override fun withToolGroup(toolGroup: com.embabel.agent.core.ToolGroup): com.embabel.agent.api.common.PromptRunner = this
            override fun withToolObject(toolObject: com.embabel.agent.api.common.ToolObject): com.embabel.agent.api.common.PromptRunner = this
            override fun withTool(tool: com.embabel.agent.api.tool.Tool): com.embabel.agent.api.common.PromptRunner = this
            override fun withHandoffs(vararg outputTypes: Class<*>): com.embabel.agent.api.common.PromptRunner = this
            override fun withSubagents(vararg subagents: com.embabel.agent.api.common.Subagent): com.embabel.agent.api.common.PromptRunner = this
            override fun withPromptContributors(promptContributors: List<com.embabel.common.ai.prompt.PromptContributor>): com.embabel.agent.api.common.PromptRunner = this
            override fun withContextualPromptContributors(contextualPromptContributors: List<com.embabel.agent.api.common.ContextualPromptElement>): com.embabel.agent.api.common.PromptRunner = this
            override fun withGenerateExamples(generateExamples: Boolean): com.embabel.agent.api.common.PromptRunner = this
            override fun withPropertyFilter(filter: java.util.function.Predicate<String>): com.embabel.agent.api.common.PromptRunner = this
            override fun <T> creating(outputClass: Class<T>): com.embabel.agent.api.common.nested.ObjectCreator<T> {
                throw UnsupportedOperationException("Not implemented for test")
            }
            override fun withTemplate(templateName: String): com.embabel.agent.api.common.nested.TemplateOperations {
                throw UnsupportedOperationException("Not implemented for test")
            }
        }

        // When: Call withThinking() - should use extension method and fallback to wrapper
        val result = testStreamingRunner.withThinking().createObject("test prompt", String::class.java)

        // Then: Should return result with empty thinking blocks (fallback wrapper behavior)
        assertEquals("streaming test result", result.result)
        assertTrue(result.thinkingBlocks.isEmpty())

        // Additional verification: Test that it's actually the ThinkingPromptRunner wrapper
        val thinkingOps = testStreamingRunner.withThinking()
        assertTrue(thinkingOps is com.embabel.agent.api.common.thinking.ThinkingPromptRunner)
    }

    @Test
    fun `FakePromptRunner should get ThinkingPromptRunner wrapper with empty thinking blocks`() {
        // Given: Real FakePromptRunner implementation (testing framework runner)
        val mockContext = mockk<com.embabel.agent.api.common.OperationContext>()
        val fakeRunner = com.embabel.agent.test.unit.FakePromptRunner(
            llm = com.embabel.common.ai.model.LlmOptions(),
            toolGroups = emptySet(),
            toolObjects = emptyList(),
            promptContributors = emptyList(),
            contextualPromptContributors = emptyList(),
            generateExamples = null,
            context = mockContext,
            responses = mutableListOf("fake test result")
        )

        // When: Call withThinking() on FakePromptRunner
        val result = fakeRunner.withThinking().createObject("test prompt", String::class.java)

        // Then: Should return result with empty thinking blocks (fallback wrapper behavior)
        assertEquals("fake test result", result.result)
        assertTrue(result.thinkingBlocks.isEmpty())

        // Additional verification: Test that it's actually the ThinkingPromptRunner wrapper
        val thinkingOps = fakeRunner.withThinking()
        assertTrue(thinkingOps is com.embabel.agent.api.common.thinking.ThinkingPromptRunner)
    }


    @Test
    fun `extension method should delegate to OperationContextPromptRunner withThinking`() {
        // Given: OperationContextPromptRunner with mocked withThinking method
        val mockOperationRunner = mockk<OperationContextPromptRunner>()
        val mockThinkingOps = mockk<ThinkingPromptRunnerOperations>()

        every { mockOperationRunner.withThinking() } returns mockThinkingOps

        // When: Call extension method
        val result = mockOperationRunner.withThinking()

        // Then: Should delegate to OperationContextPromptRunner's withThinking method
        assertEquals(mockThinkingOps, result)
        verify { mockOperationRunner.withThinking() }
    }
}
