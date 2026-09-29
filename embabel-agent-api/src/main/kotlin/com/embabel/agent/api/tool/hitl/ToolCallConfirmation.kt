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

import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.hitl.ConfirmationRequest
import com.embabel.agent.core.hitl.ConfirmationResponse
import com.embabel.agent.core.hitl.ResponseImpact
import java.time.Instant
import java.util.UUID

/**
 * How a [ConfirmationGuardedTool] gets its proposal in front of the human.
 */
enum class ConfirmationMode {

    /**
     * Return an instruction to the LLM. The LLM asks the human in conversation and
     * records the answer through the guard's verdict tool. The agent process does not pause.
     */
    ASK_VIA_LLM,

    /**
     * Throw [com.embabel.agent.core.hitl.AwaitableResponseException] with a
     * [ToolCallConfirmationRequest]. The hosting UX resolves it through
     * [com.embabel.agent.core.hitl.Awaitable.onResponse] and resumes the process.
     */
    PAUSE_PROCESS,
}

/**
 * Who recorded a [ToolCallVerdict]. Informational only; behaviour does not branch on it.
 */
enum class VerdictSource {

    /** Recorded by the LLM through the verdict tool. */
    LLM,

    /** Recorded by a UI through [ToolCallConfirmationRequest.onResponse]. */
    UI,
}

/**
 * A proposed tool call awaiting the human's verdict.
 * Immutable blackboard record; state lives here, never on tool instances.
 *
 * @param toolName Name of the guarded tool
 * @param arguments Canonical JSON arguments, see [ToolArgumentsCanonicalizer]
 * @param message Human-facing confirmation text
 */
data class ToolCallProposal(
    val toolName: String,
    val arguments: String,
    val message: String,
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Instant = Instant.now(),
)

/**
 * The human's answer to a [ToolCallProposal], however it arrived.
 */
data class ToolCallVerdict(
    val proposalId: String,
    val accepted: Boolean,
    val source: VerdictSource,
    val note: String? = null,
    val timestamp: Instant = Instant.now(),
)

/**
 * Terminal record for a [ToolCallProposal]: it has been executed or cancelled
 * and can no longer be acted on.
 */
data class ToolCallOutcome(
    val proposalId: String,
    val executed: Boolean,
    val timestamp: Instant = Instant.now(),
)

/**
 * [ConfirmationRequest] raised by a [ConfirmationGuardedTool] in
 * [ConfirmationMode.PAUSE_PROCESS]. Existing UX that handles `ConfirmationRequest`
 * keeps working; resolution is recorded as a [ToolCallVerdict] so the guard can act
 * on it when the process resumes.
 */
class ToolCallConfirmationRequest @JvmOverloads constructor(
    proposal: ToolCallProposal,
    persistent: Boolean = false,
) : ConfirmationRequest<ToolCallProposal>(
    payload = proposal,
    message = proposal.message,
    persistent = persistent,
) {

    override fun onResponse(
        response: ConfirmationResponse,
        agentProcess: AgentProcess,
    ): ResponseImpact {
        // Do not call super: the proposal is already on the blackboard and must not be duplicated.
        agentProcess.blackboard += ToolCallVerdict(
            proposalId = payload.id,
            accepted = response.accepted,
            source = VerdictSource.UI,
        )
        return ResponseImpact.UPDATED
    }

    override fun toString(): String =
        "ToolCallConfirmationRequest(id=$id, tool=${payload.toolName}, arguments=${payload.arguments}, message='$message')"
}
