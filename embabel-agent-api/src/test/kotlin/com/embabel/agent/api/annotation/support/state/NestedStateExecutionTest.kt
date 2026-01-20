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
package com.embabel.agent.api.annotation.support.state

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.State
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.Verbosity
import com.embabel.agent.test.integration.IntegrationTestUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import com.embabel.agent.core.Agent as CoreAgent

data class TaskInput(val content: String)
data class TaskOutput(val result: String)

/**
 * Agent with linear nested states: Input -> State1 -> State2 -> Output
 */
@Agent(description = "Linear nested state agent")
class LinearNestedStateAgent {

    @Action
    fun start(input: TaskInput): FirstStage = FirstStage(input.content)

    @State
    data class FirstStage(val data: String) {
        @Action
        fun processFirst(): SecondStage = SecondStage(data.uppercase())
    }

    @State
    data class SecondStage(val data: String) {
        @Action
        fun processSecond(): ThirdStage = ThirdStage("[$data]")
    }

    @State
    data class ThirdStage(val data: String) {
        @AchievesGoal(description = "All stages complete")
        @Action
        fun complete(): TaskOutput = TaskOutput(data)
    }
}

/**
 * Agent with branching states that converge: Input -> Decision -> (BranchA | BranchB) -> Final -> Output
 */
@Agent(description = "Branching and converging state agent")
class BranchingConvergingStateAgent {

    @Action
    fun start(input: TaskInput): DecisionPoint = DecisionPoint(input.content)

    @State
    data class DecisionPoint(val data: String) {
        @Action
        fun decide(): Branch =
            if (data.length > 5) BranchA(data) else BranchB(data)
    }

    @State
    sealed interface Branch

    @State
    data class BranchA(val data: String) : Branch {
        @Action
        fun processA(): FinalStage = FinalStage("A:${data.uppercase()}")
    }

    @State
    data class BranchB(val data: String) : Branch {
        @Action
        fun processB(): FinalStage = FinalStage("B:${data.lowercase()}")
    }

    @State
    data class FinalStage(val data: String) {
        @AchievesGoal(description = "Processing complete")
        @Action
        fun complete(): TaskOutput = TaskOutput(data)
    }
}

/**
 * Agent with a looping state that eventually terminates
 */
@Agent(description = "Looping state agent")
class LoopingStateAgent {

    companion object {
        private var iterationCount = 0

        fun resetIterationCount() {
            iterationCount = 0
        }
    }

    @Action
    fun start(input: TaskInput): ProcessingState = ProcessingState(input.content, 0)

    @State
    sealed interface LoopOutcome

    @State
    data class ProcessingState(val data: String, val iteration: Int) : LoopOutcome {
        @Action(clearBlackboard = true)
        fun process(): LoopOutcome {
            val newIteration = iteration + 1
            iterationCount++
            return if (newIteration >= 3) {
                DoneState("$data (iterations: $newIteration)")
            } else {
                ProcessingState("$data+", newIteration)
            }
        }
    }

    @State
    data class DoneState(val data: String) : LoopOutcome {
        @AchievesGoal(description = "Loop completed")
        @Action
        fun complete(): TaskOutput = TaskOutput(data)
    }
}

class NestedStateExecutionTest {

    private val reader = AgentMetadataReader()

