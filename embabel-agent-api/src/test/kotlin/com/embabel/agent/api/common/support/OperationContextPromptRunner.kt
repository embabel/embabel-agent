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

import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.AgentImage
import com.embabel.agent.api.common.ContextualPromptElement
import com.embabel.agent.api.common.InteractionId
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.api.common.nested.support.PromptRunnerCreating
import com.embabel.agent.api.common.nested.support.PromptRunnerRendering
import com.embabel.agent.api.common.streaming.StreamingPromptRunner
import com.embabel.agent.api.common.support.streaming.StreamingCapabilityDetector
import com.embabel.agent.api.common.support.streaming.StreamingImpl
import com.embabel.agent.api.common.thinking.support.ThinkingPromptRunnerOperationsImpl
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.ToolObject
import com.embabel.agent.api.tool.agentic.DomainToolPredicate
import com.embabel.agent.api.validation.guardrails.GuardRail
import com.embabel.agent.core.ToolGroup
import com.embabel.agent.core.ToolGroupRequirement
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.core.support.safelyGetTools
import com.embabel.agent.experimental.primitive.Determination
import com.embabel.agent.spi.support.springai.ChatClientLlmOperations
import com.embabel.agent.spi.support.springai.streaming.StreamingChatClientOperations
import com.embabel.chat.ImagePart
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.Thinking
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.core.types.ZeroToOne
import com.embabel.common.util.loggerFor
import java.util.function.Predicate

/**
 * Uses the platform's LlmOperations to execute the prompt.
 * All prompt running ends up through here.
 */
