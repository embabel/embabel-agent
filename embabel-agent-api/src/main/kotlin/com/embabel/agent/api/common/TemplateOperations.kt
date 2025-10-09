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

import com.embabel.chat.AssistantMessage
import com.embabel.chat.Conversation
import com.embabel.chat.SystemMessage
import com.embabel.common.textio.template.TemplateRenderer

/**
 * Llm operations based on a compiled template.
 * Similar to [PromptRunnerOperations], but taking a model instead of a template string.
 * Template names will be resolved by the [com.embabel.common.textio.template.TemplateRenderer] provided.
 */
class TemplateOperations(
    templateName: String,
    templateRenderer: TemplateRenderer,
    private val promptRunnerOperations: PromptRunnerOperations,
) {

    private val compiledTemplate = templateRenderer.compileLoadedTemplate(templateName)

    /**
     * Create an object of the given type using the given model to render the template
     * and LLM options from context
     */
    fun <T> createObject(
        outputClass: Class<T>,
        model: Map<String, Any>,
    ): T = promptRunnerOperations.createObject(
        prompt = compiledTemplate.render(model = model),
        outputClass = outputClass,
    )

    /**
     * Generate text using the given model to render the template
     * and LLM options from context
     */
    fun generateText(
        model: Map<String, Any>,
    ): String = promptRunnerOperations.generateText(
        prompt = compiledTemplate.render(model = model),
    )

    /**
     * Respond in the conversation using the rendered template as system prompt.
     * @param conversation the conversation so far
     * @param model the model to render the system prompt template with.
     * Defaults to the empty map (which is appropriate for static templates)
     */
    @JvmOverloads
    fun respondWithSystemPrompt(
        conversation: Conversation,
        model: Map<String, Any> = emptyMap(),
    ): AssistantMessage = promptRunnerOperations.respond(
        messages = listOf(
            SystemMessage(
                content = compiledTemplate.render(model = model)
            )
        ) + conversation.messages,
    )
}
