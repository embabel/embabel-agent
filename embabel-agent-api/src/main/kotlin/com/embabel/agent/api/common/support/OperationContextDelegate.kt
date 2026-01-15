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
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.ToolGroup
import com.embabel.agent.core.ToolGroupRequirement
import com.embabel.agent.core.Verbosity
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.core.support.safelyGetTools
import com.embabel.agent.experimental.primitive.Determination
import com.embabel.agent.tools.agent.AgentTool
import com.embabel.agent.tools.agent.Handoffs
import com.embabel.agent.tools.agent.PromptedTextCommunicator
import com.embabel.chat.AssistantMessage
import com.embabel.chat.ImagePart
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.core.types.ZeroToOne
import com.embabel.common.textio.template.TemplateRenderer
import com.embabel.common.util.loggerFor
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.function.Predicate

/**
 * Default implementation of PromptExecutionDelegate that delegates to OperationContext.
 * Default implementation used by DelegatingPromptRunner, DelegatingObjectCreator,
 * and DelegatingTemplateOperations.
 */
internal data class OperationContextDelegate(
    internal val context: OperationContext,
    private val interactionId: InteractionId? = null,
    override val llm: LlmOptions,
    override val messages: List<Message> = emptyList(),
    override val images: List<AgentImage> = emptyList(),
    override val toolGroups: Set<ToolGroupRequirement>,
    override val toolObjects: List<ToolObject>,
    override val promptContributors: List<PromptContributor>,
    private val contextualPromptContributors: List<ContextualPromptElement> = emptyList(),
    override val generateExamples: Boolean? = null,
    override val propertyFilter: Predicate<String> = Predicate { true },
    override val validation: Boolean = true,
    private val otherTools: List<Tool> = emptyList(),
) : PromptExecutionDelegate {

    val action = (context as? ActionContext)?.action

    private fun idForPrompt(
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
                parts = lastMessage.parts + imageParts, name = lastMessage.name, timestamp = lastMessage.timestamp
            )
            return messages.dropLast(1) + updatedLastMessage
        } else {
            // If last message is not a UserMessage, append a new UserMessage with images
            return messages + UserMessage(parts = imageParts)
        }
    }

    override val objectMapper: ObjectMapper
        get() = context.agentPlatform().platformServices.objectMapper

    override val templateRenderer: TemplateRenderer
        get() = context.agentPlatform().platformServices.templateRenderer

    override fun withInteractionId(interactionId: InteractionId): PromptExecutionDelegate =
        copy(interactionId = interactionId)

    override fun withLlm(llm: LlmOptions): PromptExecutionDelegate = copy(llm = llm)

    override fun withMessages(messages: List<Message>): PromptExecutionDelegate =
        copy(messages = this.messages + messages)

    override fun withImages(images: List<AgentImage>): PromptExecutionDelegate = copy(images = this.images + images)

    override fun withToolGroup(toolGroup: ToolGroupRequirement): PromptExecutionDelegate =
        copy(toolGroups = this.toolGroups + toolGroup)

    override fun withToolGroup(toolGroup: ToolGroup): PromptExecutionDelegate =
        copy(otherTools = otherTools + toolGroup.tools)

    override fun withToolObject(toolObject: ToolObject): PromptExecutionDelegate =
        copy(toolObjects = this.toolObjects + toolObject)

    override fun withTool(tool: Tool): PromptExecutionDelegate = copy(otherTools = this.otherTools + tool)

    override fun withHandoffs(vararg outputTypes: Class<*>): PromptExecutionDelegate {
        val handoffs = Handoffs(
            autonomy = context.agentPlatform().platformServices.autonomy(),
            outputTypes = outputTypes.toList(),
            applicationName = context.agentPlatform().name,
        )
        return copy(
            otherTools = this.otherTools + handoffs.tools,
        )
    }

    override fun withSubagents(vararg subagents: Subagent): PromptExecutionDelegate {
        val newTools = subagents.map { subagent ->
            val agent = subagent.resolve(context.agentPlatform())
            AgentTool(
                autonomy = context.agentPlatform().platformServices.autonomy(),
                agent = agent,
                textCommunicator = PromptedTextCommunicator,
                objectMapper = context.agentPlatform().platformServices.objectMapper,
                inputType = subagent.inputClass,
                processOptionsCreator = { agentProcess ->
                    val blackboard = agentProcess.processContext.blackboard.spawn()
                    loggerFor<OperationContextDelegate>().info(
                        "Creating subagent process for {} with blackboard {}",
                        agent.name,
                        blackboard,
                    )
                    ProcessOptions(
                        verbosity = Verbosity(showPrompts = true),
                        blackboard = blackboard,
                    )
                },
            )
        }
        return copy(
            otherTools = this.otherTools + newTools,
        )
    }

    override fun withPromptContributors(promptContributors: List<PromptContributor>): PromptExecutionDelegate =
        copy(promptContributors = this.promptContributors + promptContributors)

    override fun withContextualPromptContributors(
        contextualPromptContributors: List<ContextualPromptElement>,
    ): PromptExecutionDelegate =
        copy(contextualPromptContributors = this.contextualPromptContributors + contextualPromptContributors)

    override fun withGenerateExamples(generateExamples: Boolean): PromptExecutionDelegate =
        copy(generateExamples = generateExamples)

    override fun withPropertyFilter(filter: Predicate<String>): PromptExecutionDelegate =
        copy(propertyFilter = this.propertyFilter.and(filter))

    override fun withValidation(validation: Boolean): PromptExecutionDelegate = copy(validation = validation)

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
                id = interactionId ?: idForPrompt(outputClass),
                generateExamples = generateExamples,
                propertyFilter = propertyFilter,
                validation = validation,
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
                id = interactionId ?: idForPrompt(outputClass),
                generateExamples = generateExamples,
                propertyFilter = propertyFilter,
                validation = validation,
            ),
            outputClass = outputClass,
            agentProcess = context.processContext.agentProcess,
            action = action,
        )
        if (result.isFailure) {
            loggerFor<OperationContextDelegate>().warn(
                "Failed to create object of type {} with messages {}: {}",
                outputClass.name,
                messages,
                result.exceptionOrNull()?.message,
            )
        }
        return result.getOrNull()
    }

    override fun respond(messages: List<Message>): AssistantMessage {
        val response = createObject(
            messages = messages,
            outputClass = String::class.java,
        )
        return AssistantMessage(response)
    }

    override fun evaluateCondition(
        condition: String,
        context: String,
        confidenceThreshold: ZeroToOne,
    ): Boolean {
        val prompt = """
            Evaluate this condition given the context.
            Return "result": whether you think it is true, your confidence level from 0-1,
            and an explanation of what you base this on.

            # Condition
            $condition

            # Context
            $context
            """.trimIndent()
        val determination = createObject(
            messages = listOf(UserMessage(prompt)),
            outputClass = Determination::class.java,
        )
        loggerFor<OperationContextDelegate>().info(
            "Condition {}: determination from {} was {}",
            condition,
            llm.criteria,
            determination,
        )
        return determination.result && determination.confidence >= confidenceThreshold
    }
}
