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
package com.embabel.agent.api.annotation.support

import com.embabel.agent.api.dsl.Frog
import com.embabel.agent.core.*
import com.embabel.agent.core.hitl.ConfirmationRequest
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.event.AgenticEventListener.Companion.DevNull
import com.embabel.agent.spi.LlmInteraction
import com.embabel.agent.spi.LlmOperations
import com.embabel.agent.spi.PlatformServices
import com.embabel.agent.testing.IntegrationTestUtils
import com.embabel.common.ai.model.DefaultModelSelectionCriteria
import com.embabel.plan.goap.ConditionDetermination
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import com.embabel.agent.core.Agent as CoreAgent


class AgentMetadataReaderActionTest {

    @Test
    fun `no actions`() {
        val reader = TestAgentMetadataReader.create()
        val metadata = reader.createAgentMetadata(OneGoalOnly())
        assertNull(metadata) // The metadata should be null! Due to the NoPathToCompletionValidator
    }

    @Test
    fun `one action only`() {
        val reader = TestAgentMetadataReader.create()
        val metadata = reader.createAgentMetadata(OneTransformerActionOnly())
        assertNotNull(metadata)
        assertEquals(1, metadata!!.actions.size)
        val action = metadata.actions.single()
        assertEquals(1, action.inputs.size, "Should have 1 input")
        assertEquals(UserInput::class.java.name, action.inputs.single().type)
        assertEquals(1, action.outputs.size, "Should have 1 output")
        assertEquals(
            PersonWithReverseTool::class.java.name,
            action.outputs.single().type,
            "Output name must match",
        )
    }

    @Test
    fun `one action referencing condition by name`() {
        val reader = TestAgentMetadataReader.create()
        val metadata = reader.createAgentMetadata(OneTransformerActionReferencingConditionByName())
        assertNotNull(metadata)
        assertEquals(1, metadata!!.actions.size)
        val action = metadata.actions.single()
        assertEquals(1, action.inputs.size, "Should have 1 input")
        assertEquals(UserInput::class.java.name, action.inputs.single().type)
        assertEquals(1, action.outputs.size, "Should have 1 output")
        assertEquals(
            PersonWithReverseTool::class.java.name,
            action.outputs.single().type,
            "Output name must match",
        )
        assertEquals(
            ConditionDetermination.TRUE,
            action.preconditions["it:${UserInput::class.qualifiedName}"],
            "Should have input precondition",
        )
        assertEquals(
            ConditionDetermination.TRUE,
            action.preconditions["condition1"],
            "Should have custom precondition",
        )
    }

    @Test
    fun `one action with custom tool group`() {
        val reader = TestAgentMetadataReader.create()
        val metadata = reader.createAgentMetadata(OneTransformerActionWithCustomToolGroupOnly())
        assertNotNull(metadata)
        assertEquals(1, metadata!!.actions.size)
        val action = metadata.actions.single()
        assertEquals(1, action.inputs.size, "Should have 1 input")
        assertEquals(UserInput::class.java.name, action.inputs.single().type)
        assertEquals(1, action.outputs.size, "Should have 1 output")
        assertEquals(
            PersonWithReverseTool::class.java.name,
            action.outputs.single().type,
            "Output name must match",
        )
        assertEquals(1, action.toolGroups.size)
        assertEquals("magic", action.toolGroups.single())
    }

    @Test
    fun `one action with custom tool group taking interface`() {
        val reader = TestAgentMetadataReader.create()
        val metadata = reader.createAgentMetadata(OneTransformerActionTakingInterfaceWithCustomToolGroupOnly())
        assertNotNull(metadata)
        assertEquals(1, metadata!!.actions.size)
        val action = metadata.actions.single()
        assertEquals(1, action.inputs.size, "Should have 1 input")
        assertEquals(PersonWithReverseTool::class.java.name, action.inputs.single().type)
        assertEquals(1, action.outputs.size, "Should have 1 output")
        assertEquals(
            Frog::class.java.name,
            action.outputs.single().type,
            "Output name must match",
        )
        assertEquals(1, action.toolGroups.size)
        assertEquals("magic", action.toolGroups.single())
        val ap = IntegrationTestUtils.dummyAgentPlatform()
        val agentProcess =
            ap.runAgentFrom(metadata as CoreAgent, ProcessOptions(), mapOf("it" to PersonWithReverseTool("John Doe")))
        assertEquals(AgentProcessStatusCode.COMPLETED, agentProcess.status)
        assertEquals(Frog("John Doe"), agentProcess.lastResult())
    }

