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
import com.embabel.agent.api.channel.OutputChannelEvent
import com.embabel.agent.api.reference.LlmReference
import com.embabel.agent.api.identity.SimpleUser
import com.embabel.agent.api.identity.User
import com.embabel.agent.core.hitl.ConfirmationRequest
import com.embabel.chat.support.InMemoryConversation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Tests for [ChatSession] default methods.
 */
class ChatSessionTest {

    @Nested
    inner class SaveAndSendTests {

        @Test
        fun `saveAndSend adds message to conversation`() {
            val (session, _) = createTestSession()
            val message = AssistantMessage("Hello, world!")

            session.saveAndSend(message)

            assertThat(session.conversation.messages).hasSize(1)
            assertThat(session.conversation.messages[0].content).isEqualTo("Hello, world!")
        }

        @Test
        fun `saveAndSend sends message to output channel`() {
            val (session, channel) = createTestSession()
            val message = AssistantMessage("Hello, world!")

            session.saveAndSend(message)

            assertThat(channel.events).hasSize(1)
            val event = channel.events[0] as MessageOutputChannelEvent
            assertThat(event.message.content).isEqualTo("Hello, world!")
        }

        @Test
        fun `saveAndSend preserves awaitable on sent message`() {
            val (session, channel) = createTestSession()
            val confirmationRequest = ConfirmationRequest(
                payload = "test payload",
                message = "Please confirm",
            )
            val message = AssistantMessage(
                content = "Please confirm this action",
                awaitable = confirmationRequest,
            )

            session.saveAndSend(message)

            val event = channel.events[0] as MessageOutputChannelEvent
            val sentMessage = event.message as AssistantMessage
            assertThat(sentMessage.awaitable).isNotNull
            assertThat(sentMessage.awaitable).isSameAs(confirmationRequest)
        }

        @Test
        fun `saveAndSend preserves assets on sent message`() {
            val (session, channel) = createTestSession()
            val asset = TestAsset("asset-1", "Test Asset")
            val message = AssistantMessage(
                content = "Here's your result",
                assets = listOf(asset),
            )

            session.saveAndSend(message)

            val event = channel.events[0] as MessageOutputChannelEvent
            val sentMessage = event.message as AssistantMessage
            assertThat(sentMessage.assets).hasSize(1)
            assertThat(sentMessage.assets[0].id).isEqualTo("asset-1")
        }

        @Test
        fun `saveAndSend preserves both awaitable and assets`() {
            val (session, channel) = createTestSession()
            val confirmationRequest = ConfirmationRequest(
                payload = "payload",
                message = "Confirm?",
            )
            val asset = TestAsset("asset-1", "Asset")
            val message = AssistantMessage(
                content = "Message with both",
                awaitable = confirmationRequest,
                assets = listOf(asset),
            )

            session.saveAndSend(message)

            val event = channel.events[0] as MessageOutputChannelEvent
            val sentMessage = event.message as AssistantMessage
            assertThat(sentMessage.awaitable).isSameAs(confirmationRequest)
            assertThat(sentMessage.assets).hasSize(1)
        }

        @Test
        fun `saveAndSend preserves message name`() {
            val (session, channel) = createTestSession()
            val message = AssistantMessage(
                content = "Hello",
                name = "CustomAssistant",
            )

            session.saveAndSend(message)

            val event = channel.events[0] as MessageOutputChannelEvent
            val sentMessage = event.message as AssistantMessage
            assertThat(sentMessage.name).isEqualTo("CustomAssistant")
        }

        @Test
        fun `saveAndSend uses session processId`() {
            val (session, channel) = createTestSession(processId = "test-process-123")
            val message = AssistantMessage("Hello")

            session.saveAndSend(message)

            val event = channel.events[0] as MessageOutputChannelEvent
            assertThat(event.processId).isEqualTo("test-process-123")
        }

        @Test
        fun `saveAndSend uses anonymous when processId is null`() {
            val (session, channel) = createTestSession(processId = null)
            val message = AssistantMessage("Hello")

            session.saveAndSend(message)

            val event = channel.events[0] as MessageOutputChannelEvent
            assertThat(event.processId).isEqualTo("anonymous")
        }
    }

    @Nested
    inner class OnTriggerTests {

        @Test
        fun `default onTrigger is a no-op`() {
            val (session, channel) = createTestSession()
            val trigger = EventTrigger(
                prompt = "Greet the user",
                onBehalfOf = listOf(
                    SimpleUser(id = "u1", displayName = "Alice", username = "alice", email = null)
                ),
            )

            session.onTrigger(trigger)

            assertThat(session.conversation.messages).isEmpty()
            assertThat(channel.events).isEmpty()
        }
    }

    private fun createTestSession(processId: String? = "test-process"): Pair<ChatSession, RecordingOutputChannel> {
        val channel = RecordingOutputChannel()
        val session = TestChatSession(
            outputChannel = channel,
            conversation = InMemoryConversation(),
            testProcessId = processId,
        )
        return session to channel
    }

    private class TestChatSession(
        override val outputChannel: OutputChannel,
        override val conversation: Conversation,
        private val testProcessId: String?,
    ) : ChatSession {
        override val user: User? = null
        override val processId: String? = testProcessId

        override fun onUserMessage(userMessage: UserMessage) {
            conversation.addMessage(userMessage)
        }
    }

    private class RecordingOutputChannel : OutputChannel {
        val events = mutableListOf<OutputChannelEvent>()

        override fun send(event: OutputChannelEvent) {
            events.add(event)
        }
    }

    private class TestAsset(
        override val id: String,
        val name: String,
        override val timestamp: Instant = Instant.now(),
    ) : Asset {
        override fun persistent(): Boolean = false
        override fun reference(): LlmReference = LlmReference.of(name, "Test asset $name", emptyList())
    }
}
