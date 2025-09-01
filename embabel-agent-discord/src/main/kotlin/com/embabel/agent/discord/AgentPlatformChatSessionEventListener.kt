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
package com.embabel.agent.discord

import com.embabel.agent.api.common.Asyncer
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.common.autonomy.DefaultPlanLister
import com.embabel.agent.api.common.autonomy.ProcessWaitingException
import com.embabel.agent.core.ProcessOptions
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Message
import com.embabel.chat.MessageListener
import com.embabel.chat.UserMessage
import com.embabel.chat.agent.*
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class AgentPlatformChatSessionEventListener(
    private val discordSessionService: DiscordSessionService,
    private val autonomy: Autonomy,
    private val asyncer: Asyncer,
    private val discordConfigProperties: DiscordConfigProperties,
) : ListenerAdapter() {

    private val logger = LoggerFactory.getLogger(AgentPlatformChatSessionEventListener::class.java)

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot) {
            return
        }

        val session = discordSessionService.getOrCreateSession(event)
        val message = event.message.contentRaw
        val channel = event.channel
        val guild = if (session.isDirectMessage) null else event.guild

        if (session.isDirectMessage) {
            respondToDm(event, session)
            return
        } else {
            reactToChannelMessage(event, session)
        }
    }

    private fun reactToChannelMessage(
        event: MessageReceivedEvent,
        session: DiscordUserSession,
    ) {
        logger.info(
            "User {} sent a message in channel {} at {}}",
            session.user, session.channelId, session.lastActivity,
        )
    }

    private fun respondToDm(
        event: MessageReceivedEvent,
        discordUserSession: DiscordUserSession,
    ) {
        logger.info("Responding to DM from user: ${discordUserSession.user}")
        val chatSession = chatSessionFor(discordUserSession)
        asyncer.async {
            chatSession.respond(
                userMessage = UserMessage(content = event.message.contentRaw),
                messageListener = ChannelRespondingMessageListener(event),
            )
        }
    }

    private fun chatSessionFor(discordUserSession: DiscordUserSession): AgentPlatformChatSession {
        return discordUserSession.sessionData.getOrPut("chatSession") {
            AgentPlatformChatSession(
                planLister = DefaultPlanLister(autonomy.agentPlatform),
                processOptions = ProcessOptions(),
                responseGenerator = AgentResponseGenerator(
                    agentPlatform = autonomy.agentPlatform,
                    agent = DefaultChatAgentBuilder(
                        autonomy = autonomy,
                        llm = discordConfigProperties.chatConfig.llm,
                        persona = K9,
                    ).build()
                )
            )
        } as AgentPlatformChatSession
    }
}

class DiscordProcessWaitingHandler : ProcessWaitingHandler {
    override fun handleProcessWaitingException(
        pwe: ProcessWaitingException,
        basis: Any,
    ): AssistantMessage {
        TODO("Not yet implemented")
    }
}

/**
 * Listens for Embabel messages and responds in the same channel
 * as the given Discord event.
 */
class ChannelRespondingMessageListener(
    private val event: MessageReceivedEvent,
) : MessageListener {

    override fun onMessage(message: Message) {
        event.channel.sendMessage(message.content).queue()
    }
}