    @Nested
    inner class LinearNestedStates {

        @Test
        fun `executes through linear nested states`() {
            val agent = reader.createAgentMetadata(LinearNestedStateAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions().withVerbosity(Verbosity.DEFAULT.showPlanning()),
                mapOf("it" to TaskInput("hello"))
            )
            println("Status: ${process.status}")
            val history = process.history.map { it.actionName }
            println("History: $history")
            assertEquals(AgentProcessStatusCode.COMPLETED, process.status, "Agent should complete")
            assertEquals(4, history.size, "Should have 4 actions: $history")
            assertTrue(history.any { it.contains("start") }, "Should have start: $history")
            assertTrue(history.any { it.contains("processFirst") }, "Should have processFirst: $history")
            assertTrue(history.any { it.contains("processSecond") }, "Should have processSecond: $history")
            assertTrue(history.any { it.contains("complete") }, "Should have complete: $history")
        }

        @Test
        fun `produces correct output through state chain`() {
            val agent = reader.createAgentMetadata(LinearNestedStateAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions(),
                mapOf("it" to TaskInput("test"))
            )
            assertEquals(AgentProcessStatusCode.COMPLETED, process.status)
            val output = process.getValue("it", TaskOutput::class.java.name) as? TaskOutput
            assertNotNull(output, "Should have output on blackboard")
            assertEquals("[TEST]", output!!.result, "Output should be transformed through all stages")
        }
    }

