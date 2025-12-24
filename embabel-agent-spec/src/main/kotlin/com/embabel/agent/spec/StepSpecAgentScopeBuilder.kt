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
package com.embabel.agent.spec

import com.embabel.agent.api.common.scope.AgentScopeBuilder
import com.embabel.agent.core.*
import com.embabel.agent.spec.model.StepSpecContext
import com.embabel.agent.spec.persistence.StepSpecRepository
import com.embabel.agent.spec.yml.YmlStepSpecRepository
import org.slf4j.LoggerFactory

/**
 * Deploys step specs from a repository as an AgentScope.
 * Steps (actions and goals) are loaded from YAML files and converted
 * to executable Actions and Goals.
 */
class StepSpecAgentScopeBuilder(
    private val name: String = "yml-steps",
    private val agentPlatform: AgentPlatform,
    private val repository: StepSpecRepository = YmlStepSpecRepository(System.getProperty("user.dir") + "/steps"),
    private val dataDictionary: DataDictionary = agentPlatform,
    private val toolGroups: List<ToolGroupDescription> = agentPlatform.toolGroupResolver.availableToolGroups()
) : AgentScopeBuilder {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun createAgentScope(): AgentScope {
        val steps = repository.findAll().toList()
        logger.info("Building AgentScope from {} step definitions", steps.size)

        val stepContext = StepSpecContext(
            name = name,
            dataDictionary = dataDictionary,
            toolGroups = toolGroups,
        )

        val actions = mutableListOf<Action>()
        val goals = mutableSetOf<Goal>()

        for (step in steps) {
            when (val emitted = step.emit(stepContext)) {
                is Action -> {
                    actions.add(emitted)
                    logger.debug("Emitted action: {}", emitted.name)
                }

                is Goal -> {
                    goals.add(emitted)
                    logger.debug("Emitted goal: {}", emitted.name)
                }
            }
        }

        logger.info("Built AgentScope with {} actions and {} goals", actions.size, goals.size)

        return AgentScope(
            name = name,
            description = "Agent scope built from YAML step definitions",
            actions = actions,
            goals = goals,
            conditions = emptySet(),
        )
    }

    /**
     * Convenience method to create and deploy the AgentScope
     */
    fun deploy(): AgentScope {
        val agentScope = createAgentScope()
        agentPlatform.deploy(agentScope)
        logger.info("Deployed AgentScope '{}' to platform", agentScope.name)
        return agentScope
    }
}
