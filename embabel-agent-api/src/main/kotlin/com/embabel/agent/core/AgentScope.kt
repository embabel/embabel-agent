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
interface AgentScope : Named, Described, GoalSource, ConditionSource, ActionSource, DataDictionary, HasInfoString {

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
            description = name,
            actions = actions,
            goals = goals,
            conditions = conditions,
        )
        return newAgent
    }

    fun resolveType(name: String): DomainType {
        return domainTypes.find { it.name == name }
            ?: error("Schema type '$name' not found in agent ${this.name}: types were ${domainTypes.joinToString(", ") { it.name }}")
    }

    companion object {

        operator fun invoke(
            name: String,
            description: String = name,
            actions: List<Action> = emptyList(),
            goals: Set<Goal> = emptySet(),
            conditions: Set<Condition> = emptySet(),
        ): AgentScope {
            return AgentScopeImpl(
                name = name,
                description = description,
                actions = actions,
                goals = goals,
                conditions = conditions,
            )
        }
    }
}

private data class AgentScopeImpl(
    override val name: String,
    override val description: String,
    override val actions: List<Action>,
    override val goals: Set<Goal>,
    override val conditions: Set<Condition>,
    override val domainTypes: Collection<DynamicType> = emptyList(),
) : AgentScope
