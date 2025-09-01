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

import com.embabel.agent.event.AgenticEventListener
import com.embabel.agent.identity.User
import java.util.function.Consumer

interface LlmVerbosity {
    val showPrompts: Boolean
    val showLlmResponses: Boolean
}

/**
 * Controls log output.
 */
data class Verbosity(
    override val showPrompts: Boolean = false,
    override val showLlmResponses: Boolean = false,
    val debug: Boolean = false,
    val showPlanning: Boolean = false,
) : LlmVerbosity {
    val showLongPlans: Boolean get() = showPlanning || debug || showLlmResponses || showPrompts

    companion object {

        /**
         * Obtain a new [Builder] to for [Verbosity].
         *
         * @return a builder through which you can set verbosity options
         */
        @JvmStatic
        fun builder(): Builder {
            return Builder()
        }
    }

    /**
     * Nested builder for [Verbosity] objects.
     */
    class Builder internal constructor() {

        private var verbosity = Verbosity()

        /**
         * Show or hide the prompts sent to the agent.
         * @param showPrompts whether to display prompts
         * @return this [Builder]
         */
        fun showPrompts(showPrompts: Boolean): Builder {
            this.verbosity = this.verbosity.copy(showPrompts = showPrompts)
            return this
        }

        /**
         * Show or hide the responses received from the LLM.
         * @param showLlmResponses whether to display LLM responses
         * @return this [Builder]
         */
        fun showLlmResponses(showLlmResponses: Boolean): Builder {
            this.verbosity = this.verbosity.copy(showLlmResponses = showLlmResponses)
            return this
        }

        /**
         * Enable or disable debugging output.
         * @param debug true to enable debugging, false otherwise
         * @return this [Builder]
         */
        fun debug(debug: Boolean): Builder {
            this.verbosity = this.verbosity.copy(debug = debug)
            return this
        }

        /**
         * Show or hide planning steps taken by the agent.
         * @param showPlanning whether to display planning details
         * @return this [Builder]
         */
        fun showPlanning(showPlanning: Boolean): Builder {
            this.verbosity = this.verbosity.copy(showPlanning = showPlanning)
            return this
        }

        /**
         * Build the [Verbosity].
         * @return a newly built [Verbosity]
         */
        fun build(): Verbosity {
            return this.verbosity
        }
    }

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

        /**
         * Obtain a new [Builder] to for [Budget].
         *
         * @return a builder through which you can set budget options
         */
        @JvmStatic
        fun builder(): Builder {
            return Builder()
        }

    }

    /**
     * Nested builder for [Budget] objects.
     */
    class Builder internal constructor() {

        private var budget = Budget()

        /**
         * Sets the cost of running the process, in USD.
         * @param cost the cost limit
         * @return this [Builder]
         */
        fun cost(cost: Double): Builder {
            this.budget = this.budget.copy(cost = cost)
            return this
        }

        /**
         * Set the maximum number of actions the agent can perform before termination.
         * @param actions the action count limit
         * @return this [Builder]
         */
        fun actions(actions: Int): Builder {
            this.budget = this.budget.copy(actions = actions)
            return this
        }

        /**
         * Set a maximum the maximum number of tokens the agent can use before termination.
         * This can be useful in the case of local models where the cost is not directly measurable,
         * but we don't want excessive work.
         * @param tokens the token count limit
         * @return this [Builder]
         */
        fun tokens(tokens: Int): Builder {
            this.budget = this.budget.copy(tokens = tokens)
            return this
        }

        /**
         * Build the [Budget].
         * @return a newly built [Budget]
         */
        fun build(): Budget {
            return this.budget
        }

    }

}

/**
 * Identities associated with an agent process.
 * @param forUser the user for whom the process is running. Can be null.
 * @param runAs the user under which the process is running. Can be null.
 */
data class Identities(
    val forUser: User? = null,
    val runAs: User? = null,
)

/**
 * How to run an AgentProcess
 * @param contextId context id to use for this process. Can be null.
 * If set it can enable connection to external resources and persistence
 * from previous runs.
 * @param identities identities associated with this process.
 * @param blackboard an existing blackboard to use for this process.
 * By default, it will be modified as the process runs.
 * Whether this is an independent copy is up to the caller, who can call spawn()
 * before passing this argument.
 * @param test whether to run in test mode. In test mode, the agent platform
 * will not use any external resources such as LLMs, and will not persist any state.
 * @param verbosity detailed verbosity settings for logging etc.
 * @param prune whether to prune the agent to only relevant actions
 * @param listeners additional listeners (beyond platform event listeners) to receive events from this process.
 */
