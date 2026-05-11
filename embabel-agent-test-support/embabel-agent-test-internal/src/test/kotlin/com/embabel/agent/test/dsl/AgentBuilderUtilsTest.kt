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

import com.embabel.agent.api.dsl.agent
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.objectsOfType
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.domain.AllNames
import com.embabel.agent.test.domain.Frog
import com.embabel.agent.test.domain.GeneratedName
import com.embabel.agent.test.domain.SpiPerson
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyAgentPlatform
import com.embabel.agent.test.type.Wumpus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies the reusable DSL test agents defined in [agentBuilderUtils.kt].
 */
class AgentBuilderUtilsTest {

    /**
     * Confirms that [splitGarden] exposes the expected static agent metadata.
     */
    @Test
    fun `splitGarden metadata is correct`() {
        // Arrange
        val agent: Agent = splitGarden()

        // Act

        // Assert
        assertEquals("splitter", agent.name)
        assertEquals("splitter0", agent.description)
        assertEquals(0, agent.conditions.size)
        assertEquals(3, agent.actions.size)
        assertEquals(1, agent.goals.size)
    }

    /**
     * Confirms that [splitGarden] executes successfully and produces a [SnakeMeal].
     */
    @Test
    fun `splitGarden runs`() {
        // Arrange
        val agent: Agent = splitGarden()

        // Act
        val result = dummyAgentPlatform().runAgentFrom(
            agent = agent,
            processOptions = ProcessOptions(),
            bindings = mapOf("it" to UserInput("do something")),
        )

        // Assert
        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals(3, result.processContext.agentProcess.history.size)
        assertIs<SnakeMeal>(result.lastResult())
    }

    /**
     * Confirms that [userInputToFrogOrPersonBranch] exposes the expected static agent metadata.
     */
    @Test
    fun `userInputToFrogOrPersonBranch metadata is correct`() {
        // Arrange
        val agent: Agent = userInputToFrogOrPersonBranch()

        // Act

        // Assert
        assertEquals("brancher", agent.name)
        assertEquals("brancher0", agent.description)
        assertEquals(0, agent.conditions.size)
        assertEquals(1, agent.actions.size)
        assertEquals(1, agent.goals.size)
    }

    /**
     * Confirms that [userInputToFrogOrPersonBranch] executes successfully and selects [SpiPerson].
     */
    @Test
    fun `userInputToFrogOrPersonBranch runs`() {
        // Arrange
        val agent: Agent = userInputToFrogOrPersonBranch()

        // Act
        val result = dummyAgentPlatform().runAgentFrom(
            agent = agent,
            processOptions = ProcessOptions(),
            bindings = mapOf("it" to UserInput("do something")),
        )

        // Assert
        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals(1, result.processContext.agentProcess.history.size)
        assertIs<SpiPerson>(result.lastResult())
    }

    /**
     * Confirms that [userInputToFrogChain] exposes the expected static agent metadata.
     */
    @Test
    fun `userInputToFrogChain metadata is correct`() {
        // Arrange
        val agent: Agent = userInputToFrogChain()

        // Act

        // Assert
        assertEquals("uitf", agent.name)
        assertEquals("Evil frogly wizard", agent.description)
        assertEquals(0, agent.conditions.size)
        assertEquals(2, agent.actions.size)
        assertEquals(1, agent.goals.size)
    }

    /**
     * Confirms that [userInputToFrogChain] executes successfully and produces a [Frog].
     */
    @Test
    fun `userInputToFrogChain runs`() {
        // Arrange
        val agent: Agent = userInputToFrogChain()

        // Act
        val result = dummyAgentPlatform().runAgentFrom(
            agent = agent,
            processOptions = ProcessOptions(),
            bindings = mapOf("it" to UserInput("do something")),
        )

        // Assert
        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals(2, result.processContext.agentProcess.history.size)
        assertIs<Frog>(result.lastResult())
    }

