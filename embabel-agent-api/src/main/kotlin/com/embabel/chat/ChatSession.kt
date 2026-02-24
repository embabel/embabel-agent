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
package com.embabel.chat

import com.embabel.agent.api.channel.MessageOutputChannelEvent
import com.embabel.agent.api.channel.OutputChannel
import com.embabel.agent.api.identity.User

/**
 * Simplest possible conversation session implementation
 * Responsible for keeping its conversation up to date
 * via Conversation.addMessage(),
 * and for sending messages to the OutputChannel.
 */
interface ChatSession {

    /**
     * OutputChannel to send messages to.
     */
    val outputChannel: OutputChannel

    /**
     * The Embabel User if known, null if not.
     */
    val user: User?

    /**
     * Conversation history. Kept up to date.
     */
    val conversation: Conversation

    /**
     * Subclasses should override this to provide a process ID if available.
     */
    val processId: String? get() = null

    /**
     * Update the conversation with a new message
     * and respond to it.
     * Any response messages will be sent to the messageListener,
     * but also should be added to the conversation.
     * @param userMessage message to send
     */
    fun onUserMessage(
        userMessage: UserMessage,
    )

    /**
     * Handle a system-initiated chat trigger.
     * The trigger prompt is sent to the LLM but not stored in the conversation.
     * Only the chatbot's response is stored and sent to the output channel.
     *
     * @param trigger the chat trigger to process
     */
    fun onTrigger(trigger: ChatTrigger)

    /**
     * Is the conversation finished?
     */
    fun isFinished(): Boolean = false

    /**
     * Convenience method to add a message to the conversation
     * and send it to the output channel.
     * Preserves all message properties including awaitable and assets.
     */
    fun saveAndSend(message: AssistantMessage) {
        conversation.addMessage(message)
        outputChannel.send(
            MessageOutputChannelEvent(
                processId = processId ?: "anonymous",
                message = message,
            )
        )
    }
}
