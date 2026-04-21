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
import com.embabel.agent.spi.ToolDecorator
import com.embabel.agent.spi.loop.streaming.LlmMessageStreamer
import com.embabel.agent.spi.support.streaming.StreamingLlmOperationsImpl
import com.embabel.agent.spi.validation.DefaultValidationPromptGenerator
import com.embabel.chat.Message
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.ai.model.ModelSelectionCriteria
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.validation.Validation
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertIs

/**
 * Tests for [AbstractLlmOperations.createStreamingOperations].
 */
class AbstractLlmOperationsStreamingTest {

    private lateinit var mockModelProvider: ModelProvider
    private lateinit var mockToolDecorator: ToolDecorator
    private lateinit var mockLlmService: LlmService<*>
    private lateinit var mockMessageStreamer: LlmMessageStreamer
    private lateinit var mockAsyncer: Asyncer
    private val objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
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
    fun `createStreamingOperations calls createMessageStreamer on LlmService`() {
        val options = LlmOptions()

        llmOperations.createStreamingOperations(options)

        verify { mockLlmService.createMessageStreamer(options) }
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
            throw UnsupportedOperationException("Not needed for streaming tests")
        }

        override fun <O> doTransform(
            messages: List<Message>,
            interaction: LlmInteraction,
            outputClass: Class<O>,
            llmRequestEvent: LlmRequestEvent<O>?,
        ): O {
            throw UnsupportedOperationException("Not needed for streaming tests")
        }

        override fun <O> doTransformWithThinking(
            messages: List<Message>,
            interaction: LlmInteraction,
            outputClass: Class<O>,
            llmRequestEvent: LlmRequestEvent<O>?,
        ): com.embabel.common.core.thinking.ThinkingResponse<O> {
            throw UnsupportedOperationException("Not needed for streaming tests")
        }

        override fun <O> doTransformWithThinkingIfPossible(
            messages: List<Message>,
            interaction: LlmInteraction,
            outputClass: Class<O>,
            llmRequestEvent: LlmRequestEvent<O>?,
        ): Result<com.embabel.common.core.thinking.ThinkingResponse<O>> {
            throw UnsupportedOperationException("Not needed for streaming tests")
        }
    }
}
