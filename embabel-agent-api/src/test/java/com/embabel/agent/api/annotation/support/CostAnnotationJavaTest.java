/*
 * Copyright 2024-2025 Embabel Pty Ltd.
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
package com.embabel.agent.api.annotation.support;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Cost;
import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.ProcessOptions;
import com.embabel.agent.test.integration.IntegrationTestUtils;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java tests for @Cost annotation.
 */
class CostAnnotationJavaTest {

    @Test
    void costMethodIsDiscoveredByAgentMetadataReader() {
        var reader = new AgentMetadataReader();
        var instance = new JavaAgentWithCostMethod();
        var metadata = reader.createAgentMetadata(instance);

        assertNotNull(metadata);
        assertEquals(1, metadata.getActions().size());
    }

    @Test
    void costMethodIsInvokedDuringPlanning() {
        var reader = new AgentMetadataReader();
        var instance = new JavaAgentWithCostMethod();
        var metadata = reader.createAgentMetadata(instance);

        assertNotNull(metadata);
        var agent = (com.embabel.agent.core.Agent) metadata;

        var ap = IntegrationTestUtils.dummyAgentPlatform();
        var agentProcess = ap.createAgentProcess(
                agent,
                ProcessOptions.DEFAULT.withPlannerType(PlannerType.UTILITY),
                Map.of("input", new JavaTestInput("test"))
        );

        var planner = agentProcess.getPlanner();
        planner.plansToGoals(agent.getPlanningSystem());

        assertTrue(
                instance.costInvocationCount.get() > 0,
                "Cost method should have been invoked during planning"
        );
    }

    @Test
    void costMethodReceivesNullWhenDomainObjectNotOnBlackboard() {
        var reader = new AgentMetadataReader();
        var instance = new JavaAgentWithCostMethod();
        var metadata = reader.createAgentMetadata(instance);

        assertNotNull(metadata);
        var agent = (com.embabel.agent.core.Agent) metadata;

        var ap = IntegrationTestUtils.dummyAgentPlatform();
        var agentProcess = ap.createAgentProcess(
                agent,
                ProcessOptions.DEFAULT.withPlannerType(PlannerType.UTILITY),
                Map.of("input", new JavaTestInput("test"))
                // Note: JavaExpensiveData is NOT provided
        );

        var planner = agentProcess.getPlanner();
        planner.plansToGoals(agent.getPlanningSystem());

        assertTrue(
                instance.costInvocationCount.get() > 0,
                "Cost method should have been invoked"
        );
        assertNull(
                instance.lastDataSize,
                "Data should have been null since JavaExpensiveData wasn't on blackboard"
        );
    }

    @Test
    void costMethodReceivesDomainObjectWhenAvailableOnBlackboard() {
        var reader = new AgentMetadataReader();
        var instance = new JavaAgentWithCostMethod();
        var metadata = reader.createAgentMetadata(instance);

        assertNotNull(metadata);
        var agent = (com.embabel.agent.core.Agent) metadata;

        var ap = IntegrationTestUtils.dummyAgentPlatform();
        var agentProcess = ap.createAgentProcess(
                agent,
                ProcessOptions.DEFAULT.withPlannerType(PlannerType.UTILITY),
                Map.of(
                        "input", new JavaTestInput("test"),
                        "data", new JavaExpensiveData(200)
                )
        );

        var planner = agentProcess.getPlanner();
        planner.plansToGoals(agent.getPlanningSystem());

        assertTrue(
                instance.costInvocationCount.get() > 0,
                "Cost method should have been invoked"
        );
        assertEquals(
                Integer.valueOf(200),
                instance.lastDataSize,
                "Data size should have been passed from blackboard"
        );
    }

    @Test
    void plannerPrefersActionWithLowerDynamicCost() {
        var reader = new AgentMetadataReader();
        var instance = new JavaAgentWithTwoCostActions();
        var metadata = reader.createAgentMetadata(instance);

        assertNotNull(metadata);
        assertEquals(2, metadata.getActions().size());
        var agent = (com.embabel.agent.core.Agent) metadata;

        var ap = IntegrationTestUtils.dummyAgentPlatform();
        var agentProcess = ap.createAgentProcess(
                agent,
                ProcessOptions.DEFAULT.withPlannerType(PlannerType.GOAP),
                Map.of("input", new JavaTestInput("test"))
        );

        var planner = agentProcess.getPlanner();
        var plan = planner.bestValuePlanToAnyGoal(agent.getPlanningSystem());

        assertNotNull(plan, "Should find a plan");
        // Both cost methods should have been evaluated during planning
        assertTrue(
                instance.cheapCostInvocations.get() > 0,
                "Cheap action cost should have been evaluated"
        );
        assertTrue(
                instance.expensiveCostInvocations.get() > 0,
                "Expensive action cost should have been evaluated"
        );
        // The plan should prefer the cheaper action
        assertEquals(
                1, plan.getActions().size(),
                "Plan should have one action"
        );
        assertTrue(
                plan.getActions().iterator().next().getName().contains("cheapAction"),
                "Plan should use the cheaper action"
        );
    }
}

/**
 * Simple domain class for testing.
 */
record JavaTestInput(String value) {}

/**
 * Simple output class for testing.
 */
record JavaTestOutput(String result) {}

/**
 * Domain class that represents expensive data.
 */
record JavaExpensiveData(int size) {}

/**
 * Agent with @Cost method that uses nullable domain parameter.
 */
@Agent(description = "Java agent with dynamic cost", planner = PlannerType.UTILITY)
class JavaAgentWithCostMethod {

    final AtomicInteger costInvocationCount = new AtomicInteger(0);
    Integer lastDataSize = null;

    @Cost(name = "processingCost")
    public double computeProcessingCost(@Nullable JavaExpensiveData data) {
        costInvocationCount.incrementAndGet();
        lastDataSize = data != null ? data.size() : null;
        return data != null && data.size() > 100 ? 0.9 : 0.1;
    }

    @Action(costMethod = "processingCost")
    public JavaTestOutput processData(JavaTestInput input) {
        return new JavaTestOutput("processed: " + input.value());
    }
}

/**
 * Agent with two actions with different dynamic costs.
 */
@Agent(description = "Java agent with two dynamic cost actions", planner = PlannerType.GOAP)
class JavaAgentWithTwoCostActions {

    final AtomicInteger cheapCostInvocations = new AtomicInteger(0);
    final AtomicInteger expensiveCostInvocations = new AtomicInteger(0);

    @Cost(name = "cheapCost")
    public double computeCheapCost() {
        cheapCostInvocations.incrementAndGet();
        return 0.1;
    }

    @Cost(name = "expensiveCost")
    public double computeExpensiveCost() {
        expensiveCostInvocations.incrementAndGet();
        return 0.9;
    }

    @AchievesGoal(description = "Process the input")
    @Action(costMethod = "cheapCost")
    public JavaTestOutput cheapAction(JavaTestInput input) {
        return new JavaTestOutput("cheap: " + input.value());
    }

    @AchievesGoal(description = "Process the input expensively")
    @Action(costMethod = "expensiveCost")
    public JavaTestOutput expensiveAction(JavaTestInput input) {
        return new JavaTestOutput("expensive: " + input.value());
    }
}
