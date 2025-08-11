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

import com.embabel.agent.api.common.workflow.control.SimpleAgentBuilder
import com.embabel.agent.core.Agent
import com.embabel.agent.core.last
import com.embabel.agent.prompt.persona.Persona
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Conversation
import com.embabel.chat.WindowingConversationFormatter
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.textio.template.TemplateRenderer
import com.embabel.common.util.loggerFor

class DefaultChatAgentBuilder(
    private val templateRenderer: TemplateRenderer,
    private val llm: LlmOptions,
    private val persona: Persona = K9,
    private val promptTemplate: String = "chat/default_chat",
) {

    fun build(): Agent =
        SimpleAgentBuilder
            .returning(AssistantMessage::class.java)
            .running { context ->
                // TODO should arguably use messages
                val conversation = context.last<Conversation>()
                    ?: throw IllegalStateException("No conversation found in context")
                val formattedConversation =
                    conversation.promptContributor(WindowingConversationFormatter(windowSize = 100))
                val prompt = templateRenderer.renderLoadedTemplate(
                    promptTemplate,
                    mapOf(
                        "persona" to persona,
                        "formattedConversation" to formattedConversation,
                    )
                )
                loggerFor<Persona>().info("Continuing conversation with prompt:\n{}", prompt)
                val assistantMessageContext = context.ai()
                    .withLlm(llm)
                    .withPromptElements(persona)
                    .generateText(prompt)
                AssistantMessage(
                    name = persona.name,
                    content = assistantMessageContext,
                )
            }
            .buildAgent("Default chat Agent", description = "Default conversation agent")
}
