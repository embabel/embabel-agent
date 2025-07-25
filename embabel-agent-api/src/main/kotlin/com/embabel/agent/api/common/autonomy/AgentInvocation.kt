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
import java.util.concurrent.CompletableFuture
import kotlin.reflect.KClass


/**
 * Defines the contract for invoking an agent.
 *
 * Default instances are created with [create];
 * [builder] allows for customization of the invocation before creation.
 * Once created, [invoke] or [invokeAsync] is used to invoke the agent.
 *
 * @param T type of result returned by the invocation
 */
interface AgentInvocation<T> {

    /**
     * Invokes the agent with one or more arguments.
     *
     * @param obj the first (and possibly only) input value to be added to the blackboard
     * @param objs additional input values to add to the blackboard
     * @return the result of type [T] from the agent invocation
     */
    fun invoke(obj: Any, vararg objs: Any): T

    /**
     * Invokes the agent with a map of named inputs.
     *
     * @param map A [Map] that initializes the blackboard
     * @return the result of type [T] from the agent invocation
     */
    fun invoke(map: Map<String, Any>): T

    /**
     * Invokes the agent asynchronously with one or more arguments.
     *
     * @param obj the first (and possibly only) input value to be added to the blackboard
     * @param objs additional input values to add to the blackboard
     * @return the result of type [T] from the agent invocation
     */
    fun invokeAsync(obj: Any, vararg objs: Any): CompletableFuture<T>

    /**
     * Invokes the agent asynchronously with a map of named inputs.
     *
     * @param map A [Map] that initializes the blackboard
     * @return the result of type [T] from the agent invocation
     */
    fun invokeAsync(map: Map<String, Any>): CompletableFuture<T>

    companion object {

        /**
         * Create a new [AgentInvocation] for the given platform and explicit result type.
         *
         * @param agentPlatform the platform in which this agent will run
         * @param resultType the Java [Class] of the type of result the agent will return
         * @param T type of result returned by the invocation
         * @return a configured [AgentInvocation] that produces values of type [T]
         */
        @JvmStatic
        fun <T : Any > create(agentPlatform: AgentPlatform, resultType: Class<T>): AgentInvocation<T> {
            return builder(agentPlatform).build(resultType)
        }

        /**
         * Create a new [AgentInvocation] for the given platform, inferring the result type
         * from the reified type parameter.
         *
         * @param agentPlatform the platform or environment in which this agent will run
         * @param T type of result returned by the invocation
         * @return a configured [AgentInvocation] that produces values of type [T]
         */
        inline fun <reified T : Any> create(agentPlatform: AgentPlatform): AgentInvocation<T> {
            return builder(agentPlatform).build()
        }

        /**
         * Obtain a new [AgentInvocationBuilder] to customize agent settings before building.
         *
         * @param agentPlatform the platform or environment in which this agent will run
         * @return a builder through which you can set processing options
         */
        @JvmStatic
        fun builder(agentPlatform: AgentPlatform): AgentInvocationBuilder {
            return DefaultAgentInvocationBuilder(agentPlatform)
        }

    }

}

/**
 * Builder for configuring and creating instances of [AgentInvocation].
 *
 * Use this builder to set process options such as context, blackboard,
 * verbosity, budget, and control policies before constructing the agent invocation.
 */
interface AgentInvocationBuilder {

    /**
     * Set the [ProcessOptions] to use for this invocation.
     * @param processOptions the process-level options
     * @return this builder instance for chaining
     */
    fun options(processOptions: ProcessOptions): AgentInvocationBuilder

    /**
     * Begin configuring process options via a nested builder.
     * @return a [ProcessOptionsBuilder] for fine-grained option setup
     */
    fun options(): ProcessOptionsBuilder

    /**
     * Build the [AgentInvocation] with the given explicit result type.
     * @param resultType the Java [Class] of the result type [T]
     * @return a new [AgentInvocation] producing values of type [T]
     */
    fun <T: Any> build(resultType: Class<T>): AgentInvocation<T>

