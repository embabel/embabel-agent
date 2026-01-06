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

import com.embabel.agent.api.channel.*
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion

/**
 * OutputChannel that responds in a Discord channel, updating a progress message as needed.
 */
class ChannelRespondingOutputChannel(
    private val channel: MessageChannelUnion,
) : OutputChannel {
    private var progressMessage: Message? = null

    override fun send(
        event: OutputChannelEvent,
    ) {
        when (event) {
            is LoggingOutputChannelEvent -> {
                DiscordMessageUtils.sendLongMessage(channel, event.message)
            }

            is MessageOutputChannelEvent -> {
                clearProgressMessage()
                DiscordMessageUtils.sendLongMessage(channel, event.message.content)
            }

            is ProgressOutputChannelEvent -> {
                handleProgressMessage(event.message)
            }

            else -> {
                // Handle other event types if necessary
            }
        }

    }

    private fun handleProgressMessage(message: String) {
        if (message.isBlank()) {
            clearProgressMessage()
        } else {
            // Update or create progress message
            if (progressMessage == null) {
                // Create new progress message
                channel.sendMessage("⏳ $message").queue { sentMessage ->
                    progressMessage = sentMessage
                }
            } else {
                // Update existing progress message
                progressMessage?.editMessage("⏳ $message")?.queue()
            }
        }
    }

    private fun clearProgressMessage() {
        progressMessage?.let { msg ->
            msg.delete().queue(
                { progressMessage = null },
                { /* ignore delete failures */ }
            )
        }
    }
}
