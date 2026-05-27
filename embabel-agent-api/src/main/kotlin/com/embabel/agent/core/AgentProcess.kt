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
package com.embabel.agent.core

import com.embabel.agent.api.event.ProcessKilledEvent
import com.embabel.common.ai.model.LlmMetadata
import com.embabel.common.ai.model.ModelMetadata
import com.embabel.common.core.types.HasInfoString
import com.embabel.common.core.types.Timed
import com.embabel.common.core.types.Timestamped
import com.embabel.common.util.ComputerSaysNoSerializer
import com.embabel.plan.Planner
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
    LlmInvocationHistory, EmbeddingInvocationHistory {

    /**
     * Unique id of this process. Set on creation.
     */
    val id: String

    /**
     * The blackboard for this process.
     * Implementations should delegate to it to implement the Blackboard interface for convenience,
     * but explicitly separating it simplifies persistence.
     */
    val blackboard: Blackboard

    /**
     * ID of the parent AgentProcess, if any.
     */
    val parentId: String?

    /**
     * True if this is a root process (no parent).
     */
    val isRootProcess: Boolean
        get() = parentId == null

    /**
     * Options this process was started with
     */
    val processOptions: ProcessOptions

    /**
     * Get the planner for this process
     */
    val planner: Planner<*, *, *>

    /**
     * History of actions taken by this process
     */
    val history: List<ActionInvocation>

    /**
     * Goal of this process. Utility processes may not have a goal.
     */
    val goal: com.embabel.plan.Goal?

    /**
     * Is the process finished, whether successfully or not?
     */
    val finished: Boolean
        get() = status in setOf(
            AgentProcessStatusCode.COMPLETED,
            AgentProcessStatusCode.FAILED,
            AgentProcessStatusCode.KILLED,
            AgentProcessStatusCode.TERMINATED,
        )

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
     * Request graceful termination of the entire agent process.
     * The agent will terminate at the next natural checkpoint (before next tick).
     *
     * @param reason Human-readable explanation for termination
     * @see com.embabel.agent.api.tool.TerminateAgentException for immediate termination
     */
    fun terminateAgent(reason: String)

    /**
     * Request graceful termination of the current action only.
     * The action will terminate at the next natural checkpoint (between tool calls),
     * and the agent will continue with the next planned action.
     *
     * @param reason Human-readable explanation for termination
     * @see com.embabel.agent.api.tool.TerminateActionException for immediate termination
     */
    fun terminateAction(reason: String)

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
     * Record an embedding invocation against this process.
     * Default implementation is a no-op for [AgentProcess] implementations
     * that don't track embeddings; [com.embabel.agent.core.support.AbstractAgentProcess]
     * provides a thread-safe storage.
     */
    fun recordEmbeddingInvocation(invocation: EmbeddingInvocation) {}

    /**
     * Cost of this process's own LLM invocations, excluding any child processes.
     * See [cost] for the LLM cost aggregate across the entire process subtree,
     * and [totalCost] for the cost including embeddings.
     */
    fun ownCost(): Double = llmInvocations.sumOf { it.cost() }

    /**
     * Token usage of this process's own LLM invocations, excluding any child processes.
     * See [usage] for the LLM usage aggregate across the entire process subtree,
     * and [totalUsage] for the usage including embeddings.
     */
    fun ownUsage(): Usage {
        val promptTokens = llmInvocations.sumOf { it.usage.promptTokens ?: 0 }
        val completionTokens = llmInvocations.sumOf { it.usage.completionTokens ?: 0 }
        return Usage(promptTokens, completionTokens, null)
    }

    /**
     * Distinct LLMs used by this process's own invocations, excluding any child processes.
     * See [modelsUsed] for the aggregate across the entire process subtree.
     */
    fun ownModelsUsed(): List<LlmMetadata> =
        llmInvocations.map { it.llmMetadata }.distinctBy { it.name }.sortedBy { it.name }

    /**
     * Total cost in dollars combining LLM and embedding invocations, including any
     * child processes when the implementation supports subtree aggregation.
     * Use this when you want the full cost of the process; use [cost] for LLM-only.
     */
    fun totalCost(): Double = cost() + embeddingCost()

    /**
     * Total token usage combining LLM and embedding invocations, including any
     * child processes when the implementation supports subtree aggregation.
     * Use this when you want the full token usage; use [usage] for LLM-only.
     */
    fun totalUsage(): Usage = usage() + embeddingUsage()

    /**
     * Distinct models used (LLM and embedding), including any child processes when
     * the implementation supports subtree aggregation.
     * Use this when you want all models; use [modelsUsed] for LLM-only.
     */
    fun totalModelsUsed(): List<ModelMetadata> =
        (modelsUsed() + embeddingModelsUsed())
            .distinctBy { it.name }
            .sortedBy { it.name }

    /**
     * Combined cost/usage info across LLM and embedding invocations.
     * Use this for a full report; use [costInfoString] for LLM-only.
     */
    fun totalCostInfoString(verbose: Boolean): String {
        val llmInfo = costInfoString(verbose)
        val embeddingInfo = embeddingCostInfoString(verbose)
        val totalLine = "Total cost: $${"%.4f".format(totalCost())}"
        return if (verbose)
            "$llmInfo$embeddingInfo$totalLine\n"
        else
            "$llmInfo\n$embeddingInfo\n$totalLine"
    }

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
        return getValue(IoBinding.DEFAULT_BINDING, outputClass.simpleName) as O?
            ?: error("No result of type ${outputClass.name} found in process status")
    }

    /**
     * Get a variable value. Handles "it" default type specially,
     * because it could be an "it" of different variables, defined
     * as the most recently added entry.
     */
    fun getValue(
        variable: String,
        type: String,
    ): Any? =
        blackboard.getValue(
            variable = variable, type = type,
            dataDictionary = agent,
        )

    companion object {
        private val threadLocalAgentProcess = ThreadLocal<AgentProcess>()

        @PublishedApi
        internal fun set(agentProcess: AgentProcess) {
            threadLocalAgentProcess.set(agentProcess)
        }

        @PublishedApi
        internal fun remove() {
            threadLocalAgentProcess.remove()
        }

        /**
         * Get the current agent process for this thread, if any.
         */
        @JvmStatic
        fun get(): AgentProcess? {
            return threadLocalAgentProcess.get()?.let { return it }
        }

        /**
         * Execute a block with this AgentProcess as the current process for the thread.
         * Properly saves and restores any previous value, ensuring cleanup even on exception.
         */
        inline fun <T> AgentProcess.withCurrent(block: () -> T): T {
            val previous = get()
            return try {
                set(this)
                block()
            } finally {
                if (previous != null) {
                    set(previous)
                } else {
                    remove()
                }
            }
        }

    }

}

/**
 * Convenience function to get the result of a specific type
 */
inline fun <reified O> AgentProcess.resultOfType(): O = resultOfType(O::class.java)
