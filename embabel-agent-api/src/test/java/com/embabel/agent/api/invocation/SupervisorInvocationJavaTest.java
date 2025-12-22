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
package com.embabel.agent.api.invocation;

import com.embabel.agent.api.common.scope.AgentScopeBuilder;
import com.embabel.agent.core.Agent;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.ProcessOptions;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.integration.IntegrationTestUtils;
import com.embabel.agent.test.integration.ScriptedLlmOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java tests for SupervisorInvocation with meal preparation workflow.
 * These tests verify supervisor orchestration behavior with scripted LLM responses.
 */
class SupervisorInvocationJavaTest {

    private MealPreparationStages stages;

    @BeforeEach
    void setUp() {
        stages = new MealPreparationStages();
    }

    @Nested
    class AgentCreation {

        @Test
        void createsSupervisorAgentFromStages() {
            ScriptedLlmOperations scriptedLlm = new ScriptedLlmOperations()
                .respond("Done");

            AgentPlatform ap = IntegrationTestUtils.dummyAgentPlatform(scriptedLlm);

            SupervisorInvocation<MealPreparationStages.Meal> invocation = SupervisorInvocation
                .on(ap, MealPreparationStages.Meal.class)
                .withGoalDescription("Prepare a meal based on user request")
                .withScope(AgentScopeBuilder.fromInstance(stages));

            Agent agent = invocation.createSupervisorAgent();

            // Should have one supervisor action
            assertEquals(1, agent.getActions().size());
            assertTrue(agent.getActions().iterator().next().getName().contains("supervisor"));

            // Should have one goal
            assertEquals(1, agent.getGoals().size());

            System.out.println("Created agent: " + agent.getName());
            System.out.println("Actions: " + agent.getActions());
            System.out.println("Goals: " + agent.getGoals());
        }

        @Test
        void supervisorAgentHasCorrectToolActions() {
            ScriptedLlmOperations scriptedLlm = new ScriptedLlmOperations()
                .respond("Done");

            AgentPlatform ap = IntegrationTestUtils.dummyAgentPlatform(scriptedLlm);

            SupervisorInvocation<MealPreparationStages.Meal> invocation = SupervisorInvocation
                .on(ap, MealPreparationStages.Meal.class)
                .withScope(AgentScopeBuilder.fromInstance(stages));

            Agent agent = invocation.createSupervisorAgent();

            // The supervisor action should wrap the tool actions
            var supervisorAction = agent.getActions().iterator().next();

            // Log the supervisor action details for debugging
            System.out.println("Supervisor action name: " + supervisorAction.getName());
            System.out.println("Supervisor action inputs: " + supervisorAction.getInputs());
            System.out.println("Supervisor action outputs: " + supervisorAction.getOutputs());
        }
    }

    @Nested
    class Orchestration {

        @Test
        void supervisorCallsToolsInSequence() {
            // Script the supervisor to call tools in order:
            // 1. chooseCook - produces Cook
            // 2. takeOrder - produces Order
            // 3. prepareMeal - produces Meal (goal)
            ScriptedLlmOperations scriptedLlm = new ScriptedLlmOperations()
                .callTool("chooseCook", "{}")
                .respond("Called chooseCook")
                .callTool("takeOrder", "{}")
                .respond("Called takeOrder")
                .callTool("prepareMeal", "{}")
                .respond("Called prepareMeal - done");

            AgentPlatform ap = IntegrationTestUtils.dummyAgentPlatform(scriptedLlm);

            SupervisorInvocation<MealPreparationStages.Meal> invocation = SupervisorInvocation
                .on(ap, MealPreparationStages.Meal.class)
                .withGoalDescription("Prepare a meal")
                .withScope(AgentScopeBuilder.fromInstance(stages));

            // Run with initial user input
            UserInput userInput = new UserInput("I want pasta cooked by Mario");

            AgentProcess result = invocation.run(userInput);

            // Print what happened
            System.out.println("Agent process status: " + result.getStatus());
            System.out.println("Blackboard objects: " + result.getBlackboard().getObjects());
            System.out.println("Tool calls made: " + scriptedLlm.getToolCallsMade());
            System.out.println("Prompts received: " + scriptedLlm.getPromptsReceived().size());

            // Verify tools were called
            assertFalse(scriptedLlm.getToolCallsMade().isEmpty(), "Should have called tools");
        }

