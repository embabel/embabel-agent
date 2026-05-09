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
package com.embabel.agent.api.termination;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.support.AgentMetadataReader;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.dsl.Frog;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.AgentProcessStatusCode;
import com.embabel.agent.core.ProcessOptions;
import com.embabel.agent.core.support.InMemoryBlackboard;
import com.embabel.agent.core.support.SimpleAgentProcess;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.spi.support.DefaultPlannerFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.embabel.agent.api.termination.Termination.terminateAction;
import static com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Java test demonstrating the use of {@code terminateAction(processContext, reason)}
 * extension function from Java using static import.
 *
 * <p>This test verifies that the static import syntax works correctly from Java
 * and that graceful ACTION termination signal is cleared after action completes.
 */
class TerminateActionJavaTest {

    /**
     * Test agent that explicitly calls terminateAction using Java syntax.
     */
    @Agent(description = "Java agent with graceful action termination")
    static class JavaActionTerminatingAgent {

        @Action
        public String firstAction(UserInput input, ActionContext context) {
            context.set("firstActionRan", true);

            // This is the key test: calling terminateAction from Java using static import
            terminateAction(context.getProcessContext(), "Graceful action termination from Java");

            return "first-" + input.getContent();
        }

        @Action
        public Frog secondAction(String input, ActionContext context) {
            context.set("secondActionRan", true);
            return new Frog(input);
        }

        @Action
        @AchievesGoal(description = "Turn input into frog")
        public Frog frogGoal(Frog frog) {
            return frog;
        }
    }

    @Test
    void terminateActionFromJavaWithStaticImport() {
        // Setup
        var platformServices = dummyPlatformServices();
        var blackboard = new InMemoryBlackboard();
        blackboard.addObject(new UserInput("TestUser"));

        // Create agent from Java class
        var reader = new AgentMetadataReader();
        var agent = (com.embabel.agent.core.Agent) reader.createAgentMetadata(new JavaActionTerminatingAgent());

        var agentProcess = new SimpleAgentProcess(
            "test-java-terminate-action",
            null,  // parentId
            agent,
            ProcessOptions.DEFAULT,
            blackboard,
            platformServices,
            DefaultPlannerFactory.INSTANCE,
            Instant.now()
        );

        // Execute
        AgentProcess result = agentProcess.run();

        // Verify - both actions should have run (signal cleared after first action)
        assertThat(blackboard.get("firstActionRan")).isEqualTo(true);
        assertThat(blackboard.get("secondActionRan")).isEqualTo(true);

        // Agent should complete successfully
        assertThat(result.getStatus()).isEqualTo(AgentProcessStatusCode.COMPLETED);

        // Final result should be a Frog
        var frog = (Frog) blackboard.lastResult();
        assertThat(frog).isNotNull();
        assertThat(frog.getName()).isEqualTo("first-TestUser");
    }
}
