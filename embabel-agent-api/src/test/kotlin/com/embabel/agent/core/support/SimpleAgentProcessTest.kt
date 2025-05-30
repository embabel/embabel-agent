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
package com.embabel.agent.core.support

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.confirm
import com.embabel.agent.api.annotation.support.TestAgentMetadataReader
import com.embabel.agent.api.annotation.waitFor
import com.embabel.agent.api.common.StuckHandler
import com.embabel.agent.api.common.StuckHandlerResult
import com.embabel.agent.api.common.StuckHandlingResultCode
import com.embabel.agent.api.dsl.Frog
import com.embabel.agent.api.dsl.agent
import com.embabel.agent.core.*
import com.embabel.agent.core.hitl.ConfirmationRequest
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.domain.library.Person
import com.embabel.agent.event.AgenticEventListener
import com.embabel.agent.event.ObjectAddedEvent
import com.embabel.agent.event.ObjectBoundEvent
import com.embabel.agent.event.logging.personality.severance.SeveranceLoggingAgenticEventListener
import com.embabel.agent.spi.OperationScheduler
import com.embabel.agent.spi.PlatformServices
import com.embabel.agent.support.SimpleTestAgent
import com.embabel.agent.testing.EventSavingAgenticEventListener
import com.embabel.agent.testing.IntegrationTestUtils.dummyPlatformServices
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

data class LocalPerson(
    override val name: String,
) : Person

@com.embabel.agent.api.annotation.Agent(
    description = "waiting agent",
    scan = false,
)
class AnnotationWaitingAgent {

    @Action
    fun fromInput(input: UserInput): LocalPerson {
        return confirm(
            LocalPerson(name = input.content),
            "Is this the right dude?"
        )
    }

    @Action
    @AchievesGoal(description = "the big goal in the sky")
    fun toFrog(person: LocalPerson): Frog {
        return Frog(person.name)
    }
}

val DslWaitingAgent = agent("Waiter", description = "Simple test agent that waits") {
    transformation<UserInput, LocalPerson>(name = "thing") {
        val person = LocalPerson(name = "Rod")
        waitFor(
            ConfirmationRequest(
                person,
                "Is this the dude?"
            )
        )

    }

    transformation<LocalPerson, Frog>(name = "thing2") {
        Frog(name = it.input.name)
    }


    goal(name = "done", description = "done", satisfiedBy = Frog::class)
}

class SimpleAgentProcessTest {

    @Nested
    inner class Serialization {

        @Test
        fun `should not be able to serialize AgentProcess`() {
            val sap = SimpleAgentProcess(
                id = "test",
                agent = SimpleTestAgent,
                processOptions = ProcessOptions(),
                blackboard = InMemoryBlackboard(),
                platformServices = dummyPlatformServices(),
                parentId = null,
            )
            assertThrows<IOException> {
                jacksonObjectMapper().writeValueAsString(sap)
            }
        }
    }

    @Nested
    inner class Waiting {

        @Test
        fun `wait on tick for DSL agent`() {
            waitOnTick(DslWaitingAgent)
        }

        @Test
        fun `wait on run for DSL agent`() {
            waitOnRun(DslWaitingAgent)
        }

        @Test
        fun `wait on tick for annotation agent`() {
            waitOnTick(TestAgentMetadataReader.create().createAgentMetadata(AnnotationWaitingAgent()) as Agent)
        }

        @Test
        fun `wait on run for annotation agent`() {
            waitOnRun(TestAgentMetadataReader.create().createAgentMetadata(AnnotationWaitingAgent()) as Agent)
        }

        private fun waitOnTick(agent: Agent) {
            val ese = EventSavingAgenticEventListener()
            val mockPlatformServices = mockk<PlatformServices>()
            every { mockPlatformServices.eventListener } returns ese
            every { mockPlatformServices.llmOperations } returns mockk()
            every { mockPlatformServices.operationScheduler } returns OperationScheduler.PRONTO

            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("Rod")
            val agentProcess = SimpleAgentProcess(
                id = "test",
                agent = agent,
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = mockPlatformServices,
                parentId = null,
            )
            val agentStatus = agentProcess.tick()
            assertEquals(AgentProcessStatusCode.WAITING, agentStatus.status)
            val confirmation = blackboard.lastResult()
            assertTrue(confirmation is ConfirmationRequest<*>)
        }

        private fun waitOnRun(agent: Agent) {
            val ese: AgenticEventListener = SeveranceLoggingAgenticEventListener()
            val mockPlatformServices = mockk<PlatformServices>()
            every { mockPlatformServices.eventListener } returns ese
            every { mockPlatformServices.llmOperations } returns mockk()
            every { mockPlatformServices.operationScheduler } returns OperationScheduler.PRONTO

            val blackboard = InMemoryBlackboard()
            blackboard += (IoBinding.DEFAULT_BINDING to UserInput("Rod"))
            val agentProcess = SimpleAgentProcess(
                id = "test",
                agent = agent,
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = mockPlatformServices,
                parentId = null,
            )
            val agentStatus = agentProcess.run()
            assertEquals(AgentProcessStatusCode.WAITING, agentStatus.status)
        }
    }

