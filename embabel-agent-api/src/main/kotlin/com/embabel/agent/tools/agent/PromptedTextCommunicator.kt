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
package com.embabel.agent.tools.agent

import com.embabel.agent.api.common.autonomy.AgentProcessExecution
import com.embabel.agent.api.common.autonomy.ProcessWaitingException
import com.embabel.agent.core.Goal
import com.embabel.agent.core.hitl.ConfirmationRequest
import com.embabel.agent.core.hitl.FormBindingRequest
import com.embabel.agent.domain.library.HasContent
import com.embabel.common.core.types.HasInfoString

/**
 * Prompted awaitable communicator
 */
object PromptedTextCommunicator : TextCommunicator {

    override fun communicateResult(agentProcessExecution: AgentProcessExecution): String {
        return when (val output = agentProcessExecution.output) {
            is String -> output
            is HasInfoString -> {
                output.infoString(verbose = true)
            }

            is HasContent -> output.content
            else -> output.toString()
        }
    }

    override fun communicateAwaitable(
        goal: Goal,
        pwe: ProcessWaitingException,
    ): String {
        return when (pwe.awaitable) {
            is FormBindingRequest<*> -> """
                You must invoke the $FORM_SUBMISSION_TOOL_NAME tool to proceed with the goal "${goal.name}".
                The arguments will be
                - processId: ${pwe.agentProcess.id},
                - formData: English text describing the form data to submit. See below

                Before invoking this, you must obtain information from the user
                as described in this form structure.
                ${pwe.awaitable.toString()}
                """.trimIndent()

            is ConfirmationRequest<*> ->
                """
                Please ask the user to confirm before proceeding with the goal "${goal.name}".
                The confirmation request is as follows:
                '${pwe.awaitable.message}'
                Use your judgment to determine how to ask the user for confirmation
                and what confirmation will be acceptable.

                Once the user has responded, you must invoke the $CONFIRMATION_TOOL_NAME tool
                with the following arguments:
                - awaitableId: ${pwe.agentProcess.id}
                - confirmed: true if the user confirmed, false if they rejected the request.
                """.trimIndent()

            else -> {
                TODO("HITL error: Unsupported Awaitable type: ${pwe.awaitable.infoString(verbose = true)}")
            }
        }
    }
}
