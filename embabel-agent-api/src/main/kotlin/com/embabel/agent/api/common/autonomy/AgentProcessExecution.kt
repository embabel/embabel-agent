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
package com.embabel.agent.api.common.autonomy

import com.embabel.agent.core.*
import com.embabel.agent.core.hitl.Awaitable
import com.embabel.agent.core.hitl.AwaitableResponse
import com.embabel.agent.event.AgentPlatformEvent
import com.embabel.agent.spi.Rankings
import com.embabel.common.core.types.HasInfoString
import java.time.Instant

/**
 * Successful result of directly trying to execute a goal.
 * Failure results in an exception being thrown.
 */
class AgentProcessExecution private constructor(

    /**
     * What triggered this result. Process input.
     */
    val basis: Any,

    /**
     * Output object
     */
    val output: Any,

    /**
     * Process that executed and is now complete
     */
    val agentProcess: AgentProcess,
) : HasInfoString {

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String =
        if (verbose == true)
            "${javaClass.simpleName}(basis=$basis, output=$output, agentProcess=${
                agentProcess.infoString(verbose,  1)
            })"
        else
            "${javaClass.simpleName}(basis=$basis, output=${output::class.simpleName}, agentProcess=${agentProcess.id})"

    override fun toString(): String = infoString(verbose = false)

    companion object {

        @Throws(
            ProcessExecutionException::class,
            ProcessExecutionStuckException::class,
            ProcessExecutionFailedException::class,
            ProcessWaitingException::class,
            ProcessExecutionTerminatedException::class,
        )
        @Suppress("UNCHECKED_CAST")
        fun fromProcessStatus(
            basis: Any,
            agentProcess: AgentProcess,
        ): AgentProcessExecution =
            when (agentProcess.status) {
                AgentProcessStatusCode.COMPLETED -> {
                    AgentProcessExecution(
                        basis = basis,
                        output = agentProcess.lastResult()!!,
                        agentProcess = agentProcess,
                    )
                }

                AgentProcessStatusCode.WAITING -> {
                    throw ProcessWaitingException(
                        agentProcess = agentProcess,
                        // TODO this is dirty
                        awaitable = agentProcess.lastResult() as Awaitable<*, AwaitableResponse>,
                    )
                }

                AgentProcessStatusCode.FAILED -> {
                    throw ProcessExecutionFailedException(
                        agentProcess = agentProcess,
                        detail = "Process ${agentProcess.id} failed: ${agentProcess.failureInfo}",
                    )
                }

                AgentProcessStatusCode.STUCK -> {
                    throw ProcessExecutionStuckException(
                        agentProcess = agentProcess,
                        detail = "Process ${agentProcess.id} stuck"
                    )
                }

                AgentProcessStatusCode.TERMINATED -> {
                    throw ProcessExecutionTerminatedException(
                        agentProcess = agentProcess,
                        detail = "Process ${agentProcess.id} was terminated: ${agentProcess.failureInfo}",
                    )
                }

                else -> {
                    error("Unexpected process status: ${agentProcess.status}")
                }
            }
    }
}

/**
 * Used for control flow
 */
sealed class ProcessExecutionException(
    open val agentProcess: AgentProcess?,
    message: String,
) : Exception(message)

class NoGoalFound(
    val basis: Any,
    val goalRankings: Rankings<Goal>,
) : ProcessExecutionException(null, "Goal not found: ${goalRankings.rankings.joinToString(",")}")

/**
 * The Ranker chose a goal, but it was rejected by the GoalApprover
 */
class GoalNotApproved(
    val basis: Any,
    val goalRankings: Rankings<Goal>,
    val reason: String,
    override val agentPlatform: AgentPlatform,
) : AgentPlatformEvent,
    ProcessExecutionException(null, "Goal not approved because $reason: ${goalRankings.rankings.joinToString(",")}") {

    override val timestamp: Instant = Instant.now()

}

class NoAgentFound(
    val basis: Any,
    val agentRankings: Rankings<Agent>,
) : ProcessExecutionException(null, "Agent not found: ${agentRankings.rankings.joinToString(",")}")

class ProcessExecutionFailedException(
    override val agentProcess: AgentProcess,
    val detail: String,
) : ProcessExecutionException(agentProcess, "Process ${agentProcess.id} failed: $detail")

class ProcessExecutionTerminatedException(
    override val agentProcess: AgentProcess,
    val detail: String,
) : ProcessExecutionException(agentProcess, "Process ${agentProcess.id} terminated: $detail")

class ProcessExecutionStuckException(
    override val agentProcess: AgentProcess,
    val detail: String,
) : ProcessExecutionException(agentProcess, "Process ${agentProcess.id} stuck: $detail")

class ProcessWaitingException(
    override val agentProcess: AgentProcess,
    val awaitable: Awaitable<*, AwaitableResponse>,
) : ProcessExecutionException(agentProcess, "Process ${agentProcess.id} is waiting for ${awaitable.infoString()}")
