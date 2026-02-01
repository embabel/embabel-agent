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
package com.embabel.agent.api.tool.agentic.state

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.agentic.AgenticTool
import com.embabel.agent.api.tool.agentic.AgenticToolSupport
import com.embabel.agent.api.tool.agentic.DomainToolFactory
import com.embabel.agent.api.tool.agentic.DomainToolSource
import com.embabel.agent.api.tool.agentic.DomainToolTracker
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.spi.config.spring.executingOperationContextFor
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.util.loggerFor

/**
 * Operations for starting a state machine in a particular state.
 */
interface StateMachineToolOperations<S : Enum<S>> {
    /**
     * Create a version of this tool that starts in the specified state.
     * Useful when the starting state depends on runtime context.
     *
     * Example:
     * ```kotlin
     * // Statically configured
     * val tool = StateMachineTool("order", "Process order", OrderState::class.java)
     *     .withInitialState(OrderState.DRAFT)
     *     ...
     *
     * // Dynamically start in a different state
     * val resumedTool = tool.startingIn(OrderState.CONFIRMED)
     * ```
     */
    fun startingIn(state: S): Tool
}

/**
 * A tool that manages state transitions, with tools available based on the current state.
 *
 * Unlike [com.embabel.agent.api.tool.playbook.PlaybookTool] which uses unlock conditions,
 * StateMachineTool uses explicit states defined by an enum. Tools are registered with
 * specific states where they're available, and can trigger transitions to other states.
 *
 * ## Usage
 *
 * ```kotlin
 * enum class OrderState { DRAFT, CONFIRMED, SHIPPED, DELIVERED }
 *
 * StateMachineTool("orderProcessor", "Process orders", OrderState::class.java)
 *     .withInitialState(OrderState.DRAFT)
 *     .inState(OrderState.DRAFT)
 *         .withTool(addItemTool)
 *         .withTool(confirmTool).transitionsTo(OrderState.CONFIRMED)
 *     .inState(OrderState.CONFIRMED)
 *         .withTool(shipTool).transitionsTo(OrderState.SHIPPED)
 *     .inState(OrderState.SHIPPED)
 *         .withTool(deliverTool).transitionsTo(OrderState.DELIVERED)
 *     .inState(OrderState.DELIVERED)
 *         .withTool(reviewTool)
 * ```
 *
 * @param S The enum type defining the states
 * @param definition Tool definition (name, description, input schema)
 * @param stateType The enum class for states
 * @param initialState The starting state
 * @param stateTools Map of state to tools available in that state
 * @param llm LLM options for orchestration
 * @param maxIterations Maximum iterations before stopping
 */
