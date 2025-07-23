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

import com.embabel.agent.domain.io.UserInput

data class MagicVictim(
    val name: String,
)

data class Frog(
    val name: String,
)

val EvilWizardAgent = agent("EvilWizard", description = "Turn a person into a frog") {

    transformation<UserInput, MagicVictim>(name = "thing") {
        MagicVictim(name = "Hamish")
    }

    promptedTransformer<MagicVictim, Frog>(name = "turn-into-frog") {
        "Turn the person named ${it.input.name} into a frog"

    }

    goal(name = "done", description = "done", satisfiedBy = Frog::class)
}

data class SnakeMeal(val frogs: List<Frog>)

fun evenMoreEvilWizard() = agent("EvenMoreEvilWizard", description = "Turn a person into a frog") {

    transformation<UserInput, MagicVictim>(name = "thing") {
        MagicVictim(name = "Hamish")
    }

    flow {
        aggregate<MagicVictim, Frog, SnakeMeal>(
            transforms = listOf({ Frog(it.input.name) }, { Frog("2") }, { Frog("3") }),
            merge = { frogs, _ -> SnakeMeal(frogs) },
        )
    }

    goal(
        name = "done",
        description = "done",
        satisfiedBy = SnakeMeal::class,
    )
}

fun evenMoreEvilWizardWithStructuredInput() =
    agent("EvenMoreEvilWizardWithStructuredInput", description = "Turn a person into a frog") {

        transformation<UserInput, MagicVictim>(name = "thing") {
            MagicVictim(name = "Hamish")
        }

        flow {
            aggregate<MagicVictim, Frog, SnakeMeal>(
                transforms = listOf({ Frog(it.input.name) }, { Frog("2") }, { Frog("3") }),
                merge = { frogs, _ -> SnakeMeal(frogs) },
            )
        }

        goal(
            name = "done",
            description = "done",
            satisfiedBy = SnakeMeal::class,
            startingInputTypes = setOf(MagicVictim::class),
        )
    }
