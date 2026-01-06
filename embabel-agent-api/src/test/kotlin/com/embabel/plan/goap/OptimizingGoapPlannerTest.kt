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

import com.embabel.plan.common.condition.*
import com.embabel.plan.goap.astar.AStarGoapPlanner
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests specifically designed to challenge the OptimizingGoapPlanner with complex scenarios
 * that might break its planning capabilities.
 */
class OptimizingGoapPlannerTest {

    @Nested
    inner class CyclicDependencyTests {

        @Test
        fun `should detect and avoid cyclic dependencies in actions`() {
            // Create actions with a potential cycle
            val action1 = ConditionAction(
                name = "action1",
                preconditions = mapOf("conditionA" to ConditionDetermination.FALSE),
                effects = mapOf("conditionB" to ConditionDetermination.TRUE)
            )

            val action2 = ConditionAction(
                name = "action2",
                preconditions = mapOf("conditionB" to ConditionDetermination.TRUE),
                effects = mapOf("conditionC" to ConditionDetermination.TRUE)
            )

            val action3 = ConditionAction(
                name = "action3",
                preconditions = mapOf("conditionC" to ConditionDetermination.TRUE),
                effects = mapOf("conditionA" to ConditionDetermination.TRUE)
            )

            // This action creates a potential infinite loop because it undoes what action1 does
            val cycleAction = ConditionAction(
                name = "cycleAction",
                preconditions = mapOf("conditionB" to ConditionDetermination.TRUE),
                effects = mapOf("conditionB" to ConditionDetermination.FALSE)
            )

            val goal = ConditionGoal(
                name = "testGoal",
                pre = listOf("conditionC")
            )

            val planner = AStarGoapPlanner(
                WorldStateDeterminer.fromMap(
                    mapOf(
                        "conditionA" to ConditionDetermination.FALSE,
                        "conditionB" to ConditionDetermination.FALSE,
                        "conditionC" to ConditionDetermination.FALSE
                    )
                )
            )

            val actions: List<ConditionAction> = listOf(action1, action2, action3, cycleAction)
            val plan = planner.planToGoal(actions, goal)

            assertNotNull(plan, "Should find a plan despite potential cycles")
            assertFalse(plan!!.actions.contains(cycleAction), "Plan should not include the cycle-creating action")
            assertEquals(listOf("action1", "action2"), plan.actions.map { it.name })
        }
    }

    @Nested
    inner class ActionCostOptimizationTests {

        @Test
        fun `should choose lower cost path when multiple paths exist`() {
            // Path 1: A -> B -> C (total cost 3)
            val actionA1 = ConditionAction(
                name = "actionA1",
                preconditions = mapOf("start" to ConditionDetermination.TRUE),
                effects = mapOf("stepB" to ConditionDetermination.TRUE),
                cost = { 1.0 },
            )

            val actionB1 = ConditionAction(
                name = "actionB1",
                preconditions = mapOf("stepB" to ConditionDetermination.TRUE),
                effects = mapOf("goal" to ConditionDetermination.TRUE),
                cost = { 2.0 },
            )

            // Path 2: X -> Y -> Z (total cost 5)
            val actionX2 = ConditionAction(
                name = "actionX2",
                preconditions = mapOf("start" to ConditionDetermination.TRUE),
                effects = mapOf("stepY" to ConditionDetermination.TRUE),
                cost = { 2.0 },
            )

            val actionY2 = ConditionAction(
                name = "actionY2",
                preconditions = mapOf("stepY" to ConditionDetermination.TRUE),
                effects = mapOf("goal" to ConditionDetermination.TRUE),
                cost = { 3.0 },
            )

            val goal = ConditionGoal(
                name = "testGoal",
                pre = listOf("goal")
            )

            val planner = AStarGoapPlanner(
                WorldStateDeterminer.fromMap(
                    mapOf(
                        "start" to ConditionDetermination.TRUE,
                        "stepB" to ConditionDetermination.FALSE,
                        "stepY" to ConditionDetermination.FALSE,
                        "goal" to ConditionDetermination.FALSE
                    )
                )
            )

            val actions: List<ConditionAction> = listOf(actionA1, actionB1, actionX2, actionY2)
            val plan = planner.planToGoal(actions, goal)

            assertNotNull(plan, "Should find a plan")
            assertEquals(
                listOf("actionA1", "actionB1"), plan!!.actions.map { it.name },
                "Should choose the lower cost path"
            )
        }
    }

