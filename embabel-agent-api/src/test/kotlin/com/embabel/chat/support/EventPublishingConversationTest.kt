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

import com.embabel.agent.api.identity.SimpleUser
import com.embabel.chat.AssistantMessage
import com.embabel.chat.UserMessage
import com.embabel.chat.event.MessageEvent
import com.embabel.chat.event.MessageStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

class EventPublishingConversationTest {

    private class RecordingEventPublisher : ApplicationEventPublisher {
        val events = mutableListOf<Any>()
        override fun publishEvent(event: Any) {
            events.add(event)
        }

        fun messageEvents(): List<MessageEvent> = events.filterIsInstance<MessageEvent>()
    }

    @Test
    fun `addMessage delegates to the conversation and publishes an ADDED event`() {
        val publisher = RecordingEventPublisher()
        val delegate = InMemoryConversation(id = "c1")
        val conversation = EventPublishingConversation(delegate, publisher)

        val message = UserMessage("Hello!")
        val returned = conversation.addMessage(message)

        assertSame(message, returned)
        assertEquals(listOf(message), delegate.messages)
        assertEquals(listOf(message), conversation.messages)

        val event = publisher.messageEvents().single()
        assertEquals("c1", event.conversationId)
        assertEquals(MessageStatus.ADDED, event.status)
        assertSame(message, event.message)
    }

    @Test
    fun `addMessageFrom publishes event with the author id as sender`() {
        val publisher = RecordingEventPublisher()
        val conversation = EventPublishingConversation(InMemoryConversation(id = "c2"), publisher)
        val author = SimpleUser("u-1", "Alice", "alice", null)

        conversation.addMessageFrom(UserMessage("Hi"), author)

        val event = publisher.messageEvents().single()
        assertEquals("c2", event.conversationId)
        assertEquals(MessageStatus.ADDED, event.status)
        assertEquals("u-1", event.fromUserId)
    }

    @Test
    fun `addMessageFromTo publishes event with both sender and recipient ids`() {
        val publisher = RecordingEventPublisher()
        val conversation = EventPublishingConversation(InMemoryConversation(id = "c3"), publisher)
        val from = SimpleUser("from-1", "Alice", "alice", null)
        val to = SimpleUser("to-1", "Bob", "bob", null)

        conversation.addMessageFromTo(AssistantMessage("Reply"), from, to)

        val event = publisher.messageEvents().single()
        assertEquals("from-1", event.fromUserId)
        assertEquals("to-1", event.toUserId)
    }

    @Test
    fun `default sender and recipient ids are used when a message supplies none`() {
        val publisher = RecordingEventPublisher()
        val conversation = EventPublishingConversation(
            InMemoryConversation(id = "c4"),
            publisher,
            fromUserId = "default-from",
            toUserId = "default-to",
        )

        conversation.addMessage(UserMessage("Hello"))

        val event = publisher.messageEvents().single()
        assertEquals("default-from", event.fromUserId)
        assertEquals("default-to", event.toUserId)
    }

    @Test
    fun `read-through properties reflect the delegate`() {
        val delegate = InMemoryConversation(id = "c5")
        val conversation = EventPublishingConversation(delegate, RecordingEventPublisher())

        assertEquals(delegate.id, conversation.id)
        assertSame(delegate.assetTracker, conversation.assetTracker)
        assertFalse(conversation.persistent())
    }
}
