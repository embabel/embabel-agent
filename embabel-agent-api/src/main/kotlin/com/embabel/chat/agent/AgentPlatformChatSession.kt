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

import com.embabel.agent.api.common.autonomy.PlanLister
import com.embabel.agent.api.common.autonomy.ProcessWaitingException
import com.embabel.agent.config.models.OpenAiModels
import com.embabel.agent.core.Blackboard
import com.embabel.agent.core.Goal
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.event.AgentProcessEvent
import com.embabel.agent.event.AgenticEventListener
import com.embabel.agent.event.ObjectBindingEvent
import com.embabel.agent.identity.User
import com.embabel.chat.*
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.util.loggerFor


/**
 * Configuration for the chat session
 * @param confirmGoals Whether to confirm goals with the user before proceeding
 * @param bindConversation Whether to bind the conversation to the chat session
 */
data class ChatConfig(
    val confirmGoals: Boolean = true,
    val bindConversation: Boolean = false,
    val multiGoal: Boolean = false,
    val model: String = OpenAiModels.GPT_41_MINI,
    val temperature: Double? = null,
) {

    /**
     * Options for the LLM used in the chat session
     */
    val llm: LlmOptions = LlmOptions
        .withModel(model)
        .withTemperature(temperature)
}

/**
 * Generates response(s) in a chat session.
 */
interface ResponseGenerator {

    /**
     * Generate response(s) in this conversation
     * @param conversation Current conversation state, hopefully including new message
     * from the user
     * @param processOptions Options for the process, including blackboard
     * @param messageListener Listener to send created messages to
     */
    fun generateResponses(
        conversation: Conversation,
        processOptions: ProcessOptions,
        messageListener: MessageListener,
    )
}

/**
 * Handles process waiting exceptions in a platform-specific way
 */
interface ProcessWaitingHandler {

    fun handleProcessWaitingException(
        pwe: ProcessWaitingException,
        basis: Any,
    ): AssistantMessage
}

/**
 * Support for chat sessions leveraging an AgentPlatform.
 */
class AgentPlatformChatSession(
    override val user: User?,
    private val planLister: PlanLister,
    val processOptions: ProcessOptions = ProcessOptions(),
    val responseGenerator: ResponseGenerator,
    override val conversation: Conversation = InMemoryConversation(),
) : ChatSession {

    private val blackboard: Blackboard = processOptions.blackboard ?: InMemoryBlackboard()

    override fun respond(
        userMessage: UserMessage,
        messageListener: MessageListener,
    ) {
        conversation.addMessage(userMessage)
        generateResponses(userMessage = userMessage, messageListener = { message, conversation ->
            messageListener.onMessage(message, conversation)
        })
    }

    private fun generateResponses(
        userMessage: UserMessage,
        messageListener: MessageListener,
    ) {
        // TODO could this be generic with subprocesses?
        val outerBindingListener = object : AgenticEventListener {
            override fun onProcessEvent(event: AgentProcessEvent) {
                if (event is ObjectBindingEvent) {
                    when (event.value) {
                        // An AssistantMessage being bound will cause the second process to think its complete
                        is Conversation, is Message -> {
                            loggerFor<AgentPlatformChatSession>().info(
                                "Ignoring subagent binding of type {}",
                                event.type,
                            )
                        }

                        else -> {
                            loggerFor<AgentPlatformChatSession>().info(
                                "Promoting subagent binding of type {}",
                                event.type,
                            )
                            blackboard.addObject(event.value)
                        }
                    }
                }
            }
        }
        val handledCommand = handleAsCommand(userMessage)
        if (handledCommand != null) {
            messageListener.onMessage(handledCommand, conversation)
        } else {
            responseGenerator.generateResponses(
                conversation = conversation,
                processOptions = processOptions.copy(
                    blackboard = blackboard,
                    listeners = listOf(outerBindingListener),
                ),
                messageListener = messageListener
            )
        }
    }

    private fun handleAsCommand(message: UserMessage): AssistantMessage? {
        return parseSlashCommand(message.content)?.let { (command, args) ->
            when (command) {
                "help" -> AssistantMessage(
                    content = """
                        |Available commands:
                        |/help - Show this help message
                        |/bb, blackboard - Show the blackboard
                        |/plans - Show possible plans from here, with the existing blackboard and user input
                    """.trimMargin(),
                )

                "bb", "blackboard" -> {
                    AssistantMessage(blackboard.infoString(verbose = true, indent = 2))
                }

                "plans" -> {
                    val plans = planLister.achievablePlans(
                        processOptions.copy(blackboard = blackboard),
                        mapOf("userInput" to UserInput("won't be used"))
                    )
                    AssistantMessage("Plans:\n\t" + plans.joinToString("\n\t") {
                        ((it.goal as? Goal)?.description) ?: it.goal.name
                    })
                }

                else -> AssistantMessage("Unrecognized slash command $command")
            }
        }
    }
}

val SLASH_COMMAND_REGEX = Regex("^/([a-zA-Z0-9_-]+)(?:\\s+(.*))?$")

fun parseSlashCommand(input: String): Pair<String, List<String>>? {
    val match = SLASH_COMMAND_REGEX.matchEntire(input)
    return if (match != null) {
        val command = match.groupValues[1]
        val argsString = match.groupValues[2]
        val args = if (argsString.isNotEmpty()) {
            argsString.trim().split("\\s+".toRegex())
        } else {
            emptyList()
        }
        Pair(command, args)
    } else {
        null
    }
}
