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
package com.embabel.plan.goap

import com.embabel.common.util.loggerFor
import com.embabel.plan.Action
import com.embabel.plan.Goal
import com.embabel.plan.common.condition.*

/**
 * Abstract class for a Goap planner with common optimization.
 */
abstract class OptimizingGoapPlanner(
    worldStateDeterminer: WorldStateDeterminer,
) : AbstractConditionPlanner(worldStateDeterminer) {

    final override fun planToGoal(
        actions: Collection<Action>,
        goal: Goal,
    ): ConditionPlan? {
        goal as ConditionGoal
        val startState = worldState()
        val directPlan = planToGoalFrom(startState, actions.filterIsInstance<ConditionAction>(), goal)

        val goapActions = actions.filterIsInstance<ConditionAction>()

        // See if changing any unknown conditions could change the result
        val unknownConditions = startState.unknownConditions()
        if (unknownConditions.isNotEmpty()) {
            if (unknownConditions.size > 1) {
                TODO("Handle more than one unknown condition: Have $unknownConditions")
            }
            val condition = unknownConditions.single()
            val variants = startState.variants(condition)
            val allPossiblePlans = variants.map {
                planToGoalFrom(it, goapActions, goal)
            } + directPlan
            if (allPossiblePlans.filterNotNull().distinctBy { it.actions.joinToString { a -> a.name } }.size > 1) {
                // We need to evaluate the condition
                val fullyEvaluatedState = startState + (condition to worldStateDeterminer.determineCondition(condition))
                return planToGoalFrom(fullyEvaluatedState, goapActions, goal)
            }
        }

        // Just use direct plan
        return directPlan
    }

    override fun prune(planningSystem: ConditionPlanningSystem): ConditionPlanningSystem {
        // Preserve alternative routes, including those enabled by unexpected action outcomes.
        val requiredConditions = planningSystem.goals.flatMap { it.preconditions.toList() }.toMutableSet()
        val relevantActions = mutableSetOf<ConditionAction>()
        do {
            val newlyRelevantActions = planningSystem.actions.filter { action ->
                action !in relevantActions && action.effects.any { (condition, value) ->
                    (condition to value) in requiredConditions
                }
            }
            relevantActions.addAll(newlyRelevantActions)
            newlyRelevantActions.forEach { requiredConditions.addAll(it.preconditions.toList()) }
        } while (newlyRelevantActions.isNotEmpty())

        loggerFor<OptimizingGoapPlanner>().info(
            "Pruning retained {} of {} actions relevant to goals",
            relevantActions.size,
            planningSystem.actions.size,
        )
        return planningSystem.copy(
            actions = planningSystem.actions.filter { it in relevantActions }.toSet(),
        )
    }

    protected abstract fun planToGoalFrom(
        startState: ConditionWorldState,
        actions: Collection<ConditionAction>,
        goal: ConditionGoal,
    ): ConditionPlan?
}