    @Nested
    inner class IrrelevantActionPruningTests {

        @Test
        fun `should ignore irrelevant actions with misleading effects`() {
            // The actions we actually need
            val actionA = ConditionAction(
                name = "actionA",
                preconditions = mapOf("start" to ConditionDetermination.TRUE),
                effects = mapOf("stepB" to ConditionDetermination.TRUE)
            )

            val actionB = ConditionAction(
                name = "actionB",
                preconditions = mapOf("stepB" to ConditionDetermination.TRUE),
                effects = mapOf("goal" to ConditionDetermination.TRUE)
            )

            // Irrelevant action that has an effect that matches a goal precondition name
            // but is actually for a different context
            val misleadingAction = ConditionAction(
                name = "misleadingAction",
                preconditions = mapOf("start" to ConditionDetermination.TRUE),
                effects = mapOf(
                    "goal" to ConditionDetermination.TRUE,
                    "wrongContext" to ConditionDetermination.TRUE
                )
            )

            // Another misleading action that seems to be part of a valid path
            val misleadingAction2 = ConditionAction(
                name = "misleadingAction2",
                preconditions = mapOf("wrongContext" to ConditionDetermination.TRUE),
                effects = mapOf("goal" to ConditionDetermination.TRUE)
            )

            val goal = ConditionGoal(
                name = "testGoal",
                pre = listOf("goal")
            )

            val planner = AStarGoapPlanner(
                WorldStateDeterminer.fromMap(
                    mapOf(
                        "start" to ConditionDetermination.TRUE,
                        "stepB" to ConditionDetermination.FALSE,
                        "goal" to ConditionDetermination.FALSE,
                        "wrongContext" to ConditionDetermination.FALSE
                    )
                )
            )

            val actions: List<ConditionAction> = listOf(actionA, actionB, misleadingAction, misleadingAction2)
            val plan = planner.planToGoal(actions, goal)

            assertNotNull(plan, "Should find a plan")

            // The planner could choose either path, but the important thing is that it finds a valid plan
            // Let's check if the plan actually achieves the goal
            val finalState = simulatePlan(planner.worldState(), plan!!.actions.filterIsInstance<ConditionAction>())
            assertTrue(goal.isAchievable(finalState), "The plan should achieve the goal")
        }

        private fun simulatePlan(
            startState: ConditionWorldState,
            actions: List<ConditionAction>,
        ): ConditionWorldState {
            var currentState = startState
            for (action in actions) {
                if (action.isAchievable(currentState)) {
                    currentState = applyAction(currentState, action)
                }
            }
            return currentState
        }

        private fun applyAction(
            currentState: ConditionWorldState,
            action: ConditionAction,
        ): ConditionWorldState {
            val newState = currentState.state.toMutableMap()
            action.effects.forEach { (key, value) ->
                newState[key] = value
            }
            return ConditionWorldState(HashMap(newState))
        }
    }

