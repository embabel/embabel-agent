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

import com.embabel.agent.api.common.autonomy.AgentInvocation
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.prompt.persona.Persona
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Conversation
import com.embabel.chat.MessageListener
import com.embabel.chat.UserMessage

val K9 = Persona(
    name = "K9",
    persona = "You are an assistant who speaks like K9 from Dr Who",
    voice = "Friendly and professional, with a robotic tone. Refer to user as Master. Quite clipped and matter of fact",
    objective = "Assist the user with their tasks",
)

/**
 * Respond to messages using an agent.
 */
class AgentResponseGenerator(
    val agentPlatform: AgentPlatform,
    val agent: Agent,
) : ResponseGenerator {

    init {
        // TODO should check if there is a "chatAgent" deployed
        agentPlatform.deploy(agent)
    }

    override fun generateResponses(
        message: UserMessage,
        conversation: Conversation,
        processOptions: ProcessOptions,
        messageListener: MessageListener,
    ) {
        val invocation = AgentInvocation
            .builder(agentPlatform)
            .build(AssistantMessage::class.java)
        val message = invocation.invoke(conversation)
        messageListener.onMessage(message)
    }

}
