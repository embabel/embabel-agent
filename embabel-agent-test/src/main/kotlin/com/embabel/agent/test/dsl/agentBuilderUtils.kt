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
package com.embabel.agent.test.dsl

import com.embabel.agent.api.common.support.Branch
import com.embabel.agent.api.dsl.*
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.annotation.support.Wumpus
import com.embabel.agent.test.domain.*
import com.embabel.common.core.MobyNameGenerator

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
            )
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
        )
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
        )
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
        )
    }

    goal(name = "namingDone", description = "We are satisfied with generated names", satisfiedBy = AllNames::class)
}
