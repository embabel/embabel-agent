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

import com.embabel.agent.api.identity.SimpleUser
import com.embabel.agent.api.identity.User
import com.embabel.chat.support.InMemoryConversation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [Conversation] interface default methods.
 */
class ConversationMethodsTest {

    private fun createTestUser(id: String = "user-1"): User = SimpleUser(
        id = id,
        displayName = "Test User",
        username = "testuser",
        email = "test@example.com"
    )

    @Nested
    inner class AddMessageFromTests {

        @Test
        fun `addMessageFrom delegates to addMessage by default`() {
            val conversation = InMemoryConversation()
            val message = UserMessage("Hello")
            val user = createTestUser()

            val result = conversation.addMessageFrom(message, user)

            assertThat(result).isSameAs(message)
            assertThat(conversation.messages).hasSize(1)
            assertThat(conversation.messages[0]).isSameAs(message)
        }

        @Test
        fun `addMessageFrom works with null author`() {
            val conversation = InMemoryConversation()
            val message = SystemMessage("System message")

            val result = conversation.addMessageFrom(message, null)

            assertThat(result).isSameAs(message)
            assertThat(conversation.messages).hasSize(1)
        }

        @Test
        fun `addMessageFrom works with AssistantMessage`() {
            val conversation = InMemoryConversation()
            val message = AssistantMessage("Hi there")

            val result = conversation.addMessageFrom(message, null)

            assertThat(result).isSameAs(message)
            assertThat(conversation.messages).hasSize(1)
            assertThat(conversation.messages[0].role).isEqualTo(MessageRole.ASSISTANT)
        }
    }

    @Nested
    inner class AddMessageFromToTests {

        @Test
        fun `addMessageFromTo delegates to addMessage by default`() {
            val conversation = InMemoryConversation()
            val message = UserMessage("Hello")
            val fromUser = createTestUser("sender")
            val toUser = createTestUser("recipient")

            val result = conversation.addMessageFromTo(message, fromUser, toUser)

            assertThat(result).isSameAs(message)
            assertThat(conversation.messages).hasSize(1)
            assertThat(conversation.messages[0]).isSameAs(message)
        }

        @Test
        fun `addMessageFromTo works with null from and to`() {
            val conversation = InMemoryConversation()
            val message = SystemMessage("Broadcast message")

            val result = conversation.addMessageFromTo(message, null, null)

            assertThat(result).isSameAs(message)
            assertThat(conversation.messages).hasSize(1)
        }

        @Test
        fun `addMessageFromTo works with only from user`() {
            val conversation = InMemoryConversation()
            val message = UserMessage("Message to all")
            val fromUser = createTestUser()

            val result = conversation.addMessageFromTo(message, fromUser, null)

            assertThat(result).isSameAs(message)
            assertThat(conversation.messages).hasSize(1)
        }

        @Test
        fun `addMessageFromTo works with only to user`() {
            val conversation = InMemoryConversation()
            val message = AssistantMessage("Private reply")
            val toUser = createTestUser()

            val result = conversation.addMessageFromTo(message, null, toUser)

            assertThat(result).isSameAs(message)
            assertThat(conversation.messages).hasSize(1)
        }
    }

    @Nested
    inner class LastMessageIfInRoleTests {

        @Test
        fun `returns null for empty conversation`() {
            val conversation = InMemoryConversation()

            val result = conversation.lastMessageIfInRole(MessageRole.USER)

            assertThat(result).isNull()
        }

        @Test
        fun `returns message when last message matches single role`() {
            val conversation = InMemoryConversation()
            val userMessage = UserMessage("Hello")
            conversation.addMessage(userMessage)

            val result = conversation.lastMessageIfInRole(MessageRole.USER)

            assertThat(result).isSameAs(userMessage)
        }

        @Test
        fun `returns null when last message does not match role`() {
            val conversation = InMemoryConversation()
            conversation.addMessage(UserMessage("Hello"))
            conversation.addMessage(AssistantMessage("Hi there"))

            val result = conversation.lastMessageIfInRole(MessageRole.USER)

            assertThat(result).isNull()
        }

        @Test
        fun `returns message when last message matches one of multiple roles`() {
            val conversation = InMemoryConversation()
            conversation.addMessage(AssistantMessage("Hi there"))

            val result = conversation.lastMessageIfInRole(MessageRole.USER, MessageRole.ASSISTANT)

            assertThat(result).isNotNull
            assertThat(result!!.role).isEqualTo(MessageRole.ASSISTANT)
        }

        @Test
        fun `returns null when last message matches none of multiple roles`() {
            val conversation = InMemoryConversation()
            conversation.addMessage(SystemMessage("System notification"))

            val result = conversation.lastMessageIfInRole(MessageRole.USER, MessageRole.ASSISTANT)

            assertThat(result).isNull()
        }

        @Test
        fun `only checks last message not earlier ones`() {
            val conversation = InMemoryConversation()
            conversation.addMessage(UserMessage("Hello"))
            conversation.addMessage(AssistantMessage("Hi"))
            conversation.addMessage(SystemMessage("Notification"))

            val result = conversation.lastMessageIfInRole(MessageRole.USER)

            assertThat(result).isNull()
        }
    }

    @Suppress("DEPRECATION")
    @Nested
    inner class LastMessageIfBeFromUserTests {

        @Test
        fun `lastMessageIfBeFromUser returns null for empty conversation`() {
            val conversation = InMemoryConversation()

            val result = conversation.lastMessageIfBeFromUser()

            assertThat(result).isNull()
        }

        @Test
        fun `lastMessageIfBeFromUser returns UserMessage when last message is from user`() {
            val conversation = InMemoryConversation()
            val userMessage = UserMessage("Hello")
            conversation.addMessage(userMessage)

            val result = conversation.lastMessageIfBeFromUser()

            assertThat(result).isSameAs(userMessage)
        }

        @Test
        fun `lastMessageIfBeFromUser returns null when last message is from assistant`() {
            val conversation = InMemoryConversation()
            conversation.addMessage(UserMessage("Hello"))
            conversation.addMessage(AssistantMessage("Hi there"))

            val result = conversation.lastMessageIfBeFromUser()

            assertThat(result).isNull()
        }

        @Test
        fun `lastMessageIfBeFromUser returns null when last message is system`() {
            val conversation = InMemoryConversation()
            conversation.addMessage(SystemMessage("System notification"))

            val result = conversation.lastMessageIfBeFromUser()

            assertThat(result).isNull()
        }
    }
}
