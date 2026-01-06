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
package com.embabel.plan.com

import com.embabel.plan.common.condition.ConditionAction
import com.embabel.plan.common.condition.ConditionDetermination
import com.embabel.plan.common.condition.ConditionGoal
import com.embabel.plan.common.condition.ConditionPlanningSystem
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Tests for the [ConditionPlanningSystem] class.
 *
 * The GoapPlanningSystem collects and manages a set of actions and goals for
 * goal-oriented action planning. These tests verify:
 *
 * - Collection of unique preconditions from all actions
 * - Collection of unique effects from all actions
 * - Determination of all conditions (union of preconditions and effects)
 * - System construction with multiple goals or a single goal
 * - Formatting of system information for display
 *
 * These tests ensure the planning system correctly tracks and manages
 * the conditions needed for planning with GOAP algorithms.
 */
class ConditionPlanningSystemTest {

    @Test
    fun `test knownPreconditions returns all unique preconditions`() {
        // Create actions with various preconditions
        val action1 = ConditionAction.Companion(
            "Action1",
            preconditions = mapOf("cond1" to ConditionDetermination.TRUE, "cond2" to ConditionDetermination.FALSE)
        )

        val action2 = ConditionAction.Companion(
            "Action2",
            preconditions = mapOf("cond2" to ConditionDetermination.TRUE, "cond3" to ConditionDetermination.UNKNOWN)
        )

        val system = ConditionPlanningSystem(setOf(action1, action2), ConditionGoal.Companion("Goal1"))

        // Verify
        val preconditions = system.knownPreconditions()
        Assertions.assertEquals(3, preconditions.size)
        Assertions.assertTrue(preconditions.containsAll(listOf("cond1", "cond2", "cond3")))
    }

    @Test
    fun `test knownEffects returns all unique effects`() {
        // Create actions with various effects
        val action1 = ConditionAction.Companion(
            "Action1",
            effects = mapOf("effect1" to ConditionDetermination.TRUE, "effect2" to ConditionDetermination.FALSE)
        )

        val action2 = ConditionAction.Companion(
            "Action2",
            effects = mapOf("effect2" to ConditionDetermination.TRUE, "effect3" to ConditionDetermination.UNKNOWN)
        )

        val system = ConditionPlanningSystem(setOf(action1, action2), ConditionGoal.Companion("Goal1"))

        // Verify
        val effects = system.knownEffects()
        Assertions.assertEquals(3, effects.size)
        Assertions.assertTrue(effects.containsAll(listOf("effect1", "effect2", "effect3")))
    }

    @Test
    fun `test knownConditions returns union of preconditions and effects`() {
        // Create actions with preconditions and effects
        val action1 = ConditionAction.Companion(
            "Action1",
            preconditions = mapOf("cond1" to ConditionDetermination.TRUE),
            effects = mapOf("effect1" to ConditionDetermination.TRUE)
        )

        val action2 = ConditionAction.Companion(
            "Action2",
            preconditions = mapOf("cond2" to ConditionDetermination.TRUE),
            effects = mapOf("effect2" to ConditionDetermination.TRUE)
        )

        val system = ConditionPlanningSystem(setOf(action1, action2), ConditionGoal.Companion("Goal1"))

        // Verify
        val conditions = system.knownConditions()
        Assertions.assertEquals(4, conditions.size)
        Assertions.assertTrue(conditions.containsAll(listOf("cond1", "cond2", "effect1", "effect2")))
    }

    @Test
    fun `test GoapPlanningSystem construction with multiple goals`() {
        val goal1 = ConditionGoal.Companion("Goal1")
        val goal2 = ConditionGoal.Companion("Goal2")

        val system = ConditionPlanningSystem(emptySet(), setOf(goal1, goal2))

        Assertions.assertEquals(2, system.goals.size)
        Assertions.assertTrue(system.goals.containsAll(listOf(goal1, goal2)))
    }

    @Test
    fun `test GoapPlanningSystem infoString contains all key information`() {
        val action = ConditionAction.Companion(
            "Action1",
            preconditions = mapOf("cond1" to ConditionDetermination.TRUE),
            effects = mapOf("effect1" to ConditionDetermination.TRUE)
        )

        val goal = ConditionGoal.Companion("Goal1")

        val system = ConditionPlanningSystem(setOf(action), setOf(goal))

        val info = system.infoString()
        Assertions.assertTrue(info.contains("Action1"))
        Assertions.assertTrue(info.contains("Goal1"))
        Assertions.assertTrue(info.contains("knownPreconditions"))
        Assertions.assertTrue(info.contains("knownEffects"))
    }

    @Test
    fun `test GoapPlanningSystem constructor from collection of actions and single goal`() {
        val action1 = ConditionAction.Companion("Action1")
        val action2 = ConditionAction.Companion("Action2")
        val goal = ConditionGoal.Companion("Goal1")

        val system = ConditionPlanningSystem(
            actions = listOf(action1, action2),
            goal = goal
        )

        Assertions.assertEquals(2, system.actions.size)
        Assertions.assertEquals(1, system.goals.size)
        Assertions.assertTrue(system.actions.containsAll(listOf(action1, action2)))
        Assertions.assertTrue(system.goals.contains(goal))
    }
}
