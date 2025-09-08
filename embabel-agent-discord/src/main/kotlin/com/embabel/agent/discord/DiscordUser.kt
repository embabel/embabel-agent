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

import com.embabel.agent.identity.User

data class DiscordUserInfo(
    val id: String,
    val username: String,
    val displayName: String,
    val discriminator: String,
    val avatarUrl: String? = null,
    val isBot: Boolean = false,
)

/**
 * Embabel User associated with a Discord user.
 */
interface DiscordUser : User {
    val discordUser: DiscordUserInfo

    val displayName: String get() = discordUser.displayName

    val username: String get() = discordUser.username
}

data class DiscordUserImpl(
    override val id: String,
    override val discordUser: DiscordUserInfo,
) : DiscordUser