internal data class OperationContextPromptRunner(
    private val context: OperationContext,
    private val interactionId: InteractionId? = null,
    override val llm: LlmOptions,
    override val messages: List<Message> = emptyList(),
    override val images: List<AgentImage> = emptyList(),
    override val toolGroups: Set<ToolGroupRequirement>,
    override val toolObjects: List<ToolObject>,
    override val promptContributors: List<PromptContributor>,
    private val contextualPromptContributors: List<ContextualPromptElement>,
    override val generateExamples: Boolean?,
    override val propertyFilter: Predicate<String> = Predicate { true },
    override val validation: Boolean = true,
    private val otherTools: List<Tool> = emptyList(),
    private val guardRails: List<GuardRail> = emptyList(),
) : StreamingPromptRunner {

    val action = (context as? ActionContext)?.action

    private fun idForPrompt(
        messages: List<Message>,
        outputClass: Class<*>,
    ): InteractionId {
        return InteractionId("${context.operation.name}-${outputClass.name}")
    }

    /**
     * Combine stored images with messages.
     * If there are images, they are added to the last message or a new UserMessage is created.
     */
    private fun combineImagesWithMessages(messages: List<Message>): List<Message> {
        if (images.isEmpty()) {
            return messages
        }

        val imageParts = images.map { ImagePart(it.mimeType, it.data) }

        // If there are no messages, create a UserMessage with just images
        if (messages.isEmpty()) {
            return listOf(UserMessage(parts = imageParts))
        }

        // Add images to the last message if it's a UserMessage
        val lastMessage = messages.last()
        if (lastMessage is UserMessage) {
            val updatedLastMessage = UserMessage(
                parts = lastMessage.parts + imageParts,
                name = lastMessage.name,
                timestamp = lastMessage.timestamp
            )
            return messages.dropLast(1) + updatedLastMessage
        } else {
            // If last message is not a UserMessage, append a new UserMessage with images
            return messages + UserMessage(parts = imageParts)
        }
    }

    override fun withInteractionId(interactionId: InteractionId): PromptRunner =
        copy(interactionId = interactionId)

    override fun withMessages(messages: List<Message>): PromptRunner =
        copy(messages = this.messages + messages)

    override fun withImages(images: List<AgentImage>): PromptRunner =
        copy(images = this.images + images)

    override fun <T> createObject(
        messages: List<Message>,
        outputClass: Class<T>,
    ): T {
        val allPromptContributors = promptContributors + contextualPromptContributors.map {
            it.toPromptContributor(
                context
            )
        }
        val combinedMessages = combineImagesWithMessages(this.messages + messages)
        return context.processContext.createObject(
            messages = combinedMessages,
            interaction = LlmInteraction(
                llm = llm,
                toolGroups = this.toolGroups + toolGroups,
                tools = safelyGetTools(toolObjects) + otherTools,
                promptContributors = allPromptContributors,
                id = interactionId ?: idForPrompt(messages, outputClass),
                generateExamples = generateExamples,
                propertyFilter = propertyFilter,
                validation = validation,
                guardRails = guardRails,
            ),
            outputClass = outputClass,
            agentProcess = context.processContext.agentProcess,
            action = action,
        )
    }

    override fun <T> createObjectIfPossible(
        messages: List<Message>,
        outputClass: Class<T>,
    ): T? {
        val combinedMessages = combineImagesWithMessages(this.messages + messages)
        val result = context.processContext.createObjectIfPossible<T>(
            messages = combinedMessages,
            interaction = LlmInteraction(
                llm = llm,
                toolGroups = this.toolGroups + toolGroups,
                tools = safelyGetTools(toolObjects) + otherTools,
                promptContributors = promptContributors + contextualPromptContributors.map {
                    it.toPromptContributor(
                        context
                    )
                },
                id = interactionId ?: idForPrompt(messages, outputClass),
                generateExamples = generateExamples,
                propertyFilter = propertyFilter,
                validation = validation,
                guardRails = guardRails,
            ),
            outputClass = outputClass,
            agentProcess = context.processContext.agentProcess,
            action = action,
        )
        if (result.isFailure) {
            loggerFor<OperationContextPromptRunner>().warn(
                "Failed to create object of type {} with messages {}: {}",
                outputClass.name,
                messages,
                result.exceptionOrNull()?.message,
            )
        }
        return result.getOrNull()
    }

    override fun evaluateCondition(
        condition: String,
        context: String,
        confidenceThreshold: ZeroToOne,
    ): Boolean {
        val prompt =
            """
            Evaluate this condition given the context.
            Return "result": whether you think it is true, your confidence level from 0-1,
            and an explanation of what you base this on.

            # Condition
            $condition

            # Context
            $context
            """.trimIndent()
        val determination = createObject(
            prompt = prompt,
            outputClass = Determination::class.java,
        )
        loggerFor<OperationContextPromptRunner>().info(
            "Condition {}: determination from {} was {}",
            condition,
            llm.criteria,
            determination,
        )
        return determination.result && determination.confidence >= confidenceThreshold
    }

    override fun rendering(templateName: String): PromptRunner.Rendering {
        return PromptRunnerRendering(
            templateName = templateName,
            promptRunner = this,
            templateRenderer = context.agentPlatform().platformServices.templateRenderer,
        )
    }

    override fun withLlm(llm: LlmOptions): PromptRunner =
        copy(llm = llm)

    override fun withToolGroup(toolGroup: ToolGroupRequirement): PromptRunner =
        copy(toolGroups = this.toolGroups + toolGroup)

    override fun withToolGroup(toolGroup: ToolGroup): PromptRunner =
        copy(otherTools = otherTools + toolGroup.tools)

    override fun withToolObject(toolObject: ToolObject): PromptRunner =
        copy(toolObjects = this.toolObjects + toolObject)

    override fun withTool(tool: Tool): PromptRunner =
        copy(otherTools = this.otherTools + tool)

    override fun withPromptContributors(promptContributors: List<PromptContributor>): PromptRunner =
        copy(promptContributors = this.promptContributors + promptContributors)

    override fun withContextualPromptContributors(
        contextualPromptContributors: List<ContextualPromptElement>,
    ): PromptRunner =
        copy(contextualPromptContributors = this.contextualPromptContributors + contextualPromptContributors)

    override fun withGenerateExamples(generateExamples: Boolean): PromptRunner =
        copy(generateExamples = generateExamples)

    @Deprecated("Use creating().withPropertyFilter() instead")
    override fun withPropertyFilter(filter: Predicate<String>): PromptRunner =
        copy(propertyFilter = this.propertyFilter.and(filter))

    @Deprecated("Use creating().withValidation() instead")
    override fun withValidation(validation: Boolean): PromptRunner =
        copy(validation = validation)

    override fun <T> creating(outputClass: Class<T>): PromptRunner.Creating<T> {
        return PromptRunnerCreating(
            promptRunner = this,
            outputClass = outputClass,
            objectMapper = context.agentPlatform().platformServices.objectMapper,
        )
    }

    /**
     * Check if streaming is supported by the underlying LLM model.
     * Performs three-level capability detection:
     * 1. Must be ChatClientLlmOperations for Spring AI integration
     * 2. Must have StreamingChatModel
     */
    override fun supportsStreaming(): Boolean {
        val llmOperations = context.agentPlatform().platformServices.llmOperations


        return StreamingCapabilityDetector.supportsStreaming(llmOperations, this.llm)
    }

    override fun streaming(): StreamingPromptRunner.Streaming {
        if (!supportsStreaming()) {
            throw UnsupportedOperationException(
                """
                Streaming not supported by underlying LLM model.
                Model type: ${context.agentPlatform().platformServices.llmOperations::class.simpleName}.
                Check supportsStreaming() before calling stream().
                """.trimIndent()
            )
        }

        return StreamingImpl(
            streamingLlmOperations = StreamingChatClientOperations(
                context.agentPlatform().platformServices.llmOperations as ChatClientLlmOperations
            ),
            interaction = LlmInteraction(
                llm = llm,
                toolGroups = toolGroups,
                tools = safelyGetTools(toolObjects) + otherTools,
                promptContributors = promptContributors + contextualPromptContributors.map {
                    it.toPromptContributor(context)
                },
                id = interactionId ?: InteractionId("${context.operation.name}-streaming"),
                generateExamples = generateExamples,
                propertyFilter = propertyFilter,
                guardRails = guardRails,
            ),
            messages = messages,
            agentProcess = context.processContext.agentProcess,
            action = action,
        )
    }

    /**
     * Create thinking-aware prompt operations that extract LLM reasoning blocks.
     *
     * This method creates ThinkingPromptRunnerOperations that can capture both the
     * converted results and the reasoning content that LLMs generate during processing.
     *
     * @return ThinkingPromptRunnerOperations for executing prompts with thinking extraction
     * @throws UnsupportedOperationException if the underlying LLM operations don't support thinking extraction
     */
    override fun supportsThinking(): Boolean = true

    override fun thinking(): PromptRunner.Thinking {
        val llmOperations = context.agentPlatform().platformServices.llmOperations

        if (llmOperations !is ChatClientLlmOperations) {
            throw UnsupportedOperationException(
                """
                Thinking extraction not supported by underlying LLM operations.
                Operations type: ${llmOperations::class.simpleName}.
                Thinking extraction requires ChatClientLlmOperations.
                """.trimIndent()
            )
        }

        // Auto-enable thinking extraction when withThinking() is called
        val thinkingEnabledLlm = llm.withThinking(Thinking.withExtraction())

        return ThinkingPromptRunnerOperationsImpl(
            chatClientOperations = llmOperations,
            interaction = LlmInteraction(
                llm = thinkingEnabledLlm,
                toolGroups = toolGroups,
                tools = safelyGetTools(toolObjects) + otherTools,
                promptContributors = promptContributors + contextualPromptContributors.map {
                    it.toPromptContributor(context)
                },
                id = interactionId ?: InteractionId("${context.operation.name}-thinking"),
                generateExamples = generateExamples,
                propertyFilter = propertyFilter,
                guardRails = guardRails,
            ),
            messages = messages,
            agentProcess = context.processContext.agentProcess,
            action = action,
        )
    }

    /**
     * Add guardrail instances (additive).
     */
    override fun withGuardRails(vararg guards: GuardRail): PromptRunner {
        return copy(
            guardRails = this.guardRails + guards
        )
    }

    override fun <T : Any> withToolChainingFrom(
        type: Class<T>,
        predicate: DomainToolPredicate<T>,
    ): PromptRunner = this

    override fun withToolChainingFromAny(): PromptRunner = this

}
