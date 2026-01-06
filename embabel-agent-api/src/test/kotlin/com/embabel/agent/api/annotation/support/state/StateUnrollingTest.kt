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

import com.embabel.agent.api.annotation.*
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.core.Agent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

data class Input(val content: String)
data class Output(val result: String)
data class IntermediateResult(val data: String)

/**
 * Simple agent with a single @State class containing actions
 */
@com.embabel.agent.api.annotation.Agent(description = "Simple state agent")
class SimpleStateAgent {

    @Action
    fun process(input: Input): ProcessState = ProcessState(input.content)

    @State
    data class ProcessState(val data: String) {

        @AchievesGoal(description = "Processing complete")
        @Action
        fun complete(): Output = Output(data)
    }
}

/**
 * Agent with @State interface and multiple implementations
 */
@com.embabel.agent.api.annotation.Agent(description = "Polymorphic state agent")
class PolymorphicStateAgent {

    @Action
    fun classify(input: Input): Outcome =
        if (input.content.contains("success")) SuccessOutcome(input.content)
        else FailureOutcome(input.content, "Failed to process")

    @State
    sealed interface Outcome

    @State
    data class SuccessOutcome(val data: String) : Outcome {

        @AchievesGoal(description = "Success outcome processed")
        @Action
        fun handleSuccess(): Output = Output("Success: $data")
    }

    @State
    data class FailureOutcome(val data: String, val reason: String) : Outcome {

        @AchievesGoal(description = "Failure outcome processed")
        @Action
        fun handleFailure(): Output = Output("Failure: $reason")
    }
}

/**
 * Agent with nested @State classes (state returns state)
 */
@com.embabel.agent.api.annotation.Agent(description = "Nested state agent")
class NestedStateAgent {

    @Action
    fun start(input: Input): FirstState = FirstState(input.content)

    @State
    data class FirstState(val data: String) {

        @Action
        fun processFirst(): SecondState = SecondState(data.uppercase())
    }

    @State
    data class SecondState(val data: String) {

        @AchievesGoal(description = "All states processed")
        @Action
        fun complete(): Output = Output(data)
    }
}

/**
 * Agent demonstrating state branching - one state can lead to multiple other states
 */
@com.embabel.agent.api.annotation.Agent(description = "Branching state agent")
class BranchingStateAgent {

    @Action
    fun initialize(input: Input): DecisionState = DecisionState(input.content)

    @State
    data class DecisionState(val data: String) {

        @Action
        fun decide(): BranchOutcome =
            if (data.length > 10) LongBranch(data)
            else ShortBranch(data)
    }

    @State
    sealed interface BranchOutcome

    @State
    data class LongBranch(val data: String) : BranchOutcome {

        @AchievesGoal(description = "Long content processed")
        @Action
        fun processLong(): Output = Output("Long: ${data.take(10)}...")
    }

    @State
    data class ShortBranch(val data: String) : BranchOutcome {

        @AchievesGoal(description = "Short content processed")
        @Action
        fun processShort(): Output = Output("Short: $data")
    }
}

class StateUnrollingTest {

    private val reader = AgentMetadataReader()

    @Nested
    inner class SimpleState {

        @Test
        fun `unrolls actions from simple state class`() {
            val agent = reader.createAgentMetadata(SimpleStateAgent()) as Agent
            val actionNames = agent.actions.map { it.name }
            assertTrue(actionNames.any { it.contains("process") }, "Should have process action: $actionNames")
            assertTrue(actionNames.any { it.contains("complete") }, "Should have complete action: $actionNames")
        }

        @Test
        fun `extracts goal from state action with AchievesGoal`() {
            val agent = reader.createAgentMetadata(SimpleStateAgent()) as Agent
            val goalNames = agent.goals.map { it.name }
            assertTrue(goalNames.isNotEmpty(), "Should have goals: $goalNames")
            assertTrue(goalNames.any { it.contains("complete") }, "Should have complete goal: $goalNames")
        }

        @Test
        fun `state action has state class as input`() {
            val agent = reader.createAgentMetadata(SimpleStateAgent()) as Agent
            val completeAction = agent.actions.find { it.name.contains("complete") }
            assertNotNull(completeAction, "Should have complete action")
            val inputTypes = completeAction!!.inputs.map { it.type }
            assertTrue(
                inputTypes.any { it.contains("ProcessState") },
                "Complete action should have ProcessState as input: $inputTypes"
            )
        }
    }