    @Test
    fun `custom tool group is available through operation context`() {
        val reader = TestAgentMetadataReader.create()
        val metadata =
            reader.createAgentMetadata(OneTransformerActionTakingInterfaceWithExpectationCustomToolGroupOnly())
        assertNotNull(metadata)
        assertEquals(1, metadata!!.actions.size)
        val action = metadata.actions.single()
        assertEquals(1, action.inputs.size, "Should have 1 input")
        assertEquals(PersonWithReverseTool::class.java.name, action.inputs.single().type)
        assertEquals(1, action.outputs.size, "Should have 1 output")
        assertEquals(
            Frog::class.java.name,
            action.outputs.single().type,
            "Output name must match",
        )
        assertEquals(1, action.toolGroups.size)
        assertEquals("magic", action.toolGroups.single())
        val ap = IntegrationTestUtils.dummyAgentPlatform()
        val agentProcess =
            ap.runAgentFrom(metadata as CoreAgent, ProcessOptions(), mapOf("it" to PersonWithReverseTool("John Doe")))
        assertEquals(AgentProcessStatusCode.COMPLETED, agentProcess.status)
        assertEquals(Frog("John Doe"), agentProcess.lastResult())
    }

    @Test
    fun `one action with 2 args only`() {
        val reader = TestAgentMetadataReader.create()
        val metadata = reader.createAgentMetadata(AgentWithOneTransformerActionWith2ArgsOnly())
        assertNotNull(metadata)
        assertEquals(1, metadata!!.actions.size)
        val action = metadata.actions.single()
        assertEquals(2, action.inputs.size, "Should have 2 inputs")
        assertEquals(1, action.outputs.size, "Should have 1 output")
        assertTrue(action.inputs.any { it.type == UserInput::class.java.name })
        assertTrue(action.inputs.any { it.type == Task::class.java.name })
        assertEquals(
            PersonWithReverseTool::class.java.name,
            action.outputs.single().type,
            "Output name must match"
        )
        assertEquals(IoBinding.DEFAULT_BINDING, action.outputs.single().name)
    }

    @Test
    fun `transformer action invocation`() {
        val reader = TestAgentMetadataReader.create()
        val metadata = reader.createAgentMetadata(OneTransformerActionOnly())
        assertNotNull(metadata)
        assertEquals(1, metadata!!.actions.size)
        val action = metadata.actions.first()
        val agent = mockk<CoreAgent>()
        every { agent.domainTypes } returns listOf(PersonWithReverseTool::class.java, UserInput::class.java)
        val mockAgentProcess = mockk<AgentProcess>()
        every { mockAgentProcess.agent } returns agent
        val mockPlatformServices = mockk<PlatformServices>()
        every { mockPlatformServices.llmOperations } returns mockk()
        every { mockPlatformServices.eventListener } returns DevNull
        val blackboard = InMemoryBlackboard().bind(IoBinding.DEFAULT_BINDING, UserInput("John Doe"))
        every { mockAgentProcess.getValue(any(), any(), any()) } answers {
            blackboard.getValue(
                firstArg(),
                secondArg(),
                thirdArg(),
            )
        }
        every { mockAgentProcess.set(any(), any()) } answers {
            blackboard.set(
                firstArg(),
                secondArg(),
            )
        }
        every { mockAgentProcess.lastResult() } returns PersonWithReverseTool("John Doe")

        val pc = ProcessContext(
            platformServices = mockPlatformServices,
            agentProcess = mockAgentProcess,
        )
        val result = action.execute(pc, action)
        assertEquals(ActionStatusCode.SUCCEEDED, result.status)
        assertEquals(PersonWithReverseTool("John Doe"), pc.blackboard.lastResult())
    }

