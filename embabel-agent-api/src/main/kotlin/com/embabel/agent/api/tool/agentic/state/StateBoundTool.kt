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
import com.embabel.agent.api.tool.agentic.DomainToolTracker
import com.embabel.common.util.loggerFor

/**
 * Holds the current state of a state machine execution.
 * Mutable to allow state transitions during tool execution.
 */
class StateHolder<S : Enum<S>>(
    initialState: S,
) {
    @Volatile
    var currentState: S = initialState
        private set

    fun transitionTo(newState: S) {
        loggerFor<StateHolder<*>>().info(
            "State transition: {} -> {}",
            currentState,
            newState,
        )
        currentState = newState
    }
}

/**
 * Tool wrapper that only executes when the state machine is in the correct state.
 * If called in wrong state, returns an informative error message.
 * On successful execution, optionally transitions to a new state.
 */
internal class StateBoundTool<S : Enum<S>>(
    private val delegate: Tool,
    private val availableInState: S,
    private val transitionsTo: S?,
    private val stateHolder: StateHolder<S>,
    private val domainToolTracker: DomainToolTracker? = null,
) : Tool {

    override val definition: Tool.Definition = object : Tool.Definition {
        override val name: String = delegate.definition.name
        override val description: String = buildDescription()
        override val inputSchema: Tool.InputSchema = delegate.definition.inputSchema
    }

    private fun buildDescription(): String {
        val stateNote = "[Available in state: $availableInState]"
        val transitionNote = transitionsTo?.let { " [Transitions to: $it]" } ?: ""
        return "${delegate.definition.description}\n\n$stateNote$transitionNote"
    }

    override val metadata: Tool.Metadata = delegate.metadata

    override fun call(input: String): Tool.Result {
        val currentState = stateHolder.currentState

        if (currentState != availableInState) {
            loggerFor<StateBoundTool<*>>().debug(
                "Tool '{}' not available in state {} (requires {})",
                delegate.definition.name,
                currentState,
                availableInState,
            )
            return Tool.Result.text(
                "This tool is not available in the current state ($currentState). " +
                    "It is only available in state: $availableInState"
            )
        }

        loggerFor<StateBoundTool<*>>().info(
            "Executing tool '{}' in state {}",
            delegate.definition.name,
            currentState,
        )

        val result = delegate.call(input)

        // Try to bind domain tools from any artifacts
        if (result is Tool.Result.WithArtifact) {
            val artifact = result.artifact
            when (artifact) {
                is Iterable<*> -> {} // Don't bind collections
                else -> domainToolTracker?.tryBindArtifact(artifact)
            }
        }

        // Transition state on successful execution if configured
        if (transitionsTo != null && result !is Tool.Result.Error) {
            stateHolder.transitionTo(transitionsTo)
        }

        return result
    }

    override fun toString(): String =
        "StateBoundTool(${delegate.definition.name}, state=$availableInState, transitionsTo=$transitionsTo)"
}

/**
 * Tool wrapper for global tools that are available in all states.
 * Logs the current state when called but doesn't restrict access.
 */
internal class GlobalStateTool<S : Enum<S>>(
    private val delegate: Tool,
    private val stateHolder: StateHolder<S>,
    private val domainToolTracker: DomainToolTracker? = null,
) : Tool {

    override val definition: Tool.Definition = object : Tool.Definition {
        override val name: String = delegate.definition.name
        override val description: String = "${delegate.definition.description}\n\n[Available in all states]"
        override val inputSchema: Tool.InputSchema = delegate.definition.inputSchema
    }

    override val metadata: Tool.Metadata = delegate.metadata

    override fun call(input: String): Tool.Result {
        loggerFor<GlobalStateTool<*>>().debug(
            "Executing global tool '{}' in state {}",
            delegate.definition.name,
            stateHolder.currentState,
        )

        val result = delegate.call(input)

        // Try to bind domain tools from any artifacts
        if (result is Tool.Result.WithArtifact) {
            val artifact = result.artifact
            when (artifact) {
                is Iterable<*> -> {} // Don't bind collections
                else -> domainToolTracker?.tryBindArtifact(artifact)
            }
        }

        return result
    }

    override fun toString(): String = "GlobalStateTool(${delegate.definition.name})"
}
