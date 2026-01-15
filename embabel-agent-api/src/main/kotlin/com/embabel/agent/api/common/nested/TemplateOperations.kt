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
package com.embabel.agent.api.common.nested

import com.embabel.chat.AssistantMessage
import com.embabel.chat.Conversation

/**
 * Llm operations based on a compiled template.
 * Similar to [com.embabel.agent.api.common.PromptRunnerOperations], but taking a model instead of a template string.
 */
interface TemplateOperations {

    /**
     * Create an object of the given type using the given model to render the template
     * and LLM options from context
     */
    fun <T> createObject(
        outputClass: Class<T>,
        model: Map<String, Any>,
    ): T

    /**
     * Generate text using the given model to render the template
     * and LLM options from context
     */
    fun generateText(
        model: Map<String, Any>,
    ): String

    /**
     * Respond in the conversation using the rendered template as system prompt.
     * @param conversation the conversation so far
     * @param model the model to render the system prompt template with.
     * Defaults to the empty map (which is appropriate for static templates)
     */
    fun respondWithSystemPrompt(
        conversation: Conversation,
        model: Map<String, Any> = emptyMap(),
    ): AssistantMessage
}