    /**
     * Confirms that [userInputToFrogAndThenDo] exposes the expected static agent metadata.
     */
    @Test
    fun `userInputToFrogAndThenDo metadata is correct`() {
        // Arrange
        val agent: Agent = userInputToFrogAndThenDo()

        // Act

        // Assert
        assertEquals("uitf", agent.name)
        assertEquals("Evil frogly wizard", agent.description)
        assertEquals(0, agent.conditions.size)
        assertEquals(2, agent.actions.size)
        assertEquals(1, agent.goals.size)
    }

    /**
     * Confirms that [userInputToFrogAndThenDo] executes successfully and produces a [Frog].
     */
    @Test
    fun `userInputToFrogAndThenDo runs`() {
        // Arrange
        val agent: Agent = userInputToFrogAndThenDo()

        // Act
        val result = dummyAgentPlatform().runAgentFrom(
            agent = agent,
            processOptions = ProcessOptions(),
            bindings = mapOf("it" to UserInput("do something")),
        )

        // Assert
        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals(2, result.processContext.agentProcess.history.size)
        assertIs<Frog>(result.lastResult())
    }

    /**
     * Confirms that [userInputToFrogAndThen] executes successfully and produces a [Frog].
     */
    @Test
    fun `userInputToFrogAndThen runs`() {
        // Arrange
        val agent: Agent = userInputToFrogAndThen()

        // Act
        val result = dummyAgentPlatform().runAgentFrom(
            agent = agent,
            processOptions = ProcessOptions(),
            bindings = mapOf("it" to UserInput("do something")),
        )

        // Assert
        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals(2, result.processContext.agentProcess.history.size)
        assertIs<Frog>(result.lastResult())
    }

    /**
     * Confirms that [userInputToFrogAndThenAgain] executes successfully and produces a [Wumpus].
     */
    @Test
    fun `userInputToFrogAndThenAgain runs`() {
        // Arrange
        val agent: Agent = userInputToFrogAndThenAgain()

        // Act
        val result = dummyAgentPlatform().runAgentFrom(
            agent = agent,
            processOptions = ProcessOptions(),
            bindings = mapOf("it" to UserInput("do something")),
        )

        // Assert
        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals(3, result.processContext.agentProcess.history.size)
        assertIs<Wumpus>(result.lastResult())
    }

    /**
     * Confirms that [simpleNamer] exposes the expected static agent metadata.
     */
    @Test
    fun `simpleNamer metadata is correct`() {
        // Arrange
        val agent: Agent = simpleNamer()

        // Act

        // Assert
        assertEquals("Thing namer", agent.name)
        assertEquals("Name a thing, using internet research", agent.description)
        assertEquals(1, agent.conditions.size)
        assertEquals(3, agent.actions.size)
        assertEquals(1, agent.goals.size)
    }

    /**
     * Confirms that [simpleNamer] executes successfully and merges its fixed outputs deterministically.
     */
    @Test
    fun `simpleNamer runs and merges deterministic output`() {
        // Arrange
        var count = 0
        val agent: Agent = simpleNamer { ++count }

        // Act
        val result = dummyAgentPlatform().runAgentFrom(
            agent = agent,
            processOptions = ProcessOptions(),
            bindings = mapOf("it" to UserInput("do something")),
        )

        // Assert
        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals(2, count)
        assertEquals(3, result.processContext.agentProcess.history.size)

        val allNames = assertIs<AllNames>(result.lastResult())
        assertEquals(listOf(GeneratedName("money.com", "Helps make money")), allNames.accepted)
        assertEquals(emptyList(), allNames.rejected)
    }

    /**
     * Confirms that [redoNamer] exposes the expected static agent metadata.
     */
    @Test
    fun `redoNamer metadata is correct`() {
        // Arrange
        val agent: Agent = redoNamer()

        // Act

        // Assert
        assertEquals("Thing namer", agent.name)
        assertEquals("Name a thing, using internet research, repeating until we are happy", agent.description)
        assertEquals(1, agent.conditions.size)
        assertEquals(5, agent.actions.size)
        assertEquals(1, agent.goals.size)
    }

