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

import com.embabel.agent.api.channel.DevNullOutputChannel
import com.embabel.agent.api.channel.OutputChannel
import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.api.identity.User
import com.embabel.agent.api.tool.ToolCallContext

/**
 * Control how much detail to log from LLM interactions.
 */
interface LlmVerbosity {
    val showPrompts: Boolean
    val showLlmResponses: Boolean
}

/**
 * Controls log output.
 * @param showPrompts whether to show prompts sent to the LLM
 * @param showLlmResponses whether to show responses received from the LLM
 * @param debug whether to enable debug logging. will also show planning details.
 * @param showPlanning whether to show planning details
 */
data class Verbosity @JvmOverloads constructor(
    override val showPrompts: Boolean = false,
    override val showLlmResponses: Boolean = false,
    val debug: Boolean = false,
    val showPlanning: Boolean = false,
) : LlmVerbosity {
    val showLongPlans: Boolean get() = showPlanning || debug

    fun withShowPrompts(showPrompts: Boolean): Verbosity =
        this.copy(showPrompts = showPrompts)

    fun showPrompts(): Verbosity =
        this.copy(showPrompts = true)

    fun withShowLlmResponses(showLlmResponses: Boolean): Verbosity =
        this.copy(showLlmResponses = showLlmResponses)

    fun showLlmResponses(): Verbosity =
        this.copy(showLlmResponses = true)

    fun withDebug(debug: Boolean): Verbosity =
        this.copy(debug = debug)

    fun debug(): Verbosity =
        this.copy(debug = true)

    fun withShowPlanning(showPlanning: Boolean): Verbosity =
        this.copy(showPlanning = showPlanning)

    fun showPlanning(): Verbosity =
        this.copy(showPlanning = true)

    companion object {

        @JvmField
        val DEFAULT = Verbosity()

    }

}

enum class Delay {
    NONE, MEDIUM, LONG
}

/**
 *  Controls how an AgentProcess is run.
 *  Prevents infinite loops, enforces budget limits, and manages delays.
 */
data class ProcessControl @JvmOverloads constructor(
    val toolDelay: Delay = Delay.NONE,
    val operationDelay: Delay = Delay.NONE,
    val earlyTerminationPolicy: EarlyTerminationPolicy = EarlyTerminationPolicy.maxActions(100),
) {

    fun withToolDelay(toolDelay: Delay): ProcessControl =
        this.copy(toolDelay = toolDelay)

    fun withOperationDelay(operationDelay: Delay): ProcessControl =
        this.copy(operationDelay = operationDelay)

    fun withEarlyTerminationPolicy(earlyTerminationPolicy: EarlyTerminationPolicy): ProcessControl =
        this.copy(earlyTerminationPolicy = earlyTerminationPolicy)

    fun withAdditionalEarlyTerminationPolicy(policy: EarlyTerminationPolicy): ProcessControl =
        this.copy(
            earlyTerminationPolicy = EarlyTerminationPolicy.firstOf(
                this.earlyTerminationPolicy,
                policy,
            )
        )

}

/**
 * Budget for an agent process.
 * @param cost the cost of running the process, in USD.
 * @param actions the maximum number of actions the agent can perform before termination.
 * @param tokens the maximum number of tokens the agent can use before termination. This can be useful in the case of
 * local models where the cost is not directly measurable, but we don't want excessive work.
 */
data class Budget @JvmOverloads constructor(
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

    fun withCost(cost: Double): Budget =
        this.copy(cost = cost)

    fun withActions(actions: Int): Budget =
        this.copy(actions = actions)

    fun withTokens(tokens: Int): Budget =
        this.copy(tokens = tokens)

    companion object {

        const val DEFAULT_COST_LIMIT = 2.0

        /**
         * Default maximum number of actions an agent process can perform before termination.
         */
        const val DEFAULT_ACTION_LIMIT = 50

        const val DEFAULT_TOKEN_LIMIT = 1000000

        @JvmField
        val DEFAULT = Budget()

    }

}

/**
 * Identities associated with an agent process.
 * @param forUser the user for whom the process is running. Can be null.
 * @param runAs the user under which the process is running. Can be null.
 */
data class Identities
@JvmOverloads
constructor(
    val forUser: User? = null,
    val runAs: User? = null,
) {

    fun withForUser(forUser: User?): Identities =
        this.copy(forUser = forUser)

    fun withRunAs(runAs: User?): Identities =
        this.copy(runAs = runAs)

    companion object {

        @JvmField
        val DEFAULT = Identities()

    }

}

