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

import com.embabel.chat.ConversationStoreType
import com.embabel.chat.UserMessage
import com.embabel.chat.event.MessageEvent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

class InMemoryConversationFactoryTest {

    @Test
    fun `storeType returns IN_MEMORY`() {
        val factory = InMemoryConversationFactory()
        assertEquals(ConversationStoreType.IN_MEMORY, factory.storeType)
    }

    @Test
    fun `create returns InMemoryConversation with given id`() {
        val factory = InMemoryConversationFactory()
        val conversation = factory.create("test-id")

        assertEquals("test-id", conversation.id)
        assertTrue(conversation.messages.isEmpty())
    }

    @Test
    fun `create returns different instances for same id`() {
        val factory = InMemoryConversationFactory()
        val conv1 = factory.create("same-id")
        val conv2 = factory.create("same-id")

        assertNotSame(conv1, conv2)
    }

    @Test
    fun `load returns null for in-memory factory`() {
        val factory = InMemoryConversationFactory()
        val result = factory.load("any-id")

        assertNull(result)
    }

    @Test
    fun `created conversation is not persistent`() {
        val factory = InMemoryConversationFactory()
        val conversation = factory.create("test-id")

        assertFalse(conversation.persistent())
    }

    @Test
    fun `create without event publisher returns a plain InMemoryConversation`() {
        val factory = InMemoryConversationFactory()

        assertTrue(factory.create("test-id") is InMemoryConversation)
    }

    @Test
    fun `create with event publisher wraps conversation and fires message events`() {
        val events = mutableListOf<Any>()
        val publisher = ApplicationEventPublisher { event -> events.add(event) }
        val factory = InMemoryConversationFactory(publisher)

        val conversation = factory.create("test-id")
        assertTrue(conversation is EventPublishingConversation)

        conversation.addMessage(UserMessage("Hello"))

        val messageEvent = events.filterIsInstance<MessageEvent>().single()
        assertEquals("test-id", messageEvent.conversationId)
    }
}
