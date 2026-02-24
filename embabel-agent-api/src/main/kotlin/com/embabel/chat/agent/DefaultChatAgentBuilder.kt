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
package com.embabel.chat.agent

import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.api.common.TransformationActionContext
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.dsl.agent
import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.core.Agent
import com.embabel.agent.core.ToolGroup
import com.embabel.agent.core.last
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.prompt.persona.PersonaSpec
import com.embabel.agent.tools.agent.AchievableGoalsToolGroupFactory
import com.embabel.chat.AssistantMessage
import com.embabel.chat.ChatTrigger
import com.embabel.chat.Conversation
import com.embabel.chat.MessageRole
import com.embabel.common.ai.model.LlmOptions


/**
 * Convenient class to build a default chat agent.
 * @param promptTemplate location of the prompt template to use for the agent.
 * It expects:
 * - persona: the persona of the agent
 * - formattedContext: the blackboard of the agent in a textual form
 *
 */
class DefaultChatAgentBuilder(
    autonomy: Autonomy,
    private val llm: LlmOptions,
    private val persona: PersonaSpec = MARVIN,
    private val promptTemplate: String = "chat/default_chat",
    private val blackboardFormatter: BlackboardFormatter = DefaultBlackboardFormatter(),
) {

    private val achievableGoalsToolGroupFactory = AchievableGoalsToolGroupFactory(autonomy)

    private fun buildToolGroup(context: TransformationActionContext<*, *>): ToolGroup =
        achievableGoalsToolGroupFactory.achievableGoalsToolGroup(
            context = context,
            bindings = mapOf("it" to UserInput("doesn't matter")),
            listeners = listOf(object : AgenticEventListener {
                override fun onProcessEvent(event: AgentProcessEvent) {
                    context.onProcessEvent(event)
                }
            }),
            excludedTypes = setOf(ConversationStatus::class.java)
        )

    private fun buildRendering(context: TransformationActionContext<*, *>, toolGroup: ToolGroup): PromptRunner.Rendering =
        context.ai()
            .withLlm(llm)
            .withPromptElements(persona)
            .withToolGroup(toolGroup)
            .rendering(promptTemplate)

    private fun templateModel(formattedContext: String): Map<String, Any> = mapOf(
        "persona" to persona,
        "formattedContext" to formattedContext,
    )

    private fun generateResponse(
        context: TransformationActionContext<*, *>,
        conversation: Conversation,
        trigger: ChatTrigger?,
    ): AssistantMessage {
        val toolGroup = buildToolGroup(context)
        val rendering = buildRendering(context, toolGroup)
        val model = templateModel(blackboardFormatter.format(context))
        return if (trigger != null) {
            rendering.respondWithTrigger(
                conversation = conversation,
                triggerPrompt = trigger.prompt,
                model = model,
            )
        } else {
            rendering.respondWithSystemPrompt(
                conversation = conversation,
                model = model,
            )
        }
    }

    fun build(): Agent = agent(
        name = "Default chat agent",
        description = "Default conversation agent with persona ${persona.name}"
    ) {

        val shouldRespond by conditionOf { context ->
            val conversation = context.last<Conversation>()
                ?: throw IllegalStateException("No conversation found in context")
            conversation.messages.lastOrNull()?.role == MessageRole.USER
                || context.lastResult() is ChatTrigger
        }

        transformation<Conversation, ConversationStatus>(
            canRerun = true,
            preConditions = listOf(shouldRespond)
        ) { context ->
            val conversation = context.last<Conversation>()
                ?: throw IllegalStateException("No conversation found in context")
            val trigger = context.lastResult() as? ChatTrigger
            val assistantMessage = generateResponse(context, conversation, trigger)
            conversation.addMessage(assistantMessage)
            context.sendMessage(assistantMessage)
            // Will always get stuck but that's OK
            ConversationContinues(assistantMessage)
        }

        goal(
            name = "done",
            description = "Conversation is finished",
            satisfiedBy = ConversationOver::class
        )
    }
}