/**
 * How to run an AgentProcess
 * Create and customize using withers
 * @param contextId context id to use for this process. Can be null.
 * If set it can enable connection to external resources and persistence
 * from previous runs.
 * @param identities identities associated with this process.
 * @param blackboard an existing blackboard to use for this process.
 * By default, it will be modified as the process runs.
 * Whether this is an independent copy is up to the caller, who can call spawn()
 * before passing this argument.
 * @param budget budget constraints for this process. Will be exposed to actions
 * and tools and enforced by default ProcessControl.
 * @param processControl custom ProcessControl if specified. If not specified, default will be based on Budget.
 * If specified, this overrides the budget-based defaults and may not relate
 * to the budget.
 * @param verbosity detailed verbosity settings for logging etc.
 * @param prune whether to prune the agent to only relevant actions
 * @param listeners additional listeners (beyond platform event listeners) to receive events from this process.
 * @param outputChannel custom output channel to use for this process.
 * @param plannerType the type of planner to use for this process. Defaults to GOAP planner.
 * @param toolCallContext out-of-band metadata (e.g., auth tokens, tenant IDs) passed to tools
 * at call time. This context is propagated to all tools, including MCP tools where it bridges
 * to Spring AI's ToolContext and ultimately to MCP's McpMeta.
 */
data class ProcessOptions @JvmOverloads constructor(
    val contextId: ContextId? = null,
    val identities: Identities = Identities(),
    val blackboard: Blackboard? = null,
    val verbosity: Verbosity = Verbosity(),
    val budget: Budget = Budget(),
    val processControl: ProcessControl = ProcessControl(
        toolDelay = Delay.NONE,
        operationDelay = Delay.NONE,
        earlyTerminationPolicy = budget.earlyTerminationPolicy(),
    ),
    val prune: Boolean = false,
    val listeners: List<AgenticEventListener> = emptyList(),
    val outputChannel: OutputChannel = DevNullOutputChannel,
    val plannerType: PlannerType = PlannerType.GOAP,
    val toolCallContext: ToolCallContext = ToolCallContext.EMPTY,
) {

    /**
     * Get the context ID as a String for Java interop.
     * @return the context ID string value, or null if not set
     */
    fun getContextIdString(): String? = contextId?.value

    fun withContextId(contextId: ContextId?): ProcessOptions =
        this.copy(contextId = contextId)

    fun withContextId(contextId: String?): ProcessOptions =
        this.copy(contextId = contextId?.let { ContextId(it) })

    fun withIdentities(identities: Identities): ProcessOptions =
        this.copy(identities = identities)

    fun withBlackboard(blackboard: Blackboard?): ProcessOptions =
        this.copy(blackboard = blackboard)

    fun withVerbosity(verbosity: Verbosity): ProcessOptions =
        this.copy(verbosity = verbosity)

    fun withBudget(budget: Budget): ProcessOptions =
        this.copy(budget = budget)

    fun withProcessControl(processControl: ProcessControl): ProcessOptions =
        this.copy(processControl = processControl)

    /**
     * Add an additional early termination policy to this process.
     * This is normally what you want rather than replacing the existing policy,
     */
    fun withAdditionalEarlyTerminationPolicy(policy: EarlyTerminationPolicy): ProcessOptions =
        this.copy(
            processControl = this.processControl.withEarlyTerminationPolicy(policy)
        )

    fun withPrune(prune: Boolean): ProcessOptions =
        this.copy(prune = prune)

    /**
     * Add additional listeners to this process.
     */
    fun withListeners(listeners: List<AgenticEventListener>): ProcessOptions =
        this.copy(listeners = listeners)

    /**
     * Add an additional listener to this process.
     */
    fun withListener(listener: AgenticEventListener): ProcessOptions =
        this.copy(listeners = this.listeners + listener)

    fun withOutputChannel(outputChannel: OutputChannel): ProcessOptions =
        this.copy(outputChannel = outputChannel)

    fun withPlannerType(plannerType: PlannerType): ProcessOptions =
        this.copy(plannerType = plannerType)

    fun withToolCallContext(toolCallContext: ToolCallContext): ProcessOptions =
        this.copy(toolCallContext = toolCallContext)

    /**
     * Java-friendly overload accepting a raw map.
     */
    fun withToolCallContext(context: Map<String, Any>): ProcessOptions =
        this.copy(toolCallContext = ToolCallContext.of(context))

    companion object {

        @JvmField
        val DEFAULT = ProcessOptions()

    }

}