data class StateMachineTool<S : Enum<S>> internal constructor(
    override val definition: Tool.Definition,
    override val metadata: Tool.Metadata = Tool.Metadata.DEFAULT,
    val stateType: Class<S>,
    val initialState: S?,
    internal val stateTools: Map<S, List<StateToolEntry<S>>> = emptyMap(),
    internal val globalTools: List<Tool> = emptyList(),
    internal val domainToolSources: List<DomainToolSource<*>> = emptyList(),
    override val llm: LlmOptions = LlmOptions(),
    val systemPromptCreator: (AgentProcess, S) -> String = { _, state ->
        defaultSystemPrompt(definition.description, state)
    },
    override val maxIterations: Int = AgenticTool.DEFAULT_MAX_ITERATIONS,
) : AgenticTool, StateMachineToolOperations<S> {

    /**
     * Entry for a tool registered in a state.
     */
    internal data class StateToolEntry<S : Enum<S>>(
        val tool: Tool,
        val transitionsTo: S? = null,
    )

    /**
     * Count of tools registered for each state.
     */
    val stateToolCounts: Map<S, Int>
        get() = stateTools.mapValues { it.value.size }

    /**
     * Total count of state-specific tools.
     */
    val totalStateTools: Int
        get() = stateTools.values.sumOf { it.size }

    /**
     * Create a state machine tool with name, description and state enum type.
     */
    constructor(
        name: String,
        description: String,
        stateType: Class<S>,
    ) : this(
        definition = Tool.Definition(
            name = name,
            description = description,
            inputSchema = Tool.InputSchema.empty(),
        ),
        stateType = stateType,
        initialState = null,
    )

    override fun call(input: String): Tool.Result {
        val currentInitialState = initialState
            ?: run {
                loggerFor<StateMachineTool<*>>().error(
                    "No initial state set for StateMachineTool '{}'",
                    definition.name,
                )
                return Tool.Result.error("No initial state configured for StateMachineTool")
            }

        val agentProcess = AgentProcess.get()
            ?: run {
                loggerFor<StateMachineTool<*>>().error(
                    "No AgentProcess context available for StateMachineTool '{}'",
                    definition.name,
                )
                return Tool.Result.error("No AgentProcess context available for StateMachineTool")
            }

        loggerFor<StateMachineTool<*>>().info(
            "Executing StateMachineTool '{}' starting in state {} with {} domain sources",
            definition.name,
            currentInitialState,
            domainToolSources.size,
        )

        // Create domain tool tracker if we have domain sources
        val domainToolTracker = if (domainToolSources.isNotEmpty()) {
            DomainToolTracker(domainToolSources)
        } else {
            null
        }

        // Create mutable state tracker
        val stateHolder = StateHolder(currentInitialState)

        // Create wrapped tools for each state
        val allWrappedTools = createWrappedTools(stateHolder, domainToolTracker)

        if (allWrappedTools.isEmpty() && domainToolSources.isEmpty()) {
            loggerFor<StateMachineTool<*>>().warn(
                "No tools registered for StateMachineTool '{}'",
                definition.name,
            )
            return Tool.Result.error("No tools registered for StateMachineTool")
        }

        val systemPrompt = systemPromptCreator(agentProcess, stateHolder.currentState)

        val ai = executingOperationContextFor(agentProcess).ai()
        val output = ai
            .withLlm(llm)
            .withId("state-machine-tool-${definition.name}")
            .withTools(allWrappedTools)
            .withSystemPrompt(systemPrompt)
            .generateText(input)

        return Tool.Result.text(output)
    }

    private fun createWrappedTools(
        stateHolder: StateHolder<S>,
        domainToolTracker: DomainToolTracker?,
    ): List<Tool> {
        val wrappedStateTools = stateTools.flatMap { (state, entries) ->
            entries.map { entry ->
                StateBoundTool(
                    delegate = entry.tool,
                    availableInState = state,
                    transitionsTo = entry.transitionsTo,
                    stateHolder = stateHolder,
                    domainToolTracker = domainToolTracker,
                )
            }
        }

        val wrappedGlobalTools = globalTools.map { tool ->
            GlobalStateTool(tool, stateHolder, domainToolTracker)
        }

        // Create placeholder tools for domain tool sources (available globally)
        val domainPlaceholderTools = domainToolTracker?.let { tracker ->
            domainToolSources.flatMap { source ->
                DomainToolFactory.createPlaceholderTools(source, tracker)
            }
        } ?: emptyList()

        return wrappedStateTools + wrappedGlobalTools + domainPlaceholderTools
    }

    /**
     * Set the initial state.
     */
    fun withInitialState(state: S): StateMachineTool<S> = copy(initialState = state)

    /**
     * Create a version of this tool that starts in the specified state.
     * Useful when the starting state depends on runtime context.
     */
    override fun startingIn(state: S): Tool = copy(initialState = state)

    /**
     * Begin configuring tools for a specific state.
     */
    fun inState(state: S): StateBuilder<S> = StateBuilder(state, this)

    /**
     * Add a tool available in all states.
     */
    fun withGlobalTool(tool: Tool): StateMachineTool<S> = copy(
        globalTools = globalTools + tool,
    )

    /**
     * Add tools available in all states.
     */
    fun withGlobalTools(vararg tools: Tool): StateMachineTool<S> = copy(
        globalTools = globalTools + tools.toList(),
    )

    override fun withLlm(llm: LlmOptions): StateMachineTool<S> = copy(llm = llm)

    override fun withMaxIterations(maxIterations: Int): StateMachineTool<S> = copy(
        maxIterations = maxIterations,
    )

    override fun withSystemPrompt(prompt: String): StateMachineTool<S> = copy(
        systemPromptCreator = { _, _ -> prompt },
    )

    /**
     * Set a custom system prompt creator that receives both AgentProcess and current state.
     */
    fun withSystemPromptCreator(creator: (AgentProcess, S) -> String): StateMachineTool<S> = copy(
        systemPromptCreator = creator,
    )

    override fun withParameter(parameter: Tool.Parameter): StateMachineTool<S> = copy(
        definition = definition.withParameter(parameter),
    )

    override fun withToolObject(toolObject: Any): StateMachineTool<S> {
        val additionalTools = Tool.safelyFromInstance(toolObject)
        return if (additionalTools.isEmpty()) {
            this
        } else {
            copy(globalTools = globalTools + additionalTools)
        }
    }

    internal fun addStateTool(state: S, tool: Tool, transitionsTo: S?): StateMachineTool<S> {
        val currentEntries = stateTools[state] ?: emptyList()
        val newEntry = StateToolEntry(tool, transitionsTo)
        return copy(
            stateTools = stateTools + (state to (currentEntries + newEntry)),
        )
    }

    /**
     * Register a domain class that can contribute @LlmTool methods when a single instance is retrieved.
     *
     * When a single artifact of the specified type is returned by any tool, any @LlmTool annotated
     * methods on that instance become available as tools (globally, not state-bound).
     *
     * Example:
     * ```kotlin
     * StateMachineTool("orderProcessor", "Process orders", OrderState::class.java)
     *     .withDomainToolsFrom(Order::class.java)  // Order methods become available when a single Order is retrieved
     *     .withInitialState(OrderState.DRAFT)
     *     .inState(OrderState.DRAFT)
     *         .withTool(findOrderTool)
     *     ...
     * ```
     *
     * @param type The domain class that may contribute tools
     */
    fun <T : Any> withDomainToolsFrom(type: Class<T>): StateMachineTool<S> = copy(
        domainToolSources = domainToolSources + DomainToolSource(type),
    )

    /**
     * Register a domain class that can contribute @LlmTool methods when a single instance is retrieved.
     * Kotlin-friendly version using reified type parameter.
     *
     * Example:
     * ```kotlin
     * StateMachineTool("orderProcessor", "Process orders", OrderState::class.java)
     *     .withDomainToolsFrom<Order>()  // Order methods become available when a single Order is retrieved
     *     .withInitialState(OrderState.DRAFT)
     *     ...
     * ```
     */
    inline fun <reified T : Any> withDomainToolsFrom(): StateMachineTool<S> =
        withDomainToolsFrom(T::class.java)

    companion object {
        fun <S : Enum<S>> defaultSystemPrompt(description: String, currentState: S) = """
            You are an intelligent agent that can use tools to complete tasks.

            Current state: $currentState

            Task: $description

            Use the available tools to complete the task. Some tools are only available
            in certain states. If a tool indicates it's not available in the current state,
            you may need to use other tools first to transition to the appropriate state.
            """.trimIndent()
    }
}

