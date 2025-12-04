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
package com.embabel.agent.e2e

import com.embabel.agent.AgentApiTestApplication
import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.common.streaming.StreamingPromptRunnerOperations
import com.embabel.agent.api.common.streaming.asStreaming
import com.embabel.agent.api.common.support.streaming.StreamingCapabilityDetector
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.spi.LlmOperations
import com.embabel.agent.spi.ToolDecorator
import com.embabel.agent.spi.support.FakeChatModel
import com.embabel.agent.spi.support.springai.ChatClientLlmOperations
import com.embabel.common.ai.model.DefaultOptionsConverter
import com.embabel.common.ai.model.Llm
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.ai.model.PricingModel
import com.embabel.common.textio.template.TemplateRenderer
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.validation.Validator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.DefaultChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.tool.annotation.Tool
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.test.context.ActiveProfiles
import reactor.core.publisher.Flux


/**
 * Fake ChatModel that supports streaming by returning a Flux with actual content
 */
class FakeStreamingChatModel(
    private val response: String,
    private val options: ChatOptions = DefaultChatOptions(),
) : ChatModel {

    override fun getDefaultOptions(): ChatOptions = options

    override fun call(prompt: Prompt): ChatResponse {
        return ChatResponse(
            listOf(Generation(AssistantMessage(response)))
        )
    }

    override fun stream(prompt: Prompt): Flux<ChatResponse> {
        return Flux.just(
            ChatResponse(listOf(Generation(AssistantMessage("$response\n"))))
        )
    }
}



@TestConfiguration
@Profile("streaming-test")
class StreamingTestConfig {

    companion object {
        const val NON_STREAMING_MODEL_NAME = "non-streaming-test-llm"
        const val STREAMING_MODEL_NAME = "streaming-test-llm"
        const val TEST_PROVIDER = "test"
        const val NON_STREAMING_RESPONSE = "fake non-streaming response"
        const val STREAMING_RESPONSE = "{\"name\": \"fake streaming response\"}"
        const val LOW_COST = 0.1
        const val HIGHER_COST = 0.2
    }

    @Bean
    fun nonStreamingTestLlm(): Llm {
        return Llm(
            name = NON_STREAMING_MODEL_NAME,
            model = FakeChatModel(NON_STREAMING_RESPONSE),
            pricingModel = PricingModel.usdPer1MTokens(LOW_COST, LOW_COST),
            provider = TEST_PROVIDER,
            optionsConverter = DefaultOptionsConverter,
        )
    }

    @Bean
    fun streamingTestLlm(): Llm {
        return Llm(
            name = STREAMING_MODEL_NAME,
            model = FakeStreamingChatModel(STREAMING_RESPONSE),
            pricingModel = PricingModel.usdPer1MTokens(HIGHER_COST, HIGHER_COST),
            provider = TEST_PROVIDER,
            optionsConverter = DefaultOptionsConverter,
        )
    }



    @Bean
    @Primary
    fun llmOperations(
        modelProvider: ModelProvider,
        toolDecorator: ToolDecorator,
        validator: Validator,
        templateRenderer: TemplateRenderer,
        objectMapper: ObjectMapper,
    ): LlmOperations {
        return ChatClientLlmOperations(
            modelProvider = modelProvider,
            toolDecorator = toolDecorator,
            validator = validator,
            templateRenderer = templateRenderer,
            objectMapper = objectMapper,
        )
    }
}

data class SimpleItem(val name: String)


/**
 * Simple tool for testing tool integration with streaming
 */
class SimpleTool {
    private var wasInvokedFlag = false

    @Tool(description = "Simple test tool that greets a person")
    fun greet(name: String): String {
        wasInvokedFlag = true
        return "Hello $name"
    }

    fun wasInvoked(): Boolean = wasInvokedFlag
}

