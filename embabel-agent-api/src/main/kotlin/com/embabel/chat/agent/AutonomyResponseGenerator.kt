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
import com.embabel.agent.domain.io.UserInput
import com.embabel.chat.*

/**
 * Respond to messages by choosing and executing goals using Autonomy.
 * Based on last user input.
 */
class AutonomyResponseGenerator(
    private val autonomy: Autonomy,
    private val goalChoiceApprover: GoalChoiceApprover,
    val processWaitingHandler: ProcessWaitingHandler,
    val chatConfig: ChatConfig,
) : ResponseGenerator {

    override fun generateResponses(
        message: UserMessage,
        conversation: Conversation,
        processOptions: ProcessOptions,
        messageListener: MessageListener,
    ) {
        val bindings = buildMap {
            put("userInput", UserInput(message.content))
            if (chatConfig.bindConversation)
                put("conversation", conversation)
        }
        try {
            val dynamicExecutionResult = autonomy.chooseAndAccomplishGoal(
                // We continue to use the same blackboard.
                processOptions = processOptions,
                goalChoiceApprover = goalChoiceApprover,
                agentScope = autonomy.agentPlatform,
                bindings = bindings,
                goalSelectionOptions = GoalSelectionOptions(
                    multiGoal = chatConfig.multiGoal,
                ),
            )
            val result = dynamicExecutionResult.output
            // Bind the result to the blackboard.
            dynamicExecutionResult.agentProcess += result
            messageListener.onMessage(
                AgenticResultAssistantMessage(
                    agentProcessExecution = dynamicExecutionResult,
                    content = result.toString(),
                )
            )
        } catch (pwe: ProcessWaitingException) {
            val assistantMessage = processWaitingHandler.handleProcessWaitingException(pwe, message.content)
            messageListener.onMessage(assistantMessage)
        } catch (_: NoGoalFound) {
            messageListener.onMessage(
                AssistantMessage(
                    content = """|
                    |I'm sorry Dave. I'm afraid I can't do that.
                    |
                    |Things I CAN do:
                    |${autonomy.agentPlatform.goals.joinToString("\n") { "- ${it.description}" }}
                """.trimMargin(),
                )
            )
        } catch (_: GoalNotApproved) {
            messageListener.onMessage(
                AssistantMessage(
                    content = "I obey. That action will not be executed.",
                )
            )
        }
    }

}