    /**
     * Nested builder for specifying process options in detail.
     */
    interface ProcessOptionsBuilder {

        /**
         * Set the context identifier to use for the invocation. Can be null.
         * If set it can enable connection to external resources and persistence
         * from previous runs.
         * @param contextId the context ID to associate with this invocation, or null
         * @return the parent [AgentInvocationBuilder]
         */
        fun contextId(contextId: ContextId?): AgentInvocationBuilder

        /**
         * An existing blackboard to use for this invocation.
         * By default, it will be modified as the process runs.
         * @param blackboard the existing blackboard to use
         * @return the parent [AgentInvocationBuilder]
         */
        fun blackboard(blackboard: Blackboard): AgentInvocationBuilder

        /**
         * Enable or disable test mode for this invocation.
         * In test mode, the agent platform will not use any external resources such as LLMs,
         * and will not persist any state.
         * @param test true to run in test mode, false otherwise
         * @return the parent [AgentInvocationBuilder]
         */
        fun test(test: Boolean): AgentInvocationBuilder

        /**
         * Set a specific verbosity directly.
         * @param verbosity the desired verbosity
         * @return the parent [AgentInvocationBuilder]
         */
        fun verbosity(verbosity: Verbosity): AgentInvocationBuilder

        /**
         * Begin configuring verbosity settings via a nested builder.
         * @return a [VerbosityBuilder] for verbosity options
         */
        fun verbosity(): VerbosityBuilder

        /**
         * Allow or prevent automatic goal adjustments during execution.
         * @param allowGoalChange true to permit the agent to change goals mid-execution
         * @return the parent [AgentInvocationBuilder]
         */
        fun allowGoalChange(allowGoalChange: Boolean): AgentInvocationBuilder

        /**
         * Set budget constraints directly.
         * @param budget the budget settings to apply
         * @return the parent [AgentInvocationBuilder]
         */
        fun budget(budget: Budget): AgentInvocationBuilder

        /**
         * Begin configuring budget constraints via a nested builder.
         * @return a [BudgetBuilder] for cost and resource limits
         */
        fun budget(): BudgetBuilder

        /**
         * Begin configuring process control options via a nested builder.
         * @return a [ProcessControlBuilder] for delays and termination policies
         */
        fun control(control: ProcessControl): AgentInvocationBuilder

        /**
         * Set process control settings directly.
         * @param control the control policy settings
         * @return the parent [AgentInvocationBuilder]
         */
        fun control() : ProcessControlBuilder
    }

    /**
     * Nested builder for verbosity-related settings.
     */
    interface VerbosityBuilder {

        /**
         * Show or hide the prompts sent to the agent.
         * @param showPrompts whether to display prompts
         * @return the parent [AgentInvocationBuilder]
         */
        fun showPrompts(showPrompts: Boolean): AgentInvocationBuilder

        /**
         * Show or hide the responses received from the LLM.
         * @param showLlmResponses whether to display LLM responses
         * @return the parent [AgentInvocationBuilder]
         */
        fun showLlmResponses(showLlmResponses: Boolean): AgentInvocationBuilder

        /**
         * Enable or disable debugging output.
         * @param debug true to enable debugging, false otherwise
         * @return the parent [AgentInvocationBuilder]
         */
        fun debug(debug: Boolean): AgentInvocationBuilder

        /**
         * Show or hide planning steps taken by the agent.
         * @param showPlanning whether to display planning details
         * @return the parent [AgentInvocationBuilder]
         */
        fun showPlanning(showPlanning: Boolean): AgentInvocationBuilder
    }

    /**
     * Nested builder for budget and resource constraint settings.
     */
    interface BudgetBuilder {

        /**
         * Sets the cost of running the process, in USD.
         * @param cost the cost limit
         * @return the parent [AgentInvocationBuilder]
         */
        fun cost(cost: Double): AgentInvocationBuilder

