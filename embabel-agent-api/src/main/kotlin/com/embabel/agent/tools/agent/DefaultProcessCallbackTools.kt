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
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.core.hitl.ConfirmationRequest
import com.embabel.agent.core.hitl.ConfirmationResponse
import com.embabel.agent.core.hitl.FormBindingRequest
import com.embabel.agent.core.hitl.ResponseImpact
import com.embabel.agent.spi.LlmInteraction
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelSelectionCriteria
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.annotation.Tool

/**
 * Default tools for handling agent processes
 */
class DefaultProcessCallbackTools(
    val autonomy: Autonomy,
    val textCommunicator: TextCommunicator,
) {

    private val logger = LoggerFactory.getLogger(DefaultProcessCallbackTools::class.java)


    @Tool(
        name = FORM_SUBMISSION_TOOL_NAME,
        description = "Resume a process by providing the process ID and form content",
    )
    fun submitFormAndResumeProcess(
        processId: String,
        formData: String,
    ): String {
        logger.info("Form submission tool called with processId: {}, form input: {}", processId, formData)
        val agentProcess = autonomy.agentPlatform.getAgentProcess(processId)
            ?: return "No process found with ID $processId"
        val formBindingRequest = agentProcess.lastResult() as? FormBindingRequest<Any>
            ?: return "No form binding request found for process $processId"
        val prompt = """
            Given the content below, return the given object

            # Content
            $formData
        """.trimIndent()
        val formDataObject = autonomy.agentPlatform.platformServices.llmOperations.doTransform(
            prompt = prompt,
            LlmInteraction.Companion.using(LlmOptions.Companion(criteria = ModelSelectionCriteria.Auto)),
            outputClass = formBindingRequest.outputClass,
            null,
        )
        val responseImpact = formBindingRequest.bind(
            boundInstance = formDataObject,
            agentProcess
        )
        if (responseImpact != ResponseImpact.UPDATED) {
            TODO("Handle unchanged response impact")
        }
        // Resume the agent process with the form data
        agentProcess.run()
        val ape = AgentProcessExecution.fromProcessStatus(formData, agentProcess)
        return textCommunicator.communicateResult(ape)
    }

    @Tool(
        name = CONFIRMATION_TOOL_NAME,
        description = "Resume a process by providing the process ID and form content",
    )
    fun confirmation(
        processId: String,
        confirmed: Boolean,
    ): String {
        logger.info("Confirmation tool called with processId: {}, confirmed: {}", processId, confirmed)
        val agentProcess = autonomy.agentPlatform.getAgentProcess(processId)
            ?: return "No process found with ID $processId"
        val confirmationRequest = agentProcess.lastResult() as? ConfirmationRequest<Any>
            ?: return "No confirmation binding request found for process $processId"
        val confirmationResponse = ConfirmationResponse(
            awaitableId = confirmationRequest.id,
            accepted = confirmed,
        )
        if (confirmationResponse.accepted) {
            agentProcess += confirmationRequest.payload
        } else {
            logger.info("Confirmation request rejected: {}", confirmationRequest.payload)
            // If the confirmation is rejected, we do not update the agent process
            return "Confirmation request rejected: ${confirmationRequest.payload}"
        }
        // Resume the agent process with the form data
        agentProcess.run()
        val ape = AgentProcessExecution.fromProcessStatus(confirmationRequest.payload, agentProcess)
        return textCommunicator.communicateResult(ape)
    }
}
