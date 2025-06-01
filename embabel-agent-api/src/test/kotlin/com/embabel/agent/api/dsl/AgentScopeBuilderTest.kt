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

import com.embabel.agent.api.annotation.support.Wumpus
import com.embabel.agent.api.common.support.Branch
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.all
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.spi.support.SpiPerson
import com.embabel.agent.testing.IntegrationTestUtils.dummyAgentPlatform
import com.embabel.common.core.MobyNameGenerator
import com.embabel.common.core.types.Semver
import com.embabel.common.core.types.Semver.Companion.DEFAULT_VERSION
import com.embabel.examples.dogfood.factchecker.FactCheck
import com.embabel.examples.dogfood.factchecker.FactCheckerProperties
import com.embabel.examples.dogfood.factchecker.factCheckerAgent
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class AgentScopeBuilderTest {

    @Nested
    inner class Split {
        @Test
        fun `metadata is correct`() {
            val agent: Agent = splitGarden()
            assertEquals("splitter", agent.name)
            assertEquals("splitter0", agent.description)
            assertEquals(Semver.DEFAULT_VERSION, agent.version.value)
            assertEquals(0, agent.conditions.size, "Should have no conditions")
            assertEquals(3, agent.actions.size, "Should have 3 actions")
            assertEquals(1, agent.goals.size)
        }

        @Test
        fun `agent runs`() {
            val agent: Agent = splitGarden()
            val ap = dummyAgentPlatform()
            val processOptions = ProcessOptions()
            val result = ap.runAgentWithInput(
                agent = agent,
                processOptions = processOptions,
                input = UserInput("do something"),
            )
            assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
            assertEquals(
                3,
                result.processContext.agentProcess.history.size,
                "Expected history:\nActual:\n${result.processContext.agentProcess.history.joinToString("\n")}"
            )
            assertTrue(result.lastResult() is SnakeMeal)
        }
    }

    @Nested
    inner class Branch {
        @Test
        fun `metadata is correct`() {
            val agent: Agent = userInputToFrogOrPersonBranch()
            assertEquals("brancher", agent.name)
            assertEquals("brancher0", agent.description)
            assertEquals(Semver.DEFAULT_VERSION, agent.version.value)
            assertEquals(0, agent.conditions.size, "Should have no conditions")
            assertEquals(1, agent.actions.size, "Should have 2 actions")
            assertEquals(1, agent.goals.size)
        }

        @Test
        fun `agent runs`() {
            val agent: Agent = userInputToFrogOrPersonBranch()
            val ap = dummyAgentPlatform()
            val processOptions = ProcessOptions()
            val result = ap.runAgentFrom(
                agent = agent,
                processOptions = processOptions,
                bindings = mapOf(
                    "it" to UserInput("do something")
                ),
            )
            assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
            assertEquals(
                1,
                result.processContext.agentProcess.history.size,
                "Expected history:\nActual:\n${result.processContext.agentProcess.history.joinToString("\n")}"
            )
            assertTrue(result.lastResult() is SpiPerson)
        }
    }

    @Nested
    inner class Chain {
        @Test
        fun `metadata is correct`() {
            val agent: Agent = userInputToFrogChain()
            assertEquals("uitf", agent.name)
            assertEquals("Evil frogly wizard", agent.description)
            assertEquals(Semver.DEFAULT_VERSION, agent.version.value)
            assertEquals(0, agent.conditions.size, "Should have no conditions")
            assertEquals(2, agent.actions.size, "Should have 2 actions")
            assertEquals(1, agent.goals.size)
        }

        @Test
        fun `agent runs`() {
            val agent: Agent = userInputToFrogChain()
            val ap = dummyAgentPlatform()
            val processOptions = ProcessOptions()
            val result = ap.runAgentFrom(
                agent = agent,
                processOptions = processOptions,
                bindings = mapOf(
                    "it" to UserInput("do something")
                ),
            )
            assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
            assertEquals(
                2,
                result.processContext.agentProcess.history.size,
                "Expected history:\nActual:\n${result.processContext.agentProcess.history.joinToString("\n")}"
            )
            assertTrue(result.lastResult() is Frog)
        }
    }

    @Nested
    inner class AndThen {
        @Test
        fun `metadata is correct`() {
            val agent: Agent = userInputToFrogAndThenDo()
            assertEquals("uitf", agent.name)
            assertEquals("Evil frogly wizard", agent.description)
            assertEquals(Semver.DEFAULT_VERSION, agent.version.value)
            assertEquals(0, agent.conditions.size, "Should have no conditions")
            assertEquals(2, agent.actions.size, "Should have 2 actions")
            assertEquals(1, agent.goals.size)
        }

        @Test
        fun `agent runs andThen`() {
            val agent: Agent = userInputToFrogAndThen()
            val ap = dummyAgentPlatform()
            val processOptions = ProcessOptions()
            val result = ap.runAgentFrom(
                agent = agent,
                processOptions = processOptions,
                bindings = mapOf(
                    "it" to UserInput("do something")
                ),
            )
            assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
            assertEquals(
                2,
                result.processContext.agentProcess.history.size,
                "Expected history:\nActual:\n${result.processContext.agentProcess.history.joinToString("\n")}"
            )
            assertTrue(result.lastResult() is Frog)
        }

        @Test
        fun `agent runs andThenDo`() {
            val agent: Agent = userInputToFrogAndThenDo()
            val ap = dummyAgentPlatform()
            val processOptions = ProcessOptions()
            val result = ap.runAgentFrom(
                agent = agent,
                processOptions = processOptions,
                bindings = mapOf(
                    "it" to UserInput("do something")
                ),
            )
            assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
            assertEquals(
                2,
                result.processContext.agentProcess.history.size,
                "Expected history:\nActual:\n${result.processContext.agentProcess.history.joinToString("\n")}"
            )
            assertTrue(result.lastResult() is Frog)
        }

        @Test
        fun `agent runs andThen chain`() {
            val agent: Agent = userInputToFrogAndThenAgain()
            val ap = dummyAgentPlatform()
            val processOptions = ProcessOptions()
            val result = ap.runAgentFrom(
                agent = agent,
                processOptions = processOptions,
                bindings = mapOf(
                    "it" to UserInput("do something")
                ),
            )
            assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
            assertEquals(
                3,
                result.processContext.agentProcess.history.size,
                "Expected history:\nActual:\n${result.processContext.agentProcess.history.joinToString("\n")}"
            )
            assertTrue(result.lastResult() is Wumpus)
        }
    }

    @Nested
    inner class Aggregate {
        @Test
        fun `metadata is correct`() {
            val agent: Agent = simpleNamer()
            assertEquals("Thing namer", agent.name)
            assertEquals("Name a thing, using internet research", agent.description)
            assertEquals(DEFAULT_VERSION, agent.version.value)
            assertEquals(1, agent.conditions.size, "Should have join condition")
            assertEquals(3, agent.actions.size, "Should have actions")
            assertEquals(1, agent.goals.size)
        }

        @Test
        fun `agent runs`() {
            var count = 0
            val agent: Agent = simpleNamer { ++count }
            val ap = dummyAgentPlatform()
            val processOptions = ProcessOptions()
            val result = ap.runAgentFrom(
                agent = agent,
                processOptions = processOptions,
                bindings = mapOf(
                    "it" to UserInput("do something")
                ),
            )
            assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
            assertEquals(2, count, "All transforms should have been invoked")
            assertEquals(
                3, result.processContext.agentProcess.history.size,
                "Expected history:\nActual:\n${result.processContext.agentProcess.history.joinToString("\n")}"
            )
            assertTrue(result.lastResult() is AllNames)
        }

        @Test
        fun `agent runs with listMaker`() {
            val agent: Agent = factCheckerAgent(listOf(mockk()), FactCheckerProperties())
            val ap = dummyAgentPlatform()
            val processOptions = ProcessOptions()
            val result = ap.runAgentFrom(
                agent = agent,
                processOptions = processOptions,
                bindings = mapOf(
                    "it" to UserInput("do something")
                ),
            )
            assertEquals(
                AgentProcessStatusCode.COMPLETED,
                result.status,
                "Result wasn't completed: ${result.failureInfo}",
            )
//            assertEquals(
//                3,
//                result.processContext.agentProcess.history.size,
//                "Expected history:\nActual:\n${result.processContext.agentProcess.history.joinToString("\n")}"
//            )
            assertTrue(
                result.lastResult() is FactCheck,
                "Result wasn't fact check: ${result.lastResult()}",
            )
            println(result.lastResult())
        }
    }

    @Nested
    inner class BiAggregate {
        @Test
        fun `metadata is correct`() {
            val agent: Agent = biAggregate()
            assertEquals("biAggregate", agent.name)
            assertEquals(DEFAULT_VERSION, agent.version.value)
            assertEquals(1, agent.conditions.size, "Should have join condition")
            assertEquals(4, agent.actions.size, "Should have actions")
            assertEquals(1, agent.goals.size)
        }

        @Test
        fun `agent runs`() {
            val agent: Agent = biAggregate()
            val ap = dummyAgentPlatform()
            val processOptions = ProcessOptions()
            val result = ap.runAgentFrom(
                agent = agent,
                processOptions = processOptions,
                bindings = mapOf(
                    "it" to UserInput("do something")
                ),
            )
            assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
            assertEquals(
                4,
                result.processContext.agentProcess.history.size,
                "Expected history:\nActual:\n${result.processContext.agentProcess.history.joinToString("\n")}",
            )
            assertTrue(result.lastResult() is AllNames)
        }
    }

    @Nested
    inner class Nesting {
        @Test
        fun `metadata is correct`() {
            val agent = nestingByName()

            assertEquals(1, agent.conditions.size, "Should have join condition")
            assertEquals(4, agent.actions.size, "Should have actions")
            assertEquals(1, agent.goals.size)
        }

        @Test
        fun `fails without agent`() {
            val agent = nestingByName()
            val ap = dummyAgentPlatform()
            val processOptions = ProcessOptions()
            assertThrows<IllegalArgumentException> {
                ap.runAgentFrom(
                    agent = agent,
                    processOptions = processOptions,
                    bindings = mapOf(
                        "it" to UserInput("do something")
                    ),
                )
            }
        }

        @Test
        fun `by reference - agent runs`() {
            val agent = nestingByReference()
            val ap = dummyAgentPlatform()
            val processOptions = ProcessOptions()
            val result = ap.runAgentFrom(
                agent = agent,
                processOptions = processOptions,
                bindings = mapOf(
                    "it" to UserInput("do something")
                ),
            )
            assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
            assertEquals(
                4,
                result.processContext.agentProcess.history.size,
                "Expected history:\nActual:\n${result.processContext.agentProcess.history.joinToString("\n")}"
            )
            assertTrue(result.lastResult() is AllNames)
            assertTrue(result.all<Thing>().isNotEmpty(), "Should have got interim result from agent")
        }


        @Test
        fun `by name - agent runs`() {
            val agent = nestingByName()
            val ap = dummyAgentPlatform()
            ap.deploy(agent(name = "foobar", description = "doesn't matter here") {
                transformation<UserInput, Thing>("foobar") {
                    Thing(it.input.content)
                }
                goal("name", "description", satisfiedBy = Thing::class)
            }
            )
            val processOptions = ProcessOptions()
            val result = ap.runAgentFrom(
                agent = agent,
                processOptions = processOptions,
                bindings = mapOf(
                    "it" to UserInput("do something")
                ),
            )
            assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
            assertEquals(
                4,
                result.processContext.agentProcess.history.size,
                "Expected history:\nActual:\n${result.processContext.agentProcess.history.joinToString("\n")}"
            )
            assertTrue(result.lastResult() is AllNames)
            assertTrue(result.all<Thing>().isNotEmpty(), "Should have got interim result from agent")
        }
    }

    @Nested
    inner class Redo {
        @Test
        fun `metadata is correct`() {
            val agent: Agent = redoNamer()
            assertEquals("Thing namer", agent.name)
            assertEquals("Name a thing, using internet research, repeating until we are happy", agent.description)
            assertEquals(Semver.DEFAULT_VERSION, agent.version.value)
            assertEquals(1, agent.conditions.size, "Should have join condition")
            assertEquals(5, agent.actions.size, "Should have actions")
            assertEquals(1, agent.goals.size)
        }

        @Test
        fun `agent runs`() {
            val agent: Agent = redoNamer()
            val ap = dummyAgentPlatform()
            val processOptions = ProcessOptions()
            val result = ap.runAgentFrom(
                agent = agent,
                processOptions = processOptions,
                bindings = mapOf(
                    "it" to UserInput("do something")
                ),
            )
            assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
            assertEquals(
                1,
                result.processContext.agentProcess.history.size,
                "Will have short history in top level process"
            )
            assertTrue(result.lastResult() is AllNames)
        }
    }
}

