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
package com.embabel.agent.api.annotation.subagent

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.RunSubagent
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.Verbosity
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.integration.IntegrationTestUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import com.embabel.agent.core.Agent as CoreAgent

data class SubagentTaskInput(val content: String)
data class SubagentMiddle(val data: String)
data class SubagentTaskOutput(val result: String)

/**
 * Outer agent that delegates to a sub-agent via asSubProcess
 */
@Agent(description = "Outer agent via subprocess")
class OuterAgentViaSubprocessInvocation {

    @Action
    fun start(
        input: UserInput,
        context: ActionContext,
    ): SubagentTaskOutput {
        val agent = AgentMetadataReader().createAgentMetadata(SubAgent()) as CoreAgent
        return context.asSubProcess(SubagentTaskOutput::class.java, agent)
    }

    @Action
    @AchievesGoal(description = "All stages complete")
    fun done(taskOutput: SubagentTaskOutput): SubagentTaskOutput = taskOutput
}

@Agent(description = "Outer agent via instance return")
class OuterAgentViaAnnotatedAgentNestingReturn {

    @Action
    fun start(
        input: UserInput,
    ): SubagentTaskOutput = RunSubagent.fromAnnotatedInstance(SubAgent(), SubagentTaskOutput::class.java)

    @Action
    @AchievesGoal(description = "All stages complete")
    fun done(taskOutput: SubagentTaskOutput): SubagentTaskOutput = taskOutput
}

@Agent(description = "Outer agent via agent nesting return")
class OuterAgentViaAgentSubagentReturn {

    @Action
    fun start(
        input: UserInput,
    ): SubagentTaskOutput = RunSubagent.fromAnnotatedInstance(
        AgentMetadataReader().createAgentMetadata(SubAgent()) as com.embabel.agent.core.Agent,
        SubagentTaskOutput::class.java
    )

    @Action
    @AchievesGoal(description = "All stages complete")
    fun done(taskOutput: SubagentTaskOutput): SubagentTaskOutput = taskOutput
}

/**
 * Outer agent using reified runAnnotatedSubagent (annotated instance with inferred type)
 */
@Agent(description = "Outer agent via reified annotated subagent")
class OuterAgentViaReifiedAnnotatedSubagent {

    @Action
    fun start(
        input: UserInput,
    ): SubagentTaskOutput = RunSubagent.fromAnnotatedInstance(SubAgent())

    @Action
    @AchievesGoal(description = "All stages complete")
    fun done(taskOutput: SubagentTaskOutput): SubagentTaskOutput = taskOutput
}

/**
 * Outer agent using reified runSubagent (Agent instance with inferred type)
 */
@Agent(description = "Outer agent via reified agent subagent")
class OuterAgentViaReifiedAgentSubagent {

    @Action
    fun start(
        input: UserInput,
    ): SubagentTaskOutput {
        val agent = AgentMetadataReader().createAgentMetadata(SubAgent()) as CoreAgent
        return RunSubagent.instance(agent)
    }

    @Action
    @AchievesGoal(description = "All stages complete")
    fun done(taskOutput: SubagentTaskOutput): SubagentTaskOutput = taskOutput
}

/**
 * Inner sub-agent that performs a multi-step transformation
 */
@Agent(description = "Inner sub-agent")
class SubAgent {

    @Action
    fun stepZero(input: UserInput): SubagentTaskInput = SubagentTaskInput(input.content)

    @Action
    fun stepOne(input: SubagentTaskInput): SubagentMiddle = SubagentMiddle(input.content.uppercase())

    @Action
    @AchievesGoal(description = "Subflow complete")
    fun stepTwo(middle: SubagentMiddle): SubagentTaskOutput = SubagentTaskOutput("[${middle.data}]")
}


class SubagentExecutionTest {

    private val reader = AgentMetadataReader()