        /**
         * Set the maximum number of actions the agent can perform before termination.
         * @param actions the action count limit
         * @return the parent [AgentInvocationBuilder]
         */
        fun actions(actions: Int): AgentInvocationBuilder

        /**
         * Set a maximum the maximum number of tokens the agent can use before termination.
         * This can be useful in the case of local models where the cost is not directly measurable,
         * but we don't want excessive work.
         * @param tokens the token count limit
         * @return the parent [AgentInvocationBuilder]
         */
        fun tokens(tokens: Int): AgentInvocationBuilder
    }

    /**
     * Nested builder for process control options.
     */
    interface ProcessControlBuilder {

        /**
         * Specify a delay between invoking tools.
         * @param delay the delay configuration
         * @return the parent [AgentInvocationBuilder]
         */
        fun toolDelay(delay: Delay): AgentInvocationBuilder

        /**
         * Specify a delay between operations.
         * @param delay the delay configuration
         * @return the parent [AgentInvocationBuilder]
         */
        fun operationDelay(delay: Delay): AgentInvocationBuilder

        /**
         * Set the policy for early termination.
         * @param policy the termination policy to apply
         * @return the parent [AgentInvocationBuilder]
         */
        fun earlyTerminationPolicy(policy: EarlyTerminationPolicy): AgentInvocationBuilder

    }

}

/**
 * Build the [AgentInvocation], inferring the result type from the reified type parameter.
 * @param T type of result returned by the invocation
 * @return a new [AgentInvocation] producing values of type [T]
 */
inline fun <reified T : Any> AgentInvocationBuilder.build(): AgentInvocation<T>  {
    val builder = this
    check(builder is DefaultAgentInvocationBuilder, { "Expected DefaultAgentInvocationBuilder"})
    return builder.buildInternal(T::class)
}

