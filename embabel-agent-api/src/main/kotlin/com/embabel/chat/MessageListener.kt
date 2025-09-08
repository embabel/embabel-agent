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
package com.embabel.chat

/**
 * Listener for messages in a chat session.
 * Will be called for every message sent in the session,
 * whether a user message or an assistant message from the system.
 */
fun interface MessageListener {

    /**
     * Called when a message is sent in the conversation.
     * @param message Message. It's possible the message isn't in the conversation: for example
     * if it's a diagnostic message. If it should be in the conversation, the message will have been added
     * @param conversation conversation. This is messages the users should see and
     * which will be used for context in future messages.
     */
    fun onMessage(
        message: Message,
        conversation: Conversation,
    )
}

class MessageSavingMessageListener(
    private val messageList: MutableList<Message> = mutableListOf(),
) : MessageListener {

    override fun onMessage(
        message: Message,
        conversation: Conversation,
    ) {
        messageList.add(message)
    }

    fun messages(): List<Message> = messageList.toList()

}
