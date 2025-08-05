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

import com.embabel.agent.api.common.StuckHandler
import com.embabel.common.core.types.AssetCoordinates
import com.embabel.common.core.types.Described
import com.embabel.common.core.types.Named
import com.embabel.common.core.types.Semver
import com.embabel.common.util.ComputerSaysNoSerializer
import com.embabel.common.util.indentLines
import com.embabel.plan.goap.GoapPlanningSystem
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import org.slf4j.LoggerFactory

/**
 * An agent defines a set of actions and conditions
 * that enable planning.
 * @param name The name of the agent.
 * @param provider The provider of the agent.
 * @param version The version of the agent. Defaults to 0.1.0
 * @param description A description of the agent. Required
 * @param goals The goals the agent can achieve
 * @param stuckHandler The handler to call when the agent is stuck, if provided
 * @param conditions Well-known conditions that can be referenced by actions
 * @param actions The actions the agent can use
 * @param domainTypes Data types used in this agent
 */
@JsonSerialize(using = ComputerSaysNoSerializer::class)
data class Agent(
    override val name: String,
    override val provider: String,
    override val version: Semver = Semver(),
    override val description: String,
    override val conditions: Set<Condition> = emptySet(),
    override val actions: List<Action>,
    override val goals: Set<Goal>,
    val stuckHandler: StuckHandler? = null,
    override val domainTypes: Collection<DomainType> = mergeTypes(
        agentName = name,
        defaultDataTypes = emptyList(),
        actions = actions,
    ),
) : Described, AssetCoordinates, AgentScope {

    @JvmOverloads
    constructor(
        name: String,
        provider: String,
        version: String,
        description: String,
        goals: Set<Goal>,
        actions: List<Action>,
        conditions: Set<Condition> = emptySet(),
        stuckHandler: StuckHandler? = null,
    ) : this(
        name = name,
        provider = provider,
        version = Semver(version),
        description = description,
        goals = goals,
        actions = actions,
        conditions = conditions,
        stuckHandler = stuckHandler,
    )

    /**
     * Return a version of the agent with the single goal
     */
    fun withSingleGoal(goal: Goal): Agent =
        copy(goals = setOf(goal))

    /**
     * Return a version of the agent with actions and conditions pruned to the given pruned planning system.
     */
    fun pruneTo(pruned: GoapPlanningSystem): Agent =
        copy(
            actions = actions.filter { action -> pruned.actions.any { it.name == action.name } },
            conditions = conditions.filter { condition ->
                pruned.actions.any { it.knownConditions.contains(condition.name) }
            }.toSet(),
        )

    val planningSystem: GoapPlanningSystem
        get() {
            val actions = actions.toSet()
            logger.debug(infoString())
            return GoapPlanningSystem(actions, goals)
        }

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String =
        """|description: $description
           |provider: $provider
           |version: $version
           |${super.infoString(verbose, indent)}
           |"""
            .trimMargin()
            .indentLines(indent)

    companion object {

        private val logger = LoggerFactory.getLogger(Agent::class.java)

        /**
         * Merge the default data types with the schema types from actions.
         * Combines the properties of schema types
         */
        fun mergeTypes(
            agentName: String,
            defaultDataTypes: List<DynamicType>,
            actions: List<Action>,
        ): List<DomainType> {
            // Merge properties from multiple type references
            for (action in actions) {
                logger.debug(
                    "Action {} has types {}, inputBinding={}",
                    action.name,
                    action.domainTypes,
                    action.inputs,
                )
            }
            val mergedSchemaTypes = (defaultDataTypes + actions
                .flatMap { it.dynamicTypes }
                .groupBy { it.name }
                .mapValues { (_, types) ->
                    types.reduce { acc, type ->
                        acc.copy(properties = acc.properties + type.properties)
                    }
                }
                .values)
                .map { it.copy(properties = it.properties.distinctBy { it.name }) }
                .distinctBy { it.name }
                .sortedBy { it.name }

            logger.debug(
                "Agent {} inferred types:\n\t{}",
                agentName,
                mergedSchemaTypes.joinToString("\n\t") { "${it.name} - ${it.properties.map { p -> p.name }}" })
            return mergedSchemaTypes + actions.flatMap { it.jvmTypes }.distinctBy { it.name }
        }
    }

}

/**
 * Safely serializable agent metadata
 *
 * This class provides a lightweight representation of an Agent that can be
 * safely serialized and transferred across system boundaries. It contains
 * only the essential metadata about an agent without any implementation details
 * or complex references that might cause serialization issues.
 *
 * @property name The name of the agent
 * @property version The version of the agent, defaults to DEFAULT_VERSION
 * @property description A human-readable description of the agent's purpose and capabilities
 * @property goals The set of goals this agent can achieve
 * @property actions A list of metadata about the actions this agent can perform
 * @property conditions A set of condition names that this agent recognizes
 */
data class AgentMetadata(
    override val name: String,
    override val provider: String,
    override val version: Semver,
    override val description: String,
    val goals: Set<Goal>,
    val actions: List<ActionMetadata>,
    val conditions: Set<String>,
) : Named, Described, AssetCoordinates {

    /**
     * Constructs AgentMetadata from a full Agent instance
     *
     * This constructor extracts only the serializable metadata from a complete Agent,
     * making it suitable for API responses and persistence.
     *
     * @param agent The complete Agent instance to extract metadata from
     */
    constructor(agent: Agent) : this(
        name = agent.name,
        provider = agent.provider,
        version = agent.version,
        description = agent.description,
        goals = agent.goals,
        actions = agent.actions.map { ActionMetadata(it) },
        conditions = agent.conditions.map { it.name }.toSet()
    )
}
