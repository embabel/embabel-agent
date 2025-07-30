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

import com.embabel.agent.core.AgentScope
import com.embabel.agent.core.support.Rerun.HAS_RUN_CONDITION_PREFIX
import com.embabel.plan.goap.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Validator that checks whether an agent definition has at least one possible path
 * from its initial conditions, through available actions, to achieve its defined goals.
 *
 * Uses the GOAP planner to validate that goals can be achieved through a sequence of actions.
 * Reports specific errors when no such path exists.
 */
@Component
class GoapPathToCompletionValidator : AgentValidator {

    private val logger = LoggerFactory.getLogger(GoapPathToCompletionValidator::class.java)

    override fun validate(agentScope: AgentScope): ValidationResult {
        val errors = mutableListOf<ValidationError>()

        if (agentScope.goals.isEmpty()) {
            return ValidationResult(true, emptyList())
        }

        if (agentScope.actions.isEmpty()) {
            errors.add(error("NO_ACTIONS_TO_GOALS", "Agent '${agentScope.name}' has no actions.", agentScope))
            return ValidationResult(false, errors)
        }

        // Create initial world state
        val initialWorldState = mutableMapOf<String, ConditionDetermination>()

        val actionDependencies = mutableMapOf<String, Set<String>>()
        val actionOutputs = mutableMapOf<String, Set<String>>()

        // For each action, need to track:
        // 1. What data it needs as input (preconditions)
        // 2. What data it produces as output (effects)
        // So need to exclude hasRun_ conditions as they are runtime state indicators, not data dependencies.
        agentScope.actions.forEach { action ->
            val nonHasRunPreconditions =
                action.preconditions.filterKeys { !it.startsWith(HAS_RUN_CONDITION_PREFIX) }.keys
            val nonHasRunEffects = action.effects.filterKeys { !it.startsWith(HAS_RUN_CONDITION_PREFIX) }.keys

            actionDependencies[action.name] = nonHasRunPreconditions
            actionOutputs[action.name] = nonHasRunEffects
        }

        // Find actions that can be considered first steps (executable without other actions)
        // These actions either:
        // 1. Have no preconditions (other than hasRun_ conditions), or
        // 2. Have preconditions that are set to FALSE (meaning they don't need to be true), or
        // 3. Have preconditions not produced by any other action (external input)
        val firstActions = agentScope.actions.filter { action ->
            val rawDeps = actionDependencies[action.name] ?: emptySet()
            val outputsOfThisAction = action.effects.keys

            // Remove anything this action itself produces (self-sufficient actions)
            val deps = rawDeps.filterNot { dep -> outputsOfThisAction.contains(dep) }.toSet()

            // Action can be first if:
            // 1. It has no external dependencies, OR
            // 2. All its dependencies are either not produced by other actions OR set to FALSE
            deps.isEmpty() || deps.all { dep ->
                val isProducedByOthers = actionOutputs.values.any { outputs -> outputs.contains(dep) }
                val preconditionValue = action.preconditions[dep]

                // Can start if dependency is not produced by others, or if it's explicitly set to FALSE
                !isProducedByOthers || preconditionValue == ConditionDetermination.FALSE
            }
        }

        if (firstActions.isEmpty()) {
            errors.add(
                error(
                    "NO_STARTING_ACTION",
                    "No action found that can start the chain. All actions depend on outputs from other actions.",
                    agentScope
                )
            )
            return ValidationResult(false, errors)
        }

        logger.debug("First actions: {}", firstActions.map { it.name })

        // Start by collecting all conditions mentioned in actions and goals
        val allConditions = mutableSetOf<String>()

        agentScope.actions.forEach { action ->
            allConditions.addAll(action.preconditions.keys.filter { !it.startsWith(HAS_RUN_CONDITION_PREFIX) })
            allConditions.addAll(action.effects.keys.filter { !it.startsWith(HAS_RUN_CONDITION_PREFIX) })
        }

        agentScope.goals.forEach { goal ->
            allConditions.addAll(goal.preconditions.keys.filter { !it.startsWith(HAS_RUN_CONDITION_PREFIX) })
        }

        // Initialize all conditions to FALSE by default
        allConditions.forEach { condition ->
            initialWorldState[condition] = ConditionDetermination.FALSE
        }

        // For first actions that have no preconditions (can run immediately),
        // we assume their effects are immediately available
        firstActions.forEach { action ->
            // For actions with no TRUE preconditions (can run immediately),
            // make their effects available in the initial state
            val hasTruePreconditions = action.preconditions.any { (key, value) ->
                !key.startsWith(HAS_RUN_CONDITION_PREFIX) && value == ConditionDetermination.TRUE
            }

            if (!hasTruePreconditions) {
                action.effects.forEach { (key, value) ->
                    if (!key.startsWith(HAS_RUN_CONDITION_PREFIX) && value == ConditionDetermination.TRUE) {
                        logger.debug("✅ Setting initialWorldState[$key] = TRUE from effect of ${action.name} (no preconditions)")
                        initialWorldState[key] = ConditionDetermination.TRUE
                    }
                }
            }
        }

        // Set to TRUE any condition that is an external input (appears as a precondition but not as an effect)
        allConditions.forEach { condition ->
            val isProducedByAction = agentScope.actions.any { action ->
                action.effects.containsKey(condition) && action.effects[condition] == ConditionDetermination.TRUE
            }
            val isNeededByAction = agentScope.actions.any { action ->
                action.preconditions.containsKey(condition) && action.preconditions[condition] == ConditionDetermination.TRUE
            }

            // If it's needed but not produced by any action, it must be an external input
            if (isNeededByAction && !isProducedByAction && !condition.startsWith(HAS_RUN_CONDITION_PREFIX)) {
                logger.debug("✅ Setting initialWorldState[$condition] = TRUE as external input")
                initialWorldState[condition] = ConditionDetermination.TRUE
            }
        }

        logger.debug(
            "Final initialWorldState: {}",
            initialWorldState.entries.joinToString { "${it.key} -> ${it.value}" })

        // Create planner with the determined world state
        val planner = AStarGoapPlanner(WorldStateDeterminer.fromMap(initialWorldState))

        // Convert agent actions to GOAP actions, removing hasRun_ conditions
        val goapActions = agentScope.actions.map { action ->
            GoapAction(
                name = action.name,
                preconditions = action.preconditions.filterKeys { !it.startsWith(HAS_RUN_CONDITION_PREFIX) },
                effects = action.effects.filterKeys { !it.startsWith(HAS_RUN_CONDITION_PREFIX) },
                cost = action.cost,
                value = action.value
            )
        }.toSet()

        // Check each goal
        var allGoalsAchievable = true
        val failedGoals = mutableListOf<String>()

        for (goal in agentScope.goals) {
            // Find the action that is annotated with @AchievesGoal and matches this goal
            val goalAction = agentScope.actions.find { it.name == goal.name }

            if(goalAction == null) {
                logger.error("Goal action '${goal.name}' not found in agent actions. Skipping this goal.")
                continue
            }

            // For goal actions, we achieve the action's effects.
            // The goal is to reach a state where the action has run and produced its outputs
            val goalPreconditions = goalAction.effects
                .filterKeys { !it.startsWith(HAS_RUN_CONDITION_PREFIX) }
                .filterValues { it == ConditionDetermination.TRUE }

            val goapGoal = GoapGoal(
                name = goal.name,
                preconditions = goalPreconditions,
                value = goal.value
            )

            // Try to find a plan to this goal
            val plan = planner.planToGoal(goapActions, goapGoal)

            if (plan == null || plan.actions.isEmpty()) {
                allGoalsAchievable = false
                failedGoals.add(goal.name)
                logger.debug("❌ No plan found for goal: ${goal.name}")
                logger.debug("  Goal preconditions (desired effects): {}", goapGoal.preconditions)
            } else {
                logger.debug("✅ Plan found for goal: ${goal.name}")
                logger.debug("  Actions: {}", plan.actions.map { it.name })
            }
        }

        if (!allGoalsAchievable) {
            errors.add(
                error(
                    "NO_PATH_TO_GOAL",
                    "No valid path found to achieve goals: ${failedGoals.joinToString(", ")}. " +
                            "Either no plan exists or the goals' preconditions cannot be achieved through available actions.",
                    agentScope
                )
            )
        }

        return ValidationResult(errors.isEmpty(), errors)
    }

    private fun error(code: String, message: String, agentScope: AgentScope): ValidationError =
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
}
