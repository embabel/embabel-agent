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
package com.embabel.agent.spi.support

import com.embabel.agent.api.common.Asyncer
import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.AutoLlmSelectionCriteriaResolver
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.StreamingChatCompletion
import com.embabel.agent.spi.ToolDecorator
import com.embabel.agent.spi.loop.streaming.LlmMessageStreamer
import com.embabel.agent.spi.support.streaming.StreamingLlmOperationsImpl
import com.embabel.agent.spi.validation.DefaultValidationPromptGenerator
import com.embabel.chat.Message
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.ai.model.ModelSelectionCriteria
import com.embabel.common.core.thinking.ThinkingBlock
import com.embabel.common.core.thinking.ThinkingException
import com.embabel.common.core.thinking.ThinkingOrigin
import com.embabel.common.core.thinking.ThinkingTagType
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.validation.Validation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CompletableFuture
import kotlin.test.assertIs

/**
 * Tests for [AbstractLlmOperations]: streaming operations and [AbstractLlmOperations.executeWithTimeout] behaviour.
 */
class AbstractLlmOperationsStreamingTest {

    /** An LLM service that can stream, for mocking. */
    private interface StreamingLlm : LlmService<StreamingLlm>, StreamingChatCompletion

    private lateinit var mockModelProvider: ModelProvider
    private lateinit var mockToolDecorator: ToolDecorator
    private lateinit var mockLlmService: StreamingLlm
    private lateinit var mockMessageStreamer: LlmMessageStreamer
    private lateinit var mockAsyncer: Asyncer
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private val validator = Validation.buildDefaultValidatorFactory().validator

    private lateinit var llmOperations: TestableAbstractLlmOperations

    @BeforeEach
    fun setup() {
        mockModelProvider = mockk()
        mockToolDecorator = mockk()
        mockLlmService = mockk()
        mockMessageStreamer = mockk()
        mockAsyncer = mockk()

        every { mockModelProvider.getLlm(any<ModelSelectionCriteria>()) } returns mockLlmService
        every { mockLlmService.createMessageStreamer(any()) } returns mockMessageStreamer

        llmOperations = TestableAbstractLlmOperations(
            toolDecorator = mockToolDecorator,
            modelProvider = mockModelProvider,
            validator = validator,
            objectMapper = objectMapper,
            asyncer = mockAsyncer,
        )
    }

    @Test
    fun `createStreamingOperations returns StreamingLlmOperationsImpl`() {
        val options = LlmOptions()

        val result = llmOperations.createStreamingOperations(options)

        assertIs<StreamingLlmOperationsImpl>(result)
    }

    @Test
    fun `createStreamingOperations calls chooseLlm with correct options`() {
        val options = LlmOptions()

        llmOperations.createStreamingOperations(options)

        verify { mockModelProvider.getLlm(any<ModelSelectionCriteria>()) }
    }

    @Test
    fun `createStreamingOperations asks the model's streaming capability for a streamer`() {
        val options = LlmOptions()

        llmOperations.createStreamingOperations(options)

        verify { mockLlmService.createMessageStreamer(options) }
    }

    @Test
    fun `createStreamingOperations fails and names the model when the model cannot stream`() {
        val textOnly = mockk<LlmService<*>>()
        every { textOnly.name } returns "text-only"
        every { textOnly.provider } returns "test"
        every { mockModelProvider.getLlm(any<ModelSelectionCriteria>()) } returns textOnly

        val failure = assertThrows<UnsupportedOperationException> {
            llmOperations.createStreamingOperations(LlmOptions())
        }

        assertTrue(failure.message.orEmpty().contains("'text-only'"), failure.message)
    }

    @Test
    fun `supportsStreaming is false for a model that implements streaming but says it cannot stream`() {
        // The mock implements StreamingChatCompletion, but the provider says this model does not stream.
        every { mockLlmService.supportsStreaming() } returns false

        assertFalse(llmOperations.supportsStreaming(LlmOptions()))
    }

