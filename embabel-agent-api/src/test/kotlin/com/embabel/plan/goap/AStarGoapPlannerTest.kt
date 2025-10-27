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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests specifically for AStarGoapPlanner, focusing on action selection logic
 * when multiple valid actions are available.
 */
class AStarGoapPlannerTest {

    @Nested
    inner class `Action selection priority tests` {

        @Test
        fun `should prefer action with more preconditions when costs are equal`() {
            // Action with fewer preconditions
            val simpleAction = GoapAction(
                name = "simpleAction",
                preconditions = mapOf("start" to ConditionDetermination.TRUE),
                effects = mapOf("goal" to ConditionDetermination.TRUE),
                cost = 1.0
            )

            // Action with more preconditions (more specific)
            val specificAction = GoapAction(
                name = "specificAction",
                preconditions = mapOf(
                    "start" to ConditionDetermination.TRUE,
                    "condition1" to ConditionDetermination.TRUE,
                    "condition2" to ConditionDetermination.TRUE
                ),
                effects = mapOf("goal" to ConditionDetermination.TRUE),
                cost = 1.0 // Same cost as simpleAction
            )

            val goal = GoapGoal(
                name = "testGoal",
                preconditions = mapOf("goal" to ConditionDetermination.TRUE)
            )

            val planner = AStarGoapPlanner(
                WorldStateDeterminer.fromMap(
                    mapOf(
                        "start" to ConditionDetermination.TRUE,
                        "condition1" to ConditionDetermination.TRUE,
                        "condition2" to ConditionDetermination.TRUE,
                        "goal" to ConditionDetermination.FALSE
                    )
                )
            )

            val actions = listOf(simpleAction, specificAction)
            val plan = planner.planToGoal(actions, goal)

            assertNotNull(plan, "Should find a plan")
            assertEquals(1, plan!!.actions.size, "Should have exactly one action")
            assertEquals("specificAction", plan.actions[0].name,
                "Should prefer the action with more preconditions")
        }

        @Test
        fun `should prefer action with more preconditions even when it appears later in the list`() {
            // Create several actions with different numbers of preconditions but same cost/effect
            val action1 = GoapAction(
                name = "action1",
                preconditions = mapOf("start" to ConditionDetermination.TRUE),
                effects = mapOf("goal" to ConditionDetermination.TRUE),
                cost = 1.0
            )

            val action2 = GoapAction(
                name = "action2",
                preconditions = mapOf(
                    "start" to ConditionDetermination.TRUE,
                    "extra1" to ConditionDetermination.TRUE
                ),
                effects = mapOf("goal" to ConditionDetermination.TRUE),
                cost = 1.0
            )

            val action3 = GoapAction(
                name = "action3",
                preconditions = mapOf(
                    "start" to ConditionDetermination.TRUE,
                    "extra1" to ConditionDetermination.TRUE,
                    "extra2" to ConditionDetermination.TRUE,
                    "extra3" to ConditionDetermination.TRUE
                ),
                effects = mapOf("goal" to ConditionDetermination.TRUE),
                cost = 1.0
            )

            val goal = GoapGoal(
                name = "testGoal",
                preconditions = mapOf("goal" to ConditionDetermination.TRUE)
            )

            val planner = AStarGoapPlanner(
                WorldStateDeterminer.fromMap(
                    mapOf(
                        "start" to ConditionDetermination.TRUE,
                        "extra1" to ConditionDetermination.TRUE,
                        "extra2" to ConditionDetermination.TRUE,
                        "extra3" to ConditionDetermination.TRUE,
                        "goal" to ConditionDetermination.FALSE
                    )
                )
            )

            // Test with actions in different orders
            val actions1 = listOf(action1, action2, action3)
            val plan1 = planner.planToGoal(actions1, goal)

            val actions2 = listOf(action3, action1, action2)
            val plan2 = planner.planToGoal(actions2, goal)

            assertNotNull(plan1, "Should find a plan with first ordering")
            assertNotNull(plan2, "Should find a plan with second ordering")

            assertEquals("action3", plan1!!.actions[0].name,
                "Should prefer action3 with most preconditions (first ordering)")
            assertEquals("action3", plan2!!.actions[0].name,
                "Should prefer action3 with most preconditions (second ordering)")
        }

        @Test
        fun `should still choose lower cost action over higher cost action with more preconditions`() {
            // High cost action with many preconditions
            val expensiveSpecificAction = GoapAction(
                name = "expensiveSpecificAction",
                preconditions = mapOf(
                    "start" to ConditionDetermination.TRUE,
                    "condition1" to ConditionDetermination.TRUE,
                    "condition2" to ConditionDetermination.TRUE,
                    "condition3" to ConditionDetermination.TRUE
                ),
                effects = mapOf("goal" to ConditionDetermination.TRUE),
                cost = 5.0
            )

            // Low cost action with fewer preconditions
            val cheapSimpleAction = GoapAction(
                name = "cheapSimpleAction",
                preconditions = mapOf("start" to ConditionDetermination.TRUE),
                effects = mapOf("goal" to ConditionDetermination.TRUE),
                cost = 1.0
            )

            val goal = GoapGoal(
                name = "testGoal",
                preconditions = mapOf("goal" to ConditionDetermination.TRUE)
            )

            val planner = AStarGoapPlanner(
                WorldStateDeterminer.fromMap(
                    mapOf(
                        "start" to ConditionDetermination.TRUE,
                        "condition1" to ConditionDetermination.TRUE,
                        "condition2" to ConditionDetermination.TRUE,
                        "condition3" to ConditionDetermination.TRUE,
                        "goal" to ConditionDetermination.FALSE
                    )
                )
            )

            val actions = listOf(expensiveSpecificAction, cheapSimpleAction)
            val plan = planner.planToGoal(actions, goal)

            assertNotNull(plan, "Should find a plan")
            assertEquals(1, plan!!.actions.size, "Should have exactly one action")
            assertEquals("cheapSimpleAction", plan.actions[0].name,
                "Should prefer the lower cost action despite fewer preconditions")
        }

        @Test
        fun `should use precondition count as tie-breaker in multi-step plans`() {
            // First step: multiple ways to get to intermediate state
            val simpleStep1 = GoapAction(
                name = "simpleStep1",
                preconditions = mapOf("start" to ConditionDetermination.TRUE),
                effects = mapOf("intermediate" to ConditionDetermination.TRUE),
                cost = 1.0
            )

            val specificStep1 = GoapAction(
                name = "specificStep1",
                preconditions = mapOf(
                    "start" to ConditionDetermination.TRUE,
                    "extra" to ConditionDetermination.TRUE
                ),
                effects = mapOf("intermediate" to ConditionDetermination.TRUE),
                cost = 1.0
            )

            // Second step: same for both paths
            val step2 = GoapAction(
                name = "step2",
                preconditions = mapOf("intermediate" to ConditionDetermination.TRUE),
                effects = mapOf("goal" to ConditionDetermination.TRUE),
                cost = 1.0
            )

            val goal = GoapGoal(
                name = "testGoal",
                preconditions = mapOf("goal" to ConditionDetermination.TRUE)
            )

            val planner = AStarGoapPlanner(
                WorldStateDeterminer.fromMap(
                    mapOf(
                        "start" to ConditionDetermination.TRUE,
                        "extra" to ConditionDetermination.TRUE,
                        "intermediate" to ConditionDetermination.FALSE,
                        "goal" to ConditionDetermination.FALSE
                    )
                )
            )

            val actions = listOf(simpleStep1, specificStep1, step2)
            val plan = planner.planToGoal(actions, goal)

            assertNotNull(plan, "Should find a plan")
            assertEquals(2, plan!!.actions.size, "Should have exactly two actions")
            assertEquals("specificStep1", plan.actions[0].name,
                "Should prefer the more specific first step")
            assertEquals("step2", plan.actions[1].name,
                "Should include the second step")
        }
    }

