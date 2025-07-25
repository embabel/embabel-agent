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

/**
 * Controls log output.
 */
data class Verbosity(
    val showPrompts: Boolean = false,
    val showLlmResponses: Boolean = false,
    val debug: Boolean = false,
    val showPlanning: Boolean = false,
) {
    val showLongPlans: Boolean get() = showPlanning || debug || showLlmResponses || showPrompts
}

enum class Delay {
    NONE, MEDIUM, LONG
}

/**
 *  Controls Process running.
 *  Prevents infinite loops, enforces budget limits, and manages delays.
 */
data class ProcessControl(
    val toolDelay: Delay = Delay.NONE,
    val operationDelay: Delay = Delay.NONE,
    val earlyTerminationPolicy: EarlyTerminationPolicy,
) {

    fun withToolDelay(toolDelay: Delay): ProcessControl =
        this.copy(toolDelay = toolDelay)

    fun withOperationDelay(operationDelay: Delay): ProcessControl =
        this.copy(operationDelay = operationDelay)

    fun withEarlyTerminationPolicy(earlyTerminationPolicy: EarlyTerminationPolicy): ProcessControl =
        this.copy(earlyTerminationPolicy = earlyTerminationPolicy)
}

/**
 * Budget for an agent process.
 * @param cost the cost of running the process, in USD.
 * @param actions the maximum number of actions the agent can perform before termination.
 * @param tokens the maximum number of tokens the agent can use before termination. This can be useful in the case of
 * local models where the cost is not directly measurable, but we don't want excessive work.
 */
data class Budget(
    val cost: Double = DEFAULT_COST_LIMIT,
    val actions: Int = DEFAULT_ACTION_LIMIT,
    val tokens: Int = DEFAULT_TOKEN_LIMIT,
) {

    fun earlyTerminationPolicy(): EarlyTerminationPolicy {
        return EarlyTerminationPolicy.firstOf(
            EarlyTerminationPolicy.maxActions(maxActions = actions),
            EarlyTerminationPolicy.maxTokens(maxTokens = tokens),
            EarlyTerminationPolicy.hardBudgetLimit(budget = cost),
        )
    }

    companion object {

        const val DEFAULT_COST_LIMIT = 2.0

        /**
         * Default maximum number of actions an agent process can perform before termination.
         */
        const val DEFAULT_ACTION_LIMIT = 50

        const val DEFAULT_TOKEN_LIMIT = 1000000
    }
}

/**
 * How to run an AgentProcess
 * @param contextId context id to use for this process. Can be null.
 * If set it can enable connection to external resources and persistence
 * from previous runs.
 * @param blackboard an existing blackboard to use for this process.
 * By default, it will be modified as the process runs.
 * Whether this is an independent copy is up to the caller, who can call spawn()
 * before passing this argument.
 * @param test whether to run in test mode. In test mode, the agent platform
 * will not use any external resources such as LLMs, and will not persist any state.
 * @param verbosity detailed verbosity settings for logging etc.
 * @param prune whether to prune the agent to only relevant actions
 */
data class ProcessOptions(
    val contextId: ContextId? = null,
    val blackboard: Blackboard? = null,
    val test: Boolean = false,
    val verbosity: Verbosity = Verbosity(),
    val allowGoalChange: Boolean = true,
    val budget: Budget = Budget(),
    val control: ProcessControl = ProcessControl(
        toolDelay = Delay.NONE,
        operationDelay = Delay.NONE,
        earlyTerminationPolicy = budget.earlyTerminationPolicy(),
    ),
    val prune: Boolean = false,
) {

    companion object {

        @JvmStatic
        val DEFAULT = ProcessOptions()
    }
}
