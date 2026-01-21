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

import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.core.StableIdentified
import com.embabel.common.core.types.HasInfoString

/**
 * Conversation shim for agent system.
 * Mutable.
 */
interface Conversation : StableIdentified, HasInfoString {

    /**
     * Messages in the conversation in chronological order.
     * Visible to user.
     */
    val messages: List<Message>

    /**
     * Non-null if the conversation has messages and the last message is from the user.
     */
    fun lastMessageIfBeFromUser(): UserMessage? = messages.lastOrNull() as? UserMessage

    /**
     * Modify the state of this conversation
     * Return the newly added message for convenience
     */
    fun addMessage(message: Message): Message

    /**
     * Prompt contributor that represents the conversation so far.
     * Usually we will want to add messages from the conversation to a prompt
     * instead of formatting the conversation
     */
    fun promptContributor(
        conversationFormatter: ConversationFormatter = WindowingConversationFormatter(),
    ) = PromptContributor.dynamic({ "Conversation so far:\n" + conversationFormatter.format(this) })

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        return promptContributor().contribution()
    }

    /**
     * Create a nonpersistent conversation with the last n messages from this conversation.
     */
    infix fun last(n: Int): Conversation

}
