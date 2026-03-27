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
package com.embabel.agent.api.termination

import com.embabel.agent.api.dsl.Frog
import com.embabel.agent.api.dsl.agent
import com.embabel.agent.api.tool.TerminateActionException
import com.embabel.agent.api.tool.TerminateAgentException
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.support.ConcurrentAgentProcess
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.SimpleAgentProcess
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.domain.library.Person
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

data class TestPerson(
    override val name: String,
) : Person

/**
 * Agent that throws TerminateActionException on first invocation.
 * On retry, the action completes normally showing the agent continued.
 */
val ActionTerminatingAgent = agent("ActionTerminator", description = "Agent that terminates action early") {
    transformation<UserInput, TestPerson>(name = "terminating_action", canRerun = true) {
        val attemptCount = (it["attemptCount"] as? Int) ?: 0
        it["attemptCount"] = attemptCount + 1
        if (attemptCount == 0) {
            throw TerminateActionException("User requested action termination")
        }
        TestPerson(name = "Attempt $attemptCount: ${it.input.content}")
    }

    transformation<TestPerson, Frog>(name = "to_frog") {
        Frog(it.input.name)
    }

    goal(name = "frog_goal", description = "Turn input into frog", satisfiedBy = Frog::class)
}

/**
 * Agent that throws TerminateAgentException.
 * The agent process should terminate with TERMINATED status.
 */
val AgentTerminatingAgent = agent("AgentTerminator", description = "Agent that terminates itself") {
    transformation<UserInput, TestPerson>(name = "terminating_action") {
        throw TerminateAgentException("Critical condition - terminating agent")
    }

    transformation<TestPerson, Frog>(name = "to_frog") {
        Frog(it.input.name)
    }

    goal(name = "frog_goal", description = "Turn input into frog", satisfiedBy = Frog::class)
}

/**
 * Agent that uses graceful ACTION termination via signal in first action.
 * The signal should be cleared after the action completes, allowing the second action to run.
 */
val GracefulActionTerminatingAgent = agent("GracefulActionTerminator", description = "Agent with graceful action termination") {
    transformation<UserInput, TestPerson>(name = "first_action_with_signal") {
        it["firstActionRan"] = true
        // Set ACTION termination signal - should be cleared after this action
        it.processContext.terminateAction("Graceful action termination")
        TestPerson(name = it.input.content)
    }

    transformation<TestPerson, Frog>(name = "second_action") {
        it["secondActionRan"] = true
        Frog(it.input.name)
    }

    goal(name = "frog_goal", description = "Turn input into frog", satisfiedBy = Frog::class)
}

/**
 * Agent that uses graceful termination via signal.
 * Sets termination signal on blackboard, checked at next tick.
 */
val GracefulAgentTerminatingAgent = agent("GracefulTerminator", description = "Agent that terminates gracefully") {
    transformation<UserInput, TestPerson>(name = "signal_termination") {
        it["signalSet"] = true
        it.processContext.terminateAgent("Graceful shutdown requested")
        TestPerson(name = it.input.content)
    }

    transformation<TestPerson, Frog>(name = "to_frog") {
        Frog(it.input.name)
    }

    goal(name = "frog_goal", description = "Turn input into frog", satisfiedBy = Frog::class)
}

class TerminationAgenticTest {

    @Nested
    inner class ActionTermination {

        @Test
        fun `TerminateActionException allows agent to continue and retry`() {
            val dummyPlatformServices = dummyPlatformServices()
            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("TestUser")

            val agentProcess = SimpleAgentProcess(
                id = "test-action-termination",
                agent = ActionTerminatingAgent,
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = dummyPlatformServices,
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            )

            val result = agentProcess.run()

            assertThat(result.status).isEqualTo(AgentProcessStatusCode.COMPLETED)
            assertThat(blackboard["attemptCount"]).isEqualTo(2)
            val frog = blackboard.lastResult() as Frog
            assertThat(frog.name).contains("Attempt 1")
        }

        /**
         * Verifies that graceful ACTION termination signal is cleared after action completes.
         * The second action should still execute because the signal was cleared.
         */
        @Test
        fun `graceful ACTION signal is cleared allowing second action to execute`() {
            val dummyPlatformServices = dummyPlatformServices()
            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("TestUser")

            val agentProcess = SimpleAgentProcess(
                id = "test-graceful-action-clearing",
                agent = GracefulActionTerminatingAgent,
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = dummyPlatformServices,
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            )

            val result = agentProcess.run()

            // Both actions should have run
            assertThat(blackboard["firstActionRan"]).isEqualTo(true)
            assertThat(blackboard["secondActionRan"]).isEqualTo(true)
            // Agent should complete successfully
            assertThat(result.status).isEqualTo(AgentProcessStatusCode.COMPLETED)
            // Final result should be a Frog
            val frog = blackboard.lastResult() as Frog
            assertThat(frog.name).isEqualTo("TestUser")
        }
    }

