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
import com.embabel.agent.config.models.OpenAiModels
import com.embabel.agent.core.Agent
import com.embabel.agent.core.last
import com.embabel.agent.prompt.persona.Persona
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Conversation
import com.embabel.chat.WindowingConversationFormatter
import com.embabel.common.util.loggerFor

class ChatAgentFactory(
    private val persona: Persona = K9,
) {

    fun chatAgent(): Agent =
        SimpleAgentBuilder
            .returning(AssistantMessage::class.java)
            .running { context ->
                // TODO should arguably use text
                val conversation = context.last<Conversation>()
                    ?: throw IllegalStateException("No conversation found in context")
                val formattedConversation =
                    conversation.promptContributor(WindowingConversationFormatter(windowSize = 100))
                val prompt = """
                ${persona.contribution()}
                Continue the following conversation:

                $formattedConversation
            """.trimIndent()
                loggerFor<Persona>().info("Continuing conversation with prompt:\n{}", prompt)
                val response = context.ai().withLlm(OpenAiModels.Companion.GPT_41_MINI).generateText(
                    prompt
                )
                AssistantMessage(
                    name = persona.name,
                    content = response,
                )
            }
            .buildAgent("Default Agent", description = "Default conversation agent")
}
