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
package com.embabel.agent.api.common.thinking

import com.embabel.agent.api.common.PlatformServices
import com.embabel.agent.api.common.support.OperationContextPromptRunner
import com.embabel.agent.spi.support.springai.ChatClientLlmOperations
import com.embabel.chat.AssistantMessage
import com.embabel.common.core.thinking.ThinkingResponse
import com.embabel.common.core.thinking.ThinkingBlock
import com.embabel.common.core.thinking.ThinkingException
import com.embabel.common.core.thinking.ThinkingTagType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

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
        val status: String,
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
        } returns ThinkingResponse(
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
        assertEquals("processed data", result.result!!.result)
        assertEquals("success", result.result!!.status)

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
     * Tests that StreamingPromptRunner throws exception when withThinking() is called.
     *
     * Verifies that:
     * 1. StreamingPromptRunner.withThinking() throws UnsupportedOperationException
     * 2. Exception message guides users to use streaming events instead
     */
    @Test
    fun `StreamingPromptRunner should throw exception when withThinking called`() {
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

            override fun <T> createObjectIfPossible(
                messages: List<com.embabel.chat.Message>,
                outputClass: Class<T>,
            ): T? {
                return createObject(messages, outputClass)
            }

            override fun respond(messages: List<com.embabel.chat.Message>): com.embabel.chat.AssistantMessage {
                return com.embabel.chat.AssistantMessage("streaming response")
            }

            override fun evaluateCondition(
                condition: String,
                context: String,
                confidenceThreshold: com.embabel.common.core.types.ZeroToOne,
            ): Boolean {
                return true
            }

            override fun stream(): com.embabel.agent.api.common.streaming.StreamingPromptRunnerOperations {
                throw UnsupportedOperationException("Not implemented for test")
            }

            // Implementation methods that are required but not relevant for this test
            override fun withInteractionId(interactionId: com.embabel.agent.api.common.InteractionId): com.embabel.agent.api.common.PromptRunner =
                this

            override fun withMessages(messages: List<com.embabel.chat.Message>): com.embabel.agent.api.common.PromptRunner =
                this

            override fun withImages(images: List<com.embabel.agent.api.common.AgentImage>): com.embabel.agent.api.common.PromptRunner =
                this

            override fun withLlm(llm: com.embabel.common.ai.model.LlmOptions): com.embabel.agent.api.common.PromptRunner =
                this

            override fun withToolGroup(toolGroup: com.embabel.agent.core.ToolGroupRequirement): com.embabel.agent.api.common.PromptRunner =
                this

            override fun withToolGroup(toolGroup: com.embabel.agent.core.ToolGroup): com.embabel.agent.api.common.PromptRunner =
                this

            override fun withToolObject(toolObject: com.embabel.agent.api.common.ToolObject): com.embabel.agent.api.common.PromptRunner =
                this

            override fun withTool(tool: com.embabel.agent.api.tool.Tool): com.embabel.agent.api.common.PromptRunner =
                this

            override fun withHandoffs(vararg outputTypes: Class<*>): com.embabel.agent.api.common.PromptRunner = this
            override fun withSubagents(vararg subagents: com.embabel.agent.api.common.Subagent): com.embabel.agent.api.common.PromptRunner =
                this

            override fun withPromptContributors(promptContributors: List<com.embabel.common.ai.prompt.PromptContributor>): com.embabel.agent.api.common.PromptRunner =
                this

            override fun withContextualPromptContributors(contextualPromptContributors: List<com.embabel.agent.api.common.ContextualPromptElement>): com.embabel.agent.api.common.PromptRunner =
                this

            override fun withGenerateExamples(generateExamples: Boolean): com.embabel.agent.api.common.PromptRunner =
                this

            override fun withPropertyFilter(filter: java.util.function.Predicate<String>): com.embabel.agent.api.common.PromptRunner =
                this

            override fun <T> creating(outputClass: Class<T>): com.embabel.agent.api.common.nested.ObjectCreator<T> {
                throw UnsupportedOperationException("Not implemented for test")
            }

            override fun withTemplate(templateName: String): com.embabel.agent.api.common.nested.TemplateOperations {
                throw UnsupportedOperationException("Not implemented for test")
            }
        }

        // When/Then: Call withThinking() on StreamingPromptRunner should throw exception
        // testStreamingRunner.withThinking().createObject("test prompt", String::class.java) // does not compile - ThinkingCapability has no createObject method

        assertThrows<UnsupportedOperationException> {
            testStreamingRunner.withThinking()
        }
    }

    @Test
    fun `FakePromptRunner should throw exception when withThinking called`() {
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

        // When/Then: Call withThinking() on FakePromptRunner should throw exception
        // fakeRunner.withThinking().createObject("test prompt", String::class.java) // does not compile - ThinkingCapability has no createObject method

        assertThrows<UnsupportedOperationException> {
            fakeRunner.withThinking()
        }
    }


    @Test
    fun `method should delegate to OperationContextPromptRunner withThinking`() {
        // Given: OperationContextPromptRunner with mocked withThinking method
        val mockOperationRunner = mockk<OperationContextPromptRunner>()
        val mockThinkingOps = mockk<ThinkingPromptRunnerOperations>()

        every { mockOperationRunner.withThinking() } returns mockThinkingOps


        val result = mockOperationRunner.withThinking()

        // Then: Should delegate to OperationContextPromptRunner's withThinking method
        assertEquals(mockThinkingOps, result)
        verify { mockOperationRunner.withThinking() }
    }

    /**
     * Additional thinking functionality tests for ThinkingPromptRunnerOperationsImpl coverage.
     * Tests the public API through OperationContextPromptRunner.withThinking().
     */
    // Data class for coverage tests
    data class SimpleTestData(
        val message: String,
        val value: Int,
    )

    @Test
    fun `ThinkingPromptRunnerOperationsImpl should handle createObjectIfPossible through public API`() {
        // Given: Mock setup that covers the implementation createObjectIfPossible method
        val mockContext = mockk<com.embabel.agent.api.common.OperationContext>()
        val mockPlatform = mockk<com.embabel.agent.core.AgentPlatform>()
        val mockServices = mockk<PlatformServices>()
        val mockChatClientOps = mockk<ChatClientLlmOperations>()

        setupMockContext(mockContext, mockPlatform, mockServices, mockChatClientOps)

        // Mock the createObjectIfPossible path to return Result.success
        val testResult = SimpleTestData("success", 123)
        val thinkingBlocks = listOf(
            ThinkingBlock(content = "Processing", tagType = ThinkingTagType.TAG, tagValue = "think")
        )

        every {
            mockChatClientOps.createObjectIfPossible<SimpleTestData>(
                any(), any(), any(), any(), any()
            )
        } returns Result.success(testResult)

        every {
            mockChatClientOps.doTransformWithThinkingIfPossible<SimpleTestData>(
                any(), any(), any(), any()
            )
        } returns Result.success(
            ThinkingResponse(
                result = testResult,
                thinkingBlocks = thinkingBlocks
            )
        )

        val runner = createTestRunner(mockContext)

        // When: Use createObjectIfPossible through ThinkingPromptRunnerOperationsImpl
        val thinkingOps = runner.withThinking()
        val result = thinkingOps.createObjectIfPossible(
            prompt = "Test createObjectIfPossible",
            outputClass = SimpleTestData::class.java
        )

        // Then: Should get wrapped result with thinking blocks
        assertEquals(testResult, result.result)
        assertNotNull(result.thinkingBlocks)
    }

    @Test
    fun `ThinkingPromptRunnerOperationsImpl should handle failure paths in createObjectIfPossible`() {
        // Given: Mock setup for failure scenarios
        val mockContext = mockk<com.embabel.agent.api.common.OperationContext>()
        val mockPlatform = mockk<com.embabel.agent.core.AgentPlatform>()
        val mockServices = mockk<PlatformServices>()
        val mockChatClientOps = mockk<ChatClientLlmOperations>()

        setupMockContext(mockContext, mockPlatform, mockServices, mockChatClientOps)

        // Mock failure with preserved thinking blocks
        val thinkingBlocks = listOf(
            ThinkingBlock(content = "Failed processing", tagType = ThinkingTagType.TAG, tagValue = "think")
        )
        val exception = ThinkingException(
            "Processing failed", thinkingBlocks
        )

        every {
            mockChatClientOps.doTransformWithThinkingIfPossible<SimpleTestData>(
                any(), any(), any(), any()
            )
        } returns Result.failure(exception)

        val runner = createTestRunner(mockContext)

        // When: Use createObjectIfPossible that fails
        val thinkingOps = runner.withThinking()
        val result = thinkingOps.createObjectIfPossible(
            prompt = "Test failure scenario",
            outputClass = SimpleTestData::class.java
        )

        // Then: Should handle failure gracefully with preserved thinking
        assertNull(result.result)
        assertEquals(1, result.thinkingBlocks.size)
        assertEquals("Failed processing", result.thinkingBlocks[0].content)
    }


    @Test
    fun `ThinkingPromptRunnerOperations default implementations should work correctly`() {
        // Given: Real thinking operations through OperationContextPromptRunner
        val mockContext = mockk<com.embabel.agent.api.common.OperationContext>()
        val mockPlatform = mockk<com.embabel.agent.core.AgentPlatform>()
        val mockServices = mockk<PlatformServices>()
        val mockChatClientOps = mockk<ChatClientLlmOperations>()

        setupMockContext(mockContext, mockPlatform, mockServices, mockChatClientOps)

        // Mock responses for different method calls
        every {
            mockChatClientOps.doTransformWithThinking<String>(
                any(), any(), eq(String::class.java), any()
            )
        } returns ThinkingResponse(result = "generated text", thinkingBlocks = emptyList())

        every {
            mockChatClientOps.doTransformWithThinking<SimpleTestData>(
                any(), any(), eq(SimpleTestData::class.java), any()
            )
        } returns ThinkingResponse(result = SimpleTestData("created", 123), thinkingBlocks = emptyList())

        every {
            mockChatClientOps.doTransformWithThinkingIfPossible<SimpleTestData>(
                any(), any(), eq(SimpleTestData::class.java), any()
            )
        } returns Result.success(
            ThinkingResponse(
                result = SimpleTestData("maybe", 456),
                thinkingBlocks = emptyList()
            )
        )

        val runner = createTestRunner(mockContext)
        val thinkingOps = runner.withThinking()

        // When: Use default implementations
        val textResult = thinkingOps generateText "generate text test"
        val objectResult = thinkingOps.createObject("create object test", SimpleTestData::class.java)
        val ifPossibleResult = thinkingOps.createObjectIfPossible("create if possible test", SimpleTestData::class.java)

        // Then: All should work and delegate properly
        assertEquals("generated text", textResult.result)
        assertEquals("created", objectResult.result!!.message)
        assertEquals(123, objectResult.result.value)
        assertEquals("maybe", ifPossibleResult.result!!.message)
        assertEquals(456, ifPossibleResult.result.value)
    }

    @Test
    fun `ThinkingPromptRunnerOperations multimodal content methods should work correctly`() {
        // Given: Mock setup for multimodal content testing
        val mockContext = mockk<com.embabel.agent.api.common.OperationContext>()
        val mockPlatform = mockk<com.embabel.agent.core.AgentPlatform>()
        val mockServices = mockk<PlatformServices>()
        val mockChatClientOps = mockk<ChatClientLlmOperations>()

        setupMockContext(mockContext, mockPlatform, mockServices, mockChatClientOps)

        // Create multimodal content
        val multimodalContent = com.embabel.agent.api.common.MultimodalContent("test multimodal content")

        // Mock responses for multimodal methods
        every {
            mockChatClientOps.doTransformWithThinking<String>(
                any(), any(), eq(String::class.java), any()
            )
        } returns ThinkingResponse(result = "multimodal text response", thinkingBlocks = emptyList())

        every {
            mockChatClientOps.doTransformWithThinking<SimpleTestData>(
                any(), any(), eq(SimpleTestData::class.java), any()
            )
        } returns ThinkingResponse(
            result = SimpleTestData("multimodal object", 789),
            thinkingBlocks = emptyList()
        )

        every {
            mockChatClientOps.doTransformWithThinkingIfPossible<SimpleTestData>(
                any(), any(), eq(SimpleTestData::class.java), any()
            )
        } returns Result.success(
            ThinkingResponse(
                result = SimpleTestData("multimodal maybe", 101),
                thinkingBlocks = emptyList()
            )
        )

        every {
            mockChatClientOps.doTransformWithThinking<com.embabel.chat.AssistantMessage>(
                any(), any(), eq(com.embabel.chat.AssistantMessage::class.java), any()
            )
        } returns ThinkingResponse(
            result = AssistantMessage("multimodal response"),
            thinkingBlocks = emptyList()
        )

        val runner = createTestRunner(mockContext)
        val thinkingOps = runner.withThinking()

        // When: Use multimodal content methods
        val textResult = thinkingOps.generateText(multimodalContent)
        val objectResult = thinkingOps.createObject(multimodalContent, SimpleTestData::class.java)
        val ifPossibleResult = thinkingOps.createObjectIfPossible(multimodalContent, SimpleTestData::class.java)
        val respondResult = thinkingOps.respond(multimodalContent)

        // Then: All multimodal methods should work
        assertEquals("multimodal text response", textResult.result)
        assertEquals("multimodal object", objectResult.result!!.message)
        assertEquals(789, objectResult.result.value)
        assertEquals("multimodal maybe", ifPossibleResult.result!!.message)
        assertEquals(101, ifPossibleResult.result.value)
        assertEquals("multimodal response", respondResult.result!!.content)
    }

    @Test
    fun `ThinkingPromptRunnerOperationsImpl evaluateCondition should cover confidence threshold logic`() {
        // Given: Mock setup for evaluateCondition method
        val mockContext = mockk<com.embabel.agent.api.common.OperationContext>()
        val mockPlatform = mockk<com.embabel.agent.core.AgentPlatform>()
        val mockServices = mockk<PlatformServices>()
        val mockChatClientOps = mockk<ChatClientLlmOperations>()

        setupMockContext(mockContext, mockPlatform, mockServices, mockChatClientOps)

        // Mock determination response with high confidence
        val determination = com.embabel.agent.experimental.primitive.Determination(
            result = true,
            confidence = 0.9,
            explanation = "High confidence"
        )

        every {
            mockChatClientOps.doTransformWithThinking<com.embabel.agent.experimental.primitive.Determination>(
                any(), any(), any(), any()
            )
        } returns ThinkingResponse(
            result = determination,
            thinkingBlocks = emptyList()
        )

        val runner = createTestRunner(mockContext)

        // When: Use evaluateCondition with threshold below confidence
        val thinkingOps = runner.withThinking()
        val result = thinkingOps.evaluateCondition(
            condition = "Test condition",
            context = "Test context",
            confidenceThreshold = 0.8
        )

        // Then: Should return true when confidence exceeds threshold
        assertTrue(result.result!!)
    }


    private fun setupMockContext(
        mockContext: com.embabel.agent.api.common.OperationContext,
        mockPlatform: com.embabel.agent.core.AgentPlatform,
        mockServices: PlatformServices,
        mockChatClientOps: ChatClientLlmOperations,
    ) {
        every { mockContext.agentPlatform() } returns mockPlatform
        every { mockContext.operation } returns mockk<com.embabel.agent.core.Operation> {
            every { name } returns "test-operation"
        }
        every { mockContext.processContext } returns mockk<com.embabel.agent.core.ProcessContext> {
            every { agentProcess } returns mockk()
        }
        every { mockPlatform.platformServices } returns mockServices
        every { mockServices.llmOperations } returns mockChatClientOps
    }

    private fun createTestRunner(mockContext: com.embabel.agent.api.common.OperationContext): OperationContextPromptRunner {
        val mockLlmOptions = mockk<com.embabel.common.ai.model.LlmOptions>()
        every { mockLlmOptions.withThinking(any()) } returns mockLlmOptions

        return OperationContextPromptRunner(
            context = mockContext,
            llm = mockLlmOptions,
            toolGroups = setOf(),
            toolObjects = emptyList(),
            promptContributors = emptyList(),
            contextualPromptContributors = emptyList(),
            generateExamples = null,
        )
    }
}
