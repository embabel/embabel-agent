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
package com.embabel.agent.api.annotation.support

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.support.MultiTransformationAction
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.test.integration.IntegrationTestUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import com.embabel.agent.core.Agent as CoreAgent

/**
 * Domain types for purge testing
 */
data class InputData(val value: String)
data class IntermediateData(val processed: String)
data class FinalData(val result: String)

/**
 * Agent that does NOT use purge - blackboard accumulates all intermediate values
 */
@Agent(description = "Process data without purging blackboard")
class NonPurgingAgent {

    @Action
    fun processInput(input: InputData): IntermediateData {
        return IntermediateData("processed: ${input.value}")
    }

    @AchievesGoal(description = "Produce final data")
    @Action
    fun produceOutput(intermediate: IntermediateData): FinalData {
        return FinalData("final: ${intermediate.processed}")
    }
}

/**
 * Agent that uses clearBlackboard=true on an action - blackboard is cleared after that action
 */
@Agent(description = "Process data with clearBlackboard after first action")
class PurgingAgent {

    @Action(clearBlackboard = true)
    fun processInput(input: InputData): IntermediateData {
        return IntermediateData("processed: ${input.value}")
    }

    @AchievesGoal(description = "Produce final data")
    @Action
    fun produceOutput(intermediate: IntermediateData): FinalData {
        return FinalData("final: ${intermediate.processed}")
    }
}

/**
 * Agent that uses clearBlackboard=true on the final action.
 * NOTE: clearBlackboard on a goal-achieving action clears hasRun conditions,
 * which may cause goal satisfaction issues. This is intended behavior -
 * clearBlackboard is meant for intermediate actions, not final ones.
 */
@Agent(description = "Process data with clearBlackboard on final action")
class FinalPurgingAgent {

    @Action
    fun processInput(input: InputData): IntermediateData {
        return IntermediateData("processed: ${input.value}")
    }

    @AchievesGoal(description = "Produce final data")
    @Action(clearBlackboard = true)
    fun produceOutput(intermediate: IntermediateData): FinalData {
        return FinalData("final: ${intermediate.processed}")
    }
}

/**
 * Agent with multi-step processing where clearBlackboard happens in the middle,
 * followed by a final goal-achieving action without clearBlackboard.
 */
@Agent(description = "Multi-step processing with clearBlackboard in middle")
class MultiStepPurgingAgent {

    data class Step1Output(val step1: String)
    data class Step2Output(val step2: String)
    data class Step3Output(val step3: String)

    @Action
    fun step1(input: InputData): Step1Output {
        return Step1Output("step1: ${input.value}")
    }

    @Action(clearBlackboard = true)
    fun step2ClearBlackboard(s1: Step1Output): Step2Output {
        return Step2Output("step2: ${s1.step1}")
    }

    @AchievesGoal(description = "Final step")
    @Action
    fun step3(s2: Step2Output): Step3Output {
        return Step3Output("step3: ${s2.step2}")
    }
}

class ActionPurgeTest {

    private val reader = AgentMetadataReader()

    @Nested
    inner class MetadataReading {

        @Test
        fun `action without clearBlackboard has clearBlackboard false`() {
            val agent = reader.createAgentMetadata(NonPurgingAgent()) as CoreAgent
            val processInputAction = agent.actions.find { it.name.contains("processInput") }
            assertNotNull(processInputAction)
            assertTrue(processInputAction is MultiTransformationAction<*>)
            assertFalse((processInputAction as MultiTransformationAction<*>).clearBlackboard)
        }

        @Test
        fun `action with clearBlackboard true has clearBlackboard true`() {
            val agent = reader.createAgentMetadata(PurgingAgent()) as CoreAgent
            val processInputAction = agent.actions.find { it.name.contains("processInput") }
            assertNotNull(processInputAction)
            assertTrue(processInputAction is MultiTransformationAction<*>)
            assertTrue((processInputAction as MultiTransformationAction<*>).clearBlackboard)
        }

        @Test
        fun `multiple actions can have different clearBlackboard settings`() {
            val agent = reader.createAgentMetadata(FinalPurgingAgent()) as CoreAgent

            val processInputAction = agent.actions.find { it.name.contains("processInput") }
            val produceOutputAction = agent.actions.find { it.name.contains("produceOutput") }

            assertNotNull(processInputAction)
            assertNotNull(produceOutputAction)

            assertFalse((processInputAction as MultiTransformationAction<*>).clearBlackboard)
            assertTrue((produceOutputAction as MultiTransformationAction<*>).clearBlackboard)
        }
    }