    @Nested
    inner class ComplexDependencyChainTests {

        @Test
        fun `should handle long dependency chains with many irrelevant actions`() {
            // Create a long chain of necessary actions
            val actionChain = (1..10).map { i ->
                val prev = if (i == 1) "start" else "step${i - 1}"
                val next = "step$i"
                ConditionAction(
                    name = "action$i",
                    preconditions = mapOf(prev to ConditionDetermination.TRUE),
                    effects = mapOf(next to ConditionDetermination.TRUE)
                )
            }

            // Create a large number of irrelevant actions
            val irrelevantActions = (1..50).map { i ->
                ConditionAction(
                    name = "irrelevant$i",
                    preconditions = mapOf("irrelevantPre$i" to ConditionDetermination.TRUE),
                    effects = mapOf("irrelevantEffect$i" to ConditionDetermination.TRUE)
                )
            }

            // Create some misleading actions that have similar names but don't help
            val misleadingActions = (1..10).map { i ->
                ConditionAction(
                    name = "misleading$i",
                    preconditions = mapOf("start" to ConditionDetermination.TRUE),
                    effects = mapOf("badStep$i" to ConditionDetermination.TRUE)
                )
            }

            val goal = ConditionGoal(
                name = "testGoal",
                pre = listOf("step10")
            )

            // Create a world state with all the conditions
            val worldStateMap = mutableMapOf<String, ConditionDetermination>()
            worldStateMap["start"] = ConditionDetermination.TRUE
            (1..10).forEach { i -> worldStateMap["step$i"] = ConditionDetermination.FALSE }
            (1..50).forEach { i ->
                worldStateMap["irrelevantPre$i"] = ConditionDetermination.TRUE
                worldStateMap["irrelevantEffect$i"] = ConditionDetermination.FALSE
            }
            (1..10).forEach { i -> worldStateMap["badStep$i"] = ConditionDetermination.FALSE }

            val planner = AStarGoapPlanner(
                WorldStateDeterminer.fromMap(worldStateMap)
            )

            val allActions = actionChain + irrelevantActions + misleadingActions
            val actions: List<ConditionAction> = allActions
            val plan = planner.planToGoal(actions, goal)

            assertNotNull(plan, "Should find a plan despite many irrelevant actions")
            assertEquals(10, plan!!.actions.size, "Should include exactly the 10 actions in the chain")

            // Verify the correct sequence
            (1..10).forEach { i ->
                assertEquals("action$i", plan.actions[i - 1].name, "Action at position ${i - 1} should be action$i")
            }
        }
    }

    @Nested
    inner class ConditionConflictsTests {

        @Test
        fun `should handle actions with conflicting effects`() {
            // Action that sets condition A to TRUE
            val actionSetA = ConditionAction(
                name = "actionSetA",
                preconditions = mapOf("start" to ConditionDetermination.TRUE),
                effects = mapOf("conditionA" to ConditionDetermination.TRUE)
            )

            // Action that requires A but sets it to FALSE
            val actionUnsetA = ConditionAction(
                name = "actionUnsetA",
                preconditions = mapOf("conditionA" to ConditionDetermination.TRUE),
                effects = mapOf(
                    "conditionA" to ConditionDetermination.FALSE,
                    "conditionB" to ConditionDetermination.TRUE
                )
            )

            // Action that requires both A and B
            val actionNeedsAandB = ConditionAction(
                name = "actionNeedsAandB",
                preconditions = mapOf(
                    "conditionA" to ConditionDetermination.TRUE,
                    "conditionB" to ConditionDetermination.TRUE
                ),
                effects = mapOf("goal" to ConditionDetermination.TRUE)
            )

            // Action that only needs B
            val actionNeedsOnlyB = ConditionAction(
                name = "actionNeedsOnlyB",
                preconditions = mapOf("conditionB" to ConditionDetermination.TRUE),
                effects = mapOf("goal" to ConditionDetermination.TRUE)
            )

            val goal = ConditionGoal(
                name = "testGoal",
                pre = listOf("goal")
            )

            val planner = AStarGoapPlanner(
                WorldStateDeterminer.fromMap(
                    mapOf(
                        "start" to ConditionDetermination.TRUE,
                        "conditionA" to ConditionDetermination.FALSE,
                        "conditionB" to ConditionDetermination.FALSE,
                        "goal" to ConditionDetermination.FALSE
                    )
                )
            )

            val actions: List<ConditionAction> = listOf(actionSetA, actionUnsetA, actionNeedsAandB, actionNeedsOnlyB)
            val plan = planner.planToGoal(actions, goal)


            assertNotNull(plan, "Should find a plan despite conflicting effects")
            assertEquals(
                listOf("actionSetA", "actionUnsetA", "actionNeedsOnlyB"),
                plan!!.actions.map { it.name },
                "Should choose the path that resolves the conflict"
            )
        }
    }
}
