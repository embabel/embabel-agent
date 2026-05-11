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
package com.embabel.agent.test.dsl

import com.embabel.agent.core.*
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.SimpleAgentProcess
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.test.domain.Frog
import com.embabel.agent.test.domain.MagicVictim
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyAgentPlatform
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Direct unit coverage for the helper agents defined in [dslAgents.kt].
 */
class DslAgentsTest {

    @Test
    fun `EvilWizardAgent metadata is correct`() {
        // Arrange
        val agent: Agent = EvilWizardAgent

        // Act

        // Assert
        assertEquals("EvilWizard", agent.name)
        assertEquals("Turn a person into a frog", agent.description)
        assertEquals(2, agent.actions.size)
        assertEquals(1, agent.goals.size)
    }

    @Test
    fun `EvilWizardAgent thing action binds MagicVictim`() {
        // Arrange
        val action = EvilWizardAgent.actions.find { it.name == "thing" } ?: error("Action not found: ${EvilWizardAgent.actions.map { it.name }}")
        val blackboard = InMemoryBlackboard()
        val platformServices = dummyPlatformServices()
        val processContext = ProcessContext(
            agentProcess = SimpleAgentProcess(
                agent = EvilWizardAgent,
                blackboard = blackboard,
                processOptions = ProcessOptions(blackboard = blackboard),
                platformServices = platformServices,
                id = "test",
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            ),
            processOptions = ProcessOptions(blackboard = blackboard),
            platformServices = platformServices,
        )

        // Act
        val result = action.execute(processContext)

        // Assert
        assertEquals(ActionStatusCode.SUCCEEDED, result.status)
        assertEquals(MagicVictim("Hamish"), processContext.blackboard[IoBinding.DEFAULT_BINDING])
    }

    @Test
    fun `EvilWizardAgent prompted transform returns Frog`() {
        // Arrange
        val action = EvilWizardAgent.actions.find { it.name == "turn-into-frog" } ?: error("Action not found: ${EvilWizardAgent.actions.map { it.name }}")
        val blackboard = InMemoryBlackboard()
        blackboard += MagicVictim("Hash")
        val platformServices = dummyPlatformServices()
        val processContext = ProcessContext(
            agentProcess = SimpleAgentProcess(
                agent = EvilWizardAgent,
                blackboard = blackboard,
                processOptions = ProcessOptions(blackboard = blackboard),
                platformServices = platformServices,
                id = "test",
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            ),
            processOptions = ProcessOptions(blackboard = blackboard),
            platformServices = platformServices,
        )

        // Act
        val result = action.execute(processContext)

        // Assert
        assertEquals(ActionStatusCode.SUCCEEDED, result.status)
        assertIs<Frog>(processContext.blackboard.lastResult())
    }

    @Test
    fun `EvilWizardAgent runs end to end`() {
        // Arrange

        // Act
        val result = dummyAgentPlatform().runAgentFrom(
            agent = EvilWizardAgent,
            processOptions = ProcessOptions(),
            bindings = mapOf("it" to UserInput("do something")),
        )

        // Assert
        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertIs<Frog>(result.lastResult())
    }

    @Test
    fun `evenMoreEvilWizard metadata is correct`() {
        // Arrange
        val agent: Agent = evenMoreEvilWizard()

        // Act

        // Assert
        assertEquals("EvenMoreEvilWizard", agent.name)
        assertEquals("Turn a person into a frog", agent.description)
        assertEquals(1, agent.conditions.size)
        assertEquals(5, agent.actions.size)
        assertEquals(1, agent.goals.size)
    }

    @Test
    fun `evenMoreEvilWizard runs and aggregates frogs deterministically`() {
        // Arrange

        // Act
        val result = dummyAgentPlatform().runAgentFrom(
            agent = evenMoreEvilWizard(),
            processOptions = ProcessOptions(),
            bindings = mapOf("it" to UserInput("do something")),
        )

        // Assert
        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        val snakeMeal = assertIs<SnakeMeal>(result.lastResult())
        assertEquals(3, snakeMeal.frogs.size)
        assertEquals(setOf("Hamish", "2", "3"), snakeMeal.frogs.map { it.name }.toSet())
    }
}
