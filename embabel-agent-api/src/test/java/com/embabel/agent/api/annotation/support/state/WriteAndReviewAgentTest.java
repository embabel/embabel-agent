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
package com.embabel.agent.api.annotation.support.state;

import com.embabel.agent.api.annotation.support.AgentMetadataReader;
import com.embabel.agent.api.event.AgentProcessEvent;
import com.embabel.agent.api.event.AgenticEventListener;
import com.embabel.agent.api.event.StateTransitionEvent;
import com.embabel.agent.core.*;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.integration.IntegrationTestUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WriteAndReviewAgentTest {

    @Nested
    class MetadataUnrolling {

        @Test
        void unrollsActionsFromStateClasses() {
            var reader = new AgentMetadataReader();
            var metadata = reader.createAgentMetadata(new WriteAndReviewAgent(100, 100));
            assertNotNull(metadata);
            assertTrue(metadata instanceof Agent);
            var agent = (Agent) metadata;
            var actionNames = agent.getActions().stream().map(a -> a.getName()).toList();
            System.out.println("Actions: " + actionNames);

            // Debug: print preconditions and effects for each action
            for (var action : agent.getActions()) {
                System.out.println("\n=== " + action.getName() + " ===");
                System.out.println("  Inputs: " + action.getInputs());
                System.out.println("  Outputs: " + action.getOutputs());
                System.out.println("  Preconditions: " + action.getPreconditions());
                System.out.println("  Effects: " + action.getEffects());
            }

            assertTrue(actionNames.stream().anyMatch(n -> n.contains("craftStory")),
                    "Should have craftStory action: " + actionNames);
            assertTrue(actionNames.stream().anyMatch(n -> n.contains("AssessStory")),
                    "Should have AssessStory actions: " + actionNames);
            assertTrue(actionNames.stream().anyMatch(n -> n.contains("ReviseStory")),
                    "Should have ReviseStory actions: " + actionNames);
            assertTrue(actionNames.stream().anyMatch(n -> n.contains("Done")),
                    "Should have Done actions: " + actionNames);
        }

        @Test
        void hasGoalsFromStateClasses() {
            var reader = new AgentMetadataReader();
            var metadata = reader.createAgentMetadata(new WriteAndReviewAgent(100, 100));
            assertNotNull(metadata);
            var agent = (Agent) metadata;
            var goalNames = agent.getGoals().stream().map(g -> g.getName()).toList();
            System.out.println("Goals: " + goalNames);
            assertFalse(agent.getGoals().isEmpty(), "Should have goals: " + goalNames);
        }
    }

    @Nested
    class Execution {

        @Test
        void executesWriteAndReviewFlowWithLoop() {
            // Reset counter so first assessment rejects, second accepts
            WriteAndReviewAgent.resetAssessmentCount();

            var reader = new AgentMetadataReader();
            var metadata = reader.createAgentMetadata(new WriteAndReviewAgent(100, 100));
            assertNotNull(metadata);
            assertTrue(metadata instanceof Agent);
            var ap = IntegrationTestUtils.dummyAgentPlatform();
            AgentProcess agentProcess = ap.runAgentFrom(
                    (Agent) metadata,
                    new ProcessOptions().withVerbosity(
                            Verbosity.DEFAULT.showPlanning()
                    ),
                    Map.of("it", new UserInput("Write a story about a dragon"))
            );
            System.out.println("Status: " + agentProcess.getStatus());
            var history = agentProcess.getHistory().stream()
                    .map(ActionInvocation::getActionName).toList();
            System.out.println("History: " + history);
            System.out.println("Last world state: " + agentProcess.getLastWorldState());
            System.out.println("Blackboard: " + agentProcess.infoString(true, 0));
            assertEquals(
                    AgentProcessStatusCode.COMPLETED,
                    agentProcess.getStatus(),
                    "Agent should complete successfully"
            );
            // Verify the flow went through revision loop:
            // craftStory -> getFeedback -> assess (rejects) -> reviseStory -> getFeedback -> assess (accepts) -> reviewStory
            assertTrue(history.contains("ReviseStory.reviseStory"),
                    "Should have gone through revision loop: " + history);
            assertEquals("Done.reviewStory", history.get(history.size() - 1),
                    "Should end with reviewStory: " + history);
        }

        @Test
        void publishesStateTransitionEventsWhenTransitioningBetweenStates() {
            // Reset counter so first assessment rejects, second accepts
            WriteAndReviewAgent.resetAssessmentCount();

            // Create an event listener that captures StateTransitionEvents
            List<StateTransitionEvent> capturedEvents = new ArrayList<>();
            AgenticEventListener eventCapture = new AgenticEventListener() {
                @Override
                public void onProcessEvent(AgentProcessEvent event) {
                    if (event instanceof StateTransitionEvent) {
                        capturedEvents.add((StateTransitionEvent) event);
                    }
                }
            };

            var reader = new AgentMetadataReader();
            var metadata = reader.createAgentMetadata(new WriteAndReviewAgent(100, 100));
            assertNotNull(metadata);
            assertTrue(metadata instanceof Agent);
            var ap = IntegrationTestUtils.dummyAgentPlatform(null, eventCapture, null);
            AgentProcess agentProcess = ap.runAgentFrom(
                    (Agent) metadata,
                    new ProcessOptions(),
                    Map.of("it", new UserInput("Write a story about a dragon"))
            );

            assertEquals(AgentProcessStatusCode.COMPLETED, agentProcess.getStatus());

            // Verify StateTransitionEvents were published for each state transition
            assertFalse(capturedEvents.isEmpty(), "Should have captured StateTransitionEvents");
            System.out.println("Captured " + capturedEvents.size() + " StateTransitionEvents:");
            for (var event : capturedEvents) {
                System.out.println("  State: " + event.getNewState().getClass().getSimpleName());
            }

            // Verify we have transitions to expected states
            var stateClasses = capturedEvents.stream()
                    .map(e -> e.getNewState().getClass().getSimpleName())
                    .toList();
            assertTrue(stateClasses.contains("AssessStory"),
                    "Should have transitioned to AssessStory: " + stateClasses);
            assertTrue(stateClasses.contains("ReviseStory"),
                    "Should have transitioned to ReviseStory: " + stateClasses);
            assertTrue(stateClasses.contains("Done"),
                    "Should have transitioned to Done: " + stateClasses);
        }
    }
}
