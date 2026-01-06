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
package com.embabel.agent.api.channel

import com.embabel.agent.core.InProcess
import com.embabel.agent.domain.library.HasContent
import com.embabel.chat.Message
import org.slf4j.LoggerFactory

/**
 * Allows agents to interact with the outside world through multiple channels
 */
interface OutputChannel {

    fun send(event: OutputChannelEvent)

    operator fun plus(other: OutputChannel): OutputChannel =
        if (this == DevNullOutputChannel) other else if (other == DevNullOutputChannel) this else MulticastOutputChannel(
            listOf(this, other)
        )
}

object DevNullOutputChannel : OutputChannel {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun send(event: OutputChannelEvent) {
        logger.warn("DevNullOutputChannel received event: {}", event)
    }
}

interface OutputChannelEvent : InProcess

/**
 * Chat message event relating to this process.
 * Most likely an Assistant message, but could also be a User message in some cases.
 * @param processId Process that generated this message
 */
data class MessageOutputChannelEvent(
    override val processId: String,
    val message: Message,
) : OutputChannelEvent

data class ContentOutputChannelEvent(
    override val processId: String,
    val content: HasContent,
) : OutputChannelEvent

/**
 * Logging or progress event
 */
interface InformativeOutputChannelEvent : OutputChannelEvent {
    val message: String
}

/**
 * Ephemeral message.
 * Not meant to be part of a conversation
 */
data class LoggingOutputChannelEvent @JvmOverloads constructor(
    override val processId: String,
    override val message: String,
    val level: Level = Level.INFO,
    val throwable: Throwable? = null,
) : InformativeOutputChannelEvent {

    enum class Level {
        TRACE, DEBUG, INFO, WARN, ERROR
    }
}

data class ProgressOutputChannelEvent(
    override val processId: String,
    override val message: String,
) : InformativeOutputChannelEvent

/**
 * Send to all channels
 */
class MulticastOutputChannel(
    private val outputChannels: List<OutputChannel>,
) : OutputChannel {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun send(event: OutputChannelEvent) {
        outputChannels.forEach {
            try {
                it.send(event)
            } catch (t: Throwable) {
                logger.warn("Exception in onPlatformEvent from $it", t)
            }
        }
    }

}