@PublishedApi
internal class DefaultAgentInvocationBuilder(
    private val agentPlatform: AgentPlatform
) : AgentInvocationBuilder,
    AgentInvocationBuilder.ProcessOptionsBuilder,
    AgentInvocationBuilder.VerbosityBuilder,
    AgentInvocationBuilder.BudgetBuilder,
    AgentInvocationBuilder.ProcessControlBuilder {

    private var processOptions: ProcessOptions = ProcessOptions.DEFAULT

    override fun options(processOptions: ProcessOptions): AgentInvocationBuilder {
        this.processOptions = processOptions
        return this
    }

    override fun options(): AgentInvocationBuilder.ProcessOptionsBuilder {
        return this
    }

    override fun <T: Any> build(resultType: Class<T>): AgentInvocation<T> {
        return buildInternal(resultType.kotlin)
    }

    fun <T : Any> buildInternal(resultType: KClass<T>): AgentInvocation<T> {
        return DefaultAgentInvocation(
            agentPlatform = this.agentPlatform,
            processOptions = processOptions,
            resultType = resultType
        )
    }

// ProcessOptionsBuilder

    override fun contextId(contextId: ContextId?): AgentInvocationBuilder {
        this.processOptions = this.processOptions.copy(contextId = contextId)
        return this
    }

    override fun blackboard(blackboard: Blackboard): AgentInvocationBuilder {
        this.processOptions = this.processOptions.copy(blackboard = blackboard)
        return this
    }

    override fun test(test: Boolean): AgentInvocationBuilder {
        this.processOptions = this.processOptions.copy(test = test)
        return this
    }

    override fun verbosity(verbosity: Verbosity): AgentInvocationBuilder {
        this.processOptions = this.processOptions.copy(verbosity = verbosity)
        return this
    }

    override fun allowGoalChange(allowGoalChange: Boolean): AgentInvocationBuilder {
        this.processOptions = this.processOptions.copy(allowGoalChange = allowGoalChange)
        return this
    }

    override fun budget(budget: Budget): AgentInvocationBuilder {
        this.processOptions = this.processOptions.copy(budget = budget)
        return this
    }

    override fun control(control: ProcessControl): AgentInvocationBuilder {
        this.processOptions = this.processOptions.copy(control = control)
        return this
    }

    override fun verbosity(): AgentInvocationBuilder.VerbosityBuilder {
        return this
    }

    override fun budget(): AgentInvocationBuilder.BudgetBuilder {
        return this
    }

    override fun control(): AgentInvocationBuilder.ProcessControlBuilder {
        return this
    }

    // VerbosityBuilder

    override fun showPrompts(showPrompts: Boolean): AgentInvocationBuilder {
        this.processOptions = this.processOptions.copy(verbosity =
            this.processOptions.verbosity.copy(showPrompts = showPrompts))
        return this
    }

    override fun showLlmResponses(showLlmResponses: Boolean): AgentInvocationBuilder {
        this.processOptions = this.processOptions.copy(verbosity =
            this.processOptions.verbosity.copy(showLlmResponses = showLlmResponses))
        return this
    }

    override fun debug(debug: Boolean): AgentInvocationBuilder {
        this.processOptions = this.processOptions.copy(verbosity =
            this.processOptions.verbosity.copy(debug = debug))
        return this
    }

    override fun showPlanning(showPlanning: Boolean): AgentInvocationBuilder {
        this.processOptions = this.processOptions.copy(verbosity =
            this.processOptions.verbosity.copy(showPlanning = showPlanning))
        return this
    }

    // BudgetBuilder

    override fun cost(cost: Double): AgentInvocationBuilder {
        this.processOptions = this.processOptions.copy(budget =
            this.processOptions.budget.copy(cost = cost))
        return this
    }

    override fun actions(actions: Int): AgentInvocationBuilder {
        this.processOptions = this.processOptions.copy(budget =
            this.processOptions.budget.copy(actions = actions))
        return this
    }

    override fun tokens(tokens: Int): AgentInvocationBuilder {
        this.processOptions = this.processOptions.copy(budget =
            this.processOptions.budget.copy(tokens = tokens))
        return this
    }

    // ProcessControlBuilder

    override fun toolDelay(delay: Delay): AgentInvocationBuilder {
        this.processOptions = this.processOptions.copy(control =
            this.processOptions.control.copy(toolDelay = delay))
        return this
    }

    override fun operationDelay(delay: Delay): AgentInvocationBuilder {
        this.processOptions = this.processOptions.copy(control =
            this.processOptions.control.copy(operationDelay = delay))
        return this
    }

    override fun earlyTerminationPolicy(policy: EarlyTerminationPolicy): AgentInvocationBuilder {
        this.processOptions = this.processOptions.copy(control =
            this.processOptions.control.copy(earlyTerminationPolicy =  policy))
        return this
    }

}

internal class DefaultAgentInvocation<T : Any> (
    private val agentPlatform: AgentPlatform,
    private val processOptions: ProcessOptions,
    private val resultType: KClass<T>
): AgentInvocation<T> {

    override fun invoke(obj: Any, vararg objs: Any): T {
        return invokeAsync(obj, *objs)
            .get()
    }

    override fun invoke(map: Map<String, Any>): T {
        return invokeAsync(map)
            .get()
    }

    override fun invokeAsync(obj: Any, vararg objs: Any): CompletableFuture<T> {
        val agent = agentPlatform.findAgentByResultType(resultType)
            ?: error("No agent for $resultType found.")

        val args = arrayOf(obj, *objs)

        val agentProcess = agentPlatform.createAgentProcessFrom(
            agent = agent,
            processOptions = processOptions,
            *args
        )
        return agentPlatform.start(agentProcess)
            .thenApply { it.last(resultType.java) }
    }

    override fun invokeAsync(map: Map<String, Any>): CompletableFuture<T> {
        val agent = agentPlatform.findAgentByResultType(resultType)
            ?: error("No agent for ${resultType} found.")

        val agentProcess = agentPlatform.createAgentProcess(
            agent = agent,
            processOptions,
            bindings = map
        )
        return agentPlatform.start(agentProcess)
            .thenApply { it.last(resultType.java) }
    }

}