    @Test
    fun `transformer action invocation with payload`() {
        val reader = TestAgentMetadataReader.create()
        val metadata = reader.createAgentMetadata(OneTransformerActionTakingPayloadOnly())
        assertNotNull(metadata)
        assertEquals(1, metadata!!.actions.size)
        val action = metadata.actions.first()
        assertEquals(
            1,
            action.inputs.size,
            "Should not consider payload as input: ${action.inputs}",
        )
        assertEquals(
            UserInput::class.java.name,
            action.inputs.single().type,
            "Should not consider payload as input: ${action.inputs}",
        )
        val agent = mockk<CoreAgent>()
        every { agent.domainTypes } returns listOf(PersonWithReverseTool::class.java, UserInput::class.java)
        val mockAgentProcess = mockk<AgentProcess>()
        every { mockAgentProcess.agent } returns agent
        val mockPlatformServices = mockk<PlatformServices>()
        every { mockPlatformServices.llmOperations } returns mockk()
        every { mockPlatformServices.eventListener } returns DevNull
        val blackboard = InMemoryBlackboard().bind(IoBinding.DEFAULT_BINDING, UserInput("John Doe"))
        every { mockAgentProcess.getValue(any(), any(), any()) } answers {
            blackboard.getValue(
                firstArg(),
                secondArg(),
                thirdArg(),
            )
        }
        every { mockAgentProcess.set(any(), any()) } answers {
            blackboard.set(
                firstArg(),
                secondArg(),
            )
        }
        every { mockAgentProcess.lastResult() } returns PersonWithReverseTool("John Doe")

        val pc = ProcessContext(

            platformServices = mockPlatformServices,
            agentProcess = mockAgentProcess,
        )
        val result = action.execute(pc, action)
        assertEquals(ActionStatusCode.SUCCEEDED, result.status)
        assertEquals(PersonWithReverseTool("John Doe"), pc.blackboard.lastResult())
    }

    @Test
    fun `action invocation with OperationPayload`() {
        val reader = TestAgentMetadataReader.create()
        val metadata = reader.createAgentMetadata(OneTransformerActionTakingOperationPayload())
        assertNotNull(metadata)
        assertEquals(1, metadata!!.actions.size)
        val action = metadata.actions.first()
        assertEquals(
            1,
            action.inputs.size,
            "Should not consider payload as input: ${action.inputs}",
        )
        assertEquals(
            UserInput::class.java.name,
            action.inputs.single().type,
            "Should not consider payload as input",
        )
        val agent = mockk<CoreAgent>()
        every { agent.domainTypes } returns listOf(PersonWithReverseTool::class.java, UserInput::class.java)
        val mockAgentProcess = mockk<AgentProcess>()
        every { mockAgentProcess.agent } returns agent
        val mockPlatformServices = mockk<PlatformServices>()
        every { mockPlatformServices.llmOperations } returns mockk()
        every { mockPlatformServices.eventListener } returns DevNull
        val blackboard = InMemoryBlackboard().bind(IoBinding.DEFAULT_BINDING, UserInput("John Doe"))
        every { mockAgentProcess.getValue(any(), any(), any()) } answers {
            blackboard.getValue(
                firstArg(),
                secondArg(),
                thirdArg(),
            )
        }
        every { mockAgentProcess.set(any(), any()) } answers {
            blackboard.set(
                firstArg(),
                secondArg(),
            )
        }
        every { mockAgentProcess.lastResult() } returns PersonWithReverseTool("John Doe")

        val pc = ProcessContext(

            platformServices = mockPlatformServices,
            agentProcess = mockAgentProcess,
        )
        val result = action.execute(pc, action)
        assertEquals(ActionStatusCode.SUCCEEDED, result.status)
        assertEquals(PersonWithReverseTool("John Doe"), pc.blackboard.lastResult())
    }

