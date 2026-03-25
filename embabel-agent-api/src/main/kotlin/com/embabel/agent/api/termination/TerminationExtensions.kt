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
@file:JvmName("Termination")

package com.embabel.agent.api.termination

import com.embabel.agent.api.common.TerminationScope
import com.embabel.agent.api.common.TerminationSignal
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.EarlyTermination
import com.embabel.agent.core.EarlyTerminationPolicy
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.core.support.AbstractAgentProcess

/**
 * Request graceful termination of the entire agent process.
 * The agent will terminate at the next natural checkpoint (before next tick).
 *
 * This works for all action types (LLM-based and simple transformations).
 * For immediate termination without waiting for a checkpoint, use
 * [com.embabel.agent.api.tool.TerminateAgentException].
 *
 * @param reason Human-readable explanation for termination
 * @see com.embabel.agent.api.tool.TerminateAgentException for immediate termination
 */
fun ProcessContext.terminateAgent(reason: String) {
    check(agentProcess is AbstractAgentProcess) {
        "Termination signals require AbstractAgentProcess (found: ${agentProcess::class.simpleName})"
    }
    agentProcess.setTerminationRequest(TerminationSignal(TerminationScope.AGENT, reason))
}

/**
 * Request graceful termination of the current action only.
 * The action will terminate at the next natural checkpoint (between tool calls),
 * and the agent will continue with the next planned action.
 *
 * **Important:** This graceful termination mechanism only works for LLM-based actions
 * that use a tool loop. For simple agents actions (non-LLM), use
 * [com.embabel.agent.api.tool.TerminateActionException] instead:
 * ```
 * throw TerminateActionException("reason")
 * ```
 *
 * @param reason Human-readable explanation for termination
 * @see com.embabel.agent.api.tool.TerminateActionException for immediate termination
 */
fun ProcessContext.terminateAction(reason: String) {
    check(agentProcess is AbstractAgentProcess) {
        "Termination signals require AbstractAgentProcess (found: ${agentProcess::class.simpleName})"
    }
    agentProcess.setTerminationRequest(TerminationSignal(TerminationScope.ACTION, reason))
}

/**
 * Early termination policy that checks for API-driven termination signals.
 * Terminates the agent process when a [TerminationSignal] with [TerminationScope.AGENT] scope is found.
 */
internal object TerminationSignalPolicy : EarlyTerminationPolicy {
    override val name: String = "TerminationSignal"

    override fun shouldTerminate(agentProcess: AgentProcess): EarlyTermination? {
        val process = agentProcess as? AbstractAgentProcess ?: return null
        val signal = process.terminationRequest
        return if (signal != null && signal.scope == TerminationScope.AGENT) {
            EarlyTermination(
                agentProcess = agentProcess,
                error = false,
                reason = signal.reason,
                policy = this,
            )
        } else null
    }
}
