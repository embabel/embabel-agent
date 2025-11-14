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
package com.embabel.plan

import com.embabel.common.core.types.HasInfoString
import com.embabel.common.core.types.Timestamped
import com.embabel.common.util.loggerFor

/**
 * A planning system is a set of actions and goals.
 */
interface PlanningSystem : HasInfoString {

    val actions: Set<Action>

    val goals: Set<Goal>

    fun knownConditions(): Set<String>
}

/**
 * Tag interface for WorldState
 * Different planners have different world state.
 */
interface WorldState : HasInfoString, Timestamped

/**
 * A planner is a system that can plan from a set of actions to a set of goals.
 * A planner should have a way of determining present state, such as
 * the GOAP WorldStateDeterminer. The representation of state
 * can differ between planners.
 */
interface Planner<S : PlanningSystem, W : WorldState, P : Plan> {

    /**
     * Current world state
     */
    fun worldState(): W

    /**
     * Plan from here to the given goal
     */
    fun planToGoal(
        actions: Collection<Action>,
        goal: Goal,
    ): P?

    /**
     * Return the best plan to each goal from the present world state.
     * The plans (one for each goal) are sorted by net value, descending.
     */
    fun plansToGoals(system: PlanningSystem): List<P> =
        system.goals.mapNotNull { goal ->
            val plan = planToGoal(system.actions, goal)
            if (plan != null) {
                loggerFor<Planner<*, *, *>>().info(
                    "Found plan to goal {}: {}",
                    goal.name,
                    plan.infoString(verbose = false),
                )
            } else {
                loggerFor<Planner<*, *, *>>().info(
                    "No plan found to goal {}",
                    goal.name,
                )
            }
            plan
        }.sortedByDescending { p -> p.netValue(state = worldState()) }

    /**
     * Return the best plan to any goal
     */
    fun bestValuePlanToAnyGoal(system: PlanningSystem): P? =
        plansToGoals(system).firstOrNull()

    /**
     * Return a PlanningSystem that excludes all actions that cannot
     * help achieve one of the goals from the present world state.
     */
    fun prune(planningSystem: S): S

}
