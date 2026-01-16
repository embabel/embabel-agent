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

import com.embabel.agent.api.common.*
import com.embabel.agent.api.common.nested.ObjectCreator
import com.embabel.agent.api.common.nested.TemplateOperations
import com.embabel.agent.api.common.streaming.StreamingPromptRunner
import com.embabel.agent.api.common.streaming.StreamingPromptRunnerOperations
import com.embabel.agent.api.common.thinking.ThinkingPromptRunnerOperations
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.ToolGroup
import com.embabel.agent.core.ToolGroupRequirement
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Message
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.core.types.ZeroToOne

/**
 * Implementation of [StreamingPromptRunner] that delegates to a [PromptExecutionDelegate].
 */
internal data class DelegatingStreamingPromptRunner(
    internal val delegate: PromptExecutionDelegate,
) : StreamingPromptRunner {

    // Properties
    override val toolObjects: List<ToolObject>
        get() = delegate.toolObjects

    override val messages: List<Message>
        get() = delegate.messages

    override val images: List<AgentImage>
        get() = delegate.images

    override val llm: LlmOptions?
        get() = delegate.llm

    override val generateExamples: Boolean?
        get() = delegate.generateExamples

    override val propertyFilter: java.util.function.Predicate<String>
        get() = delegate.propertyFilter

    override val validation: Boolean
        get() = delegate.validation

    override val promptContributors: List<PromptContributor>
        get() = delegate.promptContributors

    override val toolGroups: Set<ToolGroupRequirement>
        get() = delegate.toolGroups

    // With-ers
    override fun withInteractionId(interactionId: InteractionId): PromptRunner =
        copy(delegate = delegate.withInteractionId(interactionId))

    override fun withLlm(llm: LlmOptions): PromptRunner =
        copy(delegate = delegate.withLlm(llm))

    override fun withMessages(messages: List<Message>): PromptRunner =
        copy(delegate = delegate.withMessages(messages))

    override fun withImages(images: List<AgentImage>): PromptRunner =
        copy(delegate = delegate.withImages(images))

    override fun withToolGroup(toolGroup: String): PromptRunner =
        copy(delegate = delegate.withToolGroup(ToolGroupRequirement(toolGroup)))

    override fun withToolGroup(toolGroup: ToolGroup): PromptRunner =
        copy(delegate = delegate.withToolGroup(toolGroup))

    override fun withToolGroup(toolGroup: ToolGroupRequirement): PromptRunner =
        copy(delegate = delegate.withToolGroup(toolGroup))

    override fun withToolObject(toolObject: ToolObject): PromptRunner =
        copy(delegate = delegate.withToolObject(toolObject))

    override fun withTool(tool: Tool): PromptRunner =
        copy(delegate = delegate.withTool(tool))

    override fun withHandoffs(vararg outputTypes: Class<*>): PromptRunner =
        copy(delegate = delegate.withHandoffs(*outputTypes))

    override fun withSubagents(vararg subagents: Subagent): PromptRunner =
        copy(delegate = delegate.withSubagents(*subagents))

    override fun withPromptContributors(promptContributors: List<PromptContributor>): PromptRunner =
        copy(delegate = delegate.withPromptContributors(promptContributors))

    override fun withContextualPromptContributors(
        contextualPromptContributors: List<ContextualPromptElement>,
    ): PromptRunner =
        copy(delegate = delegate.withContextualPromptContributors(contextualPromptContributors))

    override fun withGenerateExamples(generateExamples: Boolean): PromptRunner =
        copy(delegate = delegate.withGenerateExamples(generateExamples))

    @Deprecated("Use creating().withPropertyFilter() instead")
    override fun withPropertyFilter(filter: java.util.function.Predicate<String>): PromptRunner =
        copy(delegate = delegate.withPropertyFilter(filter))

    @Deprecated("Use creating().withValidation() instead")
    override fun withValidation(validation: Boolean): PromptRunner =
        copy(delegate = delegate.withValidation(validation))

    // Execution methods
    override fun <T> createObject(
        messages: List<Message>,
        outputClass: Class<T>,
    ): T = delegate.createObject(messages, outputClass)

    override fun <T> createObjectIfPossible(
        messages: List<Message>,
        outputClass: Class<T>,
    ): T? = delegate.createObjectIfPossible(messages, outputClass)

    override fun respond(
        messages: List<Message>,
    ): AssistantMessage = delegate.respond(messages)

    override fun evaluateCondition(
        condition: String,
        context: String,
        confidenceThreshold: ZeroToOne,
    ): Boolean = delegate.evaluateCondition(condition, context, confidenceThreshold)

    // Factory methods
    override fun <T> creating(outputClass: Class<T>): ObjectCreator<T> =
        DelegatingObjectCreator(
            delegate = delegate,
            outputClass = outputClass
        )

    override fun withTemplate(templateName: String): TemplateOperations =
        DelegatingTemplateOperations(
            delegate = delegate,
            templateName = templateName,
        )

    override fun supportsStreaming(): Boolean =
        delegate.supportsStreaming()

    override fun stream(): StreamingPromptRunnerOperations {
        if (!supportsStreaming()) {
            throw UnsupportedOperationException(
                """
                Streaming not supported by underlying LLM model.
                Model type: ${delegate.llmOperations::class.simpleName}.
                Check supportsStreaming() before calling stream().
                """.trimIndent()
            )
        }
        return DelegatingStreamingOperations(
            delegate = delegate,
        )
    }

    override fun supportsThinking(): Boolean =
        delegate.supportsThinking()

    override fun withThinking(): ThinkingPromptRunnerOperations {
        return DelegatingThinkingOperations(
            delegate = delegate,
        )
    }
}
