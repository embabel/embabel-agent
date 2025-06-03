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
package com.embabel.agent.api.common

import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.common.autonomy.AutonomyProperties
import com.embabel.agent.core.*
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.testing.IntegrationTestUtils
import com.embabel.agent.testing.RandomRanker
import com.embabel.common.core.types.ZeroToOne
import com.embabel.plan.goap.ConditionDetermination
import io.mockk.clearAllMocks
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import kotlin.reflect.full.declaredMemberExtensionFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaMethod
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests to verify there's no "action leakage" between agents during agent creation
 * and goal accomplishment.
 *
 * This test suite focuses on:
 *
 * Testing the private prune method:
 *    - Ensures actions from one goal don't leak into agents created for other goals
 *    - Verifies correct action filtering based on the goal's requirements
 *
 * These tests are critical for maintaining isolation between different agents and
 * ensuring that only required actions are included during agent creation.
 *
 * Test suite can be as used as template for actions scalability testing.
 */
class AutonomyActionLeakageTest {

    data class DummyType(val x: Int) {}

    @BeforeEach
    fun setUp() {
        clearAllMocks()
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    /**
     * Test the prune method directly to ensure it correctly filters actions
     * and doesn't allow actions from one goal to leak into agents for another goal.
     *
     * This test:
     * 1. Creates two distinctly different actions, each for a different goal
     * 2. Creates an agent containing both actions
     * 3. Uses reflection to call the private prune method
     * 4. Verifies that the pruned agent only contains the action needed for the target goal
     *
     * Relationships between Conditions, Goals and Actions:
     *
     * Cond1 -------------input ---->Goal1
     * InputCondition --- input ---->Action1
     * Action1 ---------- output --->Cond1
     * Cond1 ------------ input ---->Action2
     * Cond2 -------------input----->Goal2
     * Action2 ---------- output --->Cond2
     *
     * GOAP constructs 2 plans: {action1 to goal1} and {action1-->action2 to goal2}
     *
     */
    @Test
    @DisplayName("test prune method correctly filters actions without leakage")
    fun testSingleAgentPruneMethodPreventsActionLeakage() {
        // Create test conditions
        val condition1 = ComputedBooleanCondition(
            name = "Test Condition 1",
            cost = 0.25,
            evaluator = { _, _ -> true }
        )

        val condition2 = ComputedBooleanCondition(
            name = "Test Condition 2",
            cost = 0.25,
            evaluator = { _, _ -> true }
        )
        val inputCondition = ComputedBooleanCondition(
            name = "UserInput Condition",
            cost = 0.5,
            evaluator = { _, _ -> true }
        )

        // Create test goals with different conditions
        val goal1 = Goal(
            name = "goal1",
            description = "Test goal 1",
            value = 0.8,
            pre = setOf(condition1.name)
        )

        val goal2 = Goal(
            name = "goal2",
            description = "Test goal 2",
            value = 0.8,
            pre = setOf(condition2.name)
        )

        // Create action1 that satisfies goal1
        val action1 = object : Action {
            override val outputs: Set<IoBinding> = setOf(IoBinding("goal1", type = UserInput::class.java))
            override val cost: ZeroToOne
                get() = 0.9
            override val canRerun: Boolean
                get() = true
            override val qos: ActionQos
                get() = ActionQos()
            override val name = "action1"
            override val value: ZeroToOne = 0.3
            override fun infoString(verbose: Boolean?): String {
                return "ACTION 1"
            }

            override val description = "Action for goal 1"
            override val inputs: Set<IoBinding> = setOf(IoBinding("goal1", type = UserInput::class.java))
            val output = String::class.java
            override val preconditions = mapOf(   // pushes towards achievable goal
                inputCondition.name to ConditionDetermination.FALSE
            )
            override val effects = mapOf(
                condition1.name to ConditionDetermination.TRUE
            )

            override fun execute(processContext: ProcessContext, action: Action): ActionStatus {
                return ActionStatus(runningTime = java.time.Duration.ofSeconds(2), status = ActionStatusCode.SUCCEEDED)
            }

            override fun referencedInputProperties(variable: String): Set<String> {
                return emptySet()
            }

            override val domainTypes: Collection<Class<*>>
                get() = listOf(DummyType::class.java)
            override val toolGroups: Set<String>
                get() = emptySet()
            override val schemaTypes = emptyList<SchemaType>()
        }

        // Create action2 that satisfies goal2
        val action2 = object : Action {
            override val outputs: Set<IoBinding> = setOf(IoBinding("goal1", type = UserInput::class.java))
            override val cost: ZeroToOne
                get() = 0.9
            override val canRerun: Boolean
                get() = true
            override val qos: ActionQos
                get() = ActionQos()
            override val name = "action2"
            override val value: ZeroToOne = 0.3
            override fun infoString(verbose: Boolean?): String {
                return "ACTION 2"
            }

            override val description = "Action for goal 2"
            override val inputs: Set<IoBinding> = setOf(IoBinding("goal2", type = UserInput::class.java))
            val output = String::class.java
            override val preconditions = mapOf(
                condition1.name to ConditionDetermination.TRUE
            )
            override val effects = mapOf(
                condition2.name to ConditionDetermination.TRUE
            )

            override fun execute(processContext: ProcessContext, action: Action): ActionStatus {
                return ActionStatus(runningTime = java.time.Duration.ofSeconds(2), status = ActionStatusCode.SUCCEEDED)
            }

            override fun referencedInputProperties(variable: String): Set<String> {
                return emptySet()
            }

            override val domainTypes: Collection<Class<*>>
                get() = listOf(DummyType::class.java)
            override val toolGroups: Set<String>
                get() = emptySet()
            override val schemaTypes = emptyList<SchemaType>()
        }

        // Create an agent with both actions
        val agent = Agent(
            name = "testAgent",
            description = "Test agent",
            actions = listOf(action1, action2),
            goals = setOf(goal1, goal2),
            conditions = setOf(condition1, condition2, inputCondition),
            provider = "Test Provider"
        )

        // Create dependencies
        val agentPlatform = IntegrationTestUtils.dummyAgentPlatform()
        val platformServices = IntegrationTestUtils.dummyPlatformServices()
        agentPlatform.deploy(agent)

        val ranker = RandomRanker()

        // Create Autonomy instance
        val autonomy = Autonomy(
            agentPlatform = agentPlatform,
            ranker = ranker,
            properties = AutonomyProperties()
        )

        // Create test user input
        val userInput = UserInput("test input for goal 1")

        // Use reflection to access the private prune method
        val pruneMethod = getPruneMethod(autonomy)

        // Call prune method with our agent and userInput
        val prunedAgent = pruneMethod.invoke(autonomy, agent, userInput) as Agent

        // Verify correct actions gets included into plan after pruning
        assertEquals(2, prunedAgent.actions.size, "Pruned agent should  have 2 action")
        assertEquals("action1", prunedAgent.actions[0].name, "Pruned agent should contain  action1")
        assertTrue(prunedAgent.actions.any { it.name == "action2" }, "Pruned agent should contain  action2")
    }

    /**
     *  Purpose of test to detect scenario when actions planned for achieving goal for Agent 1 and 2 are mutually exclusive.
     *  As example, actions 3 and 4 for Agent 2 should never leak into any plan for Agent 1.
     *  Test employs "shared condition" between Agents in order to test for action leakages.
     */
    @Test
    @DisplayName("test that actions from one agent don't leak into another agent")
    fun testCrossAgentActionLeakage() {
        // Create shared condition used by both agents
        val sharedCondition = ComputedBooleanCondition(
            name = "SharedCondition",
            cost = 0.25,
            evaluator = { _, _ -> true }
        )

        // Agent1 conditions and goals
        val condition1 = ComputedBooleanCondition(
            name = "Agent1Condition1",
            cost = 0.25,
            evaluator = { _, _ -> true }
        )

        val condition2 = ComputedBooleanCondition(
            name = "Agent1Condition2",
            cost = 0.25,
            evaluator = { _, _ -> true }
        )

        val goal1 = Goal(
            name = "goal1",
            description = "Agent1 goal 1",
            value = 0.8,
            pre = setOf(condition1.name)
        )

        val goal2 = Goal(
            name = "goal2",
            description = "Agent1 goal 2",
            value = 0.8,
            pre = setOf(condition2.name)
        )

        // Agent2 conditions and goals
        val condition3 = ComputedBooleanCondition(
            name = "Agent2Condition1",
            cost = 0.25,
            evaluator = { _, _ -> true }
        )

        val condition4 = ComputedBooleanCondition(
            name = "Agent2Condition2",
            cost = 0.25,
            evaluator = { _, _ -> true }
        )

        val goal3 = Goal(
            name = "goal3",
            description = "Agent2 goal 1",
            value = 0.8,
            pre = setOf(condition3.name)
        )

        val goal4 = Goal(
            name = "goal4",
            description = "Agent2 goal 2",
            value = 0.8,
            pre = setOf(condition4.name)
        )

        // Create Agent1 actions
        val action1 = createTestAction(
            name = "action1",
            description = "Action for Agent1 goal1",
            preconditions = mapOf(sharedCondition.name to ConditionDetermination.FALSE),
            effects = mapOf(condition1.name to ConditionDetermination.TRUE)
        )

        val action2 = createTestAction(
            name = "action2",
            description = "Action for Agent1 goal2",
            preconditions = mapOf(sharedCondition.name to ConditionDetermination.TRUE),
            effects = mapOf(condition2.name to ConditionDetermination.TRUE)
        )

        // Create Agent2 actions that also use the shared condition
        val action3 = createTestAction(
            name = "action3",
            description = "Action for Agent2 goal3",
            preconditions = mapOf(sharedCondition.name to ConditionDetermination.FALSE),
            effects = mapOf(condition3.name to ConditionDetermination.TRUE)
        )

        val action4 = createTestAction(
            name = "action4",
            description = "Action for Agent2 goal4",
            preconditions = mapOf(sharedCondition.name to ConditionDetermination.TRUE),
            effects = mapOf(condition4.name to ConditionDetermination.TRUE)
        )

        // Create Agent1
        val agent1 = Agent(
            name = "agent1",
            description = "Test agent 1",
            actions = listOf(action1, action2),
            goals = setOf(goal1, goal2),
            conditions = setOf(condition1, condition2, sharedCondition),
            provider = "Test Provider"
        )

        // Create Agent2
        val agent2 = Agent(
            name = "agent2",
            description = "Test agent 2",
            actions = listOf(action3, action4),
            goals = setOf(goal3, goal4),
            conditions = setOf(condition3, condition4, sharedCondition),
            provider = "Test Provider"
        )

        // Create and set up platform
        val agentPlatform = IntegrationTestUtils.dummyAgentPlatform()
        agentPlatform.deploy(agent1)
        agentPlatform.deploy(agent2)

        // Create Autonomy
        val autonomy = Autonomy(
            agentPlatform = agentPlatform,
            ranker = RandomRanker(),
            properties = AutonomyProperties()
        )

        // Create user input
        val userInput = UserInput("test input for goal1")

        // Get the prune method
        val pruneMethod = getPruneMethod(autonomy)

        // Test pruning Agent1 with goal1
        val prunedAgent1 = pruneMethod.invoke(autonomy, agent1, userInput) as Agent

        // Verify no action leakage from Agent2 to Agent1
        assertEquals(1, prunedAgent1.actions.size, "Pruned agent should only have 1 action")
        assertEquals("action1", prunedAgent1.actions[0].name, "Pruned agent should contain only action1")
        assertFalse(
            prunedAgent1.actions.any { it.name == "action3" || it.name == "action4" },
            "Agent1 should not contain any actions from Agent2"
        )

        // Test pruning Agent2 with goal3
        val prunedAgent2 = pruneMethod.invoke(autonomy, agent2, userInput) as Agent

        // Verify no action leakage from Agent1 to Agent2
        assertEquals(1, prunedAgent2.actions.size, "Pruned agent should only have 1 action")
        assertEquals("action3", prunedAgent2.actions[0].name, "Pruned agent should contain only action3")
        assertFalse(
            prunedAgent2.actions.any { it.name == "action1" || it.name == "action2" },
            "Agent2 should not contain any actions from Agent1"
        )
    }

    // Helper method to create test actions
    private fun createTestAction(
        name: String,
        description: String,
        preconditions: Map<String, ConditionDetermination>,
        effects: Map<String, ConditionDetermination>
    ): Action {
        return object : Action {
            override val outputs: Set<IoBinding> = setOf(IoBinding(name, type = UserInput::class.java))
            override val cost: ZeroToOne = 0.9
            override val canRerun: Boolean = true
            override val qos: ActionQos = ActionQos()
            override val name = name
            override val value: ZeroToOne = 0.3
            override fun infoString(verbose: Boolean?): String = name
            override val description = description
            override val inputs: Set<IoBinding> = setOf(IoBinding(name, type = UserInput::class.java))
            override val preconditions = preconditions
            override val effects = effects
            override val schemaTypes = emptyList<SchemaType>()

            override fun execute(processContext: ProcessContext, action: Action): ActionStatus {
                return ActionStatus(runningTime = java.time.Duration.ofSeconds(2), status = ActionStatusCode.SUCCEEDED)
            }

            override fun referencedInputProperties(variable: String): Set<String> = emptySet()
            override val domainTypes: Collection<Class<*>> = listOf(DummyType::class.java)
            override val toolGroups: Set<String> = emptySet()
        }
    }

    /**
     * Helper method to get the prune extension method via reflection
     */
    private fun getPruneMethod(autonomy: Autonomy): Method {
        // Get the prune extension method via reflection
        val autonomyClass = Autonomy::class

        // Use Kotlin reflection first to find the extension function
        val pruneFunction = autonomyClass.declaredMemberExtensionFunctions.first { it.name == "prune" }
        // Make it accessible
        pruneFunction.isAccessible = true

        // Get the Java method to invoke it
        val pruneMethod = pruneFunction.javaMethod!!
        pruneMethod.isAccessible = true

        return pruneMethod
    }
}
