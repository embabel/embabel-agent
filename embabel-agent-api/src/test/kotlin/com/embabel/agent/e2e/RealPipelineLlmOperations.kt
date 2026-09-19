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
package com.embabel.agent.e2e

import com.embabel.agent.api.common.Asyncer
import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.AutoLlmSelectionCriteriaResolver
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.support.AbstractLlmOperations
import com.embabel.agent.spi.support.DefaultToolDecorator
import com.embabel.agent.spi.support.LlmDataBindingProperties
import com.embabel.chat.Message
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.ai.model.ModelSelectionCriteria
import com.embabel.common.core.thinking.ThinkingResponse
import com.embabel.common.util.DummyInstanceCreator
import io.mockk.every
import io.mockk.mockk
import jakarta.validation.Validation
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.concurrent.CompletableFuture

/**
 * Fake LLM that keeps the real [AbstractLlmOperations] pipeline and replaces only the model.
 *
 * [com.embabel.agent.test.integration.DummyObjectCreatingLlmOperations] implements
 * [com.embabel.agent.core.internal.LlmOperations] directly, so it bypasses
 * [AbstractLlmOperations] entirely - including the single place where published tool names are
 * resolved and where [LlmRequestEvent] is emitted. A test that fakes at that level cannot observe
 * tool naming at all. Faking at the model boundary instead leaves tool resolution, naming,
 * decoration and event emission running as they do in production.
 */
class RealPipelineLlmOperations : AbstractLlmOperations(
    toolDecorator = DefaultToolDecorator(),
    modelProvider = fakeModelProvider(),
    validator = Validation.buildDefaultValidatorFactory().validator,
    autoLlmSelectionCriteriaResolver = AutoLlmSelectionCriteriaResolver.DEFAULT,
    dataBindingProperties = LlmDataBindingProperties(),
    asyncer = SameThreadAsyncer,
    objectMapper = jacksonObjectMapper(),
) {

    private val instanceCreator = DummyInstanceCreator(listOf("lorem", "ipsum"))

    override fun <O> doTransform(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?,
    ): O = dummy(outputClass)

    override fun <O> doTransformIfPossible(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>,
    ): Result<O> = Result.success(dummy(outputClass))

    override fun <O> doTransformWithThinking(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?,
    ): ThinkingResponse<O> = ThinkingResponse(dummy(outputClass), emptyList())

    override fun <O> doTransformWithThinkingIfPossible(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?,
    ): Result<ThinkingResponse<O>> = Result.success(
        ThinkingResponse(dummy(outputClass), emptyList())
    )

    @Suppress("UNCHECKED_CAST")
    private fun <O> dummy(outputClass: Class<O>): O = instanceCreator.createDummyInstance(outputClass) as O

    private object SameThreadAsyncer : Asyncer {
        override fun <T> async(block: () -> T): CompletableFuture<T> =
            CompletableFuture.completedFuture(block())

        override fun <T, R> parallelMap(items: Collection<T>, maxConcurrency: Int, transform: (T) -> R): List<R> =
            items.map(transform)
    }

    private companion object {

        fun fakeModelProvider(): ModelProvider {
            val llmService = mockk<LlmService<*>>(relaxed = true)
            every { llmService.name } returns "test-llm"
            every { llmService.provider } returns "test-provider"
            val modelProvider = mockk<ModelProvider>(relaxed = true)
            every { modelProvider.getLlm(any<ModelSelectionCriteria>()) } returns llmService
            return modelProvider
        }
    }
}
