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
package com.embabel.plan.goap

import com.embabel.common.util.loggerFor
import com.embabel.plan.Action
import com.embabel.plan.Goal

/**
 * Abstract class for a Goap planner with common optimization.
 */
abstract class OptimizingGoapPlanner(
    val worldStateDeterminer: WorldStateDeterminer,
) : GoapPlanner {

    override fun worldState(): GoapWorldState {
        return worldStateDeterminer.determineWorldState()
    }

    final override fun planToGoal(
        actions: Collection<Action>,
        goal: Goal,
    ): GoapPlan? {
        goal as GoapGoal
        val startState = worldState()
        val directPlan = planToGoalFrom(startState, actions.filterIsInstance<GoapAction>(), goal)

        val goapActions = actions.filterIsInstance<GoapAction>()

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

    override fun prune(planningSystem: GoapPlanningSystem): GoapPlanningSystem {
        val allPlans = plansToGoals(planningSystem)
        loggerFor<OptimizingGoapPlanner>().info(
            "${allPlans.size} plan(s) to consider in pruning{}",
            if (allPlans.isEmpty())
                ""
            else
                ":" + allPlans.joinToString("\n") { it.infoString(true, 1) }
        )
        return planningSystem.copy(
            actions = planningSystem.actions.filter { action ->
                allPlans.any { plan ->
                    plan.actions.contains(action)
                }
            }.toSet(),
        )
    }

    protected abstract fun planToGoalFrom(
        startState: GoapWorldState,
        actions: Collection<GoapAction>,
        goal: GoapGoal,
    ): GoapPlan?
}