    @Test
    fun `supportsStreaming is false for a model that says it streams but does not implement streaming`() {
        val textOnly = mockk<LlmService<*>>()
        // The model says it streams, but the mock does not implement StreamingChatCompletion.
        every { textOnly.supportsStreaming() } returns true
        every { mockModelProvider.getLlm(any<ModelSelectionCriteria>()) } returns textOnly

        assertFalse(llmOperations.supportsStreaming(LlmOptions()))
    }

    /**
     * Testable concrete implementation of AbstractLlmOperations.
     */
    private class TestableAbstractLlmOperations(
        toolDecorator: ToolDecorator,
        modelProvider: ModelProvider,
        validator: jakarta.validation.Validator,
        objectMapper: ObjectMapper,
        asyncer: Asyncer,
    ) : AbstractLlmOperations(
        toolDecorator = toolDecorator,
        modelProvider = modelProvider,
        validator = validator,
        validationPromptGenerator = DefaultValidationPromptGenerator(),
        autoLlmSelectionCriteriaResolver = AutoLlmSelectionCriteriaResolver.DEFAULT,
        dataBindingProperties = LlmDataBindingProperties(),
        promptsProperties = LlmOperationsPromptsProperties(),
        asyncer = asyncer,
        objectMapper = objectMapper,
    ) {
        override fun <O> doTransformIfPossible(
            messages: List<Message>,
            interaction: LlmInteraction,
            outputClass: Class<O>,
            llmRequestEvent: LlmRequestEvent<O>,
        ): Result<O> {
            throw UnsupportedOperationException("Not needed for these tests")
        }

        override fun <O> doTransform(
            messages: List<Message>,
            interaction: LlmInteraction,
            outputClass: Class<O>,
            llmRequestEvent: LlmRequestEvent<O>?,
        ): O {
            throw UnsupportedOperationException("Not needed for these tests")
        }

        override fun <O> doTransformWithThinking(
            messages: List<Message>,
            interaction: LlmInteraction,
            outputClass: Class<O>,
            llmRequestEvent: LlmRequestEvent<O>?,
        ): com.embabel.common.core.thinking.ThinkingResponse<O> {
            throw UnsupportedOperationException("Not needed for these tests")
        }

        override fun <O> doTransformWithThinkingIfPossible(
            messages: List<Message>,
            interaction: LlmInteraction,
            outputClass: Class<O>,
            llmRequestEvent: LlmRequestEvent<O>?,
        ): Result<com.embabel.common.core.thinking.ThinkingResponse<O>> {
            throw UnsupportedOperationException("Not needed for these tests")
        }

        fun <T> executeWithTimeoutPublic(interactionId: String, llmOptions: LlmOptions, operation: () -> T): T =
            executeWithTimeout(interactionId, llmOptions, operation = operation)
    }

    @Nested
    inner class ExecuteWithTimeoutThinkingExceptionTest {

        @Test
        fun `ThinkingException propagates unwrapped from executeWithTimeout, blocks preserved`() {
            val blocks = listOf(
                ThinkingBlock(
                    content = "native thought",
                    tagType = ThinkingTagType.NO_PREFIX,
                    tagValue = "",
                    origin = ThinkingOrigin.NATIVE,
                )
            )
            val original = ThinkingException("Conversion failed", blocks)
            val failed = CompletableFuture.failedFuture<String>(original)
            every { mockAsyncer.async<String>(any()) } returns failed

            val thrown = assertThrows<ThinkingException> {
                llmOperations.executeWithTimeoutPublic("id", LlmOptions()) { "unused" }
            }
            assertEquals(original, thrown)
            assertEquals(blocks, thrown.thinkingBlocks)
        }

        @Test
        fun `plain Exception is wrapped in RuntimeException by executeWithTimeout`() {
            val failed = CompletableFuture.failedFuture<String>(Exception("plain failure"))
            every { mockAsyncer.async<String>(any()) } returns failed

            assertThrows<RuntimeException> {
                llmOperations.executeWithTimeoutPublic("id", LlmOptions()) { "unused" }
            }
        }
    }
}
