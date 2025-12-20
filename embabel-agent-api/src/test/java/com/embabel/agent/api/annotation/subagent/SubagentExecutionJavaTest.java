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
package com.embabel.agent.api.annotation.subagent;

import com.embabel.agent.api.annotation.support.AgentMetadataReader;
import com.embabel.agent.core.*;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.integration.IntegrationTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java tests for subagent execution patterns.
 * Tests verify that outer agents can invoke inner sub-agents
 * via various mechanisms: asSubProcess, annotated nesting return, and agent nesting return.
 */
class SubagentExecutionJavaTest {

    private AgentMetadataReader reader;

    @BeforeEach
    void setUp() {
        reader = new AgentMetadataReader();
    }

    @Nested
    class SubAgentTests {

        @Test
        void innerSubAgentExecutesThroughAllSteps() {
            Agent agent = (Agent) reader.createAgentMetadata(new SubagentTestFixtures.SubAgentJava());
            AgentPlatform ap = IntegrationTestUtils.dummyAgentPlatform();
            AgentProcess process = ap.runAgentFrom(
                    agent,
                    new ProcessOptions().withVerbosity(Verbosity.DEFAULT.showPlanning()),
                    Map.of("it", new UserInput("hello"))
            );

            List<String> history = process.getHistory().stream()
                    .map(h -> h.getActionName())
                    .toList();

            assertEquals(AgentProcessStatusCode.COMPLETED, process.getStatus(), "Agent should complete");
            assertEquals(3, history.size(), "Should have 3 actions: " + history);
            assertTrue(history.stream().anyMatch(h -> h.contains("stepZero")), "Should have stepZero: " + history);
            assertTrue(history.stream().anyMatch(h -> h.contains("stepOne")), "Should have stepOne: " + history);
            assertTrue(history.stream().anyMatch(h -> h.contains("stepTwo")), "Should have stepTwo: " + history);
        }

        @Test
        void innerSubAgentProducesCorrectOutput() {
            Agent agent = (Agent) reader.createAgentMetadata(new SubagentTestFixtures.SubAgentJava());
            AgentPlatform ap = IntegrationTestUtils.dummyAgentPlatform();
            AgentProcess process = ap.runAgentFrom(
                    agent,
                    new ProcessOptions(),
                    Map.of("it", new UserInput("test"))
            );

            assertEquals(AgentProcessStatusCode.COMPLETED, process.getStatus());

            Object outputObj = process.getValue("it", SubagentTestFixtures.SubagentTaskOutput.class.getName());
            assertNotNull(outputObj, "Should have output on blackboard");

            SubagentTestFixtures.SubagentTaskOutput output = (SubagentTestFixtures.SubagentTaskOutput) outputObj;
            assertEquals("[TEST]", output.result(), "Output should be transformed through all stages");
        }
    }

    @Nested
    class OuterAgentWithSubprocessTests {

        @Test
        void outerAgentMetadataIsValid() {
            Object agentMetadata = reader.createAgentMetadata(new SubagentTestFixtures.OuterAgentViaSubprocessInvocation());
            assertNotNull(agentMetadata, "Agent metadata should not be null");
        }

        @Test
        void outerAgentWithSubprocessHasCorrectStructure() {
            Agent agent = (Agent) reader.createAgentMetadata(new SubagentTestFixtures.OuterAgentViaSubprocessInvocation());
            assertNotNull(agent, "Agent should not be null");
            assertTrue(
                    agent.getActions().stream().anyMatch(a -> a.getName().contains("start")),
                    "Should have start action"
            );
            assertTrue(
                    agent.getActions().stream().anyMatch(a -> a.getName().contains("done")),
                    "Should have done action"
            );
        }

        private void testOuterAgentExecutesSteps(Object instance) {
            Agent agent = (Agent) reader.createAgentMetadata(instance);
            AgentPlatform ap = IntegrationTestUtils.dummyAgentPlatform();
            var process = ap.runAgentFrom(
                    agent,
                    new ProcessOptions().withVerbosity(Verbosity.DEFAULT.showPlanning()),
                    Map.of("it", new UserInput("subprocess test"))
            );

            List<String> history = process.getHistory().stream()
                    .map(ActionInvocation::getActionName)
                    .toList();

            assertEquals(AgentProcessStatusCode.COMPLETED, process.getStatus(), "Agent should complete");
            assertTrue(history.stream().anyMatch(h -> h.contains("start")), "Should have start action: " + history);
            assertTrue(history.stream().anyMatch(h -> h.contains("done")), "Should have done action: " + history);
        }

        private void testWithOuterAgentInstance(Object instance) {
            Agent agent = (Agent) reader.createAgentMetadata(instance);
            AgentPlatform ap = IntegrationTestUtils.dummyAgentPlatform();
            AgentProcess process = ap.runAgentFrom(
                    agent,
                    new ProcessOptions(),
                    Map.of("it", new UserInput("hello"))
            );

            assertEquals(AgentProcessStatusCode.COMPLETED, process.getStatus());

            Object outputObj = process.getValue("it", SubagentTestFixtures.SubagentTaskOutput.class.getName());
            assertNotNull(outputObj, "Should have output on blackboard");

            SubagentTestFixtures.SubagentTaskOutput output = (SubagentTestFixtures.SubagentTaskOutput) outputObj;
            assertEquals("[HELLO]", output.result(), "Output should be transformed by inner sub-agent");
        }

        @Test
        void outerAgentProducesCorrectOutputViaInnerSubagent() {
            testWithOuterAgentInstance(new SubagentTestFixtures.OuterAgentViaSubprocessInvocation());
        }

        @Test
        void outerAgentProducesCorrectOutputViaAnnotatedSubagentReturn() {
            testWithOuterAgentInstance(new SubagentTestFixtures.OuterAgentViaAnnotatedAgentNestingReturn());
        }

        @Test
        void outerAgentProducesCorrectOutputViaSubagentReturn() {
            testWithOuterAgentInstance(new SubagentTestFixtures.OuterAgentViaAgentSubagentReturn());
        }

        @Test
        void outerAgentViaSubprocessExecutesAllSteps() {
            testOuterAgentExecutesSteps(new SubagentTestFixtures.OuterAgentViaSubprocessInvocation());
        }

        @Test
        void outerAgentViaAnnotatedAgentInstanceReturnExecutesAllSteps() {
            testOuterAgentExecutesSteps(new SubagentTestFixtures.OuterAgentViaAnnotatedAgentNestingReturn());
        }

        @Test
        void outerAgentViaAgentInstanceReturnExecutesAllSteps() {
            testOuterAgentExecutesSteps(new SubagentTestFixtures.OuterAgentViaAgentSubagentReturn());
        }
    }
}
