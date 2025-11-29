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
package com.embabel.agent.api.common.support.streaming

import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.spi.LlmInteraction
import com.embabel.agent.spi.streaming.StreamingLlmOperations
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.core.streaming.StreamingEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import kotlin.test.assertEquals

/**
 * Unit tests for StreamingPromptRunnerOperationsImpl.
 *
 * Tests the delegation/bridge behavior that connects the API layer
 * (StreamingPromptRunnerOperations) to the SPI layer (StreamingLlmOperations).
 * This implementation enables polymorphic access through the StreamingCapability
 * tag interface while maintaining clean separation between API and SPI layers.
 *
 * Key testing objectives:
 * - Verifies proper parameter delegation to StreamingLlmOperations
 * - Ensures fluent API message handling (withPrompt, withMessages)
 * - Confirms immutable builder pattern behavior
 * - Validates that streaming results pass through correctly
 *
 * This component acts as a bridge in the streaming capability architecture:
 * API Layer (OperationContextPromptRunner.stream()) →
 * API Support (StreamingPromptRunnerOperationsImpl) →
 * SPI Implementation (StreamingChatClientOperations) →
 * Spring AI (ChatClient)
 *
 * The component should not modify data or implement streaming logic itself,
 * only delegate appropriately to the SPI layer.
 */
class StreamingPromptRunnerOperationsImplTest {

    private lateinit var mockStreamingLlmOperations: StreamingLlmOperations
    private lateinit var mockInteraction: LlmInteraction
    private lateinit var mockAgentProcess: AgentProcess
    private lateinit var mockAction: Action
    private lateinit var initialMessages: List<Message>
    private lateinit var streamingOperations: StreamingPromptRunnerOperationsImpl

    @BeforeEach
    fun setUp() {
        mockStreamingLlmOperations = mockk()
        mockInteraction = mockk()
        mockAgentProcess = mockk()
        mockAction = mockk()
        initialMessages = listOf(UserMessage("Initial message"))

        streamingOperations = StreamingPromptRunnerOperationsImpl(
            streamingLlmOperations = mockStreamingLlmOperations,
            interaction = mockInteraction,
            messages = initialMessages,
            agentProcess = mockAgentProcess,
            action = mockAction
        )
    }

    @Test
    fun `should delegate createObjectList to StreamingLlmOperations`() {
        // Given
        val outputClass = TestItem::class.java
        val mockStream = Flux.just(TestItem("test", 42))
        every {
            mockStreamingLlmOperations.createObjectStream(
                any(), any(), outputClass, mockAgentProcess, mockAction
            )
        } returns mockStream

        // When
        val result = streamingOperations.createObjectList(outputClass)

        // Then
        verify {
            mockStreamingLlmOperations.createObjectStream(
                initialMessages, mockInteraction, outputClass, mockAgentProcess, mockAction
            )
        }

        // Simple verification - get first item from stream
        val firstItem = result.blockFirst()
        assertEquals("test", firstItem?.name)
        assertEquals(42, firstItem?.value)
    }

    @Test
    fun `should delegate createObjectListWithThinking to StreamingLlmOperations`() {
        // Given
        val outputClass = TestItem::class.java
        val mockStream = Flux.just(
            StreamingEvent.Thinking("Thinking..."),
            StreamingEvent.Object(TestItem("test", 42))
        )
        every {
            mockStreamingLlmOperations.createObjectStreamWithThinking(
                any(), any(), outputClass, mockAgentProcess, mockAction
            )
        } returns mockStream

        // When
        val result = streamingOperations.createObjectListWithThinking(outputClass)

        // Then
        verify {
            mockStreamingLlmOperations.createObjectStreamWithThinking(
                initialMessages, mockInteraction, outputClass, mockAgentProcess, mockAction
            )
        }

        // Collect items and verify
        val items = result.collectList().block()
        assertEquals(2, items?.size)
        assertEquals(true, items?.get(0)?.isThinking())
        assertEquals(true, items?.get(1)?.isObject())
    }

    @Test
    fun `should pass updated messages after withPrompt to delegation`() {
        // Given
        val newPrompt = "New prompt"
        val outputClass = TestItem::class.java
        val mockStream = Flux.just(TestItem("result", 100))
        every {
            mockStreamingLlmOperations.createObjectStream<TestItem>(any(), any(), any(), any(), any())
        } returns mockStream

        // When
        streamingOperations
            .withPrompt(newPrompt)
            .createObjectList(outputClass)

        // Then
        verify {
            mockStreamingLlmOperations.createObjectStream(
                match { messages ->
                    messages.size == 1 &&
                    (messages[0] as UserMessage).content == newPrompt
                },
                mockInteraction,
                outputClass,
                mockAgentProcess,
                mockAction
            )
        }
    }

    @Test
    fun `should pass updated messages after withMessages to delegation`() {
        // Given
        val additionalMessages = listOf(UserMessage("Additional"))
        val outputClass = TestItem::class.java
        val mockStream = Flux.just(TestItem("result", 100))
        every {
            mockStreamingLlmOperations.createObjectStream<TestItem>(any(), any(), any(), any(), any())
        } returns mockStream

        // When
        streamingOperations
            .withMessages(additionalMessages)
            .createObjectList(outputClass)

        // Then
        verify {
            mockStreamingLlmOperations.createObjectStream(
                match { messages ->
                    messages.size == 1 && // withMessages replaces, doesn't append
                    (messages[0] as UserMessage).content == "Additional"
                },
                mockInteraction,
                outputClass,
                mockAgentProcess,
                mockAction
            )
        }
    }

    @Test
    fun `should preserve interaction, agentProcess and action across fluent calls`() {
        // Given
        val outputClass = TestItem::class.java
        val mockStream = Flux.just(TestItem("result", 100))
        every {
            mockStreamingLlmOperations.createObjectStream<TestItem>(any(), any(), any(), any(), any())
        } returns mockStream

        // When
        streamingOperations
            .withPrompt("New prompt")
            .withMessages(listOf(UserMessage("Additional")))
            .createObjectList(outputClass)

        // Then
        verify {
            mockStreamingLlmOperations.createObjectStream(
                any(),
                mockInteraction,  // Same interaction
                outputClass,
                mockAgentProcess, // Same agent process
                mockAction       // Same action
            )
        }
    }

    data class TestItem(val name: String, val value: Int)
}
