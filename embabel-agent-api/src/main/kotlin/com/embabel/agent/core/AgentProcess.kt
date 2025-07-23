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
package com.embabel.agent.core

import com.embabel.agent.event.ProcessKilledEvent
import com.embabel.common.core.types.HasInfoString
import com.embabel.common.core.types.Timed
import com.embabel.common.core.types.Timestamped
import com.embabel.common.util.ComputerSaysNoSerializer
import com.embabel.plan.WorldState
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import java.time.Duration
import java.time.Instant

/**
 * History element
 */
data class ActionInvocation(
    val actionName: String,
    override val timestamp: Instant = Instant.now(),
    override val runningTime: Duration,
) : Timestamped, Timed, HasInfoString {

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        return "$actionName(${"%,d".format(runningTime.toMillis())}ms)"
    }
}

/**
 * Safely serializable status for agent processes.
 */
data class AgentProcessStatusReport(
    val id: String,
    override val status: AgentProcessStatusCode,
    override val timestamp: Instant,
    override val runningTime: Duration,
) : Timed, Timestamped, OperationStatus<AgentProcessStatusCode>

/**
 * Run of an agent
 */
@JsonSerialize(using = ComputerSaysNoSerializer::class)
interface AgentProcess : Blackboard, Timestamped, Timed, OperationStatus<AgentProcessStatusCode>,
    LlmInvocationHistory {

    /**
     * Unique id of this process
     */
    val id: String

    val parentId: String?

    val history: List<ActionInvocation>

    /**
     * Goal of this process.
     */
    val goal: com.embabel.plan.Goal?

    /**
     * Return a serializable status report for this process.
     */
    fun statusReport(): AgentProcessStatusReport =
        AgentProcessStatusReport(
            id = id,
            status = status,
            timestamp = timestamp,
            runningTime = runningTime,
        )

    /**
     * Kill this process and return an event describing the kill if we are successful
     */
    fun kill(): ProcessKilledEvent?

    /**
     * If we failed, this may contain the reason for the failure.
     */
    val failureInfo: Any?

    /**
     * The last world state that was used to plan the next action
     * Will be non-null if the process is running
     */
    val lastWorldState: WorldState?

    val processContext: ProcessContext

    /**
     * The agent that this process is running.
     * Many processes can run the same agent.
     */
    val agent: Agent

    fun recordLlmInvocation(llmInvocation: LlmInvocation)

    /**
     * Perform the next step only.
     * Return when an action has been completed and the process is ready to plan,
     * regardless of the result of the action.
     * @return status code of the action. Side effects may have occurred in Blackboard
     */
    fun tick(): AgentProcess

    /**
     * Run the process as far as we can.
     * Might complete, fail, get stuck or hit a waiting state.
     * This is a slow operation. We may wish to run this async.
     * Events will be emitted as the process runs, so we can track progress.
     * @return status code of the process. Side effects may have occurred in Blackboard
     */
    fun run(): AgentProcess

    /**
     * How long this process has been running
     */
    override val runningTime
        get(): Duration = if (status == AgentProcessStatusCode.NOT_STARTED) Duration.ZERO else Duration.between(
            timestamp,
            Instant.now(),
        )

    @Suppress("UNCHECKED_CAST")
    fun <O> resultOfType(outputClass: Class<O>): O {
        require(status == AgentProcessStatusCode.COMPLETED) {
            "Cannot get result of process that is not completed: Status=$status"
        }
        return processContext.getValue(IoBinding.DEFAULT_BINDING, outputClass.simpleName) as O?
            ?: error("No result of type ${outputClass.name} found in process status")
    }

}

/**
 * Convenience function to get the result of a specific type
 */
inline fun <reified O> AgentProcess.resultOfType(): O = resultOfType(O::class.java)
