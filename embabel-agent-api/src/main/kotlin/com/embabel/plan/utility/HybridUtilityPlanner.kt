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
package com.embabel.plan.utility

import com.embabel.plan.Action
import com.embabel.plan.Goal
import com.embabel.plan.common.condition.AbstractConditionPlanner
import com.embabel.plan.common.condition.ConditionAction
import com.embabel.plan.common.condition.ConditionGoal
import com.embabel.plan.common.condition.ConditionPlan
import com.embabel.plan.common.condition.ConditionPlanningSystem
import com.embabel.plan.common.condition.WorldStateDeterminer

/**
 * A planner that combines Utility AI's value-based action picking
 * with goal-satisfaction termination — the "iterate then stop" mode.
 *
 * **The shape it expresses.** Some reducer-style pipelines benefit
 * from doing all opportunistic work (research, enrichment, context
 * gathering) before a single terminal action that synthesises the
 * outcome. Pure GOAP A* doesn't pick those opportunistic actions —
 * it minimises path cost to the goal and skips anything off the
 * critical path. Pure [UtilityPlanner] picks by value but, on a
 * real (satisfiable) goal, gives up at step 1 if no single action
 * reaches the goal; on `NIRVANA` it iterates forever, even past
 * goal satisfaction. This planner is the missing middle.
 *
 * **How it works.** Register two goals on the agent:
 *  - The real terminal goal (e.g. `attention-candidate-produced`).
 *  - [com.embabel.agent.core.support.NIRVANA] — the unsatisfiable
 *    "keep firing useful things" goal.
 *
 * At each tick the planner generates a plan per goal. For NIRVANA
 * it returns the highest-netValue achievable action as a 1-step
 * plan; that's how research actions get picked while they still
 * have positive value and haven't been locked out by `canRerun=false`.
 * For the real goal it returns an empty plan once the goal is
 * already satisfied, or a 1-step plan if a single action reaches
 * it, or null. The host process picks the highest-net-value plan
 * across goals — empty plan has netValue=0, beating NIRVANA's
 * negative-value tail once research is exhausted. The process
 * then sees `plan.isComplete()` and terminates cleanly.
 *
 * **Difference from [UtilityPlanner].** UtilityPlanner only checks
 * "is the goal already achievable?" when there are zero achievable
 * actions. That means once your real goal is satisfied but other
 * (low-value, no-op) actions remain achievable, UtilityPlanner keeps
 * picking them. HybridUtilityPlanner checks "is the goal already
 * satisfied?" *first*, so satisfied-goal returns an empty plan even
 * when other actions could still run. This is the natural
 * termination signal for two-goal patterns.
 *
 * **When to choose this over [UtilityPlanner].**
 *  - You have a real terminal goal AND want greedy value-based
 *    intermediate scheduling.
 *  - You're combining NIRVANA with a satisfiable goal and need
 *    clean termination once the real goal is reached.
 *  - You don't want the A* multi-step cost-minimization of GOAP.
 *
 * For pure-iteration use cases (chat surfaces, exploratory loops),
 * keep [UtilityPlanner] + `NIRVANA` — the over-firing behavior is
 * benign there.
 */
class HybridUtilityPlanner(
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
        if (availableActions.isNotEmpty()) {
            val rendered = availableActions.joinToString(", ") { (action, value) ->
                "${action.name} - ${"%.2f".format(value)}"
            }
            logger.info(
                "{}/{} known actions available in current state:\n\t[{}]",
                availableActions.size, actions.size, rendered,
            )
        } else {
            logger.info("0/{} known actions available in current state", actions.size)
        }
        val firstAction = availableActions.map { it.first }.firstOrNull()

        // NIRVANA: unsatisfiable. Pick highest-netValue action if any,
        // else null. Identical to [UtilityPlanner.NIRVANA] semantics.
        if (goal.name == UtilityPlanner.NIRVANA) {
            return firstAction?.let {
                ConditionPlan(
                    actions = listOf(it),
                    goal = goal,
                    worldState = currentState,
                )
            }
        }

        val businessGoal = goal as? ConditionGoal
        if (businessGoal == null) {
            logger.info("No goal provided")
            return null
        }

        // **The hybrid contract.** Check "already satisfied" BEFORE
        // selecting an action. Without this, a satisfied goal still
        // gets scheduled the highest-netValue action if it doesn't
        // unset the goal — which is the bug that motivated this
        // planner. The empty plan returned here has netValue=0, which
        // beats NIRVANA's then-only-negative-value leftovers in
        // [com.embabel.plan.Planner.plansToGoals]; the host process
        // sees `plan.isComplete()` and terminates.
        if (businessGoal.isAchievable(currentState)) {
            logger.info("Business goal {} is already satisfied", businessGoal.name)
            return ConditionPlan(
                actions = emptyList(),
                goal = goal,
                worldState = currentState,
            )
        }

        // Goal not yet satisfied; need an action to make progress.
        if (firstAction == null) {
            return null
        }

        // Single-step lookahead — same as [UtilityPlanner]. If
        // applying the highest-netValue action satisfies the goal,
        // emit it as a 1-step plan. Multi-step plans are NIRVANA's
        // territory (its perpetual single-step picks compose into
        // the full pipeline at the planner-call-loop level).
        val afterState = currentState + firstAction
        if (businessGoal.isAchievable(afterState)) {
            logger.info("Business goal {} can be satisfied by next action", businessGoal.name)
            return ConditionPlan(
                actions = listOf(firstAction),
                goal = goal,
                worldState = currentState,
            )
        }
        logger.info("Business goal {} not achievable in 1 step", businessGoal.name)
        return null
    }

    override fun prune(planningSystem: ConditionPlanningSystem): ConditionPlanningSystem =
        planningSystem
}
