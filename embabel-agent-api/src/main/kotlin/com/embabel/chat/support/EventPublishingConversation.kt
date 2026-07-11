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
package com.embabel.chat.support

import com.embabel.agent.api.identity.User
import com.embabel.chat.Conversation
import com.embabel.chat.Message
import com.embabel.chat.event.MessageEvent
import org.springframework.context.ApplicationEventPublisher

/**
 * A decorator that wraps any [Conversation] to publish [MessageEvent]s when messages are added.
 *
 * This enables event-driven patterns for any conversation implementation, including
 * [InMemoryConversation].
 *
 * ## Usage
 *
 * ```kotlin
 * val conversation = InMemoryConversation(id = "session-1")
 * val eventPublishing = EventPublishingConversation(conversation, applicationEventPublisher)
 *
 * // Now fires MessageEvent(status=ADDED) on every addMessage
 * eventPublishing.addMessage(UserMessage("Hello!"))
 * ```
 *
 * ## Event Flow
 *
 * This decorator fires [MessageEvent] with status [MessageStatus.ADDED][com.embabel.chat.event.MessageStatus.ADDED]
 * synchronously after the delegate adds the message.
 *
 * For persistent conversations (like StoredConversation), additional events
 * ([MessageStatus.PERSISTED][com.embabel.chat.event.MessageStatus.PERSISTED] or
 * [MessageStatus.PERSISTENCE_FAILED][com.embabel.chat.event.MessageStatus.PERSISTENCE_FAILED])
 * may be fired asynchronously by the underlying implementation.
 *
 * @param delegate the underlying Conversation implementation
 * @param eventPublisher Spring's event publisher
 * @param fromUserId optional default sender ID for published events
 * @param toUserId optional default recipient ID for published events
 */
class EventPublishingConversation(
    private val delegate: Conversation,
    private val eventPublisher: ApplicationEventPublisher,
    private val fromUserId: String? = null,
    private val toUserId: String? = null
) : Conversation by delegate {

    /**
     * Add a message and publish a [MessageEvent] with status ADDED.
     *
     * The event is published synchronously after the delegate adds the message.
     */
    override fun addMessage(message: Message): Message {
        return delegate.addMessage(message).also { added ->
            eventPublisher.publishEvent(MessageEvent.added(delegate.id, added, fromUserId, toUserId))
        }
    }

    override fun addMessageFrom(message: Message, author: User?): Message {
        return delegate.addMessageFrom(message, author).also { added ->
            eventPublisher.publishEvent(MessageEvent.added(delegate.id, added, author?.id ?: fromUserId, toUserId))
        }
    }

    override fun addMessageFromTo(
        message: Message,
        from: User?,
        to: User?
    ): Message {
        return delegate.addMessageFromTo(message, from, to).also { added ->
            eventPublisher.publishEvent(MessageEvent.added(delegate.id, added, from?.id ?: fromUserId, to?.id ?: toUserId))
        }
    }
}
