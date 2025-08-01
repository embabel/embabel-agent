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
package com.embabel.agent.api.dsl

import com.embabel.agent.api.annotation.support.PersonWithReverseTool
import com.embabel.agent.core.ActionStatusCode
import com.embabel.agent.core.IoBinding
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.SimpleAgentProcess
import com.embabel.agent.rag.RagService
import com.embabel.agent.spi.PlatformServices
import com.embabel.agent.spi.support.ExecutorAsyncer
import com.embabel.agent.support.Dog
import com.embabel.agent.testing.common.EventSavingAgenticEventListener
import com.embabel.agent.testing.integration.DummyObjectCreatingLlmOperations
import com.embabel.common.core.types.Semver
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.test.assertTrue

val emptyAgent = agent(name = "foo", description = "bar") {}

val oneAction = agent(
    name = "oneAction",
    description = "one action agent",
) {
    transformation<PersonWithReverseTool, PersonWithReverseTool>(name = "reverser") { payload ->
        payload.input.copy(name = "foo")
    }
}

val oneActionAndOneGoal = agent(
    name = "oneActionAndOneGoal",
    description = "one action agent",
) {
    transformation<PersonWithReverseTool, Dog>(name = "reverser") { payload ->
        Dog(name = payload.input.name)
    }

    goal(
        name = "turnedIntoDog",
        description = "the person has been turned into a dog",
        satisfiedBy = Dog::class
    )
}

class AgentBuilderTest {

    @Nested
    inner class Errors {

        @Test
        fun `empty agent`() {
            val agent = emptyAgent
            assertEquals("foo", agent.name)
            assertEquals("bar", agent.description)
            assertEquals(Semver.DEFAULT_VERSION, agent.version.value)
            assertTrue(agent.actions.isEmpty(), "Should be no actions")
            assertTrue(agent.goals.isEmpty(), "Should be no goals")
        }
    }

    @Nested
    inner class HappyPath {

        @Test
        fun `one action`() {
            val agent = oneAction
            assertEquals("oneAction", agent.name)
            assertEquals("one action agent", agent.description)
            assertEquals(Semver.DEFAULT_VERSION, agent.version.value)
            assertEquals(1, agent.actions.size)
            assertTrue(agent.goals.isEmpty())
        }

        @Test
        fun `one action and one goal`() {
            val agent = oneActionAndOneGoal
            assertEquals("oneActionAndOneGoal", agent.name)
            assertEquals(1, agent.actions.size)
            assertEquals(1, agent.goals.size)
            assertEquals("turnedIntoDog", agent.goals.first().name)
        }

        @Test
        fun `finds and invokes transform`() {
            val agent = EvilWizardAgent
            val action = agent.actions.find { it.name == "thing" }
                ?: error("Action not found: ${agent.actions.map { it.name }}")
            val blackboard = InMemoryBlackboard()
            blackboard += PersonWithReverseTool("foo")
            val platformServices = PlatformServices(
                eventListener = EventSavingAgenticEventListener(),
                llmOperations = DummyObjectCreatingLlmOperations.LoremIpsum,
                operationScheduler = mockk(),
                agentPlatform = mockk(),
                ragService = mockk(),
                asyncer = ExecutorAsyncer(Executors.newSingleThreadExecutor()),
                objectMapper = jacksonObjectMapper(),
                applicationContext = null,
            )
            val processContext = ProcessContext(
                agentProcess = SimpleAgentProcess(
                    agent = EvilWizardAgent,
                    blackboard = blackboard,
                    processOptions = ProcessOptions(blackboard = blackboard),
                    platformServices = platformServices,
                    id = "test",
                    parentId = null,
                ),
                processOptions = ProcessOptions(blackboard = blackboard),
                platformServices = platformServices,
            )
            val r = action.execute(processContext, action)
            assertEquals(ActionStatusCode.SUCCEEDED, r.status)
            assertEquals(
                MagicVictim("Hamish"),
                processContext.blackboard[IoBinding.DEFAULT_BINDING],
                "Result should have been bound to blackboard"
            )
        }

        @Test
        fun `finds and invokes prompted transform`() {
            val agent = EvilWizardAgent
            val action = agent.actions.find { it.name == "turn-into-frog" }
                ?: error("Action not found: ${agent.actions.map { it.name }}")
            val blackboard = InMemoryBlackboard()
            blackboard += MagicVictim("Hash")
            val platformServices = PlatformServices(
                eventListener = EventSavingAgenticEventListener(),
                llmOperations = DummyObjectCreatingLlmOperations.LoremIpsum,
                operationScheduler = mockk(),
                agentPlatform = mockk(),
                ragService = RagService.empty(),
                asyncer = ExecutorAsyncer(Executors.newSingleThreadExecutor()),
                objectMapper = jacksonObjectMapper(),
                applicationContext = null,
            )
            val processContext = ProcessContext(
                agentProcess = SimpleAgentProcess(
                    agent = EvilWizardAgent,
                    blackboard = blackboard,
                    processOptions = ProcessOptions(blackboard = blackboard),
                    platformServices = platformServices,
                    id = "test",
                    parentId = null,
                ),
                processOptions = ProcessOptions(blackboard = blackboard),
                platformServices = platformServices,
            )
            val r = action.execute(processContext, action)
            assertEquals(ActionStatusCode.SUCCEEDED, r.status)
            val output = processContext.blackboard.lastResult()
            assertTrue(
                output is Frog,
                "Result should be of correct type, although dummy LLM operations may have populated it strangely"
            )
        }
    }

}
