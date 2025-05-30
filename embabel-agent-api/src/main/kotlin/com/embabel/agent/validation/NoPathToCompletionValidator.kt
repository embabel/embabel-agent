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
package com.embabel.agent.validation

import com.embabel.agent.core.Action
import org.springframework.stereotype.Component
import com.embabel.agent.core.AgentScope
import com.embabel.agent.core.support.Rerun
import org.slf4j.LoggerFactory

/**
 * Validator that checks whether an agent definition has at least one possible path
 * from its initial conditions, through available actions, to achieve its defined goals.
 *
 * This includes:
 * - Verifying that actions exist for agents with goals.
 * - Ensuring there are actions whose effects can achieve goals.
 * - Propagating triggerability to confirm that, starting from initial conditions,
 *   it is possible to reach at least one goal via a valid chain of actions.
 *
 * Reports specific errors when no such path exists.
 */

@Component
class NoPathToCompletionValidator : AgentValidator {
    private val logger = LoggerFactory.getLogger(NoPathToCompletionValidator::class.java)

    override fun validate(agentScope: AgentScope): ValidationResult {
        // No goals, validation OK
        if (agentScope.goals.isEmpty()) return ValidationResult(true, emptyList())

        // No actions, error
        if (agentScope.actions.isEmpty()) {
            return errorResult(
                code = "NO_ACTIONS_TO_GOALS",
                message = "Agent '${agentScope.name}' has goals but no actions to achieve them",
                agentScope = agentScope
            )
        }

        // Precondition validation (checks for missing preconditions in action definitions)
        val preconditionErrors = findMissingPreconditionErrors(agentScope)
        if (preconditionErrors.isNotEmpty()) {
            return ValidationResult(false, preconditionErrors)
        }

        // --- Pathfinding to reach goals ---
        // Find all triggerable actions and reachable end conditions
        val triggerableActions = findTriggerableActions(agentScope)

        // All end conditions: initial + effects of triggerable actions + hasRun of executed actions
        val currentConditions = agentScope.conditions.map { it.name }.toMutableSet()
        for (action in agentScope.actions) {
            if (action.name in triggerableActions) {
                currentConditions.addAll(action.effects.map { it.key })
                currentConditions.add(Rerun.hasRunCondition(action))
            }
        }

        // Extract preconditions of the goals (mostly hasRun_* and other goal conditions)
        val goalPreconditions = agentScope.goals.flatMap { it.pre }.toSet()

        // Is at least one goal achievable?
        val canAchieveAnyGoal = goalPreconditions.any { it in currentConditions }
        if (!canAchieveAnyGoal) {
            return errorResult(
                code = "NO_PATH_FROM_INITIAL_CONDITIONS",
                message = "Agent '${agentScope.name}' has no path from initial conditions to any goal (goals unreachable)",
                agentScope = agentScope
            )
        }

        return ValidationResult(true, emptyList())
    }

    private fun errorResult(code: String, message: String, agentScope: AgentScope): ValidationResult {
        return ValidationResult(
            false,
            listOf(
                ValidationError(
                    code = code,
                    message = message,
                    severity = ValidationSeverity.ERROR,
                    location = ValidationLocation(
                        type = "Agent",
                        name = agentScope.name,
                        agentName = agentScope.name,
                        component = agentScope.name
                    )
                )
            )
        )
    }

    private fun findMissingPreconditionErrors(agentScope: AgentScope): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        for (action in agentScope.actions) {
            val missing = getActionPreconditions(action.preconditions)
                .filterNot { hasInitialCondition(agentScope, it) }
            if (missing.isNotEmpty()) {
                errors.add(
                    ValidationError(
                        code = "MISSING_PRECONDITION",
                        message = "Action '${action.name}' has preconditions that don't exist: $missing",
                        severity = ValidationSeverity.ERROR,
                        location = ValidationLocation(
                            type = "Action",
                            name = action.name,
                            agentName = agentScope.name,
                            component = action.name
                        )
                    )
                )
            }
        }
        return errors
    }

    private fun findTriggerableActions(agentScope: AgentScope): Set<String> {
        val triggerable = mutableSetOf<String>()
        val actions = agentScope.actions
        val preconditionsFor = actions.associateBy({ it.name }) { getActionPreconditions(it.preconditions) }

        addDirectlyTriggerableActions(preconditionsFor, agentScope, triggerable)
        propagateTriggerableActions(actions, preconditionsFor, agentScope, triggerable)

        return triggerable
    }

    private fun addDirectlyTriggerableActions(
        preconditionsFor: Map<String, List<String>>,
        agentScope: AgentScope,
        triggerable: MutableSet<String>
    ) {
        for ((actionName, preconditions) in preconditionsFor) {
            if (arePreconditionsMetWithInitial(agentScope, preconditions)) {
                triggerable.add(actionName)
            }
        }
    }

    private fun arePreconditionsMetWithInitial(agentScope: AgentScope, preconditions: List<String>): Boolean {
        return preconditions.isEmpty() || preconditions.all { hasInitialCondition(agentScope, it) }
    }

    private fun propagateTriggerableActions(
        actions: List<Action>,
        preconditionsFor: Map<String, List<String>>,
        agentScope: AgentScope,
        triggerable: MutableSet<String>
    ) {
        var foundNew: Boolean
        do {
            foundNew = false
            val currentConditions = getCurrentConditions(agentScope, actions, triggerable)
            for ((actionName, preconditions) in preconditionsFor) {
                if (shouldAddAsTriggerable(actionName, preconditions, currentConditions, triggerable)) {
                    triggerable.add(actionName)
                    foundNew = true
                }
            }
        } while (foundNew)
    }

    private fun getCurrentConditions(
        agentScope: AgentScope,
        actions: List<Action>,
        triggerable: Set<String>
    ): MutableSet<String> {
        val currentConditions = agentScope.conditions.map { it.name }.toMutableSet()
        for (action in actions) {
            if (action.name in triggerable) {
                currentConditions.addAll(action.effects.map { it.key })
                currentConditions.add(Rerun.hasRunCondition(action))
            }
        }
        return currentConditions
    }

    private fun shouldAddAsTriggerable(
        actionName: String,
        preconditions: List<String>,
        currentConditions: Set<String>,
        triggerable: Set<String>
    ): Boolean {
        return actionName !in triggerable &&
                (preconditions.isEmpty() || preconditions.all { hasConditionInSet(currentConditions, it) })
    }


    private fun hasInitialCondition(agentScope: AgentScope, preconditionName: String): Boolean =
        agentScope.conditions.any { it.name == preconditionName }

    private fun hasConditionInSet(conditions: Set<String>, preconditionName: String): Boolean =
        conditions.contains(preconditionName)

    private fun getActionPreconditions(preconditions: Map<String, Any>) =
        preconditions.keys.filterNot { it.startsWith("it:") || it.startsWith("hasRun_") }
}
