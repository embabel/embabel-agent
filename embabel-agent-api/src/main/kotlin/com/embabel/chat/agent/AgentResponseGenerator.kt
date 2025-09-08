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
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Conversation
import com.embabel.chat.MessageListener

/**
 * Respond to user messages using an agent.
 */
class AgentResponseGenerator(
    private val agentPlatform: AgentPlatform,
    agent: Agent,
) : ResponseGenerator {

    init {
        // TODO should check if there is a "chatAgent" deployed
        agentPlatform.deploy(agent)
    }

    override fun generateResponses(
        conversation: Conversation,
        processOptions: ProcessOptions,
        messageListener: MessageListener,
    ) {
        val invocation = AgentInvocation
            .builder(agentPlatform)
            .options(processOptions)
            .build(AssistantMessage::class.java)
        val message = invocation.invoke(conversation)
        messageListener.onMessage(message, conversation)
    }

}