    /**
     * Confirms that [redoNamer] executes successfully and completes in a top-level repeat process.
     */
    @Test
    fun `redoNamer runs`() {
        // Arrange
        val agent: Agent = redoNamer()

        // Act
        val result = dummyAgentPlatform().runAgentFrom(
            agent = agent,
            processOptions = ProcessOptions(),
            bindings = mapOf("it" to UserInput("do something")),
        )

        // Assert
        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals(1, result.processContext.agentProcess.history.size)
        assertIs<AllNames>(result.lastResult())
    }

    /**
     * Confirms that [nestingByName] exposes the expected static agent metadata.
     */
    @Test
    fun `nestingByName metadata is correct`() {
        // Arrange
        val agent = nestingByName()

        // Act

        // Assert
        assertEquals("nesting test", agent.name)
        assertEquals(1, agent.conditions.size)
        assertEquals(4, agent.actions.size)
        assertEquals(1, agent.goals.size)
    }

    /**
     * Confirms that [nestingByName] fails when the referenced nested agent has not been deployed.
     */
    @Test
    fun `nestingByName fails without deployed agent`() {
        // Arrange
        val agent = nestingByName()

        // Act / Assert
        assertThrows<IllegalArgumentException> {
            dummyAgentPlatform().runAgentFrom(
                agent = agent,
                processOptions = ProcessOptions(),
                bindings = mapOf("it" to UserInput("do something")),
            )
        }
    }

    /**
     * Confirms that [nestingByReference] executes successfully and exposes nested interim [Thing] results.
     */
    @Test
    fun `nestingByReference runs`() {
        // Arrange
        val agent = nestingByReference()

        // Act
        val result = dummyAgentPlatform().runAgentFrom(
            agent = agent,
            processOptions = ProcessOptions(),
            bindings = mapOf("it" to UserInput("do something")),
        )

        // Assert
        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals(4, result.processContext.agentProcess.history.size)
        assertIs<AllNames>(result.lastResult())
        assertTrue(result.objectsOfType<Thing>().isNotEmpty())
    }

    /**
     * Confirms that [nestingByName] executes successfully once the referenced nested agent is deployed.
     */
    @Test
    fun `nestingByName runs with deployed agent`() {
        // Arrange
        val agent = nestingByName()
        val platform = dummyAgentPlatform()
        platform.deploy(
            agent(name = "foobar", description = "doesn't matter here") {
                transformation<UserInput, Thing>("foobar") {
                    Thing(it.input.content)
                }
                goal("name", "description", satisfiedBy = Thing::class)
            }
        )

        // Act
        val result = platform.runAgentFrom(
            agent = agent,
            processOptions = ProcessOptions(),
            bindings = mapOf("it" to UserInput("do something")),
        )

        // Assert
        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals(4, result.processContext.agentProcess.history.size)
        assertIs<AllNames>(result.lastResult())
        assertTrue(result.objectsOfType<Thing>().isNotEmpty())
    }

    /**
     * Confirms that [biAggregate] exposes the expected static agent metadata.
     */
    @Test
    fun `biAggregate metadata is correct`() {
        // Arrange
        val agent: Agent = biAggregate()

        // Act

        // Assert
        assertEquals("biAggregate", agent.name)
        assertEquals("Nesting test", agent.description)
        assertEquals(1, agent.conditions.size)
        assertEquals(4, agent.actions.size)
        assertEquals(1, agent.goals.size)
    }

    /**
     * Confirms that [biAggregate] executes successfully and produces [AllNames].
     */
    @Test
    fun `biAggregate runs`() {
        // Arrange
        val agent: Agent = biAggregate()

        // Act
        val result = dummyAgentPlatform().runAgentFrom(
            agent = agent,
            processOptions = ProcessOptions(),
            bindings = mapOf("it" to UserInput("do something")),
        )

        // Assert
        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals(4, result.processContext.agentProcess.history.size)
        assertIs<AllNames>(result.lastResult())
    }
}
