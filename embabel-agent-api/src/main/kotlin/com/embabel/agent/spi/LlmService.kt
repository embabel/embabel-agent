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
package com.embabel.agent.spi

import com.embabel.agent.spi.loop.LlmMessageSender
import com.embabel.common.ai.model.LlmMetadata
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.ai.prompt.PromptContributorConsumer
import java.time.LocalDate

/**
 * Framework-agnostic LLM service abstraction, parallel to [com.embabel.common.ai.model.EmbeddingService].
 *
 * Implementations wrap specific LLM backends (Spring AI, direct API clients, etc.)
 * and provide a consistent interface for creating message senders.
 *
 * The type parameter THIS enables fluent builder-style methods that preserve
 * the concrete type through method chaining.
 *
 * @param THIS The concrete implementation type for fluent method chaining
 * @see com.embabel.agent.spi.support.springai.SpringAiLlmService for the Spring AI implementation
 * @see LlmMessageSender for the interface used to make LLM calls
 */
interface LlmService<THIS : LlmService<THIS>> : LlmMetadata, PromptContributorConsumer {

    /**
     * Create a message sender for this LLM configured with the given options.
     *
     * The message sender handles the actual LLM API calls but does NOT execute tools -
     * it returns the LLM's response including any tool call requests.
     *
     * @param options Configuration options for the LLM call (temperature, max tokens, etc.)
     * @return A message sender configured for this LLM
     */
    fun createMessageSender(options: LlmOptions): LlmMessageSender

    /**
     * Returns a copy of this LLM service with the specified knowledge cutoff date.
     *
     * @param date The knowledge cutoff date for the model
     * @return A new instance with the updated cutoff date
     */
    fun withKnowledgeCutoffDate(date: LocalDate): THIS

    /**
     * Returns a copy of this LLM service with an additional prompt contributor.
     *
     * @param promptContributor The prompt contributor to add
     * @return A new instance with the added contributor
     */
    fun withPromptContributor(promptContributor: PromptContributor): THIS
}