    @Nested
    inner class SubAgentTests {

        @Test
        fun `inner sub-agent executes through all steps`() {
            val agent = reader.createAgentMetadata(SubAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions().withVerbosity(Verbosity.DEFAULT.showPlanning()),
                mapOf("it" to UserInput("hello"))
            )
            val history = process.history.map { it.actionName }
            assertEquals(AgentProcessStatusCode.COMPLETED, process.status, "Agent should complete")
            assertEquals(3, history.size, "Should have 3 actions: $history")
            assertTrue(history.any { it.contains("stepZero") }, "Should have stepZero: $history")
            assertTrue(history.any { it.contains("stepOne") }, "Should have stepOne: $history")
            assertTrue(history.any { it.contains("stepTwo") }, "Should have stepTwo: $history")
        }

        @Test
        fun `inner sub-agent produces correct output`() {
            val agent = reader.createAgentMetadata(SubAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions(),
                mapOf("it" to UserInput("test"))
            )
            assertEquals(AgentProcessStatusCode.COMPLETED, process.status)
            val output = process.getValue("it", SubagentTaskOutput::class.java.name) as? SubagentTaskOutput
            assertNotNull(output, "Should have output on blackboard")
            assertEquals("[TEST]", output!!.result, "Output should be transformed through all stages")
        }
    }

    @Nested
    inner class OuterAgentWithSubprocessTests {

        @Test
        fun `outer agent metadata is valid`() {
            val agentMetadata = reader.createAgentMetadata(OuterAgentViaSubprocessInvocation())
            assertNotNull(agentMetadata, "Agent metadata should not be null")
        }

        @Test
        fun `outer agent with subprocess has correct structure`() {
            val agent = reader.createAgentMetadata(OuterAgentViaSubprocessInvocation()) as CoreAgent
            assertNotNull(agent, "Agent should not be null")
            assertTrue(agent.actions.any { it.name.contains("start") }, "Should have start action")
            assertTrue(agent.actions.any { it.name.contains("done") }, "Should have done action")
        }

        fun testOuterAgentExecutesSteps(instance: Any) {
            val agent = reader.createAgentMetadata(instance) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions().withVerbosity(Verbosity.DEFAULT.showPlanning()),
                mapOf("it" to UserInput("subprocess test"))
            )
            val history = process.history.map { it.actionName }
            assertEquals(AgentProcessStatusCode.COMPLETED, process.status, "Agent should complete")
            assertTrue(history.any { it.contains("start") }, "Should have start action: $history")
            assertTrue(history.any { it.contains("done") }, "Should have done action: $history")
        }

        fun testWithOuterAgentInstance(instance: Any) {
            val agent = reader.createAgentMetadata(instance) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions(),
                mapOf("it" to UserInput("hello"))
            )
            assertEquals(AgentProcessStatusCode.COMPLETED, process.status)
            val output = process.getValue("it", SubagentTaskOutput::class.java.name) as? SubagentTaskOutput
            assertNotNull(output, "Should have output on blackboard")
            assertEquals("[HELLO]", output!!.result, "Output should be transformed by inner sub-agent")
        }

        @Test
        fun `outer agent produces correct output via inner subagent`() {
            testWithOuterAgentInstance(OuterAgentViaSubprocessInvocation())
        }

        @Test
        fun `outer agent produces correct output via annotated subagent return`() {
            testWithOuterAgentInstance(OuterAgentViaAnnotatedAgentNestingReturn())
        }

        @Test
        fun `outer agent produces correct output via subagent return`() {
            testWithOuterAgentInstance(OuterAgentViaAgentSubagentReturn())
        }

        @Test
        fun `outer agent via subprocess executes all steps`() {
            testOuterAgentExecutesSteps(OuterAgentViaSubprocessInvocation())
        }

        @Test
        fun `outer agent via annotated agent instance return executes all steps`() {
            testOuterAgentExecutesSteps(OuterAgentViaAnnotatedAgentNestingReturn())
        }

        @Test
        fun `outer agent via agent instance return executes all steps`() {
            testOuterAgentExecutesSteps(OuterAgentViaAgentSubagentReturn())
        }

        @Test
        fun `outer agent produces correct output via reified annotated subagent`() {
            testWithOuterAgentInstance(OuterAgentViaReifiedAnnotatedSubagent())
        }

        @Test
        fun `outer agent produces correct output via reified agent subagent`() {
            testWithOuterAgentInstance(OuterAgentViaReifiedAgentSubagent())
        }

        @Test
        fun `outer agent via reified annotated subagent executes all steps`() {
            testOuterAgentExecutesSteps(OuterAgentViaReifiedAnnotatedSubagent())
        }

        @Test
        fun `outer agent via reified agent subagent executes all steps`() {
            testOuterAgentExecutesSteps(OuterAgentViaReifiedAgentSubagent())
        }
    }
}
