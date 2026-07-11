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
package com.embabel.chat.event

import com.embabel.chat.Message
import com.embabel.chat.MessageRole
import com.embabel.common.core.types.Timestamped
import java.time.Instant

/**
 * Event published for message lifecycle changes in a conversation.
 *
 * ## Status Flow
 *
 * | Conversation Type | Events Fired |
 * |-------------------|--------------|
 * | In-memory | `ADDED` |
 * | Persistent | `ADDED` → `PERSISTED` or `PERSISTENCE_FAILED` |
 *
 * ## Usage
 *
 * ```kotlin
 * @EventListener
 * fun onMessage(event: MessageEvent) {
 *     when (event.status) {
 *         MessageStatus.ADDED -> {
 *             // Message appeared in conversation - show in UI
 *         }
 *         MessageStatus.PERSISTED -> {
 *             // Message saved - can update UI indicator
 *         }
 *         MessageStatus.PERSISTENCE_FAILED -> {
 *             // Handle failure - event.error has details
 *         }
 *     }
 * }
 *
 * // Or filter to specific status:
 * @EventListener(condition = "#event.status.name() == 'ADDED'")
 * fun onMessageAdded(event: MessageEvent) { ... }
 * ```
 *
 * @param conversationId the conversation the message belongs to
 * @param status the current status of the message
 * @param fromUserId the ID of the user who sent this message (author)
 * @param toUserId the ID of the user who should receive this message (for routing, e.g., WebSocket)
 * @param title the session/conversation title (for UI display)
 * @param message the message (always present for ADDED, present for PERSISTED)
 * @param content the message content (useful for PERSISTENCE_FAILED when message ref may be stale)
 * @param role the message role
 * @param error the exception if persistence failed (present for PERSISTENCE_FAILED)
 * @param timestamp when the event occurred
 */
data class MessageEvent(
    val conversationId: String,
    val status: MessageStatus,
    val fromUserId: String? = null,
    val toUserId: String? = null,
    val title: String? = null,
    val message: Message? = null,
    val content: String? = null,
    val role: MessageRole? = null,
    val error: Throwable? = null,
    override val timestamp: Instant = Instant.now()
) : Timestamped {

    companion object {
        /**
         * Create an ADDED event - message was added to conversation.
         */
        fun added(
            conversationId: String,
            message: Message,
            fromUserId: String? = null,
            toUserId: String? = null,
            title: String? = null
        ) = MessageEvent(
            conversationId = conversationId,
            status = MessageStatus.ADDED,
            fromUserId = fromUserId,
            toUserId = toUserId,
            title = title,
            message = message,
            content = message.content,
            role = message.role
        )

        /**
         * Create a PERSISTED event - message was saved to storage.
         */
        fun persisted(
            conversationId: String,
            message: Message,
            fromUserId: String? = null,
            toUserId: String? = null,
            title: String? = null
        ) = MessageEvent(
            conversationId = conversationId,
            status = MessageStatus.PERSISTED,
            fromUserId = fromUserId,
            toUserId = toUserId,
            title = title,
            message = message,
            content = message.content,
            role = message.role
        )

        /**
         * Create a PERSISTENCE_FAILED event.
         */
        fun persistenceFailed(
            conversationId: String,
            content: String,
            role: MessageRole,
            error: Throwable,
            fromUserId: String? = null,
            toUserId: String? = null,
            title: String? = null
        ) = MessageEvent(
            conversationId = conversationId,
            status = MessageStatus.PERSISTENCE_FAILED,
            fromUserId = fromUserId,
            toUserId = toUserId,
            title = title,
            content = content,
            role = role,
            error = error
        )
    }
}
