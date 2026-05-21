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

import com.embabel.plan.common.condition.ConditionAction
import com.embabel.plan.common.condition.ConditionDetermination
import com.embabel.plan.common.condition.ConditionGoal
import com.embabel.plan.common.condition.ConditionPlanningSystem
import com.embabel.plan.common.condition.WorldStateDeterminer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Behavioral coverage for [HybridUtilityPlanner].
 *
 * The asserts focus on the difference vs [UtilityPlanner]:
 *  - Already-satisfied goals return an empty plan even when other
 *    actions remain achievable (this is the load-bearing fix).
 *  - NIRVANA semantics are preserved (returns highest-netValue
 *    action; empty plan when nothing achievable).
 *  - Single-action goal achievement still works.
 *  - Multi-step goals return null (the planner is single-step;
 *    multi-step composition happens at the host-process loop level).
 *  - The hybrid combination (NIRVANA + real goal via
 *    [com.embabel.plan.Planner.plansToGoals]) terminates cleanly.
 */
class HybridUtilityPlannerTest {

    @Nested
    inner class AlreadySatisfiedReturnsEmptyPlan {

        @Test
        fun `goal already satisfied returns empty plan even when other actions achievable`() {
            // This is THE key difference from UtilityPlanner.
            // Vanilla UtilityPlanner picks `noopAction` and runs it
            // because the after-state keeps the goal satisfied;
            // HybridUtilityPlanner short-circuits and exits.
            val noopAction = ConditionAction(
                name = "noop",
                preconditions = mapOf("ready" to ConditionDetermination.TRUE),
                effects = mapOf("sideEffect" to ConditionDetermination.TRUE),
                cost = { 0.1 },
                value = { 0.5 },
            )
            val goal = ConditionGoal(
                name = "done",
                preconditions = mapOf("goal" to ConditionDetermination.TRUE),
            )
            val planner = HybridUtilityPlanner(
                WorldStateDeterminer.fromMap(
                    mapOf(
                        "ready" to ConditionDetermination.TRUE,
                        "goal" to ConditionDetermination.TRUE, // already satisfied
                    ),
                ),
            )

            val plan = planner.planToGoal(listOf(noopAction), goal)

            assertNotNull(plan, "Expected an empty plan, not null")
            assertTrue(plan!!.actions.isEmpty(), "Plan should be empty when goal already satisfied")
        }

        @Test
        fun `goal already satisfied with no actions still returns empty plan`() {
            val goal = ConditionGoal(
                name = "done",
                preconditions = mapOf("goal" to ConditionDetermination.TRUE),
            )
            val planner = HybridUtilityPlanner(
                WorldStateDeterminer.fromMap(
                    mapOf("goal" to ConditionDetermination.TRUE),
                ),
            )

            val plan = planner.planToGoal(emptyList(), goal)

            assertNotNull(plan)
            assertTrue(plan!!.actions.isEmpty())
        }
    }

    @Nested
    inner class NirvanaPath {

        @Test
        fun `NIRVANA picks highest-netValue achievable action`() {
            val cheapAction = ConditionAction(
                name = "cheap",
                preconditions = mapOf("ready" to ConditionDetermination.TRUE),
                effects = mapOf("did_cheap" to ConditionDetermination.TRUE),
                cost = { 0.1 },
                value = { 0.3 }, // netValue 0.2
            )
            val highValueAction = ConditionAction(
                name = "high",
                preconditions = mapOf("ready" to ConditionDetermination.TRUE),
                effects = mapOf("did_high" to ConditionDetermination.TRUE),
                cost = { 0.05 },
                value = { 1.5 }, // netValue 1.45
            )
            val nirvana = ConditionGoal(
                name = UtilityPlanner.NIRVANA,
                preconditions = mapOf("__unobtanium__" to ConditionDetermination.TRUE),
            )
            val planner = HybridUtilityPlanner(
                WorldStateDeterminer.fromMap(mapOf("ready" to ConditionDetermination.TRUE)),
            )

            val plan = planner.planToGoal(listOf(cheapAction, highValueAction), nirvana)

            assertNotNull(plan)
            assertEquals(1, plan!!.actions.size)
            assertEquals("high", plan.actions[0].name)
        }

        @Test
        fun `NIRVANA with no achievable actions returns null`() {
            val unreachable = ConditionAction(
                name = "unreachable",
                preconditions = mapOf("never_set" to ConditionDetermination.TRUE),
                effects = mapOf("ignored" to ConditionDetermination.TRUE),
            )
            val nirvana = ConditionGoal(
                name = UtilityPlanner.NIRVANA,
                preconditions = mapOf("__unobtanium__" to ConditionDetermination.TRUE),
            )
            val planner = HybridUtilityPlanner(WorldStateDeterminer.fromMap(emptyMap()))

            val plan = planner.planToGoal(listOf(unreachable), nirvana)

            assertNull(plan)
        }
    }