data class GeneratedName(val name: String, val reason: String)
data class GeneratedNames(val names: List<GeneratedName>)
data class AllNames(val accepted: List<GeneratedName>, val rejected: List<GeneratedName>)

data class Garden(val name: String)

fun splitGarden() = agent("splitter", description = "splitter0") {

    transformation<UserInput, Garden> { Garden(it.input.content) }

    flow {
        split<Garden, Frog> {
            listOf(Frog("Kermit"), Frog("Freddo"))
        } andThenDo {
            SnakeMeal(it.objects.filterIsInstance<Frog>())
        }
    }

    goal(
        name = "snakeFed",
        description = "We are satisfied with generated names",
        satisfiedBy = SnakeMeal::class,
    )
}

fun userInputToFrogOrPersonBranch() = agent("brancher", description = "brancher0") {

    flow {
        branch<UserInput, SpiPerson, Frog> { Branch(SpiPerson(it.input.content)) }
    }

    goal(name = "namingDone", description = "We are satisfied with generated names", satisfiedBy = SpiPerson::class)
}

fun userInputToFrogChain() = agent("uitf", description = "Evil frogly wizard") {

    flow {
        chain<UserInput, SpiPerson, Frog>(
            { SpiPerson(it.input.content) },
            { Frog(it.input.name) },
        )
    }

    goal(name = "namingDone", description = "We are satisfied with generated names", satisfiedBy = Frog::class)
}

