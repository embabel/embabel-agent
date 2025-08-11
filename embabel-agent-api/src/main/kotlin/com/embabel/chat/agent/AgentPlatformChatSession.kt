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

import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.common.autonomy.GoalChoiceApprover
import com.embabel.agent.api.common.autonomy.PlanLister
import com.embabel.agent.api.common.autonomy.ProcessWaitingException
import com.embabel.agent.core.Blackboard
import com.embabel.agent.core.Goal
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.domain.io.UserInput
import com.embabel.chat.*


/**
 * Configuration for the chat session
 * @param confirmGoals Whether to confirm goals with the user before proceeding
 * @param bindConversation Whether to bind the conversation to the chat session
 */
data class ChatConfig(
    val confirmGoals: Boolean = true,
    val bindConversation: Boolean = false,
    val multiGoal: Boolean = false,
)

interface ResponseGenerator {
    fun generateResponses(
        message: UserMessage,
        conversation: Conversation,
        blackboard: Blackboard,
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
 * Uses last message as intent and delegates handling to agent platform.
 * Uses ProcessWaitingHandler to handle process waiting exceptions.
 */
class AgentPlatformChatSession(
    private val autonomy: Autonomy,
    private val planLister: PlanLister,
    private val goalChoiceApprover: GoalChoiceApprover,
    override val messageListener: MessageListener,
    val processOptions: ProcessOptions = ProcessOptions(),
    val processWaitingHandler: ProcessWaitingHandler,
    val chatConfig: ChatConfig = ChatConfig(),
    private val responseGenerator: ResponseGenerator = AutonomyResponseGenerator(
        autonomy = autonomy,
        goalChoiceApprover = goalChoiceApprover,
        processOptions = processOptions,
        processWaitingHandler = processWaitingHandler,
        chatConfig = chatConfig,
    ),
) : ChatSession {

    private var internalConversation: Conversation = InMemoryConversation()

    private val blackboard: Blackboard = processOptions.blackboard ?: InMemoryBlackboard()

    override val conversation: Conversation
        get() = internalConversation

    override fun respond(
        userMessage: UserMessage,
        additionalListener: MessageListener?,
    ) {
        internalConversation = conversation.withMessage(userMessage)
        generateResponses(userMessage = userMessage, messageListener = { message ->
            messageListener.onMessage(message)
            additionalListener?.onMessage(message)
        })
    }


    private fun generateResponses(
        userMessage: UserMessage,
        messageListener: MessageListener,
    ) {
        val handledCommand = handleAsCommand(userMessage)
        if (handledCommand != null) {
            messageListener.onMessage(handledCommand)
        } else {
            responseGenerator.generateResponses(
                userMessage,
                conversation,
                blackboard,
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
