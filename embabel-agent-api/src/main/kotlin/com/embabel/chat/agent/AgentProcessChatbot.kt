/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
import com.embabel.agent.core.*
import com.embabel.agent.spi.common.Constants.EMBABEL_PROVIDER
import com.embabel.chat.ChatSession
import com.embabel.chat.Chatbot
import com.embabel.chat.Conversation
import com.embabel.chat.UserMessage
import com.embabel.chat.support.InMemoryConversation
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
 * Chatbot implementation backed by an AgentProcess
 * The AgentProcess must react to UserMessage and respond on its output channel
 * The AgentProcess can assume that the Conversation will be available in the blackboard,
 * and the latest UserMessage.
 * Action methods will often take precondition being that the last event
 * was a UserMessage. A convenient approach is for the core action methods to return ChatbotReturn, and handle ConversationOver,
 * although that is not required.
 * @param agentPlatform the agent platform to create and manage agent processes
 * @param agentSource factory for agents. The factory is called for each new session.
 * This allows lazy loading and more flexible usage patterns
 */
class AgentProcessChatbot(
    private val agentPlatform: AgentPlatform,
    private val agentSource: AgentSource,
    private val listenerProvider: ListenerProvider = ListenerProvider { _, _ -> emptyList() },
    private val plannerType: PlannerType = PlannerType.GOAP,
) : Chatbot {

    override fun createSession(
        user: User?,
        outputChannel: OutputChannel,
        systemMessage: String?,
    ): ChatSession {
        val listeners = listenerProvider.listenersFor(user, outputChannel)
        val agentProcess = agentPlatform.createAgentProcess(
            agent = agentSource.resolveAgent(user),
            processOptions = ProcessOptions(
                outputChannel = outputChannel,
                listeners = listeners,
                identities = Identities(
                    forUser = user,
                ),
                plannerType = plannerType,
            ),
            bindings = emptyMap(),
        )
        // We start the AgentProcess. It's likely to do nothing until
        // we receive a UserMessage, but that's fine as we may want to do some
        // work in the meantime
        return AgentProcessChatSession(agentProcess).apply {
            agentProcess.run()
        }
    }

    override fun findSession(conversationId: String): ChatSession? {
        return agentPlatform.getAgentProcess(conversationId)?.let { agentProcess ->
            AgentProcessChatSession(agentProcess)
        }
    }

    companion object {

        /**
         * Create a chatbot with the given agent. The agent is looked up by name from the agent platform.
         * @param agentPlatform the agent platform to create and manage agent processes
         * @param agentName the name of the agent to
         * @param listenerProvider provider for contextual event listeners
         */
        @JvmStatic
        @JvmOverloads
        fun withAgentByName(
            agentPlatform: AgentPlatform,
            agentName: String,
            listenerProvider: ListenerProvider = ListenerProvider { _, outputChannel ->
                listOf(OutputChannelHighlightingEventListener(outputChannel))
            },
        ): Chatbot = AgentProcessChatbot(
            agentPlatform = agentPlatform,
            agentSource = {
                agentPlatform.agents().find { it.name == agentName }
                    ?: throw IllegalArgumentException("No agent found with name $agentName")
            },
            listenerProvider = listenerProvider,
        )

        /**
         * Create a chatbot that will use all actions available on the platform,
         * with utility-based planning.
         */
        @JvmStatic
        @JvmOverloads
        fun utilityFromPlatform(
            agentPlatform: AgentPlatform,
            listenerProvider: ListenerProvider = ListenerProvider { _, outputChannel ->
                listOf(OutputChannelHighlightingEventListener(outputChannel))
            },
        ): Chatbot = AgentProcessChatbot(
            agentPlatform = agentPlatform,
            agentSource = {
                agentPlatform.createAgent(
                    name = "chatbot-agent",
                    provider = EMBABEL_PROVIDER,
                    description = "Chatbot agent",
                )
            },
            listenerProvider = listenerProvider,
            plannerType = PlannerType.UTILITY,
        )
    }

}

/**
 * Many instances for one AgentProcess.
 * Stores conversation in AgentProcess blackboard.
 */
private class AgentProcessChatSession(
    private val agentProcess: AgentProcess,
) : ChatSession {

    override val processId: String = agentProcess.id

    override fun isFinished(): Boolean = agentProcess.finished

    override val outputChannel: OutputChannel
        get() = agentProcess.processContext.outputChannel

    override val conversation = run {
        agentProcess[KEY] as? Conversation
            ?: run {
                val conversation = InMemoryConversation(id = agentProcess.id)
                agentProcess[KEY] = conversation
                conversation.also {
                    agentProcess.processContext.outputChannel.send(
                        LoggingOutputChannelEvent(
                            processId = agentProcess.id,
                            message = "Started chat session `${conversation.id}`",
                            level = LoggingOutputChannelEvent.Level.DEBUG,
                        )
                    )
                }
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
        loggerFor<AgentProcessChatSession>().info(
            "Agent process {} run completed with status {}",
            agentProcess.id,
            agentProcessRun.status
        )
    }

    companion object {
        const val KEY = "conversation"
    }
}