fun userInputToFrogAndThenDo() = agent("uitf", description = "Evil frogly wizard") {

    fun toSpiPerson(userInput: UserInput) = SpiPerson(userInput.content)

    flow {
        ::toSpiPerson andThenDo { Frog(it.input.name) }
    }

    goal(name = "namingDone", description = "We are satisfied with generated names", satisfiedBy = Frog::class)
}

fun userInputToFrogAndThen() = agent("uitf", description = "Evil frogly wizard") {

    fun toSpiPerson(userInput: UserInput) = SpiPerson(userInput.content)

    flow {
        ::toSpiPerson andThen { Frog(it.name) }
    }

    goal(name = "namingDone", description = "We are satisfied with generated names", satisfiedBy = Frog::class)
}

fun userInputToFrogAndThenAgain() = agent("uitf", description = "Evil frogly wizard") {

    fun toSpiPerson(userInput: UserInput) = SpiPerson(userInput.content)

    flow {
        ::toSpiPerson andThen { Frog(it.name) } andThen { Wumpus(it.name) }
    }

    goal(
        name = "namingDone", description = "We are satisfied with generated names",
        satisfiedBy = Wumpus::class
    )
}

fun simpleNamer(transformListener: () -> Unit = {}) =
    agent("Thing namer", description = "Name a thing, using internet research") {

        flow {
            aggregate<UserInput, GeneratedNames, AllNames>(
                transforms = listOf(
                    {
                        transformListener()
                        GeneratedNames(names = emptyList())
                    },
                    {
                        transformListener()
                        GeneratedNames(
                            names = listOf(
                                GeneratedName(
                                    "money.com",
                                    "Helps make money"
                                )
                            )
                        )
                    }),
                merge = { list, _ ->
                    AllNames(
                        accepted = list.flatMap { it.names }.distinctBy { it.name },
                        rejected = emptyList()
                    )
                },
            ).parallelize()
        }

        goal(name = "namingDone", description = "We are satisfied with generated names", satisfiedBy = AllNames::class)
    }