/**
 * Builder for configuring tools in a specific state.
 */
class StateBuilder<S : Enum<S>> internal constructor(
    private val state: S,
    internal val stateMachine: StateMachineTool<S>,
) {
    /**
     * Add a tool available in this state.
     */
    fun withTool(tool: Tool): StateToolRegistration<S> =
        StateToolRegistration(tool, state, stateMachine)

    /**
     * Switch to configuring a different state.
     */
    fun inState(state: S): StateBuilder<S> = StateBuilder(state, stateMachine)

    /**
     * Add a global tool (available in all states).
     */
    fun withGlobalTool(tool: Tool): StateMachineTool<S> =
        stateMachine.withGlobalTool(tool)

    /**
     * Finish building and return the StateMachineTool.
     */
    fun build(): StateMachineTool<S> = stateMachine
}

/**
 * Registration for a tool in a specific state.
 */
class StateToolRegistration<S : Enum<S>> internal constructor(
    private val tool: Tool,
    private val state: S,
    private val stateMachine: StateMachineTool<S>,
) {
    /**
     * Tool stays in current state after execution (no transition).
     */
    fun build(): StateBuilder<S> {
        val updated = stateMachine.addStateTool(state, tool, null)
        return StateBuilder(state, updated)
    }

    /**
     * Tool transitions to specified state after successful execution.
     */
    fun transitionsTo(targetState: S): StateBuilder<S> {
        val updated = stateMachine.addStateTool(state, tool, targetState)
        return StateBuilder(state, updated)
    }

    /**
     * Add another tool in the same state.
     */
    fun withTool(nextTool: Tool): StateToolRegistration<S> {
        val updated = stateMachine.addStateTool(state, tool, null)
        return StateToolRegistration(nextTool, state, updated)
    }

    /**
     * Switch to configuring a different state.
     */
    fun inState(nextState: S): StateBuilder<S> {
        val updated = stateMachine.addStateTool(state, tool, null)
        return StateBuilder(nextState, updated)
    }
}
