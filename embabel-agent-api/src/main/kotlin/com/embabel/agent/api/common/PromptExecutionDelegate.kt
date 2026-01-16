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

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.ToolGroup
import com.embabel.agent.core.ToolGroupRequirement
import com.embabel.agent.core.support.LlmUse
import com.embabel.agent.spi.LlmOperations
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Message
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
 * Used in [com.embabel.agent.api.common.support.DelegatingPromptRunner],
 * [com.embabel.agent.api.common.support.DelegatingObjectCreator], and
 * [com.embabel.agent.api.common.support.DelegatingTemplateOperations].
 */
internal interface PromptExecutionDelegate : LlmUse {

    val llmOperations: LlmOperations

    val templateRenderer: TemplateRenderer

    val objectMapper: ObjectMapper

    val toolObjects: List<ToolObject>

    val messages: List<Message>

    val images: List<AgentImage>

    /**
     * Set an interaction id for this delegate.
     */
    fun withInteractionId(interactionId: InteractionId): PromptExecutionDelegate

    /**
     * Specify an LLM for the delegate
     */
    fun withLlm(llm: LlmOptions): PromptExecutionDelegate

    /**
     * Add messages that will be included in the final prompt.
     */
    fun withMessages(messages: List<Message>): PromptExecutionDelegate

    /**
     * Add images that will be included in the final prompt.
     */
    fun withImages(images: List<AgentImage>): PromptExecutionDelegate

    /**
     * Add a tool group to the delegate
     */
    fun withToolGroup(toolGroup: ToolGroupRequirement): PromptExecutionDelegate

    /**
     * Add a dynamic tool group to the delegate
     */
    fun withToolGroup(toolGroup: ToolGroup): PromptExecutionDelegate

    /**
     * Add a tool object to the delegate
     */
    fun withToolObject(toolObject: ToolObject): PromptExecutionDelegate

    /**
     * Add a framework-agnostic Tool to the delegate
     */
    fun withTool(tool: Tool): PromptExecutionDelegate

    /**
     * Add a list of handoffs to agents on this platform
     */
    fun withHandoffs(vararg outputTypes: Class<*>): PromptExecutionDelegate

    /**
     * Add a list of subagents to hand off to
     */
    fun withSubagents(vararg subagents: Subagent): PromptExecutionDelegate

    /**
     * Add prompt contributors that can add to the prompt
     */
    fun withPromptContributors(promptContributors: List<PromptContributor>): PromptExecutionDelegate

    /**
     * Add prompt contributors that can see context
     */
    fun withContextualPromptContributors(
        contextualPromptContributors: List<ContextualPromptElement>,
    ): PromptExecutionDelegate

    /**
     * Set whether to generate examples of the output in the prompt
     */
    fun withGenerateExamples(generateExamples: Boolean): PromptExecutionDelegate

    /**
     * Adds a filter that determines which properties are to be included when creating an object
     */
    fun withPropertyFilter(filter: Predicate<String>): PromptExecutionDelegate

    /**
     * Set whether to validate created objects
     */
    fun withValidation(validation: Boolean): PromptExecutionDelegate

    /**
     * Create an object from messages (core execution method)
     */
    fun <T> createObject(
        messages: List<Message>,
        outputClass: Class<T>,
    ): T

    /**
     * Try to create an object from messages (core execution method)
     */
    fun <T> createObjectIfPossible(
        messages: List<Message>,
        outputClass: Class<T>,
    ): T?

    /**
     * Respond in a conversation (core execution method)
     */
    fun respond(
        messages: List<Message>,
    ): AssistantMessage

    /**
     * Evaluate a condition given context (core execution method)
     */
    fun evaluateCondition(
        condition: String,
        context: String,
        confidenceThreshold: ZeroToOne,
    ): Boolean

    fun supportsStreaming(): Boolean

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
