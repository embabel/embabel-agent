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

import com.embabel.agent.api.common.AgentImage
import com.embabel.agent.api.common.ContextualPromptElement
import com.embabel.agent.api.common.InteractionId
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.ToolObject
import com.embabel.agent.api.tool.agentic.DomainToolPredicate
import com.embabel.agent.api.tool.agentic.DomainToolSource
import com.embabel.agent.api.validation.guardrails.GuardRail
import com.embabel.agent.spi.loop.ToolInjectionStrategy
import com.embabel.agent.core.ToolGroup
import com.embabel.agent.core.ToolGroupRequirement
import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.core.support.LlmUse
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Message
import com.embabel.agent.api.tool.callback.ToolLoopInspector
import com.embabel.agent.api.tool.callback.ToolLoopTransformer
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.core.streaming.StreamingEvent
import com.embabel.common.core.thinking.ThinkingResponse
import com.embabel.common.core.types.ZeroToOne
import com.embabel.common.textio.template.TemplateRenderer
import com.fasterxml.jackson.databind.ObjectMapper
import reactor.core.publisher.Flux
import java.util.function.Predicate

/**
 * Delegate interface for prompt execution functionality.
 * Contains only primitive operations that cannot be expressed in terms of other methods.
 * Used in [DelegatingStreamingPromptRunner],
 * [DelegatingCreating], and
 * [DelegatingRendering].
 */
internal interface PromptExecutionDelegate : LlmUse {

    // Properties
    val llmOperations: LlmOperations

    val templateRenderer: TemplateRenderer

    val objectMapper: ObjectMapper

    val toolObjects: List<ToolObject>

    val messages: List<Message>

    val images: List<AgentImage>

    // With-ers
    fun withInteractionId(interactionId: InteractionId): PromptExecutionDelegate

    fun withLlm(llm: LlmOptions): PromptExecutionDelegate

    fun withMessages(messages: List<Message>): PromptExecutionDelegate

    fun withImages(images: List<AgentImage>): PromptExecutionDelegate

    fun withToolGroup(toolGroup: ToolGroupRequirement): PromptExecutionDelegate

    fun withToolGroup(toolGroup: ToolGroup): PromptExecutionDelegate

    fun withToolObject(toolObject: ToolObject): PromptExecutionDelegate

    fun withTool(tool: Tool): PromptExecutionDelegate

    fun withPromptContributors(promptContributors: List<PromptContributor>): PromptExecutionDelegate

    fun withContextualPromptContributors(
        contextualPromptContributors: List<ContextualPromptElement>,
    ): PromptExecutionDelegate

    fun withGenerateExamples(generateExamples: Boolean): PromptExecutionDelegate

    fun withPropertyFilter(filter: Predicate<String>): PromptExecutionDelegate

    fun withValidation(validation: Boolean): PromptExecutionDelegate

    fun withGuardRails(vararg guards: GuardRail): PromptExecutionDelegate

    fun withToolLoopInspectors(vararg inspectors: ToolLoopInspector): PromptExecutionDelegate

    fun withToolLoopTransformers(vararg transformers: ToolLoopTransformer): PromptExecutionDelegate

    val domainToolSources: List<DomainToolSource<*>>

    val autoDiscovery: Boolean

    val injectionStrategies: List<ToolInjectionStrategy>

    fun <T : Any> withToolChainingFrom(
        type: Class<T>,
        predicate: DomainToolPredicate<T>,
    ): PromptExecutionDelegate

    fun withToolChainingFromAny(): PromptExecutionDelegate

    fun withInjectionStrategies(strategies: List<ToolInjectionStrategy>): PromptExecutionDelegate

    // Execution methods
    fun <T> createObject(
        messages: List<Message>,
        outputClass: Class<T>,
    ): T

    fun <T> createObjectIfPossible(
        messages: List<Message>,
        outputClass: Class<T>,
    ): T?

    fun supportsStreaming(): Boolean

    fun generateStream(): Flux<String>

    fun <T> createObjectStream(itemClass: Class<T>): Flux<T>

    fun <T> createObjectStreamWithThinking(itemClass: Class<T>): Flux<StreamingEvent<T>>

    fun supportsThinking(): Boolean

    fun <T> createObjectIfPossibleWithThinking(
        messages: List<Message>,
        outputClass: Class<T>,
    ): ThinkingResponse<T?>

    fun <T> createObjectWithThinking(
        messages: List<Message>,
        outputClass: Class<T>
    ): ThinkingResponse<T>

    fun respondWithThinking(messages: List<Message>): ThinkingResponse<AssistantMessage>

    fun evaluateConditionWithThinking(
        condition: String,
        context: String,
        confidenceThreshold: ZeroToOne
    ): ThinkingResponse<Boolean>

}