    @Nested
    inner class BranchingStates {

        @Test
        fun `executes branch A for long input`() {
            val agent = reader.createAgentMetadata(BranchingConvergingStateAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions().withVerbosity(Verbosity.DEFAULT.showPlanning()),
                mapOf("it" to TaskInput("longInput"))
            )
            println("Status: ${process.status}")
            val history = process.history.map { it.actionName }
            println("History: $history")
            assertEquals(AgentProcessStatusCode.COMPLETED, process.status, "Agent should complete")
            assertTrue(history.any { it.contains("processA") }, "Should take branch A: $history")
            assertFalse(history.any { it.contains("processB") }, "Should not take branch B: $history")
            val output = process.getValue("it", TaskOutput::class.java.name) as? TaskOutput
            assertNotNull(output)
            assertTrue(output!!.result.startsWith("A:"), "Output should be from branch A: ${output.result}")
        }

        @Test
        fun `executes branch B for short input`() {
            val agent = reader.createAgentMetadata(BranchingConvergingStateAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions().withVerbosity(Verbosity.DEFAULT.showPlanning()),
                mapOf("it" to TaskInput("hi"))
            )
            println("Status: ${process.status}")
            val history = process.history.map { it.actionName }
            println("History: $history")
            assertEquals(AgentProcessStatusCode.COMPLETED, process.status, "Agent should complete")
            assertTrue(history.any { it.contains("processB") }, "Should take branch B: $history")
            assertFalse(history.any { it.contains("processA") }, "Should not take branch A: $history")
            val output = process.getValue("it", TaskOutput::class.java.name) as? TaskOutput
            assertNotNull(output)
            assertTrue(output!!.result.startsWith("B:"), "Output should be from branch B: ${output.result}")
        }

        @Test
        fun `both branches converge to same final state`() {
            val agent = reader.createAgentMetadata(BranchingConvergingStateAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val processA = ap.runAgentFrom(agent, ProcessOptions(), mapOf("it" to TaskInput("longInput")))
            val processB = ap.runAgentFrom(agent, ProcessOptions(), mapOf("it" to TaskInput("hi")))
            assertEquals(AgentProcessStatusCode.COMPLETED, processA.status)
            assertEquals(AgentProcessStatusCode.COMPLETED, processB.status)
            val historyA = processA.history.map { it.actionName }
            val historyB = processB.history.map { it.actionName }
            assertTrue(historyA.any { it.contains("FinalStage.complete") }, "Branch A should reach FinalStage.complete: $historyA")
            assertTrue(historyB.any { it.contains("FinalStage.complete") }, "Branch B should reach FinalStage.complete: $historyB")
        }
    }

    @Nested
    inner class LoopingStates {

        @Test
        fun `executes loop until termination condition`() {
            LoopingStateAgent.resetIterationCount()
            val agent = reader.createAgentMetadata(LoopingStateAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions().withVerbosity(Verbosity.DEFAULT.showPlanning()),
                mapOf("it" to TaskInput("start"))
            )
            println("Status: ${process.status}")
            val history = process.history.map { it.actionName }
            println("History: $history")
            assertEquals(AgentProcessStatusCode.COMPLETED, process.status, "Agent should complete")
            val processCount = history.count { it.contains("ProcessingState.process") }
            assertEquals(3, processCount, "Should have processed 3 times: $history")
            assertTrue(history.any { it.contains("complete") }, "Should have completed: $history")
        }

        @Test
        fun `loop produces accumulated output`() {
            LoopingStateAgent.resetIterationCount()
            val agent = reader.createAgentMetadata(LoopingStateAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions(),
                mapOf("it" to TaskInput("x"))
            )
            assertEquals(AgentProcessStatusCode.COMPLETED, process.status)
            val output = process.getValue("it", TaskOutput::class.java.name) as? TaskOutput
            assertNotNull(output, "Should have output")
            assertTrue(output!!.result.contains("iterations: 3"), "Output should show 3 iterations: ${output.result}")
            assertTrue(output.result.contains("x++"), "Output should show accumulated data: ${output.result}")
        }
    }

    @Nested
    inner class StateScopingTests {

        @Test
        fun `previous state is hidden when entering new state`() {
            val agent = reader.createAgentMetadata(LinearNestedStateAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions().withVerbosity(Verbosity.DEFAULT.showPlanning()),
                mapOf("it" to TaskInput("test"))
            )
            assertEquals(AgentProcessStatusCode.COMPLETED, process.status)
            // Verify only final state actions ran, not duplicates from previous states
            val history = process.history.map { it.actionName }
            assertEquals(1, history.count { it.contains("processFirst") }, "processFirst should run exactly once: $history")
            assertEquals(1, history.count { it.contains("processSecond") }, "processSecond should run exactly once: $history")
        }

        @Test
        fun `context is preserved across state transitions`() {
            val agent = reader.createAgentMetadata(ContextPreservingAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions().withVerbosity(Verbosity.DEFAULT.showPlanning()),
                mapOf("it" to TaskInput("test"), "context" to SharedContext("preserved-value"))
            )
            assertEquals(AgentProcessStatusCode.COMPLETED, process.status, "Agent should complete")
            val output = process.getValue("it", ContextOutput::class.java.name) as? ContextOutput
            assertNotNull(output, "Should have output")
            assertEquals("preserved-value", output!!.contextValue, "Context should be preserved across states")
        }

        @Test
        fun `only current state actions are available after transition`() {
            val agent = reader.createAgentMetadata(StateScopingAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions().withVerbosity(Verbosity.DEFAULT.showPlanning()),
                mapOf("it" to TaskInput("test"))
            )
            assertEquals(AgentProcessStatusCode.COMPLETED, process.status, "Agent should complete")
            val history = process.history.map { it.actionName }
            // StateA.actionA should run once, then transition to StateB
            // StateA.actionA should NOT run again because StateA is hidden
            assertEquals(1, history.count { it.contains("StateA.actionA") }, "StateA.actionA should run exactly once: $history")
            assertEquals(1, history.count { it.contains("StateB.actionB") }, "StateB.actionB should run exactly once: $history")
        }
    }
}

// Test data classes for state scoping tests
data class SharedContext(val value: String)
data class ContextOutput(val contextValue: String)

/**
 * Agent that verifies context is preserved across state transitions
 */
@Agent(description = "Context preserving agent")
class ContextPreservingAgent {

    @Action
    fun start(input: TaskInput): ContextState = ContextState()

    @State
    class ContextState {
        @AchievesGoal(description = "Context preserved")
        @Action
        fun useContext(context: SharedContext): ContextOutput = ContextOutput(context.value)
    }
}

/**
 * Agent that verifies only current state's actions are available
 */
@Agent(description = "State scoping agent")
class StateScopingAgent {

    @Action
    fun start(input: TaskInput): StateA = StateA()

    @State
    class StateA {
        @Action
        fun actionA(): StateB = StateB()
    }

    @State
    class StateB {
        @AchievesGoal(description = "Complete")
        @Action
        fun actionB(): TaskOutput = TaskOutput("done")
    }
}
