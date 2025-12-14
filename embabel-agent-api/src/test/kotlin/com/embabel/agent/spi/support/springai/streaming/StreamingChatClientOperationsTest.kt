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
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.tool.ToolCallback
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import java.time.Duration

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

    private val logger = LoggerFactory.getLogger(StreamingChatClientOperationsTest::class.java)
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
        mockChatClient = mockk<ChatClient>(relaxed = true)
        mockInteraction = mockk(relaxed = true)
        mockAgentProcess = mockk(relaxed = true)
        mockAction = mockk(relaxed = true)

        // Setup basic delegation
        every { mockChatClientLlmOperations.getLlm(any()) } returns mockLlm
        every { mockChatClientLlmOperations.createChatClient(mockLlm) } returns mockChatClient
        every { mockInteraction.promptContributors } returns emptyList()
        every { mockLlm.promptContributors } returns emptyList()
        every { mockLlm.optionsConverter } returns mockk(relaxed = true)
        every { mockLlm.optionsConverter.convertOptions(any()) } returns mockk(relaxed = true)
        every { mockInteraction.llm } returns mockk(relaxed = true)
        every { mockInteraction.toolCallbacks } returns emptyList()
        every { mockChatClientLlmOperations.objectMapper } returns jacksonObjectMapper()
        every { mockInteraction.propertyFilter } returns { true }

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
        streamingOperations.createObjectStreamWithThinking(
            messages,
            mockInteraction,
            outputClass,
            mockAgentProcess,
            mockAction
        )

        // Then
        verify { mockChatClientLlmOperations.getLlm(mockInteraction) }
    }

    @Test
    fun `should delegate createChatClient to ChatClientLlmOperations on createObjectStreamWithThinking`() {
        // Given
        val messages = listOf(UserMessage("Create objects with thinking"))
        val outputClass = TestItem::class.java

        // When
        streamingOperations.createObjectStreamWithThinking(
            messages,
            mockInteraction,
            outputClass,
            mockAgentProcess,
            mockAction
        )

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
        val result =
            streamingOperations.createObjectStream(messages, mockInteraction, outputClass, mockAgentProcess, mockAction)

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
        val result = streamingOperations.createObjectStreamWithThinking(
            messages,
            mockInteraction,
            outputClass,
            mockAgentProcess,
            mockAction
        )

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
        val result = streamingOperations.createObjectStreamIfPossible(
            messages,
            mockInteraction,
            outputClass,
            mockAgentProcess,
            mockAction
        )

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

    @Test
    fun `should handle single complete chunk`() {
        // Given: Single chunk with thinking content
        val chunkFlux = Flux.just("<think>This is thinking content</think>\n")
        mockChatClientForStreaming(chunkFlux)

        // When
        val result = streamingOperations.createObjectStreamWithThinking(
            messages = listOf(UserMessage("test")),
            interaction = mockInteraction,
            outputClass = TestItem::class.java,
            agentProcess = mockAgentProcess,
            action = mockAction
        )


        // Then: Should emit one thinking event for the complete line
        StepVerifier.create(result)
            .expectNextMatches {
                it.isThinking() && it.getThinking() == "This is thinking content"
            }
            .expectComplete()
            .verify(Duration.ofSeconds(1))
    }

    @Test
    fun `should handle multi-chunk JSONL object stream`() {
        // Given: Multiple chunks forming JSONL objects
        val chunkFlux = Flux.just(
            "{\"name\":\"Item1\",\"value\":",
            "42}\n{\"name\":\"Item2\",",
            "\"value\":84}\n"
        )
        mockChatClientForStreaming(chunkFlux)

        // When
        val result = streamingOperations.createObjectStreamWithThinking(
            messages = listOf(UserMessage("test")),
            interaction = mockInteraction,
            outputClass = TestItem::class.java,
            agentProcess = mockAgentProcess,
            action = mockAction
        )

        // Then: Should emit two object events
        StepVerifier.create(result)
            .expectNextMatches {
                it.isObject() && it.getObject()?.name == "Item1" && it.getObject()?.value == 42
            }
            .expectNextMatches {
                it.isObject() && it.getObject()?.name == "Item2" && it.getObject()?.value == 84
            }
            .expectComplete()
            .verify(Duration.ofSeconds(1))
    }

    @Test
    fun `should handle mixed thinking and object content in chunks`() {
        // Given: Realistic chunking that splits thinking and JSON across chunk boundaries
        val chunkFlux = Flux.just(
            "<think>Ana",                  // Partial thinking start
            "lyzing req",                          // Partial thinking middle
            "uirement</think>\n{\"name\":",        // Thinking end + partial JSON
            "\"TestItem\",\"va",                   // Partial JSON middle
            "lue\":123}\n<think>Done",             // JSON end + partial thinking
            "</think>\n"                           // Thinking end
        )
        mockChatClientForStreaming(chunkFlux)

        // When
        val result = streamingOperations.createObjectStreamWithThinking(
            messages = listOf(UserMessage("test")),
            interaction = mockInteraction,
            outputClass = TestItem::class.java,
            agentProcess = mockAgentProcess,
            action = mockAction
        )

        // Then: Should emit thinking, object, thinking in correct order
        StepVerifier.create(result)
            .expectNextMatches {
                it.isThinking() && it.getThinking() == "Analyzing requirement"
            }
            .expectNextMatches {
                it.isObject() && it.getObject()?.name == "TestItem" && it.getObject()?.value == 123
            }
            .expectNextMatches {
                it.isThinking() && it.getThinking() == "Done"
            }
            .expectComplete()
            .verify(Duration.ofSeconds(1))
    }

    @Test
    fun `should handle real streaming with reactive callbacks`() {
        // Given: Mixed content with multiple events
        val chunkFlux = Flux.just(
            "<think>Processing request</think>\n",
            "{\"name\":\"Item1\",\"value\":100}\n",
            "{\"name\":\"Item2\",\"value\":200}\n",
            "<think>Request completed</think>\n"
        )
        mockChatClientForStreaming(chunkFlux)

        // When: Subscribe with real reactive callbacks
        val receivedEvents = mutableListOf<String>()
        var errorOccurred: Throwable? = null
        var completionCalled = false

        val result = streamingOperations.createObjectStreamWithThinking(
            messages = listOf(UserMessage("test")),
            interaction = mockInteraction,
            outputClass = TestItem::class.java,
            agentProcess = mockAgentProcess,
            action = mockAction
        )

        result
            .doOnNext { event ->
                when {
                    event.isThinking() -> {
                        val content = event.getThinking()!!
                        receivedEvents.add("THINKING: $content")
                        logger.info("Received thinking: {}", content)
                    }

                    event.isObject() -> {
                        val obj = event.getObject()!!
                        receivedEvents.add("OBJECT: ${obj.name}=${obj.value}")
                        logger.info("Received object: {}={}", obj.name, obj.value)
                    }
                }
            }
            .doOnError { error ->
                errorOccurred = error
                logger.error("Stream error: {}", error.message)
            }
            .doOnComplete {
                completionCalled = true
                logger.info("Stream completed successfully")
            }
            .subscribe()

        // Give stream time to complete
        Thread.sleep(500)

        // Then: Verify real reactive behavior
        assertNull(errorOccurred, "No errors should occur")
        assertTrue(completionCalled, "Stream should complete successfully")
        assertEquals(4, receivedEvents.size, "Should receive all events")
        assertEquals("THINKING: Processing request", receivedEvents[0])
        assertEquals("OBJECT: Item1=100", receivedEvents[1])
        assertEquals("OBJECT: Item2=200", receivedEvents[2])
        assertEquals("THINKING: Request completed", receivedEvents[3])
    }

    @Test
    fun `rawChunksToLines should handle single line chunks`() {
        val chunks = Flux.just("line1\n", "line2\n")

        val result = streamingOperations.rawChunksToLines(chunks)

        StepVerifier.create(result)
            .expectNext("line1")
            .expectNext("line2")
            .verifyComplete()
    }

    @Test
    fun `rawChunksToLines should handle multi-line chunks from Anthropic`() {
        val chunks = Flux.just(".\n</think>\n\n{\"")

        val result = streamingOperations.rawChunksToLines(chunks)

        StepVerifier.create(result)
            .expectNext(".")
            .expectNext("</think>")
            .expectNext("{\"")
            .verifyComplete()
    }

    @Test
    fun `rawChunksToLines should handle incomplete lines across chunks`() {
        val chunks = Flux.just("partial", " line\n", "complete\n")

        val result = streamingOperations.rawChunksToLines(chunks)

        StepVerifier.create(result)
            .expectNext("partial line")
            .expectNext("complete")
            .verifyComplete()
    }

    @Test
    fun `rawChunksToLines should emit final incomplete line`() {
        val chunks = Flux.just("line1\n", "incomplete")

        val result = streamingOperations.rawChunksToLines(chunks)

        StepVerifier.create(result)
            .expectNext("line1")
            .expectNext("incomplete")
            .verifyComplete()
    }


    private fun mockChatClientForStreaming(chunkFlux: Flux<String>) {
        val mockRequestSpec = mockk<ChatClient.ChatClientRequestSpec>(relaxed = true)
        val mockContentStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)

        every { mockChatClient.prompt(any<Prompt>()) } returns mockRequestSpec
        every { mockRequestSpec.toolCallbacks(any<List<ToolCallback>>()) } returns mockRequestSpec
        every { mockRequestSpec.options(any()) } returns mockRequestSpec
        every { mockRequestSpec.stream() } returns mockContentStreamSpec
        every { mockContentStreamSpec.content() } returns chunkFlux

    }
}
