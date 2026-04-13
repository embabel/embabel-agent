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
package com.embabel.agent.core.support

import com.embabel.agent.api.dsl.Frog
import com.embabel.agent.api.dsl.agent
import com.embabel.agent.core.ReplanRequestedException
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.domain.library.Person

data class LocalPerson(
    override val name: String,
) : Person

/**
 * Agent that throws [ReplanRequestedException] on first action, triggering replanning.
 * Uses blackboard state to decide whether to throw or proceed.
 */
val ReplanningAgent = agent("Replanner", description = "Agent that triggers replanning") {
    transformation<UserInput, LocalPerson>(name = "routing_transform") {
        val routedTo = it["routedTo"] as? String
        if (routedTo == "alternate") {
            LocalPerson(name = "Alternate: ${it.input.content}")
        } else {
            throw ReplanRequestedException(
                reason = "Routing to alternate path",
                blackboardUpdater = { bb -> bb["routedTo"] = "alternate" },
            )
        }
    }

    transformation<LocalPerson, Frog>(name = "to_frog") {
        Frog(it.input.name)
    }

    goal(name = "frog_goal", description = "Turn input into frog", satisfiedBy = Frog::class)
}

/**
 * Agent with multiple replans testing repeated replanning.
 */
val MultiReplanAgent = agent("MultiReplanner", description = "Agent that replans multiple times") {
    transformation<UserInput, LocalPerson>(name = "counting_transform") {
        val count = (it["replanCount"] as? Int) ?: 0
        if (count < 3) {
            throw ReplanRequestedException(
                reason = "Need more replans (count=$count)",
                blackboardUpdater = { bb -> bb["replanCount"] = count + 1 },
            )
        }
        LocalPerson(name = "Finally done after $count replans: ${it.input.content}")
    }

    transformation<LocalPerson, Frog>(name = "person_to_frog") {
        Frog(it.input.name)
    }

    goal(name = "frog_goal", description = "Turn input into frog", satisfiedBy = Frog::class)
}

/**
 * Agent to test blacklist behavior: has two actions that can both run from [UserInput].
 * Action `always_replans` always requests replan. Action `completes_normally` completes normally.
 * After `always_replans` is blacklisted, `completes_normally` should be selected.
 */
val BlacklistTestAgent = agent("BlacklistTester", description = "Agent that tests replan blacklist") {
    transformation<UserInput, LocalPerson>(name = "always_replans") {
        throw ReplanRequestedException(
            reason = "Always replanning",
            blackboardUpdater = { bb -> bb["replanAttempts"] = ((bb["replanAttempts"] as? Int) ?: 0) + 1 },
        )
    }

    transformation<UserInput, LocalPerson>(name = "completes_normally") {
        LocalPerson(name = "Completed via fallback: ${it.input.content}")
    }

    transformation<LocalPerson, Frog>(name = "to_frog") {
        Frog(it.input.name)
    }

    goal(name = "frog_goal", description = "Turn input into frog", satisfiedBy = Frog::class)
}
