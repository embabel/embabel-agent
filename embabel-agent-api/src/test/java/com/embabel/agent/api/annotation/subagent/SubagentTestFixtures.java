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
package com.embabel.agent.api.annotation.subagent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.RunSubagent;
import com.embabel.agent.api.annotation.support.AgentMetadataReader;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.domain.io.UserInput;

/**
 * Test fixtures for subagent execution tests in Java.
 * Contains domain objects and agent classes that demonstrate
 * various patterns for running sub-agents.
 */
public class SubagentTestFixtures {

    /**
     * Input stage data class.
     */
    public record SubagentTaskInput(String content) {
    }

    /**
     * Intermediate stage data class.
     */
    public record SubagentMiddle(String data) {
    }

    /**
     * Output stage data class.
     */
    public record SubagentTaskOutput(String result) {
    }

    /**
     * Outer agent that delegates to a sub-agent via asSubProcess invocation.
     */
    @Agent(description = "Outer agent via subprocess invocation")
    public static class OuterAgentViaSubprocessInvocation {

        @Action
        public SubagentTaskOutput start(UserInput input, ActionContext context) {
            com.embabel.agent.core.Agent agent = (com.embabel.agent.core.Agent)
                    new AgentMetadataReader().createAgentMetadata(new SubAgentJava());
            return context.asSubProcess(SubagentTaskOutput.class, agent);
        }

        @Action
        @AchievesGoal(description = "All stages complete")
        public SubagentTaskOutput done(SubagentTaskOutput taskOutput) {
            return taskOutput;
        }
    }

    /**
     * Outer agent that delegates to a sub-agent via annotated agent nesting return.
     */
    @Agent(description = "Outer agent via annotated instance return")
    public static class OuterAgentViaAnnotatedAgentNestingReturn {

        @Action
        public SubagentTaskOutput start(UserInput input) {
            return RunSubagent.fromAnnotatedInstance(new SubAgentJava(), SubagentTaskOutput.class);
        }

        @Action
        @AchievesGoal(description = "All stages complete")
        public SubagentTaskOutput done(SubagentTaskOutput taskOutput) {
            return taskOutput;
        }
    }

    /**
     * Outer agent that delegates to a sub-agent via agent nesting return.
     */
    @Agent(description = "Outer agent via agent nesting return")
    public static class OuterAgentViaAgentSubagentReturn {

        @Action
        public SubagentTaskOutput start(UserInput input) {
            com.embabel.agent.core.Agent agent = (com.embabel.agent.core.Agent)
                    new AgentMetadataReader().createAgentMetadata(new SubAgentJava());
            return RunSubagent.fromAnnotatedInstance(agent, SubagentTaskOutput.class);
        }

        @Action
        @AchievesGoal(description = "All stages complete")
        public SubagentTaskOutput done(SubagentTaskOutput taskOutput) {
            return taskOutput;
        }
    }

    /**
     * Inner sub-agent that performs a multi-step transformation.
     */
    @Agent(description = "Inner sub-agent")
    public static class SubAgentJava {

        @Action
        public SubagentTaskInput stepZero(UserInput input) {
            return new SubagentTaskInput(input.getContent());
        }

        @Action
        public SubagentMiddle stepOne(SubagentTaskInput input) {
            return new SubagentMiddle(input.content().toUpperCase());
        }

        @Action
        @AchievesGoal(description = "Subflow complete")
        public SubagentTaskOutput stepTwo(SubagentMiddle middle) {
            return new SubagentTaskOutput("[" + middle.data() + "]");
        }
    }
}
