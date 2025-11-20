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
package com.embabel.agent.spi.support.springai.streaming

import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.spi.LlmInteraction
import com.embabel.agent.spi.streaming.StreamingLlmOperations
import com.embabel.agent.spi.support.springai.ChatClientLlmOperations
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.Llm
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import reactor.core.publisher.Flux
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for StreamingChatClientOperations.
 *
 * Tests the SPI layer delegation behavior and interface implementation.
 * StreamingChatClientOperations sits at the SPI layer, below the API layer's
 * streaming capability detection. This class assumes streaming has already been
 * validated as available by the API layer (OperationContextPromptRunner).
 *
 * Key responsibilities tested:
 * - Proper delegation to ChatClientLlmOperations
 * - Correct implementation of StreamingLlmOperations interface
 * - Bridge between embabel streaming interfaces and Spring AI ChatClient
 *
 * Note: Streaming capability detection is tested at the API layer.
 * Complex Spring AI interactions are tested in integration tests.
 */
class StreamingChatClientOperationsTest {

    private lateinit var mockChatClientLlmOperations: ChatClientLlmOperations
    private lateinit var mockLlm: Llm
    private lateinit var mockChatClient: ChatClient
    private lateinit var mockInteraction: LlmInteraction
    private lateinit var mockAgentProcess: AgentProcess
    private lateinit var mockAction: Action
    private lateinit var streamingOperations: StreamingChatClientOperations

    @BeforeEach
    fun setUp() {
        mockChatClientLlmOperations = mockk(relaxed = true)
        mockLlm = mockk(relaxed = true)
        mockChatClient = mockk(relaxed = true)
        mockInteraction = mockk(relaxed = true)
        mockAgentProcess = mockk(relaxed = true)
        mockAction = mockk(relaxed = true)

        // Setup basic delegation
        every { mockChatClientLlmOperations.getLlm(any()) } returns mockLlm
        every { mockChatClientLlmOperations.createChatClient(any()) } returns mockChatClient
        every { mockInteraction.promptContributors } returns emptyList()
        every { mockLlm.promptContributors } returns emptyList()

        streamingOperations = StreamingChatClientOperations(mockChatClientLlmOperations)
    }

    @Test
    fun `should implement StreamingLlmOperations interface`() {
        // Given & When & Then
        assertTrue(streamingOperations is StreamingLlmOperations)
    }

    @Test
    fun `should delegate getLlm to ChatClientLlmOperations on generateStream`() {
        // Given
        val messages = listOf(UserMessage("Test prompt"))

        // When
        streamingOperations.generateStream(messages, mockInteraction, mockAgentProcess, mockAction)

        // Then
        verify { mockChatClientLlmOperations.getLlm(mockInteraction) }
    }

    @Test
    fun `should delegate createChatClient to ChatClientLlmOperations on generateStream`() {
        // Given
        val messages = listOf(UserMessage("Test prompt"))

        // When
        streamingOperations.generateStream(messages, mockInteraction, mockAgentProcess, mockAction)

        // Then
        verify { mockChatClientLlmOperations.createChatClient(mockLlm) }
    }

    @Test
    fun `should delegate getLlm to ChatClientLlmOperations on createObjectStream`() {
        // Given
        val messages = listOf(UserMessage("Create objects"))
        val outputClass = TestItem::class.java

        // When
        streamingOperations.createObjectStream(messages, mockInteraction, outputClass, mockAgentProcess, mockAction)

        // Then
        verify { mockChatClientLlmOperations.getLlm(mockInteraction) }
    }

    @Test
    fun `should delegate createChatClient to ChatClientLlmOperations on createObjectStream`() {
        // Given
        val messages = listOf(UserMessage("Create objects"))
        val outputClass = TestItem::class.java

        // When
        streamingOperations.createObjectStream(messages, mockInteraction, outputClass, mockAgentProcess, mockAction)

        // Then
        verify { mockChatClientLlmOperations.createChatClient(mockLlm) }
    }

    @Test
    fun `should delegate getLlm to ChatClientLlmOperations on createObjectStreamWithThinking`() {
        // Given
        val messages = listOf(UserMessage("Create objects with thinking"))
        val outputClass = TestItem::class.java

        // When
        streamingOperations.createObjectStreamWithThinking(messages, mockInteraction, outputClass, mockAgentProcess, mockAction)

        // Then
        verify { mockChatClientLlmOperations.getLlm(mockInteraction) }
    }

    @Test
    fun `should delegate createChatClient to ChatClientLlmOperations on createObjectStreamWithThinking`() {
        // Given
        val messages = listOf(UserMessage("Create objects with thinking"))
        val outputClass = TestItem::class.java

        // When
        streamingOperations.createObjectStreamWithThinking(messages, mockInteraction, outputClass, mockAgentProcess, mockAction)

        // Then
        verify { mockChatClientLlmOperations.createChatClient(mockLlm) }
    }

    @Test
    fun `should return Flux from generateStream`() {
        // Given
        val messages = listOf(UserMessage("Test prompt"))

        // When
        val result = streamingOperations.generateStream(messages, mockInteraction, mockAgentProcess, mockAction)

        // Then
        assertNotNull(result)
        assertTrue(result is Flux<String>)
    }

    @Test
    fun `should return Flux from createObjectStream`() {
        // Given
        val messages = listOf(UserMessage("Create objects"))
        val outputClass = TestItem::class.java

        // When
        val result = streamingOperations.createObjectStream(messages, mockInteraction, outputClass, mockAgentProcess, mockAction)

        // Then
        assertNotNull(result)
        assertTrue(result is Flux<TestItem>)
    }

    @Test
    fun `should return Flux from createObjectStreamWithThinking`() {
        // Given
        val messages = listOf(UserMessage("Create objects with thinking"))
        val outputClass = TestItem::class.java

        // When
        val result = streamingOperations.createObjectStreamWithThinking(messages, mockInteraction, outputClass, mockAgentProcess, mockAction)

        // Then
        assertNotNull(result)
        assertTrue(result is Flux<*>)
    }

    @Test
    fun `should return Flux from createObjectStreamIfPossible`() {
        // Given
        val messages = listOf(UserMessage("Create objects safely"))
        val outputClass = TestItem::class.java

        // When
        val result = streamingOperations.createObjectStreamIfPossible(messages, mockInteraction, outputClass, mockAgentProcess, mockAction)

        // Then
        assertNotNull(result)
        assertTrue(result is Flux<*>)
    }

    @Test
    fun `should accept null action parameter`() {
        // Given
        val messages = listOf(UserMessage("Test prompt"))

        // When & Then - should not throw exception
        val result = streamingOperations.generateStream(messages, mockInteraction, mockAgentProcess, null)
        assertNotNull(result)
    }

    data class TestItem(val name: String, val value: Int)
}