    @Test
    fun `transformer action with 2 args invocation`() {
        val reader = TestAgentMetadataReader.create()
        val metadata = reader.createAgentMetadata(AgentWithOneTransformerActionWith2ArgsOnly())
        assertNotNull(metadata)
        assertEquals(1, metadata!!.actions.size)
        val action = metadata.actions.first()
        val agent = mockk<CoreAgent>()
        every { agent.domainTypes } returns listOf(PersonWithReverseTool::class.java, UserInput::class.java)
        val mockAgentProcess = mockk<AgentProcess>()
        every { mockAgentProcess.agent } returns agent
        val mockPlatformServices = mockk<PlatformServices>()
        every { mockPlatformServices.llmOperations } returns mockk()
        every { mockPlatformServices.eventListener } returns DevNull

        val blackboard = InMemoryBlackboard()
        blackboard += UserInput("John Doe")
        blackboard += ("task" to Task("task"))
        every { mockAgentProcess.getValue(any(), any(), any()) } answers {
            blackboard.getValue(
                firstArg(),
                secondArg(),
                thirdArg(),
            )
        }
        every { mockAgentProcess.set(any(), any()) } answers {
            blackboard.set(
                firstArg(),
                secondArg(),
            )
        }
        every { mockAgentProcess.lastResult() } returns PersonWithReverseTool("John Doe")

        val pc = ProcessContext(
            platformServices = mockPlatformServices,
            agentProcess = mockAgentProcess,
        )
        val result = action.execute(pc, action)
        assertEquals(ActionStatusCode.SUCCEEDED, result.status)
        assertEquals(PersonWithReverseTool("John Doe"), pc.blackboard.lastResult())
    }

    @Test
    @Disabled
    fun `consumer action with no parameters`() {
    }


    @Nested
    inner class TestToolMethodsOnDomainObject {

        @Test
        @Disabled("not yet implemented")
        fun `handles conflicting tool definitions in multiple domain objects`() {

        }

    }

    @Nested
    inner class CustomBinding {

        @Test
        fun `custom input bindings`() {
            val reader = TestAgentMetadataReader.create()
            val metadata = reader.createAgentMetadata(OneTransformerActionWith2ArgsAndCustomInputBindings())
            assertNotNull(metadata)
            assertEquals(1, metadata!!.actions.size)
            val action = metadata.actions.single()
            assertEquals(2, action.inputs.size, "Should have 2 inputs")
            assertEquals(1, action.outputs.size, "Should have 1 output")
            val uib = action.inputs.single { it.type == UserInput::class.java.name }
            val tb = action.inputs.single { it.type == Task::class.java.name }
            assertEquals("userInput", uib.name)
            assertEquals("task", tb.name)
            assertEquals(
                PersonWithReverseTool::class.java.name,
                action.outputs.single().type,
                "Output name must match",
            )
            assertEquals(
                IoBinding.DEFAULT_BINDING,
                action.outputs.single().name,
                "Output name must match",
            )
        }

        @Test
        fun `custom output binding`() {
            val reader = TestAgentMetadataReader.create()
            val metadata = reader.createAgentMetadata(OneTransformerActionWith2ArgsAndCustomOutputBinding())
            assertNotNull(metadata)
            assertEquals(1, metadata!!.actions.size)
            val action = metadata.actions.single()
            assertEquals(2, action.inputs.size, "Should have 2 inputs")
            assertEquals(1, action.outputs.size, "Should have 1 output")
            assertTrue(action.inputs.any { it.type == UserInput::class.java.name })
            assertTrue(action.inputs.any { it.type == Task::class.java.name })
            assertEquals(
                PersonWithReverseTool::class.java.name,
                action.outputs.single().type,
                "Output name must match",
            )
            assertEquals("person", action.outputs.single().name)
        }
    }

