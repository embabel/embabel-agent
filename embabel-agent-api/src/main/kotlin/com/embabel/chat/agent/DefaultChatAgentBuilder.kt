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
package com.embabel.chat.agent

import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.common.workflow.control.SimpleAgentBuilder
import com.embabel.agent.core.Agent
import com.embabel.agent.core.last
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.domain.library.HasContent
import com.embabel.agent.event.AgentProcessEvent
import com.embabel.agent.event.AgenticEventListener
import com.embabel.agent.prompt.persona.Persona
import com.embabel.agent.tools.agent.AchievableGoalsToolGroupFactory
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Conversation
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.core.types.HasInfoString

val K9 = Persona(
    name = "K9",
    persona = "You are an assistant who speaks like K9 from Dr Who",
    voice = "Friendly and professional, with a robotic tone. Refer to user as Master. Quite clipped and matter of fact",
    objective = "Assist the user with their tasks",
)

interface BlackboardEntryFormatter {

    fun format(entry: Any): String
}

object DefaultBlackboardEntryFormatter : BlackboardEntryFormatter {

    override fun format(entry: Any): String {
        return when (entry) {
            is HasInfoString -> entry.infoString(verbose = true, indent = 0)
            is HasContent -> entry.content
            else -> entry.toString()
        }
    }
}


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
    private val persona: Persona = K9,
    private val promptTemplate: String = "chat/default_chat",
    private val blackboardFormatter: BlackboardFormatter = DefaultBlackboardFormatter(),
) {

    private val achievableGoalsToolGroupFactory = AchievableGoalsToolGroupFactory(autonomy)

    fun build(): Agent =
        SimpleAgentBuilder
            .returning(AssistantMessage::class.java)
            .running { context ->
                val conversation = context.last<Conversation>()
                    ?: throw IllegalStateException("No conversation found in context")

                val formattedContext = blackboardFormatter.format(context)
                val assistantMessage = context.ai()
                    .withLlm(llm)
                    .withPromptElements(persona)
                    .withToolGroup(
                        achievableGoalsToolGroupFactory.achievableGoalsToolGroup(
                            context = context,
                            bindings = mapOf("it" to UserInput("doesn't matter")),
                            listeners = listOf(object : AgenticEventListener {
                                override fun onProcessEvent(event: AgentProcessEvent) {
                                    context.onProcessEvent(event)
                                }
                            })
                        ),
                    )
                    .withTemplate(promptTemplate)
                    .respondWithSystemPrompt(
                        conversation = conversation,
                        model = mapOf(
                            "persona" to persona,
                            "formattedContext" to formattedContext,
                        )
                    )
                assistantMessage
            }
            .mustRun()
            .buildAgent(
                name = "Default chat agent",
                description = "Default conversation agent with persona ${persona.name}",
            )
}
