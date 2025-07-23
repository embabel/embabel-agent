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

import com.embabel.common.core.types.ZeroToOne
import com.embabel.common.util.indent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for the [Plan] class.
 *
 * The Plan class represents a sequence of actions to achieve a goal, with
 * associated metrics like cost and value. These tests verify:
 *
 * - Plan completion status detection
 * - Cost calculation as the sum of action costs
 * - Action value calculation as the sum of action values
 * - Net value calculation (goal value + action values - cost)
 * - Handling of negative costs and values
 * - String representation formatting in verbose and compact modes
 *
 * These tests ensure that plans correctly calculate their metrics
 * and provide appropriate string representations for logging and debugging.
 */
class PlanTest {

    // Simple implementation of Goal for testing
    private class TestGoal(
        override val name: String,
        override val value: Double = 0.0,
    ) : Goal {
        override fun infoString(
            verbose: Boolean?,
            indent: Int,
        ): String = "Goal($name, value=$value)".indent(indent)
    }

    // Simple implementation of Action for testing
    private class TestAction(
        override val name: String,
        override val cost: ZeroToOne = 0.0,
        override val value: ZeroToOne = 0.0,
    ) : Action {
        override fun infoString(
            verbose: Boolean?,
            indent: Int,
        ): String = name.indent(indent)
    }

    @Test
    fun `test plan isComplete detects empty action list`() {
        // Create plan with no actions
        val goal = TestGoal("TestGoal", 5.0)
        val emptyPlan = Plan(emptyList(), goal)

        // Complete when no actions
        assertTrue(emptyPlan.isComplete())

        // Not complete when there are actions
        val actionPlan = Plan(listOf(TestAction("Action1")), goal)
        assertFalse(actionPlan.isComplete())
    }

    @Test
    fun `test plan cost calculation`() {
        val goal = TestGoal("TestGoal")
        val actions = listOf(
            TestAction("Action1", cost = 2.5),
            TestAction("Action2", cost = 1.5),
            TestAction("Action3", cost = 3.0)
        )

        val plan = Plan(actions, goal)

        // Total cost should be sum of action costs
        assertEquals(7.0, plan.cost)
    }

    @Test
    fun `test plan actionsValue calculation`() {
        val goal = TestGoal("TestGoal")
        val actions = listOf(
            TestAction("Action1", value = 2.0),
            TestAction("Action2", value = 3.0),
            TestAction("Action3", value = 1.0)
        )

        val plan = Plan(actions, goal)

        // Total action value should be sum of action values
        assertEquals(6.0, plan.actionsValue)
    }

    @Test
    fun `test plan netValue calculation`() {
        val goal = TestGoal("TestGoal", value = 10.0)
        val actions = listOf(
            TestAction("Action1", cost = 2.0, value = 1.0),
            TestAction("Action2", cost = 3.0, value = 2.0)
        )

        val plan = Plan(actions, goal)

        // Net value = goal value + action values - cost
        // 10 + (1 + 2) - (2 + 3) = 8
        assertEquals(8.0, plan.netValue)
    }

    @Test
    fun `test plan handles negative values and costs`() {
        val goal = TestGoal("TestGoal", value = -5.0)
        val actions = listOf(
            TestAction("Action1", cost = -1.0, value = -2.0),
            TestAction("Action2", cost = 3.0, value = 1.0)
        )

        val plan = Plan(actions, goal)

        // Net value = goal value + action values - cost
        // -5 + (-2 + 1) - (-1 + 3) = -8
        assertEquals(-8.0, plan.netValue)
    }

    @Test
    fun `test plan verbose infoString formatting`() {
        val goal = TestGoal("TestGoal")
        val actions = listOf(
            TestAction("Action1"),
            TestAction("Action2"),
            TestAction("Action3")
        )

        val plan = Plan(actions, goal)

        val verboseInfo = plan.infoString(verbose = true)

        // Check format with indentation
        assertTrue(verboseInfo.contains("\t".repeat(1) + "Action1"))
        assertTrue(verboseInfo.contains("\t".repeat(2) + "Action2"))
        assertTrue(verboseInfo.contains("\t".repeat(3) + "Action3"))

        // Should contain cost and netValue
        assertTrue(verboseInfo.contains("cost="))
        assertTrue(verboseInfo.contains("netValue="))
    }

    @Test
    fun `test plan non-verbose infoString formatting`() {
        val goal = TestGoal("TestGoal")
        val actions = listOf(
            TestAction("Action1"),
            TestAction("Action2"),
            TestAction("Action3")
        )

        val plan = Plan(actions, goal)

        val info = plan.infoString(verbose = false)

        // Should contain action names with arrows
        assertTrue(info.contains("Action1 -> Action2 -> Action3"))

        // Should contain netValue but be more compact
        assertTrue(info.contains("netValue="))
    }
}
