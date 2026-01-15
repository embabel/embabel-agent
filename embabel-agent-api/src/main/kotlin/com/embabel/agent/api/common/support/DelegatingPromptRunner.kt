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
import com.embabel.agent.api.common.nested.support.DelegatingObjectCreator
import com.embabel.agent.api.common.nested.support.DelegatingTemplateOperations
import com.embabel.agent.api.common.streaming.StreamingPromptRunner
import com.embabel.agent.api.common.streaming.StreamingPromptRunnerOperations
import com.embabel.agent.api.common.support.streaming.StreamingCapabilityDetector
import com.embabel.agent.api.common.support.streaming.StreamingPromptRunnerOperationsImpl
import com.embabel.agent.api.common.thinking.ThinkingPromptRunnerOperations
import com.embabel.agent.api.common.thinking.support.ThinkingPromptRunnerOperationsImpl
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.ToolGroup
import com.embabel.agent.core.ToolGroupRequirement
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.core.support.safelyGetTools
import com.embabel.agent.spi.support.springai.ChatClientLlmOperations
import com.embabel.agent.spi.support.springai.streaming.StreamingChatClientOperations
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Message
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.Thinking
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.core.types.ZeroToOne

internal data class DelegatingPromptRunner(
    internal val delegate: PromptExecutionDelegate,
) : StreamingPromptRunner {

    // Properties delegated to the delegate
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

    // Configuration methods - wrap delegate and return new DelegatingPromptRunner
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

    // Factory methods - create nested delegates
    override fun <T> creating(outputClass: Class<T>): ObjectCreator<T> {
        return DelegatingObjectCreator(
            delegate = delegate,
            outputClass = outputClass
        )
    }

    override fun withTemplate(templateName: String): TemplateOperations {
        return DelegatingTemplateOperations(
            delegate = delegate,
            templateName = templateName,
        )
    }

    // Execution methods - delegate to core methods
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

    // Streaming support - requires access to context through OperationContextDelegate
    override fun supportsStreaming(): Boolean {
        val operationContextDelegate = delegate as? OperationContextDelegate
            ?: return false
        val context = operationContextDelegate.context
        val llmOperations = context.agentPlatform().platformServices.llmOperations
        return StreamingCapabilityDetector.supportsStreaming(llmOperations, delegate.llm)
    }

    override fun stream(): StreamingPromptRunnerOperations {
        if (!supportsStreaming()) {
            throw UnsupportedOperationException(
                "Streaming not supported. Check supportsStreaming() before calling stream()."
            )
        }

        val operationContextDelegate = delegate as OperationContextDelegate
        val context = operationContextDelegate.context
        val action = (context as? ActionContext)?.action

        return StreamingPromptRunnerOperationsImpl(
            streamingLlmOperations = StreamingChatClientOperations(
                context.agentPlatform().platformServices.llmOperations as ChatClientLlmOperations
            ),
            interaction = LlmInteraction(
                llm = delegate.llm,
                toolGroups = delegate.toolGroups,
                tools = safelyGetTools(delegate.toolObjects),
                promptContributors = delegate.promptContributors,
                id = InteractionId("${context.operation.name}-streaming"),
                generateExamples = delegate.generateExamples,
                propertyFilter = delegate.propertyFilter,
            ),
            messages = delegate.messages,
            agentProcess = context.processContext.agentProcess,
            action = action,
        )
    }

    override fun supportsThinking(): Boolean = true

    override fun withThinking(): ThinkingPromptRunnerOperations {
        val operationContextDelegate = delegate as? OperationContextDelegate
            ?: throw UnsupportedOperationException("Thinking extraction requires OperationContextDelegate")

        val context = operationContextDelegate.context
        val llmOperations = context.agentPlatform().platformServices.llmOperations

        if (llmOperations !is ChatClientLlmOperations) {
            throw UnsupportedOperationException(
                "Thinking extraction not supported by underlying LLM operations."
            )
        }

        val action = (context as? ActionContext)?.action

        return ThinkingPromptRunnerOperationsImpl(
            chatClientOperations = llmOperations,
            interaction = LlmInteraction(
                llm = delegate.llm.withThinking(Thinking.withExtraction()),
                toolGroups = delegate.toolGroups,
                tools = safelyGetTools(delegate.toolObjects),
                promptContributors = delegate.promptContributors,
                id = InteractionId("${context.operation.name}-thinking"),
                generateExamples = delegate.generateExamples,
                propertyFilter = delegate.propertyFilter,
            ),
            messages = delegate.messages,
            agentProcess = context.processContext.agentProcess,
            action = action,
        )
    }
}