    @Nested
    inner class Prompts {
        @Test
        fun `prompt action invocation`() {
            val reader = TestAgentMetadataReader.create()
            val metadata = reader.createAgentMetadata(OnePromptActionOnly())
            assertNotNull(metadata)
            assertEquals(1, metadata!!.actions.size)
            val action = metadata.actions.first()
            val agent = mockk<CoreAgent>()
            every { agent.domainTypes } returns listOf(PersonWithReverseTool::class.java, UserInput::class.java)
            val mockAgentProcess = mockk<AgentProcess>()
            every { mockAgentProcess.agent } returns agent
            val llmo = slot<LlmInteraction>()
            val llmt = mockk<LlmOperations>()
            every {
                llmt.createObject<Any>(
                    any(),
                    capture(llmo),
                    any(),
                    any(),
                    any(),
                )
            } returns PersonWithReverseTool("John Doe")
            val mockPlatformServices = mockk<PlatformServices>()
            every { mockPlatformServices.llmOperations } returns llmt
            every { mockPlatformServices.eventListener } returns DevNull
            val blackboard = InMemoryBlackboard().bind(IoBinding.DEFAULT_BINDING, UserInput("John Doe"))
            every { mockAgentProcess.getValue(any(), any(), any()) } answers {
                blackboard.getValue(
                    firstArg(),
                    secondArg(),
                    thirdArg(),
                )
            }
            every { mockAgentProcess.set(any(), any()) } answers {
                blackboard.set(
                    firstArg(),
                    secondArg(),
                )
            }
            every { mockAgentProcess.lastResult() } returns PersonWithReverseTool("John Doe")

            val pc = ProcessContext(
                platformServices = mockPlatformServices,
                agentProcess = mockAgentProcess,
            )
            val result = action.execute(pc, action)
            assertEquals(ActionStatusCode.SUCCEEDED, result.status)
            assertEquals(PersonWithReverseTool("John Doe"), pc.blackboard.lastResult())
            assertEquals(DefaultModelSelectionCriteria, llmo.captured.llm.criteria)
            assertEquals(1.7, llmo.captured.llm.temperature)
        }

        @Test
        fun `prompt action invocation with tools`() {
            val reader = TestAgentMetadataReader.create()
            val metadata = reader.createAgentMetadata(OnePromptActionWithToolOnly())
            assertNotNull(metadata)
            assertEquals(1, metadata!!.actions.size)
            val action = metadata.actions.first()
            val agent = mockk<CoreAgent>()
            every { agent.domainTypes } returns listOf(PersonWithReverseTool::class.java, UserInput::class.java)
            val mockAgentProcess = mockk<AgentProcess>()
            every { mockAgentProcess.agent } returns agent
            val llmi = slot<LlmInteraction>()
            val llmt = mockk<LlmOperations>()
            every {
                llmt.createObject<Any>(
                    any(),
                    capture(llmi),
                    any(),
                    any(),
                    any(),
                )
            } returns PersonWithReverseTool("John Doe")
            val mockPlatformServices = mockk<PlatformServices>()
            every { mockPlatformServices.llmOperations } returns llmt
            every { mockPlatformServices.eventListener } returns DevNull
            val blackboard = InMemoryBlackboard().bind(IoBinding.DEFAULT_BINDING, UserInput("John Doe"))
            every { mockAgentProcess.getValue(any(), any(), any()) } answers {
                blackboard.getValue(
                    firstArg(),
                    secondArg(),
                    thirdArg(),
                )
            }
            every { mockAgentProcess.set(any(), any()) } answers {
                blackboard.set(
                    firstArg(),
                    secondArg(),
                )
            }
            every { mockAgentProcess.lastResult() } returns PersonWithReverseTool("John Doe")

            val pc = ProcessContext(
                platformServices = mockPlatformServices,
                agentProcess = mockAgentProcess,
            )
            val result = action.execute(pc, action)
            assertEquals(ActionStatusCode.SUCCEEDED, result.status)
            assertEquals(PersonWithReverseTool("John Doe"), pc.blackboard.lastResult())
//                assertEquals(1, llmi.captured.toolCallbacks.size)
//                assertEquals("thing", llmi.captured.toolCallbacks.single().toolDefinition.name())
            assertEquals(DefaultModelSelectionCriteria, llmi.captured.llm.criteria)
        }

        @Test
        fun `prompt action invocation with tools on domain object parameter via using`() {
            testToolsAreExposed(FromPersonUsesDomainObjectTools())
        }

        @Test
        fun `prompt action invocation with tools on domain object parameter via context`() {
            testToolsAreExposed(FromPersonUsesDomainObjectToolsViaContext())
        }

        @Test
        fun `prompt action invocation with tool object passed in via using`() {
            testToolsAreExposed(FromPersonUsesObjectToolsViaUsing(), expectedToolCount = 2)
        }

        @Test
        fun `prompt action invocation with tool object passed in via context`() {
            testToolsAreExposed(FromPersonUsesObjectToolsViaContext(), expectedToolCount = 2)
        }

        private fun testToolsAreExposed(instance: Any, expectedToolCount: Int = 1) {
            val reader = TestAgentMetadataReader.create()
            val metadata = reader.createAgentMetadata(instance)
            assertNotNull(metadata)
            assertEquals(1, metadata!!.actions.size)
            val action = metadata.actions.first()
            val agent = mockk<CoreAgent>()
            every { agent.domainTypes } returns listOf(PersonWithReverseTool::class.java, UserInput::class.java)
            val mockAgentProcess = mockk<AgentProcess>()
            every { mockAgentProcess.agent } returns agent
            val llmo = slot<LlmInteraction>()
            val llmt = mockk<LlmOperations>()
            every {
                llmt.createObject<Any>(
                    any(),
                    capture(llmo),
                    any(),
                    any(),
                    any(),
                )
            } returns UserInput("John Doe")
            val mockPlatformServices = mockk<PlatformServices>()
            every { mockPlatformServices.llmOperations } returns llmt
            every { mockPlatformServices.eventListener } returns DevNull
            val blackboard = InMemoryBlackboard().bind(IoBinding.DEFAULT_BINDING, PersonWithReverseTool("John Doe"))
            every { mockAgentProcess.getValue(any(), any(), any()) } answers {
                blackboard.getValue(
                    firstArg(),
                    secondArg(),
                    thirdArg(),
                )
            }
            every { mockAgentProcess.set(any(), any()) } answers {
                blackboard.set(
                    firstArg(),
                    secondArg(),
                )
            }
            every { mockAgentProcess.lastResult() } returns UserInput("John Doe")

            val pc = ProcessContext(
                platformServices = mockPlatformServices,
                agentProcess = mockAgentProcess,
            )
            val result = action.execute(pc, action)
            assertEquals(ActionStatusCode.SUCCEEDED, result.status)
            assertTrue(pc.blackboard.lastResult() is UserInput)
            assertEquals("John Doe", (pc.blackboard.lastResult() as UserInput).content)
            assertEquals(
                expectedToolCount,
                llmo.captured.toolCallbacks.size,
                "Should have $expectedToolCount tools, had ${llmo.captured.toolCallbacks.map { it.toolDefinition.name() }}",
            )
            assertTrue(llmo.captured.toolCallbacks.any { it.toolDefinition.name() == "reverse" })
            assertEquals(DefaultModelSelectionCriteria, llmo.captured.llm.criteria)
        }
    }

