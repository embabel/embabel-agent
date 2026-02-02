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

import com.embabel.agent.core.LlmVerbosity
import com.embabel.agent.core.ProcessOptions
import com.embabel.chat.ConversationFactory
import com.embabel.chat.ConversationFactoryProvider
import com.embabel.chat.ConversationStoreType
import com.embabel.common.ai.model.*

typealias Embedding = FloatArray


/**
 * Gateway to AI functionality in the context of an operation.
 * This includes both LLM and embedding models.
 */
interface Ai {

    /**
     * Return an embedding service with the given name
     */
    fun withEmbeddingService(model: String): EmbeddingService =
        withEmbeddingService(ModelSelectionCriteria.byName(model))

    /**
     * Return an embedding service matching the given criteria.
     */
    fun withEmbeddingService(criteria: ModelSelectionCriteria): EmbeddingService

    fun withDefaultEmbeddingService(): EmbeddingService =
        withEmbeddingService(DefaultModelSelectionCriteria)

    /**
     * Get a configurable PromptRunner for this context using
     * the given LLM. Allows full control over LLM options.
     */
    fun withLlm(llm: LlmOptions): PromptRunner

    /**
     * Get a configurable PromptRunner for this context choosing
     * the given model by name and the default LLM options.
     * Does not allow for any other LLM options to be set.
     */
    fun withLlm(model: String): PromptRunner {
        return withLlm(LlmOptions(model = model))
    }

    /**
     * Get a configurable PromptRunner for this context choosing
     * the given model by role and the default LLM options.
     * Does not allow for any other LLM options to be set.
     * Users must configure roles, for example in application.properties.
     */
    fun withLlmByRole(role: String): PromptRunner {
        return withLlm(LlmOptions(criteria = ModelSelectionCriteria.byRole(role)))
    }

    /**
     * Get a configurable PromptRunner for this context using
     * automatic model selection criteria. This may consider prompt
     * and tools, so is not the same as default.
     */
    fun withAutoLlm(): PromptRunner {
        return withLlm(LlmOptions(criteria = AutoModelSelectionCriteria))
    }

    /**
     * Get a configurable PromptRunner for this context using
     * the default model selection criteria.
     */
    fun withDefaultLlm(): PromptRunner {
        return withLlm(LlmOptions(criteria = DefaultModelSelectionCriteria))
    }

    fun withFirstAvailableLlmOf(vararg llms: String): PromptRunner {
        return withLlm(LlmOptions(criteria = FallbackByNameModelSelectionCriteria(llms.toList())))
    }

    /**
     * Get a conversation factory for the given store type.
     *
     * Requires `embabel-chat-store` on the classpath. Without it,
     * this method throws [org.springframework.beans.factory.NoSuchBeanDefinitionException].
     *
     * @param type the conversation store type
     * @return the factory for that type
     * @throws IllegalArgumentException if no factory is registered for the type
     */
    fun conversationFactory(type: ConversationStoreType): ConversationFactory

    /**
     * Get a conversation factory provider for resolving factories by type.
     *
     * Requires `embabel-chat-store` on the classpath. Without it,
     * this method throws [org.springframework.beans.factory.NoSuchBeanDefinitionException].
     */
    fun conversationFactoryProvider(): ConversationFactoryProvider
}

/**
 * Builder that can be injected into components
 * to obtain Ai instances.
 * Use when you want custom configuration.
 */
interface AiBuilder : LlmVerbosity {

    /**
     * Build an Ai instance according to the configuration.
     */
    fun ai(): Ai

    fun withProcessOptions(options: ProcessOptions): AiBuilder

    fun withShowPrompts(show: Boolean): AiBuilder

    fun withShowLlmResponses(show: Boolean): AiBuilder
}