    @Nested
    inner class `Action selection edge cases` {

        @Test
        fun `should handle actions with zero preconditions`() {
            val actionWithNoPreconditions = GoapAction(
                name = "noPreconditions",
                preconditions = emptyMap(),
                effects = mapOf("goal" to ConditionDetermination.TRUE),
                cost = 1.0
            )

            val actionWithOnePrecondition = GoapAction(
                name = "onePrecondition",
                preconditions = mapOf("start" to ConditionDetermination.TRUE),
                effects = mapOf("goal" to ConditionDetermination.TRUE),
                cost = 1.0
            )

            val goal = GoapGoal(
                name = "testGoal",
                preconditions = mapOf("goal" to ConditionDetermination.TRUE)
            )

            val planner = AStarGoapPlanner(
                WorldStateDeterminer.fromMap(
                    mapOf(
                        "start" to ConditionDetermination.TRUE,
                        "goal" to ConditionDetermination.FALSE
                    )
                )
            )

            val actions = listOf(actionWithNoPreconditions, actionWithOnePrecondition)
            val plan = planner.planToGoal(actions, goal)

            assertNotNull(plan, "Should find a plan")
            assertEquals(1, plan!!.actions.size, "Should have exactly one action")
            assertEquals("onePrecondition", plan.actions[0].name,
                "Should prefer action with preconditions over action with none")
        }

        @Test
        fun `should handle identical actions with same precondition count`() {
            val action1 = GoapAction(
                name = "action1",
                preconditions = mapOf(
                    "start" to ConditionDetermination.TRUE,
                    "condition1" to ConditionDetermination.TRUE
                ),
                effects = mapOf("goal" to ConditionDetermination.TRUE),
                cost = 1.0
            )

            val action2 = GoapAction(
                name = "action2",
                preconditions = mapOf(
                    "start" to ConditionDetermination.TRUE,
                    "condition2" to ConditionDetermination.TRUE
                ),
                effects = mapOf("goal" to ConditionDetermination.TRUE),
                cost = 1.0
            )

            val goal = GoapGoal(
                name = "testGoal",
                preconditions = mapOf("goal" to ConditionDetermination.TRUE)
            )

            val planner = AStarGoapPlanner(
                WorldStateDeterminer.fromMap(
                    mapOf(
                        "start" to ConditionDetermination.TRUE,
                        "condition1" to ConditionDetermination.TRUE,
                        "condition2" to ConditionDetermination.TRUE,
                        "goal" to ConditionDetermination.FALSE
                    )
                )
            )

            val actions = listOf(action1, action2)
            val plan = planner.planToGoal(actions, goal)

            assertNotNull(plan, "Should find a plan")
            assertEquals(1, plan!!.actions.size, "Should have exactly one action")
            // Either action is acceptable since they have the same precondition count
            assertTrue(plan.actions[0].name in listOf("action1", "action2"),
                "Should choose one of the actions with equal precondition counts")
        }
    }

