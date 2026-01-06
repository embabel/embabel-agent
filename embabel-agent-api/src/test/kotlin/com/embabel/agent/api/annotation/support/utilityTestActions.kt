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
import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.api.dsl.Frog
import com.embabel.agent.api.dsl.SnakeMeal
import com.embabel.agent.domain.io.UserInput
import io.mockk.mockk

@Agent(
    description = "thing",
    planner = PlannerType.UTILITY
)
class Utility2ActionsNoGoal {

    @Action
    fun makeFrog(): Frog {
        return Frog("Kermit")
    }

    @Action
    fun makePerson(frog: Frog): PersonWithReverseTool {
        return PersonWithReverseTool(frog.name)
    }

}

@Agent(
    description = "thing",
    planner = PlannerType.UTILITY
)
class Utility2Actions1SatisfiableGoal {

    @Action
    fun makeFrog(): Frog {
        return Frog("Kermit")
    }

    @Action
    @AchievesGoal(
        description = "Create a person with reverse tool from a frog",
    )
    fun makePerson(frog: Frog): PersonWithReverseTool {
        return PersonWithReverseTool(frog.name)
    }

}

@Agent(
    description = "thing",
    planner = PlannerType.UTILITY
)
class Utility2Actions1UnsatisfiableGoal {

    @Action
    fun makeFrog(): Frog {
        return Frog("Kermit")
    }

    @Action
    fun makePerson(frog: Frog): PersonWithReverseTool {
        return PersonWithReverseTool(frog.name)
    }

    @Action
    @AchievesGoal(
        description = "Unsatisfiable goal",
    )
    fun unsatisfiableMakeSnakeMeal(userInput: UserInput): SnakeMeal {
        return mockk()
    }

}


@Agent(
    description = "thing",
    planner = PlannerType.UTILITY
)
class Utility2Actions1VoidNoGoal {

    var invokedThing2 = false

    @Action
    fun makeFrog(): Frog {
        return Frog("Kermit")
    }

    @Action
    fun thing2(frog: Frog) {
        invokedThing2 = true
    }

}
