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

import com.embabel.agent.api.common.autonomy.*
import com.embabel.agent.core.ProcessOptions
import com.embabel.chat.*

/**
 * Base class for agent platform chat sessions.
 * Uses last message as intent and delegates handling to agent platform.
 */
abstract class AgentPlatformChatSession(
    private val autonomy: Autonomy,
    private val goalChoiceApprover: GoalChoiceApprover,
    override val messageListener: MessageListener,
    val processOptions: ProcessOptions = ProcessOptions(),
) : ChatSession {

    private var internalConversation: Conversation = InMemoryConversation()

    override val conversation: Conversation
        get() = internalConversation

    override fun respond(message: UserMessage, additionalListener: MessageListener?) {
        internalConversation = conversation.withMessage(message)
        val assistantMessage = generateResponse(message)
        internalConversation = conversation.withMessage(assistantMessage)
        messageListener.onMessage(assistantMessage)
        additionalListener?.onMessage(assistantMessage)
    }

    protected fun generateResponse(message: UserMessage): AssistantMessage {
        try {
            val dynamicExecutionResult = autonomy.chooseAndAccomplishGoal(
                intent = message.content,
                processOptions = processOptions,
                goalChoiceApprover = goalChoiceApprover,
                agentScope = autonomy.agentPlatform,
                additionalBindings = if (shouldBindConversation())
                    mapOf("conversation" to conversation)
                else emptyMap()
            )
            val result = dynamicExecutionResult.output
            return AgenticResultAssistantMessage(
                agentProcessExecution = dynamicExecutionResult,
                content = result.toString(),
            )
        } catch (pwe: ProcessWaitingException) {
            return handleProcessWaitingException(pwe, message.content)
        } catch (_: NoGoalFound) {
            return AssistantMessage(
                content = """|
                    |I'm sorry Dave. I'm afraid I can't do that.
                    |
                    |Things I CAN do:
                    |${autonomy.agentPlatform.goals.joinToString("\n") { "- ${it.description}" }}
                """.trimMargin(),
            )
        } catch (_: GoalNotApproved) {
            return AssistantMessage(
                content = "I obey. That action will not be executed.",
            )
        }
    }

    /**
     * Determines whether conversation should be bound to the blackboard
     */
    protected abstract fun shouldBindConversation(): Boolean

    /**
     * Handles process waiting exceptions in a platform-specific way
     */
    protected abstract fun handleProcessWaitingException(pwe: ProcessWaitingException, basis: Any): AssistantMessage
}
