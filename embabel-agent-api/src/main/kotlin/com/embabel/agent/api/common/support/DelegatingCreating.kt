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

import com.embabel.agent.api.common.CreationExample
import com.embabel.agent.api.common.PromptRunner
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.textio.template.TemplateRenderer
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.function.Predicate

/**
 * Implementation of [com.embabel.agent.api.common.PromptRunner.Creating] that delegates to a [PromptExecutionDelegate].
 */
internal data class DelegatingCreating<T>(
    internal val delegate: PromptExecutionDelegate,
    internal val outputClass: Class<T>,
) : PromptRunner.Creating<T> {

    private val objectMapper: ObjectMapper = delegate.objectMapper

    private val templateRenderer: TemplateRenderer = delegate.templateRenderer

    override fun withExample(
        example: CreationExample<T>,
    ): PromptRunner.Creating<T> {
        return copy(
            delegate = delegate
                .withGenerateExamples(false)
                .withPromptContributors(
                    listOf(
                        PromptContributor.fixed(
                            """
                            Example: ${example.description}
                            ${objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(example.value)}
                            """.trimIndent()
                        )
                    )
                )
        )
    }

    override fun withPropertyFilter(
        filter: Predicate<String>
    ): PromptRunner.Creating<T> = copy(
        delegate = delegate.withPropertyFilter(filter)
    )

    override fun withValidation(
        validation: Boolean
    ): PromptRunner.Creating<T> = copy(
        delegate = delegate.withValidation(validation)
    )

    override fun fromMessages(
        messages: List<Message>,
    ): T = delegate.createObject(
        messages = messages,
        outputClass = outputClass,
    )

    override fun fromTemplate(
        templateName: String,
        model: Map<String, Any>,
    ): T {
        val compiledTemplate = templateRenderer.compileLoadedTemplate(templateName)
        val prompt = compiledTemplate.render(model = model)
        return delegate.createObject(
            messages = listOf(UserMessage(prompt)),
            outputClass = outputClass,
        )
    }
}
