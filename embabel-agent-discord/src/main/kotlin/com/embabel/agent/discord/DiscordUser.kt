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

import com.embabel.agent.api.identity.User

/**
 * Our own representation of a Discord user.
 * An interface so that it a concrete class can be persisted with JPA, OGM etc.
 */
interface DiscordUserInfo {
    val id: String
    val username: String
    val displayName: String
    val discriminator: String
    val avatarUrl: String?
    val isBot: Boolean

    companion object {
        operator fun invoke(
            discordUser: net.dv8tion.jda.api.entities.User,
            isDirectMessage: Boolean,
        ): DiscordUserInfo {
            return DelegatingDiscordUserInfo(discordUser, isDirectMessage)
        }
    }
}

class DelegatingDiscordUserInfo(
    val discordUser: net.dv8tion.jda.api.entities.User,
    isDirectMessage: Boolean = false,
) : DiscordUserInfo {

    override val displayName = if (isDirectMessage) discordUser.name else discordUser.effectiveName

    override val id: String
        get() = discordUser.id
    override val username: String
        get() = discordUser.name

    override val discriminator: String
        get() = discordUser.discriminator
    override val avatarUrl: String
        get() = discordUser.effectiveAvatarUrl
    override val isBot: Boolean
        get() = discordUser.isBot
}

/**
 * Embabel User associated with a Discord user.
 */
interface DiscordUser : User {
    val discordUser: DiscordUserInfo

    override val displayName: String get() = discordUser.displayName

    override val username: String get() = discordUser.username

    override val email: String? get() = null
}

data class DiscordUserImpl(
    override val id: String,
    override val discordUser: DiscordUserInfo,
) : DiscordUser