@SpringBootTest(
    classes = [AgentApiTestApplication::class],
    properties = [
        "embabel.models.llms.cheapest=${StreamingTestConfig.NON_STREAMING_MODEL_NAME}",
        "embabel.models.llms.fastest=${StreamingTestConfig.STREAMING_MODEL_NAME}",
        "embabel.models.default-llm=${StreamingTestConfig.NON_STREAMING_MODEL_NAME}",
        "spring.main.allow-bean-definition-overriding=true"
    ]
)
@ActiveProfiles("test", "streaming-test")
@Import(StreamingTestConfig::class)
class LLMStreamingIntegrationTest(
    @param: Autowired private val autonomy: Autonomy,
    @param: Autowired private val ai: Ai,
) {

    private val logger = LoggerFactory.getLogger(LLMStreamingIntegrationTest::class.java)
    private val agentPlatform: AgentPlatform = autonomy.agentPlatform

    @Test
    fun `test streaming capability detection`() {
        // Direct test of StreamingCapabilityDetector
        val fakeStreamingModel = FakeStreamingChatModel("test response")
        val detector = StreamingCapabilityDetector
        val supportsStreaming = detector.supportsStreaming(fakeStreamingModel)

        assertTrue(supportsStreaming, "FakeStreamingChatModel should be detected as supporting streaming")

        // Now test the full workflow
        val runner = ai.withLlmByRole("fastest")  // Should get streaming-test-llm

        assertTrue(runner.supportsStreaming(), "Streaming model should support streaming")
    }

    @Test
    fun `normal streaming workflow when model supports streaming`() {
        val runner = ai.withLlmByRole("fastest")  // Should get streaming-test-llm

        assertTrue(runner.supportsStreaming(), "Streaming model should support streaming")

        val streamingOperations = runner.stream()
        assertNotNull(streamingOperations, "stream() should return StreamingOperations")

        when (streamingOperations) {
            is StreamingPromptRunnerOperations -> {
                val results = streamingOperations
                    .withPrompt("Test streaming")
                    .createObjectStream(SimpleItem::class.java)
                val firstResult = results.blockFirst()
                assertNotNull(firstResult, "Should receive streaming result")
            }
            else -> {
                assertTrue(false, "StreamingOperations should be castable to StreamingPromptRunnerOperations")
            }
        }
    }

    @Test
    fun `non-streaming model should not support streaming`() {
        // Step 1: Test basic non-streaming model with established pattern
        val runner = ai.withLlmByRole("cheapest")  // Should get non-streaming-test-llm
        assertFalse(runner.supportsStreaming(), "Non-streaming model should not support streaming")
    }

    @Test
    fun `throws exception when streaming called on non-streaming model`() {
        val runner = ai.withLlmByRole("cheapest")

        val exception = assertThrows(UnsupportedOperationException::class.java) {
            runner.stream()
        }

        assertTrue(
            exception.message?.contains("Streaming not supported") == true,
            "Should provide helpful error message"
        )
    }

    @Test
    fun `streaming with tool objects - verifies tool registration works with streaming`() {
        // Create simple tool for testing
        val simpleTool = SimpleTool()

        // Test: withToolObject doesn't break streaming
        val runner = ai.withLlmByRole("fastest")
            .withToolObject(simpleTool)

        assertTrue(runner.supportsStreaming(), "Runner with tools should support streaming")

        // Verify streaming works with tools registered
        val streamingOperations = runner.stream()
        assertNotNull(streamingOperations, "stream() should work with tools present")

        when (streamingOperations) {
            is StreamingPromptRunnerOperations -> {
                val results = streamingOperations
                    .withPrompt("Test streaming")
                    .createObjectStream(SimpleItem::class.java)
                    .collectList()
                    .block()

                // Verify basic functionality preserved
                assertNotNull(results, "Should receive streaming results with tools present")
            }
            else -> {
                fail("StreamingOperations should be castable to StreamingPromptRunnerOperations")
            }
        }

        // Verify that withToolObject actually registered the tool
        assertEquals(1, runner.toolObjects.size, "Should have one tool object registered")
        assertEquals(simpleTool, runner.toolObjects[0].obj, "Tool object should be our SimpleTool instance")
    }

    @Test
    fun `streaming with tool objects using extension function - avoids casting`() {
        // Create simple tool for testing
        val simpleTool = SimpleTool()

        // Test: withToolObject with extension function approach
        val runner = ai.withLlmByRole("fastest")
            .withToolObject(simpleTool)

        assertTrue(runner.supportsStreaming(), "Runner with tools should support streaming")

        // Use extension function instead of manual casting
        val results = runner.asStreaming()
            .withPrompt("Test streaming")
            .createObjectStream(SimpleItem::class.java)
            .collectList()
            .block()

        // Verify functionality preserved
        assertNotNull(results, "Should receive streaming results with tools present")

        // Verify tool registration
        assertEquals(1, runner.toolObjects.size, "Should have one tool object registered")
        assertEquals(simpleTool, runner.toolObjects[0].obj, "Tool object should be our SimpleTool instance")
    }

    @Test
    fun `real streaming integration with reactive callbacks`() {
        // Given: Use the existing streaming test LLM (configured as "fastest")
        val runner = ai.withLlmByRole("fastest")
        assertTrue(runner.supportsStreaming(), "Test LLM should support streaming")

        // When: Subscribe with real reactive callbacks
        val receivedEvents = mutableListOf<String>()
        var errorOccurred: Throwable? = null
        var completionCalled = false

        val results = runner.asStreaming()
            .withPrompt("Test integration streaming")
            .createObjectStreamWithThinking(SimpleItem::class.java)

        results
            .doOnNext { event ->
                when {
                    event.isThinking() -> {
                        val content = event.getThinking()!!
                        receivedEvents.add("THINKING: $content")
                        logger.info("Integration test received thinking: {}", content)
                    }
                    event.isObject() -> {
                        val obj = event.getObject()!!
                        receivedEvents.add("OBJECT: ${obj.name}")
                        logger.info("Integration test received object: {}", obj.name)
                    }
                }
            }
            .doOnError { error ->
                errorOccurred = error
                logger.error("Integration test stream error: {}", error.message)
            }
            .doOnComplete {
                completionCalled = true
                logger.info("Integration test stream completed successfully")
            }
            .subscribe()

        // Give stream time to complete
        Thread.sleep(1000)

        // Then: Verify real integration streaming behavior
        assertNull(errorOccurred, "Integration streaming should not produce errors")
        assertTrue(completionCalled, "Integration stream should complete successfully")
        assertTrue(receivedEvents.size >= 1, "Should receive object events")

        // Verify we received object events (existing test LLM returns simple JSON)
        val objectEvents = receivedEvents.filter { it.startsWith("OBJECT:") }
        assertTrue(objectEvents.isNotEmpty(), "Should receive object events from integration streaming")

        logger.info("Integration streaming test completed successfully with {} total events", receivedEvents.size)
    }
}