    @Nested
    inner class ExecutionWithoutPurge {

        @Test
        fun `blackboard retains all intermediate values without purge`() {
            val agent = reader.createAgentMetadata(NonPurgingAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions(),
                mapOf("it" to InputData("test"))
            )
            assertEquals(AgentProcessStatusCode.COMPLETED, process.status)

            // Without purge, all types should be available
            val inputData = process.getValue("it", InputData::class.java.name)
            val intermediateData = process.getValue("it", IntermediateData::class.java.name)
            val finalData = process.getValue("it", FinalData::class.java.name)

            assertNotNull(inputData, "InputData should still be on blackboard")
            assertNotNull(intermediateData, "IntermediateData should still be on blackboard")
            assertNotNull(finalData, "FinalData should be on blackboard")
        }
    }

    @Nested
    inner class ExecutionWithPurge {

        @Test
        fun `purge clears blackboard after action - only output remains`() {
            val agent = reader.createAgentMetadata(PurgingAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions(),
                mapOf("it" to InputData("test"))
            )
            assertEquals(AgentProcessStatusCode.COMPLETED, process.status)

            // After purging action, InputData should be gone
            val inputData = process.getValue("it", InputData::class.java.name)
            assertNull(inputData, "InputData should be purged from blackboard")

            // IntermediateData was the output of purging action, then consumed by produceOutput
            // FinalData should be present
            val finalData = process.getValue("it", FinalData::class.java.name)
            assertNotNull(finalData, "FinalData should be on blackboard")
            assertEquals("final: processed: test", (finalData as FinalData).result)
        }

        @Test
        fun `multi-step agent purges all accumulated data at purge point`() {
            val agent = reader.createAgentMetadata(MultiStepPurgingAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions(),
                mapOf("it" to InputData("start"))
            )
            assertEquals(AgentProcessStatusCode.COMPLETED, process.status)

            // After step2ClearBlackboard with clearBlackboard=true, InputData and Step1Output should be gone
            val inputData = process.getValue("it", InputData::class.java.name)
            val step1 = process.getValue("it", MultiStepPurgingAgent.Step1Output::class.java.name)
            val step3 = process.getValue("it", MultiStepPurgingAgent.Step3Output::class.java.name)

            assertNull(inputData, "InputData should be purged")
            assertNull(step1, "Step1Output should be purged")
            assertNotNull(step3, "Step3Output should be present (final output)")
            assertEquals(
                "step3: step2: step1: start",
                (step3 as MultiStepPurgingAgent.Step3Output).step3
            )
        }
    }

    @Nested
    inner class ExecutionFlowVerification {

        @Test
        fun `agent completes successfully with purge in middle of flow`() {
            val agent = reader.createAgentMetadata(PurgingAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions(),
                mapOf("it" to InputData("hello"))
            )

            assertEquals(AgentProcessStatusCode.COMPLETED, process.status)
            val history = process.history.map { it.actionName }
            println("History: $history")

            assertTrue(history.any { it.contains("processInput") }, "Should run processInput")
            assertTrue(history.any { it.contains("produceOutput") }, "Should run produceOutput after purge")
        }

        @Test
        fun `action history is preserved despite purge`() {
            val agent = reader.createAgentMetadata(MultiStepPurgingAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions(),
                mapOf("it" to InputData("data"))
            )

            assertEquals(AgentProcessStatusCode.COMPLETED, process.status)
            val history = process.history.map { it.actionName }
            println("History: $history")

            // History should show all actions even though blackboard was cleared
            assertTrue(history.any { it.contains("step1") }, "History should contain step1")
            assertTrue(history.any { it.contains("step2ClearBlackboard") }, "History should contain step2ClearBlackboard")
            assertTrue(history.any { it.contains("step3") }, "History should contain step3")
            assertEquals(3, history.size, "Should have exactly 3 actions in history")
        }
    }
}
