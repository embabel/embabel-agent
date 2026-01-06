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

import com.embabel.agent.api.event.AbstractAgentProcessEvent
import com.embabel.agent.core.EarlyTerminationPolicy.Companion.maxActions

/**
 * Event triggered when an agent process is terminated early by a policy.
 *
 * @param agentProcess The agent process that is being terminated
 * @param error Whether the termination is considered an error
 * @param reason A human-readable explanation of why the process is being terminated
 * @param policy The policy that triggered the termination
 */
class EarlyTermination(
    agentProcess: AgentProcess,
    val error: Boolean,
    val reason: String,
    val policy: EarlyTerminationPolicy,
) : AbstractAgentProcessEvent(agentProcess) {

    override fun toString(): String = "Early termination by policy ${policy.name} - $reason"
}

/**
 * Enables early termination of an agent process.
 *
 * Early termination policies provide a mechanism to stop agent processes before they
 * naturally complete. This is useful for enforcing constraints like maximum number of actions,
 * budget limits, or other custom termination conditions.
 *
 * Implementations should be stateless and thread-safe.
 */
interface EarlyTerminationPolicy {

    /**
     * The name of this policy, used for logging and debugging.
     * By default, uses the simple class name.
     */
    val name: String get() = this::class.simpleName ?: "Unknown"

    /**
     * Checks if the agent process should be terminated early.
     *
     * @param agentProcess The agent process to evaluate
     * @return An EarlyTermination object if the process should be terminated, or null if it should continue
     */
    fun shouldTerminate(agentProcess: AgentProcess): EarlyTermination?

    companion object {

        /**
         * Policy that terminates the process if it becomes stuck.
         * This can be useful with utility planning,
         * when we might want an AgentProcess to terminate without error if it cannot make progress.
         */
        @JvmStatic
        val ON_STUCK: EarlyTerminationPolicy = OnStuckEarlyTerminationPolicy

        /**
         * Creates a policy that terminates the process after a maximum number of actions.
         *
         * @param maxActions The maximum number of actions allowed
         * @return An EarlyTerminationPolicy that enforces the action limit
         */
        @JvmStatic
        fun maxActions(maxActions: Int): EarlyTerminationPolicy =
            MaxActionsEarlyTerminationPolicy(maxActions)

        @JvmStatic
        fun maxTokens(maxTokens: Int): EarlyTerminationPolicy =
            MaxTokensEarlyTerminationPolicy(maxTokens)

        /**
         * Combines multiple early termination policies into one.
         * The process will terminate if any of the provided policies triggers termination.
         * Policies are evaluated in the order they are provided.
         *
         * @param earlyTerminationPolicies The policies to combine
         * @return A combined EarlyTerminationPolicy
         */
        @JvmStatic
        fun firstOf(vararg earlyTerminationPolicies: EarlyTerminationPolicy): EarlyTerminationPolicy =
            FirstOfEarlyTerminationPolicy(earlyTerminationPolicies.toList())

        /**
         * Fallback budget limit for the agent process.
         * This is a last resort termination policy to prevent runaway costs.
         *
         * @param budget The maximum cost allowed for the process in dollars
         * @return An EarlyTerminationPolicy that enforces the budget limit
         */
        @JvmStatic
        fun hardBudgetLimit(budget: Double): EarlyTerminationPolicy =
            MaxCostEarlyTerminationPolicy(budget)
    }
}

private object OnStuckEarlyTerminationPolicy : EarlyTerminationPolicy {
    override fun shouldTerminate(agentProcess: AgentProcess): EarlyTermination? =
        if (agentProcess.status == AgentProcessStatusCode.STUCK) {
            EarlyTermination(
                agentProcess = agentProcess,
                error = false,
                reason = "Agent process is stuck",
                policy = this,
            )
        } else null

}

private data class MaxActionsEarlyTerminationPolicy(
    private val maxActions: Int,
) : EarlyTerminationPolicy {
    override fun shouldTerminate(agentProcess: AgentProcess): EarlyTermination? =
        if (agentProcess.history.size >= maxActions) {
            EarlyTermination(
                agentProcess = agentProcess,
                error = true,
                reason = "Max actions of $maxActions reached",
                policy = this,
            )
        } else null

}

private data class MaxTokensEarlyTerminationPolicy(
    private val maxTokens: Int,
) : EarlyTerminationPolicy {
    override fun shouldTerminate(agentProcess: AgentProcess): EarlyTermination? =
        if ((agentProcess.usage().totalTokens ?: 0) >= maxTokens) {
            EarlyTermination(
                agentProcess = agentProcess,
                error = true,
                reason = "Max tokens of $maxTokens reached",
                policy = this,
            )
        } else null

}

private data class MaxCostEarlyTerminationPolicy(
    private val budget: Double,
) : EarlyTerminationPolicy {
    override fun shouldTerminate(agentProcess: AgentProcess): EarlyTermination? =
        if (agentProcess.cost() >= budget) {
            EarlyTermination(
                agentProcess = agentProcess,
                error = true,
                reason = "Exceeded budget of $${"%.4f".format(budget)}: cost=$${"%.4f".format(agentProcess.cost())}",
                policy = this,
            )
        } else null

}

private data class FirstOfEarlyTerminationPolicy(
    private val earlyTerminationPolicies: List<EarlyTerminationPolicy>,
) : EarlyTerminationPolicy {
    override fun shouldTerminate(agentProcess: AgentProcess): EarlyTermination? {
        for (earlyTerminationPolicy in earlyTerminationPolicies) {
            val termination = earlyTerminationPolicy.shouldTerminate(agentProcess)
            if (termination != null) {
                return termination
            }
        }
        return null
    }

}
