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
package com.embabel.chat.agent.shell

import com.embabel.agent.api.common.autonomy.*
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.shell.TerminalServices
import com.embabel.agent.shell.config.ShellProperties
import com.embabel.chat.*
import com.embabel.chat.agent.AgentPlatformChatSession

/**
 * Shell-specific implementation of agent platform chat session.
 * Uses last message as intent and delegates handling to agent platform.
 * Can bind conversation to the blackboard if so configured.
 */
class LastMessageIntentAgentPlatformChatSession(
    autonomy: Autonomy,
    goalChoiceApprover: GoalChoiceApprover,
    messageListener: MessageListener,
    processOptions: ProcessOptions = ProcessOptions(),
    private val terminalServices: TerminalServices,
    private val config: ShellProperties.ChatConfig,
) : AgentPlatformChatSession(autonomy, goalChoiceApprover, messageListener, processOptions) {

    override fun shouldBindConversation(): Boolean = config.bindConversation

    override fun handleProcessWaitingException(pwe: ProcessWaitingException, basis: Any): AssistantMessage {
        val awaitableResponse = terminalServices.handleProcessWaitingException(pwe)
        if (awaitableResponse == null) {
            return AssistantMessage("Operation cancelled.")
        }
        pwe.awaitable.onResponse(
            response = awaitableResponse,
            processContext = pwe.agentProcess!!.processContext
        )
        try {
            val agentProcess = pwe.agentProcess!!
            agentProcess.run()
            val ape = AgentProcessExecution.fromProcessStatus(
                basis = basis,
                agentProcess = agentProcess
            )
            return AgenticResultAssistantMessage(
                agentProcessExecution = ape,
                content = ape.output.toString(),
            )
        } catch (e: ProcessWaitingException) {
            return handleProcessWaitingException(e, basis)
        }
    }
}
