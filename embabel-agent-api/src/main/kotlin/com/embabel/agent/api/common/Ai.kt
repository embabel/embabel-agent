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
package com.embabel.agent.api.common

import com.embabel.agent.rag.RagService
import com.embabel.common.ai.model.*
import org.springframework.ai.embedding.EmbeddingModel

typealias Embedding = FloatArray


/**
 * Gateway to AI functionality in the context of an operation.
 * This includes both LLM and embedding models.
 */
interface Ai {

    /**
     * Return an embedding model with the given name
     */
    fun withEmbeddingModel(model: String): EmbeddingModel =
        withEmbeddingModel(ModelSelectionCriteria.byName(model))

    /**
     * Return an embedding model matching the given criteria.
     */
    fun withEmbeddingModel(criteria: ModelSelectionCriteria): EmbeddingModel

    fun withDefaultEmbeddingModel(): EmbeddingModel =
        withEmbeddingModel(DefaultModelSelectionCriteria)

    /**
     * Return the RagService
     */
    fun rag(): RagService

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
}
