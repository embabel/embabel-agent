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
 * Convenience extension that delegates to [AgentProcess.terminateAgent].
 *
 * @param reason Human-readable explanation for termination
 * @see AgentProcess.terminateAgent
 */
fun ProcessContext.terminateAgent(reason: String) = agentProcess.terminateAgent(reason)

/**
 * Request graceful termination of the current action only.
 * Convenience extension that delegates to [AgentProcess.terminateAction].
 *
 * @param reason Human-readable explanation for termination
 * @see AgentProcess.terminateAction
 */
fun ProcessContext.terminateAction(reason: String) = agentProcess.terminateAction(reason)

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
