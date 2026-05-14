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

import com.embabel.agent.api.common.StuckHandler
import com.embabel.agent.api.common.scope.AgentScopeBuilder
import com.embabel.common.core.types.Described
import com.embabel.common.core.types.HasInfoString
import com.embabel.common.core.types.Named
import com.embabel.common.util.indent
import com.embabel.common.util.indentLines
import com.fasterxml.jackson.annotation.JsonIgnore

interface ConditionSource {

    val conditions: Set<Condition>
}

interface GoalSource {

    val goals: Set<Goal>
}

interface ActionSource {

    val actions: List<Action>
}

/**
 * Defines the scope of an agent or agents: Goals, conditions and actions.
 * Both Agents and AgentPlatforms are AgentScopes.
 */
interface AgentScope : Named, Described, GoalSource, ConditionSource, ActionSource, DataDictionary, HasInfoString,
    AgentScopeBuilder {

    /**
     * Whether to hide the agent's actions and conditions
     * from the outside world, defaults to false.
     */
    val opaque: Boolean

    /**
     * Handler to call when an agent created from this scope gets stuck.
     * Defaults to null.
     */
    @get:JsonIgnore
    val stuckHandler: StuckHandler?
        get() = null

    @get:JsonIgnore
    override val domainTypes: Collection<DomainType>
        get() = actions.flatMap { it.domainTypes }.distinct()

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String =
        """|name: $name
           |goals:
           |${goals.sortedBy { it.name }.joinToString("\n") { it.infoString(true, 1) }}
           |actions:
           |${actions.sortedBy { it.name }.joinToString("\n") { it.infoString(true, 1) }}
           |conditions:
           |${conditions.map { it.name }.sorted().joinToString("\n") { it.indent(1) }}
           |schema types:
           |${domainTypes.map { it }.joinToString("\n") { it.infoString(true, 1) }}
           |"""
            .trimMargin()
            .indentLines(indent)

    /**
     * Create a new agent from the given scope
     * @param name Name of the agent to create
     * @param description Description of the agent to create
     */
    fun createAgent(
        name: String,
        provider: String,
        description: String,
    ): Agent {
        val newAgent = Agent(
            name = name,
            provider = provider,
            description = description,
            actions = actions,
            goals = goals,
            conditions = conditions,
            stuckHandler = stuckHandler,
            opaque = opaque,
            domainTypes = domainTypes.toList(),
        )
        return newAgent
    }

    fun resolveType(name: String): DomainType =
        domainTypes.findTypeByNameOrSimpleName(name)
            ?: error(
                "Schema type '$name' not found in agent ${this.name}: types were [${
                    domainTypes.joinToString(", ") { it.name }
                }]",
            )

    companion object {

        /**
         * Create an AgentScope with the given parameters. The resulting scope will have no parent scopes.
         * @param name name of the scope
         * @param description description of the scope
         * @param actions actions available to agents created from this scope
         * @param goals goals that agents created from this scope will try to achieve
         * @param conditions conditions that agents created from this scope can check
         * @param referenceTypes additional types that will be brought in.
         * Necessary if only dynamic types are used in the actions and conditions, or if you want to include types from a shared library without including all of their actions and conditions.
         */
        operator fun invoke(
            name: String,
            description: String = name,
            actions: List<Action> = emptyList(),
            goals: Set<Goal> = emptySet(),
            referenceTypes: Collection<DomainType> = emptyList(),
            conditions: Set<Condition> = emptySet(),
            opaque: Boolean = false,
            stuckHandler: StuckHandler? = null,
        ): AgentScope {
            return AgentScopeImpl(
                name = name,
                description = description,
                actions = actions,
                goals = goals,
                conditions = conditions,
                opaque = opaque,
                stuckHandler = stuckHandler,
                referenceTypes = referenceTypes,
            )
        }
    }

    override fun createAgentScope(): AgentScope = this
}

private data class AgentScopeImpl(
    override val name: String,
    override val description: String,
    override val actions: List<Action>,
    override val goals: Set<Goal>,
    override val conditions: Set<Condition>,
    override val opaque: Boolean,
    override val stuckHandler: StuckHandler?,
    private val referenceTypes: Collection<DomainType>,
) : AgentScope {

    override val domainTypes = super.domainTypes + referenceTypes
}

/**
 * Look up a [DomainType] by exact [DomainType.name] (FQN or whatever the
 * type was registered under) and fall back to a simple-name match
 * (`name.substringAfterLast('.')`). Lets callers refer to a type by its
 * natural simple name in YAML / spec form even though the platform
 * registered the JVM-backed type under its FQN. Returns `null` so callers
 * can chain other lookups (classpath, etc.) before throwing.
 *
 * Shared by [AgentScope.resolveType] and downstream action / goal
 * machinery so the lookup behaves identically everywhere.
 */
fun Collection<DomainType>.findTypeByNameOrSimpleName(name: String): DomainType? =
    find { it.name == name } ?: find { it.name.substringAfterLast('.') == name }
