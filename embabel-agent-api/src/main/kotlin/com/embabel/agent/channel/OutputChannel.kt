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
package com.embabel.agent.channel

import com.embabel.agent.core.InProcess
import com.embabel.agent.domain.library.HasContent
import com.embabel.chat.AssistantMessage
import org.slf4j.LoggerFactory

/**
 * Allows agents to interact with the outside world
 */
interface OutputChannel {

    fun send(event: OutputChannelEvent)
}

object DevNullOutputChannel : OutputChannel {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun send(event: OutputChannelEvent) {
        logger.warn("DevNullOutputChannel received event: {}", event)
    }
}

interface OutputChannelEvent : InProcess {

    // TODO priority
}

/**
 * Message relation to this process
 */
class AssistantMessageOutputChannelEvent(
    override val processId: String,
    content: String,
    name: String? = null,
) : AssistantMessage(content = content, name = name), OutputChannelEvent

data class ContentOutputChannelEvent(
    override val processId: String,
    val content: HasContent,
) : OutputChannelEvent

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