    @Nested
    inner class `Unreachable goal optimization` {

        @Test
        fun `should quickly return null for unreachable goal with no action producing required effect`() {
            val action = GoapAction(
                name = "irrelevantAction",
                preconditions = mapOf("start" to ConditionDetermination.TRUE),
                effects = mapOf("irrelevant" to ConditionDetermination.TRUE),
                cost = 1.0
            )

            val goal = GoapGoal(
                name = "unreachableGoal",
                preconditions = mapOf("impossibleCondition" to ConditionDetermination.TRUE)
            )

            val planner = AStarGoapPlanner(
                WorldStateDeterminer.fromMap(
                    mapOf(
                        "start" to ConditionDetermination.TRUE,
                        "impossibleCondition" to ConditionDetermination.FALSE
                    )
                )
            )

            val startTime = System.currentTimeMillis()
            val plan = planner.planToGoal(listOf(action), goal)
            val elapsedTime = System.currentTimeMillis() - startTime

            assertNull(plan, "Should return null for unreachable goal")
            assertTrue(elapsedTime < 100, "Should detect unreachability quickly (took ${elapsedTime}ms)")
        }

        @Test
        fun `should quickly return null for goal requiring unavailable precondition chain`() {
            // Actions that don't create the chain needed for the goal
            val action1 = GoapAction(
                name = "action1",
                preconditions = mapOf("start" to ConditionDetermination.TRUE),
                effects = mapOf("conditionA" to ConditionDetermination.TRUE),
                cost = 1.0
            )

            val action2 = GoapAction(
                name = "action2",
                preconditions = mapOf("conditionB" to ConditionDetermination.TRUE),
                effects = mapOf("conditionC" to ConditionDetermination.TRUE),
                cost = 1.0
            )

            // Goal requires conditionC, but there's no way to get conditionB
            val goal = GoapGoal(
                name = "unreachableGoal",
                preconditions = mapOf("conditionC" to ConditionDetermination.TRUE)
            )

            val planner = AStarGoapPlanner(
                WorldStateDeterminer.fromMap(
                    mapOf(
                        "start" to ConditionDetermination.TRUE,
                        "conditionA" to ConditionDetermination.FALSE,
                        "conditionB" to ConditionDetermination.FALSE,
                        "conditionC" to ConditionDetermination.FALSE
                    )
                )
            )

            val startTime = System.currentTimeMillis()
            val plan = planner.planToGoal(listOf(action1, action2), goal)
            val elapsedTime = System.currentTimeMillis() - startTime

            assertNull(plan, "Should return null for unreachable goal")
            assertTrue(elapsedTime < 100, "Should detect unreachability quickly (took ${elapsedTime}ms)")
        }

        @Test
        fun `should still find plans for reachable goals`() {
            val action1 = GoapAction(
                name = "action1",
                preconditions = mapOf("start" to ConditionDetermination.TRUE),
                effects = mapOf("intermediate" to ConditionDetermination.TRUE),
                cost = 1.0
            )

            val action2 = GoapAction(
                name = "action2",
                preconditions = mapOf("intermediate" to ConditionDetermination.TRUE),
                effects = mapOf("goal" to ConditionDetermination.TRUE),
                cost = 1.0
            )

            val goal = GoapGoal(
                name = "reachableGoal",
                preconditions = mapOf("goal" to ConditionDetermination.TRUE)
            )

            val planner = AStarGoapPlanner(
                WorldStateDeterminer.fromMap(
                    mapOf(
                        "start" to ConditionDetermination.TRUE,
                        "intermediate" to ConditionDetermination.FALSE,
                        "goal" to ConditionDetermination.FALSE
                    )
                )
            )

            val plan = planner.planToGoal(listOf(action1, action2), goal)

            assertNotNull(plan, "Should find a plan for reachable goal")
            assertEquals(2, plan!!.actions.size)
        }

        @Test
        fun `should return empty plan when goal already satisfied`() {
            val action = GoapAction(
                name = "unnecessaryAction",
                preconditions = mapOf("start" to ConditionDetermination.TRUE),
                effects = mapOf("goal" to ConditionDetermination.TRUE),
                cost = 1.0
            )

            val goal = GoapGoal(
                name = "alreadySatisfied",
                preconditions = mapOf("goal" to ConditionDetermination.TRUE)
            )

            val planner = AStarGoapPlanner(
                WorldStateDeterminer.fromMap(
                    mapOf(
                        "start" to ConditionDetermination.TRUE,
                        "goal" to ConditionDetermination.TRUE
                    )
                )
            )

            val plan = planner.planToGoal(listOf(action), goal)

            assertNotNull(plan, "Should return a plan when goal is already satisfied")
            assertEquals(0, plan!!.actions.size, "Plan should be empty when goal already satisfied")
        }
    }

