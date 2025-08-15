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

import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap


data class DiscordUserSession(
    val user: DiscordUser,
    val isDirectMessage: Boolean,
    val channelId: String,
    val serverId: String? = null,
    val serverName: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    var lastActivity: LocalDateTime = LocalDateTime.now(),
    val sessionData: MutableMap<String, Any> = mutableMapOf(),
)


@Service
class DiscordSessionService {

    private val activeSessions = ConcurrentHashMap<String, DiscordUserSession>()

    fun getOrCreateSession(event: MessageReceivedEvent): DiscordUserSession {
        val user = event.author
        val sessionKey = "${user.id}-${event.channel.id}"

        return activeSessions.computeIfAbsent(sessionKey) {
            createSessionFromEvent(event)
        }.also { session ->
            session.lastActivity = LocalDateTime.now()
        }
    }

    private fun createSessionFromEvent(event: MessageReceivedEvent): DiscordUserSession {
        val user = event.author
        val channel = event.channel
        val isDirectMessage = channel.type == ChannelType.PRIVATE
        val guild = if (isDirectMessage) null else event.guild

        return DiscordUserSession(
            DiscordUserImpl(
                id = user.id,
                discordUser = DiscordUserInfo(
                    id = user.id,
                    avatarUrl = user.effectiveAvatarUrl,
                    isBot = user.isBot,
                    username = user.name,
                    discriminator = user.discriminator,
                    displayName = if (isDirectMessage) user.name else user.effectiveName,
                )
            ),
            isDirectMessage = isDirectMessage,
            channelId = channel.id,
            serverId = guild?.id,
            serverName = guild?.name
        )
    }

    fun getSession(
        userId: String,
        channelId: String,
    ): DiscordUserSession? {
        return activeSessions["$userId-$channelId"]
    }

    fun getAllActiveSessions(): List<DiscordUserSession> {
        return activeSessions.values.toList()
    }

    fun endSession(
        userId: String,
        channelId: String,
    ) {
        activeSessions.remove("$userId-$channelId")
    }

    fun cleanupOldSessions(hoursOld: Long = 24) {
        val cutoff = LocalDateTime.now().minusHours(hoursOld)
        activeSessions.entries.removeIf { (_, session) ->
            session.lastActivity.isBefore(cutoff)
        }
    }

    fun updateSessionData(
        session: DiscordUserSession,
        key: String,
        value: Any,
    ) {
        session.sessionData[key] = value
    }

    fun getSessionData(
        session: DiscordUserSession,
        key: String,
    ): Any? {
        return session.sessionData[key]
    }
}