fun redoNamer() =
    agent(
        name = "Thing namer",
        description = "Name a thing, using internet research, repeating until we are happy"
    ) {

        flow {
            repeat<AllNames>(
                what = {
                    repeatableAggregate<UserInput, GeneratedNames, AllNames>(
                        startWith = AllNames(accepted = emptyList(), rejected = emptyList()),
                        transforms = listOf(
                            {
                                GeneratedNames(
                                    names = listOf(
                                        GeneratedName(
                                            MobyNameGenerator.generateName(),
                                            "Helps make money"
                                        )
                                    )
                                )
                            },
                            { GeneratedNames(names = listOf(GeneratedName("money.com", "Helps make money"))) }),
                        merge = { generatedNamesList ->
                            AllNames(
                                accepted = generatedNamesList.flatMap { it.names }.distinctBy { it.name },
                                rejected = emptyList()
                            )
                        },
                    )
                },
                until = { it, _ ->
                    it.accepted.size > 5
                })
        }

        goal(name = "namingDone", description = "We are satisfied with generated names", satisfiedBy = AllNames::class)
    }

data class Thing(val t: String)

fun nestingByName() = agent("nesting test", description = "Nesting test") {

    referencedAgentAction<UserInput, Thing>(agentName = "foobar")

    flow {
        aggregate<Thing, GeneratedNames, AllNames>(
            transforms = listOf(
                { GeneratedNames(names = emptyList()) },
                { GeneratedNames(names = listOf(GeneratedName("money.com", "Helps make money"))) }),
            merge = { generatedNamesList, _ ->
                AllNames(
                    accepted = generatedNamesList.flatMap { it.names }.distinctBy { it.name },
                    rejected = emptyList()
                )
            },
        ).parallelize()
    }

    goal(name = "namingDone", description = "We are satisfied with generated names", satisfiedBy = AllNames::class)
}