    @Nested
    inner class `Integration with existing optimization` {

        @Test
        fun `should maintain optimization behavior while preferring more conditions`() {
            // Setup a scenario where optimization and condition preference both matter
            val unnecessaryAction = GoapAction(
                name = "unnecessary",
                preconditions = mapOf("start" to ConditionDetermination.TRUE),
                effects = mapOf("irrelevant" to ConditionDetermination.TRUE),
                cost = 1.0
            )

            val simpleGoalAction = GoapAction(
                name = "simpleGoal",
                preconditions = mapOf("start" to ConditionDetermination.TRUE),
                effects = mapOf("goal" to ConditionDetermination.TRUE),
                cost = 1.0
            )

            val specificGoalAction = GoapAction(
                name = "specificGoal",
                preconditions = mapOf(
                    "start" to ConditionDetermination.TRUE,
                    "specific" to ConditionDetermination.TRUE
                ),
                effects = mapOf("goal" to ConditionDetermination.TRUE),
                cost = 1.0
            )

            val goal = GoapGoal(
                name = "testGoal",
                preconditions = mapOf("goal" to ConditionDetermination.TRUE)
            )

            val planner = AStarGoapPlanner(
                WorldStateDeterminer.fromMap(
                    mapOf(
                        "start" to ConditionDetermination.TRUE,
                        "specific" to ConditionDetermination.TRUE,
                        "goal" to ConditionDetermination.FALSE,
                        "irrelevant" to ConditionDetermination.FALSE
                    )
                )
            )

            val actions = listOf(unnecessaryAction, simpleGoalAction, specificGoalAction)
            val plan = planner.planToGoal(actions, goal)

            assertNotNull(plan, "Should find a plan")
            assertEquals(1, plan!!.actions.size, "Should optimize away unnecessary actions")
            assertEquals("specificGoal", plan.actions[0].name,
                "Should prefer specific action that achieves goal")
            assertFalse(plan.actions.any { it.name == "unnecessary" },
                "Should not include unnecessary action")
        }
    }
}
