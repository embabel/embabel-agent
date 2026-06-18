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
package com.embabel.agent.api.tool.hitl

import com.embabel.agent.api.tool.DelegatingTool
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.ToolCallContext
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.hitl.AwaitableResponseException
import com.embabel.agent.core.hitl.ConfirmationRequest
import com.embabel.agent.core.hitl.ConfirmationResponse
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory

/**
 * Tool decorator that enables the LLM to resolve a [ConfirmationRequest] autonomously,
 * without requiring a separate human-provided [ConfirmationResponse].
 *
 * **Flow:**
 * 1. First call (no pending confirmation): throws [AwaitableResponseException] with a
 *    [ConfirmationRequest], pausing the agent.
 * 2. Subsequent call (confirmation pending): the tool's schema dynamically adds an `accepted`
 *    boolean. The LLM provides `accepted=true` (optionally with updated parameters) or
 *    `accepted=false` to cancel. The tool records a [ConfirmationResponse] and either
 *    delegates execution or returns a cancellation message.
 *
 * @param delegate The tool to wrap
 * @param messageProvider Function to generate the confirmation message from input
 * @param objectMapper ObjectMapper for JSON manipulation
 */
class ConfirmationAwareTool @JvmOverloads constructor(
    override val delegate: Tool,
    private val messageProvider: (String) -> String,
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
) : DelegatingTool {

    private val logger = LoggerFactory.getLogger(ConfirmationAwareTool::class.java)

    private val pendingDefinition: Tool.Definition = delegate.definition.withParameter(
        Tool.Parameter(
            name = "accepted",
            type = Tool.ParameterType.BOOLEAN,
            description = "Confirm (true) to proceed, or cancel (false) to abort",
            required = true,
        )
    )

    override val definition: Tool.Definition
        get() = if (isPending()) pendingDefinition else delegate.definition

    override val metadata: Tool.Metadata = delegate.metadata

    override fun call(input: String, context: ToolCallContext): Tool.Result {
        return if (isPending()) {
            logger.debug("ConfirmationAwareTool [{}]: handling confirmation response", delegate.definition.name)
            handleConfirmation(input, context)
        } else {
            logger.debug("ConfirmationAwareTool [{}]: raising ConfirmationRequest", delegate.definition.name)
            throw AwaitableResponseException(
                ConfirmationRequest(payload = input, message = messageProvider(input))
            )
        }
    }

    private fun handleConfirmation(input: String, context: ToolCallContext): Tool.Result {
        val agentProcess = AgentProcess.get()
            ?: throw IllegalStateException("No AgentProcess available for ConfirmationAwareTool")
        val pending = agentProcess.last(ConfirmationRequest::class.java)
            ?: throw IllegalStateException("No pending ConfirmationRequest found")

        val node = objectMapper.readTree(input) as ObjectNode
        val accepted = node.path("accepted").asBoolean(true)
        node.remove("accepted")

        agentProcess += ConfirmationResponse(awaitableId = pending.id, accepted = accepted)
        logger.debug("ConfirmationAwareTool [{}]: accepted={}", delegate.definition.name, accepted)

        return if (accepted) {
            val effectiveInput = if (node.isEmpty) pending.payload as String else objectMapper.writeValueAsString(node)
            delegate.call(effectiveInput, context)
        } else {
            Tool.Result.text("Action cancelled.")
        }
    }

    private fun isPending(): Boolean {
        val agentProcess = AgentProcess.get() ?: return false
        val pending = agentProcess.last(ConfirmationRequest::class.java) ?: return false
        val response = agentProcess.last(ConfirmationResponse::class.java)
        return response == null || response.awaitableId != pending.id
    }
}

/**
 * Wrap this tool so the LLM can confirm or cancel before execution.
 *
 * @param messageProvider Function to generate the confirmation message from input
 */
fun Tool.withLlmConfirmation(messageProvider: (String) -> String): Tool =
    ConfirmationAwareTool(this, messageProvider)

/**
 * Wrap this tool so the LLM can confirm or cancel before execution.
 *
 * @param message Static confirmation message
 */
fun Tool.withLlmConfirmation(message: String): Tool =
    ConfirmationAwareTool(this, { message })
