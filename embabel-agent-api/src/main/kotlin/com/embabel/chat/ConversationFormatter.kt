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
 * Format a conversation into a String for inclusion in a prompt.
 * Note that we often prefer to use messages.
 */
fun interface ConversationFormatter {

    fun format(conversation: Conversation): String
}

fun interface MessageFormatter {

    fun format(message: Message): String
}

object SimpleMessageFormatter : MessageFormatter {
    override fun format(message: Message): String =
        if (message.name != null) "${message.name} (${message.role}): ${message.content}"
        else "${message.role}: ${message.content}"
}

/**
 * Conversation formatter that shows the last `windowSize` messages
 */
class WindowingConversationFormatter @JvmOverloads constructor(
    private val messageFormatter: MessageFormatter = SimpleMessageFormatter,
    private val windowSize: Int = 100,
) : ConversationFormatter {

    override fun format(conversation: Conversation): String =
        conversation.messages
            .takeLast(windowSize)
            .joinToString("\n") { messageFormatter.format(it) }
}