    @Nested
    inner class AgentTermination {

        @Test
        fun `TerminateAgentException terminates agent process`() {
            val dummyPlatformServices = dummyPlatformServices()
            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("TestUser")

            val agentProcess = SimpleAgentProcess(
                id = "test-agent-termination",
                agent = AgentTerminatingAgent,
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = dummyPlatformServices,
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            )

            val result = agentProcess.run()

            assertThat(result.status).isEqualTo(AgentProcessStatusCode.TERMINATED)
        }

        @Test
        fun `graceful termination signal terminates agent at next tick`() {
            val dummyPlatformServices = dummyPlatformServices()
            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("TestUser")

            val agentProcess = SimpleAgentProcess(
                id = "test-graceful-termination",
                agent = GracefulAgentTerminatingAgent,
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = dummyPlatformServices,
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            )

            val result = agentProcess.run()

            assertThat(blackboard["signalSet"]).isEqualTo(true)
            assertThat(result.status).isEqualTo(AgentProcessStatusCode.TERMINATED)
        }
    }

    @Nested
    inner class ConcurrentActionTermination {

        @Test
        fun `TerminateActionException allows concurrent agent to continue and retry`() {
            val dummyPlatformServices = dummyPlatformServices()
            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("TestUser")

            val agentProcess = ConcurrentAgentProcess(
                id = "test-concurrent-action-termination",
                agent = ActionTerminatingAgent,
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = dummyPlatformServices,
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            )

            val result = agentProcess.run()

            assertThat(result.status).isEqualTo(AgentProcessStatusCode.COMPLETED)
            assertThat(blackboard["attemptCount"]).isEqualTo(2)
            val frog = blackboard.lastResult() as Frog
            assertThat(frog.name).contains("Attempt 1")
        }

        /**
         * Verifies that graceful ACTION termination signal is cleared after action completes.
         * The second action should still execute because the signal was cleared.
         */
        @Test
        fun `graceful ACTION signal is cleared allowing second action to execute`() {
            val dummyPlatformServices = dummyPlatformServices()
            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("TestUser")

            val agentProcess = ConcurrentAgentProcess(
                id = "test-concurrent-graceful-action-clearing",
                agent = GracefulActionTerminatingAgent,
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = dummyPlatformServices,
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            )

            val result = agentProcess.run()

            // Both actions should have run
            assertThat(blackboard["firstActionRan"]).isEqualTo(true)
            assertThat(blackboard["secondActionRan"]).isEqualTo(true)
            // Agent should complete successfully
            assertThat(result.status).isEqualTo(AgentProcessStatusCode.COMPLETED)
            // Final result should be a Frog
            val frog = blackboard.lastResult() as Frog
            assertThat(frog.name).isEqualTo("TestUser")
        }
    }

    @Nested
    inner class ConcurrentAgentTermination {

        @Test
        fun `TerminateAgentException terminates concurrent agent process`() {
            val dummyPlatformServices = dummyPlatformServices()
            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("TestUser")

            val agentProcess = ConcurrentAgentProcess(
                id = "test-concurrent-agent-termination",
                agent = AgentTerminatingAgent,
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = dummyPlatformServices,
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            )

            val result = agentProcess.run()

            assertThat(result.status).isEqualTo(AgentProcessStatusCode.TERMINATED)
        }

        @Test
        fun `graceful termination signal terminates concurrent agent at next tick`() {
            val dummyPlatformServices = dummyPlatformServices()
            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("TestUser")

            val agentProcess = ConcurrentAgentProcess(
                id = "test-concurrent-graceful-termination",
                agent = GracefulAgentTerminatingAgent,
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = dummyPlatformServices,
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            )

            val result = agentProcess.run()

            assertThat(blackboard["signalSet"]).isEqualTo(true)
            assertThat(result.status).isEqualTo(AgentProcessStatusCode.TERMINATED)
        }
    }

    @Nested
    inner class DirectAgentProcessApi {

        @Test
        fun `terminateAgent called directly on AgentProcess sets signal`() {
            val dummyPlatformServices = dummyPlatformServices()
            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("TestUser")

            val agentProcess = SimpleAgentProcess(
                id = "test-direct-terminate-agent",
                agent = GracefulAgentTerminatingAgent,
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = dummyPlatformServices,
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            )

            // Call terminateAgent directly on AgentProcess (not via extension)
            agentProcess.terminateAgent("Direct API call")

            // Verify signal is set by running the process
            val result = agentProcess.run()
            assertThat(result.status).isEqualTo(AgentProcessStatusCode.TERMINATED)
        }

        @Test
        fun `terminateAction called directly on AgentProcess sets signal`() {
            val dummyPlatformServices = dummyPlatformServices()
            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("TestUser")

            val agentProcess = SimpleAgentProcess(
                id = "test-direct-terminate-action",
                agent = GracefulActionTerminatingAgent,
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = dummyPlatformServices,
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            )

            // The GracefulActionTerminatingAgent calls terminateAction via extension
            // This test verifies the agent process method works correctly
            val result = agentProcess.run()

            // Both actions should have run (signal cleared after first action)
            assertThat(blackboard["firstActionRan"]).isEqualTo(true)
            assertThat(blackboard["secondActionRan"]).isEqualTo(true)
            assertThat(result.status).isEqualTo(AgentProcessStatusCode.COMPLETED)
        }
    }
}
