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
package com.embabel.plan.goap.astar;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.annotation.support.AgentMetadataReader;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.AgentProcessStatusCode;
import com.embabel.agent.core.ProcessOptions;
import com.embabel.agent.core.support.InMemoryBlackboard;
import com.embabel.agent.core.support.SimpleAgentProcess;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.spi.support.DefaultPlannerFactory;
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension

import java.time.Instant;
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue


/**
 * This test verifies that whenever a goal is not reachable because it is not satisfying the `pre`condition,
 * agent is stuck and appropriate message is logged.
 */
@ExtendWith(OutputCaptureExtension::class)
class UnReachableGoalTest {

    @Agent(description = "A dummy agent with unreachable goal")
    inner class AgentWithUnreachableGoal {

        @Action
        fun firstAction(input: UserInput ,  context: ActionContext) :String {
            return "first-" + input.content
        }

        @Action(pre = ["firstCondition"])
        @AchievesGoal(description = "Unreachable goal" )
        fun  goal( input: String): String {
            return input
        }

        @Condition(name = "firstCondition")
        fun  isFirstCondition(input: String): Boolean {
            return input.contains("123")
        }
    }

    @Test
    fun `test unreachable condition`(output: CapturedOutput) {
        // Setup.
        var platformServices = dummyPlatformServices()
        var blackboard =  InMemoryBlackboard()
        blackboard.addObject( UserInput("TestUser"))

        var reader =  AgentMetadataReader();
        var agent = (reader.createAgentMetadata( AgentWithUnreachableGoal())) as  com.embabel.agent.core.Agent

        var agentProcess =  SimpleAgentProcess(
            "test-unreachable-condition",
            null,  // parentId
            agent,
            ProcessOptions.DEFAULT,
            blackboard,
            platformServices,
            DefaultPlannerFactory,
            Instant.now()
        );

        var result:AgentProcess = agentProcess.run()

        // Agent is stuck as the condition could not meet.
        assertEquals( AgentProcessStatusCode.STUCK, result.status);
        assertTrue(output.out.contains("Condition 'firstCondition' is not produced by any of the action that leads " +
                "to " +
                "the goal com.embabel.plan.goap.astar.UnReachableGoalTest\$AgentWithUnreachableGoal.goal. Make sure " +
                "to add it as the output/post condition of any one of the action that leads to the goal"),
            "firstCondition is produced unexpectedly.")
    }

    /* Agent as similar to the AgentWithUnreachableGoal but goal is made reachable. */
    @Agent(description = "A dummy agent with reachable goal")
    inner class AgentWithReachableGoal {

        // firstCondition is added as a post condition to make the goal reachable.
        @Action(post = ["firstCondition"])
        fun firstAction(input: UserInput ,  context: ActionContext) :String {
            context.setCondition("firstCondition", true)
            return "first-" + input.content
        }

        @Action(pre = ["firstCondition"])
        @AchievesGoal(description = "Reachable goal" )
        fun  goal( input: String): String {
            return input
        }

        @Condition(name = "firstCondition")
        fun  isFirstCondition(input: String): Boolean {
            return input.contains("123")
        }
    }

    @Test
    fun `test reachable condition`(output: CapturedOutput) {
        // Setup
        var platformServices = dummyPlatformServices()
        var blackboard =  InMemoryBlackboard()
        blackboard.addObject( UserInput("TestUser"))

        var reader =  AgentMetadataReader();
        var agent = (reader.createAgentMetadata( AgentWithReachableGoal())) as  com.embabel.agent.core.Agent

        var agentProcess =  SimpleAgentProcess(
            "test-reachable-condition",
            null,  // parentId
            agent,
            ProcessOptions.DEFAULT,
            blackboard,
            platformServices,
            DefaultPlannerFactory,
            Instant.now()
        );

       agentProcess.run()

        assertFalse (output.out.contains("Condition 'firstCondition' is not produced by any of the action that leads to" +
                " the goal com.embabel.plan.goap.astar.UnReachableGoalTest\$AgentWithReachableGoal.goal. Make sure to" +
                " add it as the output/post condition of any one of the action that leads to the goal."),
             "firstCondition is not produced as expected.")
    }
}
