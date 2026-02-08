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
package com.embabel.chat.agent

import com.embabel.agent.api.channel.DevNullOutputChannel
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.support.EmptyAgentBuilder.emptyAgent
import com.embabel.chat.Conversation
import com.embabel.chat.ConversationFactory
import com.embabel.chat.ConversationStoreType
import com.embabel.chat.UserMessage
import com.embabel.chat.support.InMemoryConversation
import com.embabel.chat.support.InMemoryConversationFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AgentProcessChatbotTest {

    @Test
    fun `createSession with no conversationId creates new conversation`() {
        val agentPlatform = mockk<AgentPlatform>()
        val agentProcess = mockk<AgentProcess>(relaxed = true)
        val factory = InMemoryConversationFactory()

        every { agentPlatform.createAgentProcess(any(), any(), any()) } returns agentProcess
        every { agentProcess.id } returns "process-123"
        every { agentProcess[any()] } returns null
        every { agentProcess.run() } returns mockk(relaxed = true)

        val chatbot = AgentProcessChatbot(
            agentPlatform = agentPlatform,
            agentSource = { emptyAgent() },
            conversationFactory = factory,
        )

        val session = chatbot.createSession(
            user = null,
            outputChannel = DevNullOutputChannel,
            contextId = null,
            conversationId = null,
        )

        assertNotNull(session)
        assertNotNull(session.conversation)
    }

    @Test
    fun `createSession with conversationId loads existing conversation`() {
        val agentPlatform = mockk<AgentPlatform>()
        val agentProcess = mockk<AgentProcess>(relaxed = true)
        val existingConversation = InMemoryConversation(
            id = "existing-conv-id",
            messages = listOf(UserMessage("previous message")),
        )
        val factory = mockk<ConversationFactory>()

        every { factory.load("existing-conv-id") } returns existingConversation
        every { factory.storeType } returns ConversationStoreType.STORED
        every { agentPlatform.createAgentProcess(any(), any(), any()) } returns agentProcess
        every { agentProcess.id } returns "process-123"
        every { agentProcess[AgentProcessChatSession.CONVERSATION_KEY] } returns existingConversation
        every { agentProcess.run() } returns mockk(relaxed = true)

        val chatbot = AgentProcessChatbot(
            agentPlatform = agentPlatform,
            agentSource = { emptyAgent() },
            conversationFactory = factory,
        )

        val session = chatbot.createSession(
            user = null,
            outputChannel = DevNullOutputChannel,
            contextId = null,
            conversationId = "existing-conv-id",
        )

        assertEquals(existingConversation, session.conversation)
        assertEquals(1, session.conversation.messages.size)
        verify { factory.load("existing-conv-id") }
        verify { agentProcess.bindProtected(AgentProcessChatSession.CONVERSATION_KEY, existingConversation) }
    }

    @Test
    fun `createSession with conversationId that does not exist creates new conversation`() {
        val agentPlatform = mockk<AgentPlatform>()
        val agentProcess = mockk<AgentProcess>(relaxed = true)
        val newConversation = InMemoryConversation(id = "new-conv")
        val factory = mockk<ConversationFactory>()

        every { factory.load("nonexistent-id") } returns null
        every { factory.create(any()) } returns newConversation
        every { factory.storeType } returns ConversationStoreType.STORED
        every { agentPlatform.createAgentProcess(any(), any(), any()) } returns agentProcess
        every { agentProcess.id } returns "process-123"
        every { agentProcess[AgentProcessChatSession.CONVERSATION_KEY] } returns null
        every { agentProcess.run() } returns mockk(relaxed = true)

        val chatbot = AgentProcessChatbot(
            agentPlatform = agentPlatform,
            agentSource = { emptyAgent() },
            conversationFactory = factory,
        )

        val session = chatbot.createSession(
            user = null,
            outputChannel = DevNullOutputChannel,
            contextId = null,
            conversationId = "nonexistent-id",
        )

        assertNotNull(session.conversation)
        verify { factory.load("nonexistent-id") }
        verify { factory.create(any()) }
    }

    @Test
    fun `utilityFromPlatform creates chatbot with default in-memory factory`() {
        val agentPlatform = mockk<AgentPlatform>(relaxed = true)

        val chatbot = AgentProcessChatbot.utilityFromPlatform(agentPlatform)

        assertNotNull(chatbot)
    }

    @Test
    fun `utilityFromPlatform creates chatbot with custom conversation factory`() {
        val agentPlatform = mockk<AgentPlatform>(relaxed = true)
        val customFactory = mockk<ConversationFactory>()
        every { customFactory.storeType } returns ConversationStoreType.STORED

        val chatbot = AgentProcessChatbot.utilityFromPlatform(
            agentPlatform = agentPlatform,
            conversationFactory = customFactory,
        )

        assertNotNull(chatbot)
    }

    @Test
    fun `findSession returns null when process not found`() {
        val agentPlatform = mockk<AgentPlatform>()
        every { agentPlatform.getAgentProcess("unknown-id") } returns null

        val chatbot = AgentProcessChatbot(
            agentPlatform = agentPlatform,
            agentSource = { emptyAgent() },
        )

        val session = chatbot.findSession("unknown-id")

        assertNull(session)
    }

    @Test
    fun `findSession returns session when process exists`() {
        val agentPlatform = mockk<AgentPlatform>()
        val agentProcess = mockk<AgentProcess>(relaxed = true)
        val conversation = InMemoryConversation(id = "conv-123")

        every { agentPlatform.getAgentProcess("conv-123") } returns agentProcess
        every { agentProcess.id } returns "conv-123"
        every { agentProcess[AgentProcessChatSession.CONVERSATION_KEY] } returns conversation

        val chatbot = AgentProcessChatbot(
            agentPlatform = agentPlatform,
            agentSource = { emptyAgent() },
        )

        val session = chatbot.findSession("conv-123")

        assertNotNull(session)
    }
}
