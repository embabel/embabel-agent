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
package com.embabel.agent.api.common.support

import com.embabel.agent.api.common.nested.TemplateOperations
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Conversation
import com.embabel.chat.SystemMessage
import com.embabel.chat.UserMessage

/**
 * Implementation of [TemplateOperations] that delegates to a [PromptExecutionDelegate].
 */
internal data class DelegatingTemplateOperations(
    internal val delegate: PromptExecutionDelegate,
    internal val templateName: String,
) : TemplateOperations {

    private val templateRenderer = delegate.templateRenderer

    private val compiledTemplate = templateRenderer.compileLoadedTemplate(templateName)

    override fun <T> createObject(
        outputClass: Class<T>,
        model: Map<String, Any>,
    ): T {
        val prompt = compiledTemplate.render(model = model)
        return delegate.createObject(
            messages = listOf(UserMessage(prompt)),
            outputClass = outputClass,
        )
    }

    override fun generateText(
        model: Map<String, Any>,
    ): String {
        val prompt = compiledTemplate.render(model = model)
        return delegate.createObject(
            messages = listOf(UserMessage(prompt)),
            outputClass = String::class.java,
        )
    }

    override fun respondWithSystemPrompt(
        conversation: Conversation,
        model: Map<String, Any>,
    ): AssistantMessage = delegate.respond(
        messages = listOf(
            SystemMessage(
                content = compiledTemplate.render(model = model)
            )
        ) + conversation.messages,
    )
}
