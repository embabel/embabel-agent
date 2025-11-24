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
package com.embabel.plan.utility

import com.embabel.plan.Action
import com.embabel.plan.Goal
import com.embabel.plan.common.condition.*

/**
 * Planner using utility AI
 */
class UtilityPlanner(
    worldStateDeterminer: WorldStateDeterminer,
) : AbstractConditionPlanner(worldStateDeterminer) {

    override fun planToGoal(
        actions: Collection<Action>,
        goal: Goal,
    ): ConditionPlan? {
        val currentState = worldStateDeterminer.determineWorldState()
        val availableActions = actions
            .filterIsInstance<ConditionAction>()
            .filter { it.isAchievable(currentState) }
            .map { Pair(it, it.netValue(currentState)) }
            .sortedByDescending { it.second }
        logger.info("${availableActions.size}/${actions.size} known actions available in current state:\n\t${availableActions.map { "${it.first.name} - ${it.second}" }}")
        val firstAction = availableActions.map { it.first }.firstOrNull()

        if (goal.name == NIRVANA) {
            if (firstAction == null) {
                // Special case. No available actions for Nirvana goal
                // We still never satisfy this goal
                return null
            }
            // We won't get there, but we take the next step
            return ConditionPlan(
                actions = listOfNotNull(firstAction),
                goal = goal,
                worldState = currentState,
            )
        }

        val businessGoal = goal as? ConditionGoal
        if (businessGoal == null) {
            logger.info("No goal provided")
            return null
        }

        // We have a meaningful, achievable goal
        // Are we there?
        if (firstAction == null) {
            if (businessGoal.isAchievable(currentState)) {
                logger.info("Business goal {} is satisfied", businessGoal.name)
                return ConditionPlan(
                    actions = emptyList(),
                    goal = goal,
                    worldState = currentState,
                )
            }
            return null
        }

        // Can we get there in 1 action?
        val afterState = currentState + firstAction
        if (businessGoal.isAchievable(afterState)) {
            logger.info("Business goal {} can be satisfied by next action", businessGoal.name)
            return ConditionPlan(
                actions = listOfNotNull(firstAction),
                goal = goal,
                worldState = currentState,
            )
        }
        logger.info("Business goal {} not achievable in 1 step", businessGoal.name)
        return null
    }

    override fun prune(planningSystem: ConditionPlanningSystem): ConditionPlanningSystem {
        return planningSystem
    }

    companion object {
        const val NIRVANA = "Nirvana"
    }
}