data class ProcessOptions(
    val contextId: ContextId? = null,
    val identities: Identities = Identities(),
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
    val listeners: List<AgenticEventListener> = emptyList(),
) {

    companion object {

        @JvmStatic
        val DEFAULT = ProcessOptions()

        /**
         * Obtain a new [Builder] to for [ProcessOptions].
         *
         * @return a builder through which you can set processing options
         */
        @JvmStatic
        fun builder(): Builder {
            return Builder()
        }

    }

    /**
     * Nested builder for [ProcessOptions] objects.
     */
    class Builder internal constructor() {

        private var processOptions = DEFAULT

        /**
         * Set the context identifier to use for the invocation. Can be null.
         * If set it can enable connection to external resources and persistence
         * from previous runs.
         * @param contextId the context ID to associate with this invocation, or null
         * @return this [Builder]
         */
        @JvmName("contextId")
        fun contextId(contextId: ContextId?): Builder {
            this.processOptions = processOptions.copy(contextId = contextId)
            return this
        }

        /**
         * Sets the identities associated with the process.
         * @param identities the identities
         * @return this [Builder]
         */
        fun identities(identities: Identities): Builder {
            this.processOptions = processOptions.copy(identities = identities)
            return this
        }

        /**
         * An existing blackboard to use for this invocation.
         * By default, it will be modified as the process runs.
         * @param blackboard the existing blackboard to use
         * @return this [Builder]
         */
        fun blackboard(blackboard: Blackboard): Builder {
            this.processOptions = processOptions.copy(blackboard = blackboard)
            return this
        }

        /**
         * Enable or disable test mode for this invocation.
         * In test mode, the agent platform will not use any external resources such as LLMs,
         * and will not persist any state.
         * @param test true to run in test mode, false otherwise
         * @return this [Builder]
         */
        fun test(test: Boolean): Builder {
            this.processOptions = processOptions.copy(test = test)
            return this
        }

        /**
         * Set a specific verbosity directly.
         * @param verbosity the desired verbosity
         * @return this [Builder]
         */
        fun verbosity(verbosity: Verbosity): Builder {
            this.processOptions = processOptions.copy(verbosity = verbosity)
            return this
        }

        /**
         * Configure verbosity settings via a nested builder.
         * @param consumer a function that takes a [Verbosity.Builder]
         * @return this [Builder]
         */
        fun verbosity(consumer: Consumer<Verbosity.Builder>): Builder {
            val verbosityBuilder = Verbosity.builder()
            consumer.accept(verbosityBuilder)
            this.processOptions = processOptions.copy(verbosity = verbosityBuilder.build())
            return this
        }

        /**
         * Allow or prevent automatic goal adjustments during execution.
         * @param allowGoalChange true to permit the agent to change goals mid-execution
         * @return this [Builder]
         */
        fun allowGoalChange(allowGoalChange: Boolean): Builder {
            this.processOptions = processOptions.copy(allowGoalChange = allowGoalChange)
            return this
        }

        /**
         * Set budget constraints directly.
         * @param budget the budget settings to apply
         * @return this [Builder]
         */
        fun budget(budget: Budget): Builder {
            this.processOptions = processOptions.copy(budget = budget)
            return this
        }

        /**
         * Configure budget constraints via a nested builder.
         * @param consumer a function that takes a [Budget.Builder]
         * @return this [Builder]
         */
        fun budget(consumer: Consumer<Budget.Builder>): Builder {
            val budgetBuilder = Budget.builder()
            consumer.accept(budgetBuilder)
            this.processOptions = processOptions.copy(budget = budgetBuilder.build())
            return this
        }

        /**
         * Set process control settings directly.
         * @param control the control policy settings
         * @return this [Builder]
         */
        fun control(control: ProcessControl): Builder {
            this.processOptions = processOptions.copy(control = control)
            return this
        }

        /**
         * Whether to prune the agent to only relevant actions
         * @param prune true to prune the agent to only relevant actions
         * @return this [Builder]
         */
        fun prune(prune: Boolean): Builder {
            this.processOptions = processOptions.copy(prune = prune)
            return this
        }

        /**
         * Add a listener to the list of [AgenticEventListener]s.
         * @param listener the listener to add
         * @return this [Builder]
         */
        fun listener(listener: AgenticEventListener): Builder {
            val listeners = this.processOptions.listeners + listener
            this.processOptions = processOptions.copy(listeners = listeners)
            return this
        }

        /**
         * Manipulate the listeners with the given consumer.
         * The list provided to the consumer can be used to remove listeners, change ordering, etc.
         * @param listener the listener to add
         * @return this [Builder]
         */
        fun listeners(consumer: Consumer<List<AgenticEventListener>>): Builder {
            val listeners = this.processOptions.listeners.toMutableList()
            consumer.accept(listeners)
            this.processOptions = processOptions.copy(listeners = listeners)
            return this
        }

        /**
         * Build the [ProcessOptions].
         * @return a newly built [ProcessOptions]
         */
        fun build(): ProcessOptions {
            return this.processOptions
        }

    }

}