fun nestingByReference() = agent("nesting test", description = "Nesting test") {

    localAgentAction<UserInput, Thing>(
        agent(name = "foobar", description = "doesn't matter here") {
            transformation<UserInput, Thing>("foobar") {
                Thing(it.input.content)
            }
            goal("name", "description", satisfiedBy = Thing::class)
        })
    flow {
        aggregate<Thing, GeneratedNames, AllNames>(
            transforms = listOf(
                { GeneratedNames(names = emptyList()) },
                { GeneratedNames(names = listOf(GeneratedName("money.com", "Helps make money"))) }),
            merge = { generatedNamesList, _ ->
                AllNames(
                    accepted = generatedNamesList.flatMap { it.names }.distinctBy { it.name },
                    rejected = emptyList()
                )
            },
        ).parallelize()
    }

    goal(name = "namingDone", description = "We are satisfied with generated names", satisfiedBy = AllNames::class)
}

fun biAggregate() = agent("biAggregate", description = "Nesting test") {

    transformation<UserInput, Thing>("foo") {
        Thing(it.input.content)
    }

    flow {
        biAggregate<UserInput, Thing, GeneratedNames, AllNames>(
            transforms = listOf(
                { GeneratedNames(names = emptyList()) },
                { GeneratedNames(names = listOf(GeneratedName("money.com", "Helps make money"))) }),
            merge = { generatedNamesList ->
                AllNames(
                    accepted = generatedNamesList.flatMap { it.names }.distinctBy { it.name },
                    rejected = emptyList()
                )
            },
        ).parallelize()
    }

    goal(name = "namingDone", description = "We are satisfied with generated names", satisfiedBy = AllNames::class)
}