    @Nested
    inner class PolymorphicState {

        @Test
        fun `unrolls actions from all state implementations`() {
            val agent = reader.createAgentMetadata(PolymorphicStateAgent()) as Agent
            val actionNames = agent.actions.map { it.name }
            assertTrue(actionNames.any { it.contains("classify") }, "Should have classify action: $actionNames")
            assertTrue(actionNames.any { it.contains("handleSuccess") }, "Should have handleSuccess action: $actionNames")
            assertTrue(actionNames.any { it.contains("handleFailure") }, "Should have handleFailure action: $actionNames")
        }

        @Test
        fun `extracts goals from all state implementations`() {
            val agent = reader.createAgentMetadata(PolymorphicStateAgent()) as Agent
            val goalNames = agent.goals.map { it.name }
            assertEquals(2, goalNames.size, "Should have 2 goals (one for each branch): $goalNames")
        }

        @Test
        fun `classify action effects include all possible outcomes`() {
            val agent = reader.createAgentMetadata(PolymorphicStateAgent()) as Agent
            val classifyAction = agent.actions.find { it.name.contains("classify") }
            assertNotNull(classifyAction, "Should have classify action")
            val effects = classifyAction!!.effects.keys
            assertTrue(
                effects.any { it.contains("Outcome") || it.contains("SuccessOutcome") || it.contains("FailureOutcome") },
                "Classify effects should include Outcome types: $effects"
            )
        }
    }

    @Nested
    inner class NestedState {

        @Test
        fun `unrolls actions from nested states recursively`() {
            val agent = reader.createAgentMetadata(NestedStateAgent()) as Agent
            val actionNames = agent.actions.map { it.name }
            assertTrue(actionNames.any { it.contains("start") }, "Should have start action: $actionNames")
            assertTrue(actionNames.any { it.contains("processFirst") }, "Should have processFirst action: $actionNames")
            assertTrue(actionNames.any { it.contains("complete") }, "Should have complete action: $actionNames")
        }

        @Test
        fun `extracts goal only from terminal state`() {
            val agent = reader.createAgentMetadata(NestedStateAgent()) as Agent
            val goalNames = agent.goals.map { it.name }
            assertEquals(1, goalNames.size, "Should have exactly 1 goal: $goalNames")
            assertTrue(goalNames.any { it.contains("complete") }, "Goal should be 'complete': $goalNames")
        }
    }

    @Nested
    inner class BranchingState {

        @Test
        fun `unrolls actions from branching states`() {
            val agent = reader.createAgentMetadata(BranchingStateAgent()) as Agent
            val actionNames = agent.actions.map { it.name }
            assertTrue(actionNames.any { it.contains("initialize") }, "Should have initialize action: $actionNames")
            assertTrue(actionNames.any { it.contains("decide") }, "Should have decide action: $actionNames")
            assertTrue(actionNames.any { it.contains("processLong") }, "Should have processLong action: $actionNames")
            assertTrue(actionNames.any { it.contains("processShort") }, "Should have processShort action: $actionNames")
        }

        @Test
        fun `extracts goals from both branches`() {
            val agent = reader.createAgentMetadata(BranchingStateAgent()) as Agent
            val goalNames = agent.goals.map { it.name }
            assertEquals(2, goalNames.size, "Should have 2 goals (one for each branch): $goalNames")
        }

        @Test
        fun `decision action effects include both branch outcomes`() {
            val agent = reader.createAgentMetadata(BranchingStateAgent()) as Agent
            val decideAction = agent.actions.find { it.name.contains("decide") }
            assertNotNull(decideAction, "Should have decide action")
            val effects = decideAction!!.effects.keys
            assertTrue(
                effects.any { it.contains("LongBranch") || it.contains("ShortBranch") || it.contains("BranchOutcome") },
                "Decide effects should include branch types: $effects"
            )
        }
    }
}