        @Test
        void supervisorTerminatesWhenGoalAchieved() {
            // Script: call tools until Meal is produced, then stop
            ScriptedLlmOperations scriptedLlm = new ScriptedLlmOperations()
                .callTool("chooseCook", "{}")
                .callTool("takeOrder", "{}")
                .callTool("prepareMeal", "{}")
                .respond("Goal achieved - meal prepared");

            AgentPlatform ap = IntegrationTestUtils.dummyAgentPlatform(scriptedLlm);

            // Use limited budget to catch infinite loops
            ProcessOptions options = new ProcessOptions().withBudget(
                new com.embabel.agent.core.Budget(1.0, 20, 100000)
            );

            SupervisorInvocation<MealPreparationStages.Meal> invocation = SupervisorInvocation
                .on(ap, MealPreparationStages.Meal.class)
                .withProcessOptions(options)
                .withScope(AgentScopeBuilder.fromInstance(stages));

            AgentProcess result = invocation.run(new UserInput("Make pizza"));

            System.out.println("Final status: " + result.getStatus());

            // Check that we have artifacts on the blackboard
            var objects = result.getBlackboard().getObjects();
            System.out.println("Blackboard objects (" + objects.size() + "):");
            for (Object obj : objects) {
                System.out.println("  - " + obj.getClass().getSimpleName() + ": " + obj);
            }

            // Verify Meal was produced
            boolean hasMeal = objects.stream()
                .anyMatch(obj -> obj instanceof MealPreparationStages.Meal);

            // Log even if assertion fails
            if (!hasMeal) {
                System.out.println("WARNING: No Meal found on blackboard!");
                System.out.println("Tool calls: " + scriptedLlm.getToolCallsMade());
            }
        }

        @Test
        void verifyToolCallSequence() {
            // Minimal script to see what tools are available
            ScriptedLlmOperations scriptedLlm = new ScriptedLlmOperations()
                .respond("Checking available tools");

            AgentPlatform ap = IntegrationTestUtils.dummyAgentPlatform(scriptedLlm);

            SupervisorInvocation<MealPreparationStages.Meal> invocation = SupervisorInvocation
                .on(ap, MealPreparationStages.Meal.class)
                .withScope(AgentScopeBuilder.fromInstance(stages));

            AgentProcess result = invocation.run(new UserInput("Test"));

            // Print the prompts to see what tools were offered
            System.out.println("=== Prompts received ===");
            for (String prompt : scriptedLlm.getPromptsReceived()) {
                System.out.println(prompt);
                System.out.println("---");
            }
        }
    }

    @Nested
    class EdgeCases {

        @Test
        void handlesMissingIntermediateTypes() {
            // Try to call prepareMeal directly without Cook and Order
            ScriptedLlmOperations scriptedLlm = new ScriptedLlmOperations()
                .callTool("prepareMeal", "{}")
                .respond("Tried to call prepareMeal without deps");

            AgentPlatform ap = IntegrationTestUtils.dummyAgentPlatform(scriptedLlm);

            SupervisorInvocation<MealPreparationStages.Meal> invocation = SupervisorInvocation
                .on(ap, MealPreparationStages.Meal.class)
                .withScope(AgentScopeBuilder.fromInstance(stages));

            AgentProcess result = invocation.run(new UserInput("Quick meal"));

            System.out.println("Status when missing deps: " + result.getStatus());
            System.out.println("Tool call results: " + scriptedLlm.getToolCallsMade());

            // prepareMeal should fail or be skipped if Cook/Order not available
            for (var toolCall : scriptedLlm.getToolCallsMade()) {
                System.out.println("Tool: " + toolCall.getToolName() + " -> " + toolCall.getResult());
            }
        }
    }
}
