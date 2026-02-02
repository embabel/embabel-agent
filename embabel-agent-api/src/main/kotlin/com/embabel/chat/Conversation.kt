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
 * Mutable. Conversation AssetView shows assets both from our AssetTracker
 * and messages.
 */
interface Conversation : StableIdentified, HasInfoString, AssetView {

    /**
     * Messages in the conversation in chronological order.
     * Visible to user.
     */
    val messages: List<Message>

    /**
     * Assets tracked in the conversation.
     */
    val assetTracker: AssetTracker

    /**
     * All assets visible in this conversation.
     *
     * Combines assets from:
     * 1. The conversation's assetTracker (first, explicit tracking)
     * 2. Assets from all messages (e.g., AssistantMessage assets)
     *
     * Assets are deduplicated by ID - tracker assets take priority.
     */
    override val assets: List<Asset>
        get() {
            val messageAssets = messages
                .filterIsInstance<AssetView>()
                .flatMap { it.assets }
            return MergedAssetView(assetTracker, AssetView.of(messageAssets)).assets
        }

    /**
     * Non-null if the conversation has messages and the last message is from the user.
     */
    fun lastMessageIfBeFromUser(): UserMessage? = messages.lastOrNull() as? UserMessage

    /**
     * Modify the state of this conversation.
     * Return the newly added message for convenience.
     */
    fun addMessage(message: Message): Message

    /**
     * Add a message with explicit author attribution.
     * Useful for group chats or when the author differs per message.
     *
     * @param message the message to add
     * @param author the author of this message (null for system/assistant messages)
     * @return the newly added message
     */
    fun addMessageFrom(message: Message, author: MessageAuthor?): Message = addMessage(message)

    /**
     * Add a message with explicit author and recipient.
     * Use this for multi-party chats where both sender and receiver need to be specified.
     *
     * @param message the message to add
     * @param from the author of this message (who sent it)
     * @param to the recipient of this message (who should receive it)
     * @return the newly added message
     */
    fun addMessageFromTo(message: Message, from: MessageAuthor?, to: MessageAuthor?): Message = addMessage(message)

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
