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
package com.embabel.agent.api.annotation.support

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Cost
import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.test.integration.IntegrationTestUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import com.embabel.agent.core.Agent as CoreAgent

data class TestInput(val value: String)
data class TestOutput(val result: String)
data class ExpensiveData(val size: Int)

/**
 * Agent with @Cost method for dynamic cost computation.
 */
@Agent(description = "Agent with dynamic cost method", planner = PlannerType.UTILITY)
class AgentWithCostMethod {

    val costInvocationCount = AtomicInteger(0)

    @Cost(name = "dynamicCost")
    fun computeDynamicCost(data: ExpensiveData?): Double {
        costInvocationCount.incrementAndGet()
        return if (data != null && data.size > 100) 0.9 else 0.1
    }

    @Action(costMethod = "dynamicCost")
    fun processData(input: TestInput): TestOutput {
        return TestOutput("processed: ${input.value}")
    }
}

/**
 * Agent with @Cost method that takes domain object parameters.
 * All domain parameters must be nullable.
 */
@Agent(description = "Agent with nullable parameter cost method", planner = PlannerType.UTILITY)
class AgentWithNullableParamCostMethod {

    val costInvocationCount = AtomicInteger(0)
    var lastDataSize: Int? = null

    @Cost(name = "sizeDependentCost")
    fun computeCostFromData(data: ExpensiveData?): Double {
        costInvocationCount.incrementAndGet()
        lastDataSize = data?.size
        return if (data != null && data.size > 50) 0.8 else 0.2
    }

    @Action(costMethod = "sizeDependentCost")
    fun transformData(input: TestInput): TestOutput {
        return TestOutput("transformed: ${input.value}")
    }
}

/**
 * Agent with two actions with different costs to test planner preference.
 */
@Agent(description = "Agent with two actions and dynamic costs", planner = PlannerType.GOAP)
class AgentWithTwoDynamicCostActions {

    val cheapActionCostInvocations = AtomicInteger(0)
    val expensiveActionCostInvocations = AtomicInteger(0)

    @Cost(name = "cheapCost")
    fun computeCheapCost(): Double {
        cheapActionCostInvocations.incrementAndGet()
        return 0.1
    }

    @Cost(name = "expensiveCost")
    fun computeExpensiveCost(): Double {
        expensiveActionCostInvocations.incrementAndGet()
        return 0.9
    }

    @AchievesGoal(description = "Process the input")
    @Action(costMethod = "cheapCost")
    fun cheapAction(input: TestInput): TestOutput {
        return TestOutput("cheap: ${input.value}")
    }

    @AchievesGoal(description = "Process the input expensively")
    @Action(costMethod = "expensiveCost")
    fun expensiveAction(input: TestInput): TestOutput {
        return TestOutput("expensive: ${input.value}")
    }
}

class CostAnnotationTest {

    @Test
    fun `@Cost method is discovered by AgentMetadataReader`() {
        val reader = AgentMetadataReader()
        val instance = AgentWithCostMethod()
        val metadata = reader.createAgentMetadata(instance)

        assertNotNull(metadata)
        assertEquals(1, metadata!!.actions.size)
    }

    @Test
    fun `@Cost method is invoked during planning`() {
        val reader = AgentMetadataReader()
        val instance = AgentWithCostMethod()
        val metadata = reader.createAgentMetadata(instance)

        assertNotNull(metadata)
        val agent = metadata as CoreAgent

        val ap = IntegrationTestUtils.dummyAgentPlatform()
        val agentProcess = ap.createAgentProcess(
            agent,
            ProcessOptions(plannerType = PlannerType.UTILITY),
            mapOf("input" to TestInput("test")),
        )

        // Get the planner and plan
        val planner = agentProcess.planner
        val plans = planner.plansToGoals(agent.planningSystem)

        // Cost method should have been invoked during planning
        assertTrue(
            instance.costInvocationCount.get() > 0,
            "Cost method should have been invoked during planning"
        )
    }

    @Test
    fun `@Cost method receives null when domain object not on blackboard`() {
        val reader = AgentMetadataReader()
        val instance = AgentWithNullableParamCostMethod()
        val metadata = reader.createAgentMetadata(instance)

        assertNotNull(metadata)
        val agent = metadata as CoreAgent

        val ap = IntegrationTestUtils.dummyAgentPlatform()
        val agentProcess = ap.createAgentProcess(
            agent,
            ProcessOptions(plannerType = PlannerType.UTILITY),
            mapOf("input" to TestInput("test")),
            // Note: ExpensiveData is NOT provided
        )

        val planner = agentProcess.planner
        planner.plansToGoals(agent.planningSystem)

        assertTrue(
            instance.costInvocationCount.get() > 0,
            "Cost method should have been invoked"
        )
        assertNull(
            instance.lastDataSize,
            "Data should have been null since ExpensiveData wasn't on blackboard"
        )
    }

    @Test
    fun `@Cost method receives domain object when available on blackboard`() {
        val reader = AgentMetadataReader()
        val instance = AgentWithNullableParamCostMethod()
        val metadata = reader.createAgentMetadata(instance)

        assertNotNull(metadata)
        val agent = metadata as CoreAgent

        val ap = IntegrationTestUtils.dummyAgentPlatform()
        val agentProcess = ap.createAgentProcess(
            agent,
            ProcessOptions(plannerType = PlannerType.UTILITY),
            mapOf(
                "input" to TestInput("test"),
                "data" to ExpensiveData(200),
            ),
        )

        val planner = agentProcess.planner
        planner.plansToGoals(agent.planningSystem)

        assertTrue(
            instance.costInvocationCount.get() > 0,
            "Cost method should have been invoked"
        )
        assertEquals(
            200,
            instance.lastDataSize,
            "Data size should have been passed from blackboard"
        )
    }

    @Test
    fun `planner prefers action with lower dynamic cost`() {
        val reader = AgentMetadataReader()
        val instance = AgentWithTwoDynamicCostActions()
        val metadata = reader.createAgentMetadata(instance)

        assertNotNull(metadata)
        assertEquals(2, metadata!!.actions.size)
        val agent = metadata as CoreAgent

        val ap = IntegrationTestUtils.dummyAgentPlatform()
        val agentProcess = ap.createAgentProcess(
            agent,
            ProcessOptions(plannerType = PlannerType.GOAP),
            mapOf("input" to TestInput("test")),
        )

        val planner = agentProcess.planner
        val plan = planner.bestValuePlanToAnyGoal(agent.planningSystem)

        assertNotNull(plan, "Should find a plan")
        // Both cost methods should have been evaluated during planning
        assertTrue(
            instance.cheapActionCostInvocations.get() > 0,
            "Cheap action cost should have been evaluated"
        )
        assertTrue(
            instance.expensiveActionCostInvocations.get() > 0,
            "Expensive action cost should have been evaluated"
        )
        // The plan should prefer the cheaper action
        assertEquals(
            1, plan!!.actions.size,
            "Plan should have one action"
        )
        assertTrue(
            plan.actions.first().name.contains("cheapAction"),
            "Plan should use the cheaper action: ${plan.actions.map { it.name }}"
        )
    }
}