    @Nested
    inner class SingleStepLookahead {

        @Test
        fun `picks action that satisfies goal in one step`() {
            val finisher = ConditionAction(
                name = "finisher",
                preconditions = mapOf("ready" to ConditionDetermination.TRUE),
                effects = mapOf("goal" to ConditionDetermination.TRUE),
                cost = { 0.2 },
                value = { 1.0 },
            )
            val goal = ConditionGoal(
                name = "done",
                preconditions = mapOf("goal" to ConditionDetermination.TRUE),
            )
            val planner = HybridUtilityPlanner(
                WorldStateDeterminer.fromMap(
                    mapOf(
                        "ready" to ConditionDetermination.TRUE,
                        "goal" to ConditionDetermination.FALSE,
                    ),
                ),
            )

            val plan = planner.planToGoal(listOf(finisher), goal)

            assertNotNull(plan)
            assertEquals(1, plan!!.actions.size)
            assertEquals("finisher", plan.actions[0].name)
        }

        @Test
        fun `multi-step goal returns null — single-step lookahead is the contract`() {
            // step1 doesn't satisfy goal; step2 does, but step2 needs
            // step1's effect first. HybridUtilityPlanner doesn't
            // multi-step plan — that's GOAP territory. NIRVANA-paired
            // hybrids get multi-step composition by being re-called
            // at each tick.
            val step1 = ConditionAction(
                name = "step1",
                preconditions = mapOf("ready" to ConditionDetermination.TRUE),
                effects = mapOf("intermediate" to ConditionDetermination.TRUE),
                cost = { 0.1 },
                value = { 0.5 },
            )
            val step2 = ConditionAction(
                name = "step2",
                preconditions = mapOf("intermediate" to ConditionDetermination.TRUE),
                effects = mapOf("goal" to ConditionDetermination.TRUE),
                cost = { 0.1 },
                value = { 0.5 },
            )
            val goal = ConditionGoal(
                name = "done",
                preconditions = mapOf("goal" to ConditionDetermination.TRUE),
            )
            val planner = HybridUtilityPlanner(
                WorldStateDeterminer.fromMap(
                    mapOf(
                        "ready" to ConditionDetermination.TRUE,
                        "intermediate" to ConditionDetermination.FALSE,
                        "goal" to ConditionDetermination.FALSE,
                    ),
                ),
            )

            val plan = planner.planToGoal(listOf(step1, step2), goal)

            assertNull(plan, "Single-step lookahead can't reach a 2-step goal")
        }
    }

    @Nested
    inner class HybridComposition {

        /**
         * The motivating scenario: NIRVANA paired with a real goal
         * yields "iterate while research is valuable, terminate
         * when goal is satisfied".
         */
        @Test
        fun `plansToGoals with NIRVANA + real goal returns empty plan once real goal satisfied`() {
            val noisyAction = ConditionAction(
                name = "noisy",
                preconditions = mapOf("ready" to ConditionDetermination.TRUE),
                effects = mapOf("noise" to ConditionDetermination.TRUE),
                cost = { 0.5 },
                value = { 0.1 }, // netValue -0.4 — the kind of leftover that over-fires
            )
            val realGoal = ConditionGoal(
                name = "done",
                preconditions = mapOf("goal" to ConditionDetermination.TRUE),
            )
            val nirvana = ConditionGoal(
                name = UtilityPlanner.NIRVANA,
                preconditions = mapOf("__unobtanium__" to ConditionDetermination.TRUE),
            )
            val planner = HybridUtilityPlanner(
                WorldStateDeterminer.fromMap(
                    mapOf(
                        "ready" to ConditionDetermination.TRUE,
                        "goal" to ConditionDetermination.TRUE, // already satisfied
                    ),
                ),
            )

            val system = ConditionPlanningSystem(
                actions = setOf(noisyAction),
                goals = setOf(realGoal, nirvana),
            )
            val winningPlan = planner.bestValuePlanToAnyGoal(system)

            assertNotNull(winningPlan, "Expected a plan to win — the satisfied real goal's empty plan")
            assertTrue(
                winningPlan!!.actions.isEmpty(),
                "Empty real-goal plan should beat NIRVANA's negative-netValue action",
            )
            assertEquals("done", winningPlan.goal.name)
        }

        @Test
        fun `plansToGoals picks NIRVANA's high-value action while real goal unsatisfied`() {
            val researchAction = ConditionAction(
                name = "research",
                preconditions = mapOf("ready" to ConditionDetermination.TRUE),
                effects = mapOf("did_research" to ConditionDetermination.TRUE),
                cost = { 0.1 },
                value = { 1.5 }, // netValue 1.4 — clearly a winner
            )
            val realGoal = ConditionGoal(
                name = "done",
                preconditions = mapOf("goal" to ConditionDetermination.TRUE),
            )
            val nirvana = ConditionGoal(
                name = UtilityPlanner.NIRVANA,
                preconditions = mapOf("__unobtanium__" to ConditionDetermination.TRUE),
            )
            val planner = HybridUtilityPlanner(
                WorldStateDeterminer.fromMap(
                    mapOf(
                        "ready" to ConditionDetermination.TRUE,
                        "goal" to ConditionDetermination.FALSE, // not satisfied yet
                    ),
                ),
            )

            val system = ConditionPlanningSystem(
                actions = setOf(researchAction),
                goals = setOf(realGoal, nirvana),
            )
            val winningPlan = planner.bestValuePlanToAnyGoal(system)

            assertNotNull(winningPlan)
            assertEquals(1, winningPlan!!.actions.size)
            assertEquals("research", winningPlan.actions[0].name)
            // NIRVANA path picked it; real goal returned null
            // (single-step lookahead doesn't reach it).
            assertEquals(UtilityPlanner.NIRVANA, winningPlan.goal.name)
        }
    }

    @Nested
    inner class NotAchievable {

        @Test
        fun `no achievable actions and goal not satisfied returns null`() {
            val blocked = ConditionAction(
                name = "blocked",
                preconditions = mapOf("never" to ConditionDetermination.TRUE),
                effects = mapOf("goal" to ConditionDetermination.TRUE),
            )
            val goal = ConditionGoal(
                name = "done",
                preconditions = mapOf("goal" to ConditionDetermination.TRUE),
            )
            val planner = HybridUtilityPlanner(
                WorldStateDeterminer.fromMap(mapOf("goal" to ConditionDetermination.FALSE)),
            )

            val plan = planner.planToGoal(listOf(blocked), goal)

            assertNull(plan)
        }
    }
}