    @Nested
    inner class AwaitableTest {

        @Test
        fun `awaitable action invocation`() {
            val reader = TestAgentMetadataReader.create()
            val metadata = reader.createAgentMetadata(AwaitableOne())
            assertNotNull(metadata)
            assertEquals(1, metadata!!.actions.size)
            val action = metadata.actions.first()
            val agent = mockk<CoreAgent>()
            every { agent.domainTypes } returns listOf(PersonWithReverseTool::class.java, UserInput::class.java)
            val mockAgentProcess = mockk<AgentProcess>()
            every { mockAgentProcess.agent } returns agent
            every { mockAgentProcess.id } returns "mythical_beast"

            val mockPlatformServices = mockk<PlatformServices>()
            val llmt = mockk<LlmOperations>()
            every { mockPlatformServices.llmOperations } returns llmt
            every { mockPlatformServices.eventListener } returns DevNull
            val blackboard = InMemoryBlackboard().bind(IoBinding.DEFAULT_BINDING, UserInput("John Doe"))
            every { mockAgentProcess.getValue(any(), any(), any()) } answers {
                blackboard.getValue(
                    firstArg(),
                    secondArg(),
                    thirdArg(),
                )
            }
            every { mockAgentProcess.addObject(any()) } answers {
                blackboard.addObject(
                    firstArg(),
                )
            }
            every {
                mockAgentProcess.lastResult()
            } answers {
                blackboard.lastResult()
            }

            val pc = ProcessContext(
                platformServices = mockPlatformServices,
                agentProcess = mockAgentProcess,
            )
            val result = action.execute(pc, action)
            assertEquals(ActionStatusCode.WAITING, result.status)
            val fr = pc.blackboard.lastResult()
            assertTrue(
                fr is ConfirmationRequest<*>,
                "Last result should be an ConfirmationRequest: had ${blackboard.infoString(true)}",
            )
            val awaitable = fr as ConfirmationRequest<*>
            assertEquals(PersonWithReverseTool("John Doe"), awaitable.payload)
        }
    }

}