    @Nested
    inner class StuckHandling {

        @Test
        fun `expect stuck for DSL agent with no stuck handler`() {
            val agentProcess = run(DslWaitingAgent)
            assertEquals(AgentProcessStatusCode.STUCK, agentProcess.status)
        }

        @Test
        fun `expect stuck for annotation agent with no stuck handler`() {
            val agentProcess = run(TestAgentMetadataReader.create().createAgentMetadata(AnnotationWaitingAgent()) as Agent)
            assertEquals(AgentProcessStatusCode.STUCK, agentProcess.status)
        }

        @Test
        fun `expect unstuck for DSL agent with magic stuck handler`() {
            unstick(DslWaitingAgent)
        }

        @Test
        fun `expect unstuck for annotation agent with magic stuck handler`() {
            unstick(TestAgentMetadataReader.create().createAgentMetadata(AnnotationWaitingAgent()) as Agent)
        }

        private fun unstick(agent: Agent) {
            var called = false
            val stuckHandler = StuckHandler {
                called = true
                it.processContext.blackboard += UserInput("Rod")
                StuckHandlerResult(
                    message = "The magic unsticker unstuck the stuckness",
                    handler = null,
                    code = StuckHandlingResultCode.REPLAN,
                    agentProcess = it,
                )
            }
            val agentProcess = run(agent.copy(stuckHandler = stuckHandler))
            assertTrue(called, "Stuck handler must have been called")
            assertEquals(AgentProcessStatusCode.WAITING, agentProcess.status)
        }


        private fun run(agent: Agent): AgentProcess {
            val ese = EventSavingAgenticEventListener()
            val mockPlatformServices = mockk<PlatformServices>()
            every { mockPlatformServices.eventListener } returns ese
            every { mockPlatformServices.llmOperations } returns mockk()
            every { mockPlatformServices.operationScheduler } returns OperationScheduler.PRONTO
            val blackboard = InMemoryBlackboard()
            // Don't add anything to the blackboard
            val agentProcess = SimpleAgentProcess(
                id = "test",
                agent = agent,
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = mockPlatformServices,
                parentId = null,
            )
            return agentProcess.run()
        }

    }

    @Nested
    inner class Binding {

        @Test
        fun adds() {
            val ese = EventSavingAgenticEventListener()
            val mockPlatformServices = mockk<PlatformServices>()
            every { mockPlatformServices.eventListener } returns ese
            every { mockPlatformServices.llmOperations } returns mockk()
            val blackboard = InMemoryBlackboard()
            val agentProcess = SimpleAgentProcess(
                id = "test",
                agent = SimpleTestAgent,
                processOptions = mockk(),
                blackboard = blackboard,
                platformServices = mockPlatformServices,
                parentId = null,
            )
            val person = LocalPerson("John")
            agentProcess += person
            assertTrue(blackboard.objects.contains(person))
        }

        @Test
        fun `emits add event`() {
            val ese = EventSavingAgenticEventListener()
            val mockPlatformServices = mockk<PlatformServices>()
            every { mockPlatformServices.eventListener } returns ese
            every { mockPlatformServices.llmOperations } returns mockk()
            val blackboard = InMemoryBlackboard()
            val agentProcess = SimpleAgentProcess(
                id = "test",
                agent = SimpleTestAgent,
                processOptions = mockk(),
                blackboard = blackboard,
                platformServices = mockPlatformServices,
                parentId = null,
            )
            val person = LocalPerson("John")
            agentProcess += person
            val e = ese.processEvents.filterIsInstance<ObjectAddedEvent>().single()
            assertEquals(person, e.value)
        }

        @Test
        fun binds() {
            val ese = EventSavingAgenticEventListener()
            val mockPlatformServices = mockk<PlatformServices>()
            every { mockPlatformServices.eventListener } returns ese
            every { mockPlatformServices.llmOperations } returns mockk()
            val blackboard = InMemoryBlackboard()
            val agentProcess = SimpleAgentProcess(
                "test", agent = SimpleTestAgent,
                processOptions = mockk(),
                blackboard = blackboard,
                platformServices = mockPlatformServices,
                parentId = null,
            )
            val person = LocalPerson("John")
            agentProcess += ("john" to person)
            assertTrue(blackboard.objects.contains(person))
            assertEquals(person, blackboard["john"])
        }

        @Test
        fun `emits binding event`() {
            val ese = EventSavingAgenticEventListener()
            val mockPlatformServices = mockk<PlatformServices>()
            every { mockPlatformServices.eventListener } returns ese
            every { mockPlatformServices.llmOperations } returns mockk()
            val blackboard = InMemoryBlackboard()
            val agentProcess = SimpleAgentProcess(
                "test", agent = SimpleTestAgent,
                processOptions = mockk(),
                blackboard = blackboard,
                platformServices = mockPlatformServices,
                parentId = null,
            )
            val person = LocalPerson("John")
            agentProcess += ("john" to person)
            assertTrue(blackboard.objects.contains(person))
            assertEquals(person, blackboard["john"])
            assertEquals(1, ese.processEvents.size, "Should have 1 event")
            val e = ese.processEvents.filterIsInstance<ObjectBoundEvent>().single()
            assertEquals(person, e.value)
            assertEquals("john", e.name)
        }
    }

}
