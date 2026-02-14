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

import com.embabel.agent.api.channel.LoggingOutputChannelEvent
import com.embabel.agent.api.channel.OutputChannel
import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.api.event.progress.OutputChannelHighlightingEventListener
import com.embabel.agent.api.identity.User
import com.embabel.agent.api.invocation.UtilityInvocation
import com.embabel.agent.core.*
import com.embabel.chat.ChatSession
import com.embabel.chat.Chatbot
import com.embabel.chat.Conversation
import com.embabel.chat.ConversationFactory
import com.embabel.chat.UserMessage
import com.embabel.chat.support.InMemoryConversationFactory
import com.embabel.common.util.loggerFor

fun interface AgentSource {

    fun resolveAgent(user: User?): Agent
}

fun interface ListenerProvider {

    fun listenersFor(
        user: User?,
        outputChannel: OutputChannel,
    ): List<AgenticEventListener>
}


/**
 * Chatbot implementation backed by an ongoing AgentProcess
 * The AgentProcess must react to UserMessage and respond on its output channel
 * The AgentProcess can assume that the Conversation will be available in the blackboard,
 * and the latest UserMessage.
 * Action methods will often take precondition being that the last event
 * was a UserMessage. A convenient approach is for the core action methods to return ChatbotReturn, and handle ConversationOver,
 * although that is not required.
 * @param agentPlatform the agent platform to create and manage agent processes
 * @param agentSource factory for agents. The factory is called for each new session.
 * This allows lazy loading and more flexible usage patterns
 * @param conversationFactory factory for creating conversations. Defaults to in-memory.
 * For persistent storage, inject a StoredConversationFactory from embabel-chat-store.
 */
class AgentProcessChatbot(
    private val agentPlatform: AgentPlatform,
    private val agentSource: AgentSource,
    private val conversationFactory: ConversationFactory = InMemoryConversationFactory(),
    private val listenerProvider: ListenerProvider = ListenerProvider { _, _ -> emptyList() },
    private val plannerType: PlannerType = PlannerType.GOAP,
    private val verbosity: Verbosity = Verbosity(),
) : Chatbot {

    override fun createSession(
        user: User?,
        outputChannel: OutputChannel,
        contextId: String?,
        conversationId: String?,
    ): ChatSession {
        // Try to load existing conversation if ID provided
        val existingConversation = conversationId?.let { conversationFactory.load(it) }

        val listeners = listenerProvider.listenersFor(user, outputChannel)
        val agentProcess = agentPlatform.createAgentProcess(
            agent = agentSource.resolveAgent(user),
            processOptions = ProcessOptions(
                contextId = contextId?.let { ContextId(it) },
                outputChannel = outputChannel,
                listeners = listeners,
                identities = Identities(
                    forUser = user,
                ),
                verbosity = verbosity,
                plannerType = plannerType,
            ),
            bindings = emptyMap(),
        )

        // Bind existing conversation to process, or let session create new one
        if (existingConversation != null) {
            agentProcess.bindProtected(AgentProcessChatSession.CONVERSATION_KEY, existingConversation)
        }

        // We start the AgentProcess. It's likely to do nothing until
        // we receive a UserMessage, but that's fine as we may want to do some
        // work in the meantime
        return AgentProcessChatSession(
            agentProcess = agentProcess,
            conversationFactory = conversationFactory,
            conversationId = conversationId,
        ).apply {
            agentProcess.run()
        }
    }

    override fun findSession(conversationId: String): ChatSession? {
        return agentPlatform.getAgentProcess(conversationId)?.let { agentProcess ->
            AgentProcessChatSession(agentProcess, conversationFactory)
        }
    }

    companion object {

        /**
         * Create a chatbot that will use all actions available on the platform,
         * with utility-based planning.
         *
         * @param agentPlatform the agent platform
         * @param conversationFactory factory for creating conversations. Defaults to in-memory.
         * @param verbosity verbosity settings for debugging
         * @param listenerProvider provider for event listeners
         */
        @JvmStatic
        @JvmOverloads
        fun utilityFromPlatform(
            agentPlatform: AgentPlatform,
            conversationFactory: ConversationFactory = InMemoryConversationFactory(),
            verbosity: Verbosity = Verbosity(),
            listenerProvider: ListenerProvider = ListenerProvider { _, outputChannel ->
                listOf(OutputChannelHighlightingEventListener(outputChannel))
            },
        ): Chatbot = AgentProcessChatbot(
            agentPlatform = agentPlatform,
            agentSource = {
                UtilityInvocation.on(agentPlatform).createPlatformAgent()
            },
            conversationFactory = conversationFactory,
            listenerProvider = listenerProvider,
            verbosity = verbosity,
            plannerType = PlannerType.UTILITY,
        )
    }

}

/**
 * Many instances for one AgentProcess.
 * Stores conversation in AgentProcess blackboard.
 */
internal class AgentProcessChatSession(
    private val agentProcess: AgentProcess,
    private val conversationFactory: ConversationFactory,
    private val conversationId: String? = null,
) : ChatSession {

    override val processId: String = agentProcess.id

    override fun isFinished(): Boolean = agentProcess.finished

    override val outputChannel: OutputChannel
        get() = agentProcess.processContext.outputChannel

    override val conversation: Conversation = run {
        // Check if conversation was pre-loaded (restored from storage)
        agentProcess[CONVERSATION_KEY] as? Conversation
            ?: run {
                // Create new conversation, preferring the caller-provided conversationId
                // over the agentProcess.id (which is a moby name like "gracious_turing")
                val conversation = conversationFactory.create(conversationId ?: agentProcess.id)
                agentProcess.bindProtected(CONVERSATION_KEY, conversation)
                agentProcess.processContext.outputChannel.send(
                    LoggingOutputChannelEvent(
                        processId = agentProcess.id,
                        message = "Started chat session `${conversation.id}`",
                        level = LoggingOutputChannelEvent.Level.DEBUG,
                    )
                )
                conversation
            }
    }

    override val user: User?
        get() = agentProcess.processContext.processOptions.identities.forUser

    override fun onUserMessage(
        userMessage: UserMessage,
    ) {
        conversation.addMessage(userMessage)
        agentProcess.addObject(userMessage)
        val agentProcessRun = agentProcess.run()
        loggerFor<AgentProcessChatSession>().debug(
            "Agent process {} run completed with status {}",
            agentProcess.id,
            agentProcessRun.status,
        )
    }

    companion object {
        const val CONVERSATION_KEY = "conversation"
    }
}
