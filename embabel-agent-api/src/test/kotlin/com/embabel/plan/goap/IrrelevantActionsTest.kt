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

import com.embabel.plan.common.condition.ConditionAction
import com.embabel.plan.common.condition.ConditionDetermination
import com.embabel.plan.common.condition.ConditionGoal
import com.embabel.plan.common.condition.WorldStateDeterminer
import com.embabel.plan.goap.astar.AStarGoapPlanner
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class IrrelevantActionsTest {
    @Nested
    inner class `Planner confusion and distraction tests` {
        @Test
        fun `should fail if planner gets distracted by chain of irrelevant actions`() {
            val chainLength = 5
            val realChain = (1..chainLength).map { i ->
                val prev = if (i == 1) "start" else "step${i - 1}"
                val next = "step$i"
                ConditionAction(
                    name = "realAction$i",
                    preconditions = mapOf(prev to ConditionDetermination.TRUE),
                    effects = mapOf(next to ConditionDetermination.TRUE)
                )
            }
            val goal = ConditionGoal(
                name = "testGoal",
                pre = listOf("step$chainLength")
            )
            val irrelevantChain = (1..10).map { i ->
                ConditionAction(
                    name = "irrelevantChain$i",
                    preconditions = mapOf("noise${i - 1}" to ConditionDetermination.TRUE),
                    effects = mapOf("noise$i" to ConditionDetermination.TRUE)
                )
            }
            val worldStateMap = mutableMapOf<String, ConditionDetermination>()
            worldStateMap["start"] = ConditionDetermination.TRUE
            (1..chainLength).forEach { i -> worldStateMap["step$i"] = ConditionDetermination.FALSE }
            (0..10).forEach { i -> worldStateMap["noise$i"] = ConditionDetermination.TRUE }
            val planner = AStarGoapPlanner(WorldStateDeterminer.fromMap(worldStateMap))
            val allActions = realChain + irrelevantChain
            val plan = planner.planToGoal(allActions, goal)
            assertNotNull(plan, "Should find a plan despite irrelevant chains")
            assertEquals(chainLength, plan!!.actions.size, "Should only use the real chain actions")
            (1..chainLength).forEach { i ->
                assertEquals("realAction$i", plan.actions[i - 1].name)
            }
        }

        @Test
        fun `should fail if planner includes actions that undo progress`() {
            val actionA = ConditionAction(
                name = "setA",
                preconditions = mapOf("start" to ConditionDetermination.TRUE),
                effects = mapOf("A" to ConditionDetermination.TRUE)
            )
            val actionB = ConditionAction(
                name = "unsetA",
                preconditions = mapOf("A" to ConditionDetermination.TRUE),
                effects = mapOf("A" to ConditionDetermination.FALSE)
            )
            val actionGoal = ConditionAction(
                name = "reachGoal",
                preconditions = mapOf("A" to ConditionDetermination.TRUE),
                effects = mapOf("goal" to ConditionDetermination.TRUE)
            )
            val goal = ConditionGoal(
                name = "goal",
                pre = listOf("goal")
            )
            val worldState = WorldStateDeterminer.fromMap(
                mapOf(
                    "start" to ConditionDetermination.TRUE,
                    "A" to ConditionDetermination.FALSE,
                    "goal" to ConditionDetermination.FALSE
                )
            )
            val planner = AStarGoapPlanner(worldState)
            val plan = planner.planToGoal(listOf(actionA, actionB, actionGoal), goal)
            assertNotNull(plan, "Should find a plan")
            // Should not undo progress by including unsetA
            assertFalse(
                plan!!.actions.any { it.name == "unsetA" },
                "Plan should not include actions that undo required conditions"
            )
        }

        @Test
        fun `should fail if planner takes a detour through irrelevant misleading actions`() {
            val actionA = ConditionAction(
                name = "setA",
                preconditions = mapOf("start" to ConditionDetermination.TRUE),
                effects = mapOf("A" to ConditionDetermination.TRUE)
            )
            val misleading = ConditionAction(
                name = "misleadA",
                preconditions = mapOf("start" to ConditionDetermination.TRUE),
                effects = mapOf("A" to ConditionDetermination.TRUE, "foo" to ConditionDetermination.TRUE)
            )
            val actionGoal = ConditionAction(
                name = "reachGoal",
                preconditions = mapOf("A" to ConditionDetermination.TRUE),
                effects = mapOf("goal" to ConditionDetermination.TRUE)
            )
            val goal = ConditionGoal(
                name = "goal",
                pre = listOf("goal")
            )
            val worldState = WorldStateDeterminer.fromMap(
                mapOf(
                    "start" to ConditionDetermination.TRUE,
                    "A" to ConditionDetermination.FALSE,
                    "goal" to ConditionDetermination.FALSE,
                    "foo" to ConditionDetermination.FALSE
                )
            )
            val planner = AStarGoapPlanner(worldState)
            val plan = planner.planToGoal(listOf(actionA, misleading, actionGoal), goal)
            assertNotNull(plan)
            // Accept either minimal plan (setA, reachGoal) or (misleadA, reachGoal), but not both
            assertTrue(
                plan!!.actions.map { it.name } == listOf("setA", "reachGoal") ||
                        plan.actions.map { it.name } == listOf("misleadA", "reachGoal")
            )
        }
    }

    // --- EXTENDED TESTS TO BREAK THE PLANNER FURTHER ---
    @Nested
    inner class `Additional confusion-breaking tests` {
        @Test
        fun `should fail if planner is tricked by actions with irrelevant side effects`() {
            val actionA = ConditionAction(
                name = "setA",
                preconditions = mapOf("start" to ConditionDetermination.TRUE),
                effects = mapOf("A" to ConditionDetermination.TRUE, "sideNoise" to ConditionDetermination.TRUE)
            )
            val actionGoal = ConditionAction(
                name = "reachGoal",
                preconditions = mapOf("A" to ConditionDetermination.TRUE),
                effects = mapOf("goal" to ConditionDetermination.TRUE)
            )
            val irrelevant = ConditionAction(
                name = "irrelevantNoise",
                preconditions = mapOf("sideNoise" to ConditionDetermination.TRUE),
                effects = mapOf("moreNoise" to ConditionDetermination.TRUE)
            )
            val goal = ConditionGoal(
                name = "goal",
                pre = listOf("goal")
            )
            val worldState = WorldStateDeterminer.fromMap(
                mapOf(
                    "start" to ConditionDetermination.TRUE,
                    "A" to ConditionDetermination.FALSE,
                    "goal" to ConditionDetermination.FALSE,
                    "sideNoise" to ConditionDetermination.FALSE,
                    "moreNoise" to ConditionDetermination.FALSE
                )
            )
            val planner = AStarGoapPlanner(worldState)
            val plan = planner.planToGoal(listOf(actionA, actionGoal, irrelevant), goal)
            assertNotNull(plan)
            assertFalse(
                plan!!.actions.any { it.name == "irrelevantNoise" },
                "Plan should not include irrelevant side-effect actions"
            )
        }

        @Test
        fun `should fail if planner is confused by multiple irrelevant chains`() {
            val chainLength = 3
            val realChain = (1..chainLength).map { i ->
                val prev = if (i == 1) "start" else "step${i - 1}"
                val next = "step$i"
                ConditionAction(
                    name = "realAction$i",
                    preconditions = mapOf(prev to ConditionDetermination.TRUE),
                    effects = mapOf(next to ConditionDetermination.TRUE)
                )
            }
            val goal = ConditionGoal(
                name = "goal",
                pre = listOf("step$chainLength")
            )
            val irrelevantChains = (1..3).flatMap { chainNum ->
                (1..5).map { i ->
                    ConditionAction(
                        name = "irrelevantChain${chainNum}_$i",
                        preconditions = mapOf("noise${chainNum}_${i - 1}" to ConditionDetermination.TRUE),
                        effects = mapOf("noise${chainNum}_$i" to ConditionDetermination.TRUE)
                    )
                }
            }
            val worldStateMap = mutableMapOf<String, ConditionDetermination>()
            worldStateMap["start"] = ConditionDetermination.TRUE
            (1..chainLength).forEach { i -> worldStateMap["step$i"] = ConditionDetermination.FALSE }
            (1..3).forEach { chainNum ->
                (0..5).forEach { i -> worldStateMap["noise${chainNum}_$i"] = ConditionDetermination.TRUE }
            }
            val planner = AStarGoapPlanner(WorldStateDeterminer.fromMap(worldStateMap))
            val allActions = realChain + irrelevantChains
            val plan = planner.planToGoal(allActions, goal)
            assertNotNull(plan, "Should find a plan despite multiple irrelevant chains")
            assertEquals(chainLength, plan!!.actions.size, "Should only use the real chain actions")
            (1..chainLength).forEach { i ->
                assertEquals("realAction$i", plan.actions[i - 1].name)
            }
        }
    }

    @Nested
    inner class `More devious planner-breaking tests` {
        @Test
        fun `should fail if planner loops between undo and redo actions`() {
            val setA = ConditionAction(
                name = "setA",
                preconditions = mapOf("start" to ConditionDetermination.TRUE),
                effects = mapOf("A" to ConditionDetermination.TRUE)
            )
            val unsetA = ConditionAction(
                name = "unsetA",
                preconditions = mapOf("A" to ConditionDetermination.TRUE),
                effects = mapOf("A" to ConditionDetermination.FALSE)
            )
            val setAagain = ConditionAction(
                name = "setAagain",
                preconditions = mapOf("A" to ConditionDetermination.FALSE),
                effects = mapOf("A" to ConditionDetermination.TRUE)
            )
            val reachGoal = ConditionAction(
                name = "reachGoal",
                preconditions = mapOf("A" to ConditionDetermination.TRUE),
                effects = mapOf("goal" to ConditionDetermination.TRUE)
            )
            val goal = ConditionGoal(
                name = "goal",
                pre = listOf("goal")
            )
            val worldState = WorldStateDeterminer.fromMap(
                mapOf(
                    "start" to ConditionDetermination.TRUE,
                    "A" to ConditionDetermination.FALSE,
                    "goal" to ConditionDetermination.FALSE
                )
            )
            val planner = AStarGoapPlanner(worldState)
            val plan = planner.planToGoal(listOf(setA, unsetA, setAagain, reachGoal), goal)
            assertNotNull(plan, "Should find a plan")
            // Should not loop between setA/unsetA/setAagain unnecessarily
            val actionNames = plan!!.actions.map { it.name }
            assertFalse(
                actionNames.windowed(2).any { it == listOf("setA", "unsetA") || it == listOf("unsetA", "setAagain") },
                "Plan should not bounce between undo and redo actions"
            )
            assertTrue(
                actionNames == listOf("setA", "reachGoal") ||
                        actionNames == listOf("setAagain", "reachGoal"),
                "Plan should be minimal and direct"
            )
        }

        @Test
        fun `should fail if planner is distracted by actions with only irrelevant net effect`() {
            val setA = ConditionAction(
                name = "setA",
                preconditions = mapOf("start" to ConditionDetermination.TRUE),
                effects = mapOf("A" to ConditionDetermination.TRUE)
            )
            val distract = ConditionAction(
                name = "distract",
                preconditions = mapOf("start" to ConditionDetermination.TRUE),
                effects = mapOf("noise" to ConditionDetermination.TRUE)
            )
            val undoNoise = ConditionAction(
                name = "undoNoise",
                preconditions = mapOf("noise" to ConditionDetermination.TRUE),
                effects = mapOf("noise" to ConditionDetermination.FALSE)
            )
            val reachGoal = ConditionAction(
                name = "reachGoal",
                preconditions = mapOf("A" to ConditionDetermination.TRUE),
                effects = mapOf("goal" to ConditionDetermination.TRUE)
            )
            val goal = ConditionGoal(
                name = "goal",
                pre = listOf("goal")
            )
            val worldState = WorldStateDeterminer.fromMap(
                mapOf(
                    "start" to ConditionDetermination.TRUE,
                    "A" to ConditionDetermination.FALSE,
                    "goal" to ConditionDetermination.FALSE,
                    "noise" to ConditionDetermination.FALSE
                )
            )
            val planner = AStarGoapPlanner(worldState)
            val plan = planner.planToGoal(listOf(setA, distract, undoNoise, reachGoal), goal)
            assertNotNull(plan, "Should find a plan")
            val names = plan!!.actions.map { it.name }
            assertFalse(names.contains("distract"), "Plan should not include irrelevant distract action")
            assertFalse(names.contains("undoNoise"), "Plan should not include undoNoise action")
            assertTrue(names == listOf("setA", "reachGoal"), "Plan should be minimal and direct")
        }
    }
}
