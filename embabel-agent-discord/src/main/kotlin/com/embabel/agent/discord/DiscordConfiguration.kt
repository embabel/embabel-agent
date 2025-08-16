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

import com.embabel.chat.agent.ChatConfig
import com.embabel.common.util.trim
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.hooks.EventListener
import net.dv8tion.jda.api.requests.GatewayIntent
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@ConfigurationProperties(prefix = "embabel.discord")
data class DiscordConfigProperties(
    val token: String,
    val chatConfig: ChatConfig = ChatConfig(),
)

@Configuration
class DiscordConfiguration {

    private val logger = LoggerFactory.getLogger(DiscordConfiguration::class.java)

    @Bean
    fun jda(
        properties: DiscordConfigProperties,
        eventListener: EventListener,
    ): JDA {
        logger.info("Starting Discord bot with token: ${trim(properties.token, max = 20, keepRight = 6)}")
        return JDABuilder
            .createDefault(properties.token)
            .enableIntents(GatewayIntent.MESSAGE_CONTENT)
            .addEventListeners(eventListener)
            .build()
            .awaitReady()
    }
}
