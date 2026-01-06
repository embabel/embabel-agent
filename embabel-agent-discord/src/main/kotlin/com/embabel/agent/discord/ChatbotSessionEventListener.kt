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
package com.embabel.agent.discord

import com.embabel.agent.api.common.Asyncer
import com.embabel.chat.ChatSession
import com.embabel.chat.Chatbot
import com.embabel.chat.UserMessage
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.stereotype.Component

/**
 * Discord SessionEventListener that uses an Embabel Chatbot
 */
@Component
@ConditionalOnBean(Chatbot::class)
class ChatbotSessionEventListener(
    private val discordSessionService: DiscordSessionService,
    private val chatbot: Chatbot,
    private val asyncer: Asyncer,
    private val discordConfigProperties: DiscordConfigProperties,
) : ListenerAdapter() {

    private val logger = LoggerFactory.getLogger(ChatbotSessionEventListener::class.java)

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot) {
            return
        }

        val session = discordSessionService.getOrCreateSession(event)
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
        val chatSession = chatSessionFor(discordUserSession, event)
        asyncer.async {
            chatSession.onUserMessage(
                userMessage = UserMessage(content = event.message.contentRaw),
            )
        }
    }

    private fun chatSessionFor(
        discordUserSession: DiscordUserSession,
        event: MessageReceivedEvent,
    ): ChatSession {
        return discordUserSession.sessionData.getOrPut("chatSession") {
            chatbot.createSession(
                user = discordUserSession.user,
                outputChannel = ChannelRespondingOutputChannel(
                    channel = event.channel,
                ),
                systemMessage = null,
            )
        } as ChatSession
    }
}
