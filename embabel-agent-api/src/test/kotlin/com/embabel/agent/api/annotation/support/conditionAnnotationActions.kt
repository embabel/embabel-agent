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

// TODO this is brainstorming for a possible condition annotation model

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.dsl.Frog
import org.springframework.stereotype.Component

@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.FUNCTION,
)
@Component
annotation class OwnsSiameseCat(
    val input: Boolean = false,
    val output: Boolean = false,
)

data class Cat(val breed: String)

@Agent(
    description = "thing",
)
class CustomConditionAnnotation {

    @OwnsSiameseCat
    fun checkKermit(cat: Cat): Boolean {
        return cat.breed == "Siamese"
    }

    @Action
    fun makeFrog(): Frog {
        return Frog("Kermit")
    }

    @OwnsSiameseCat(input = true)
    @AchievesGoal(description = "done")
    fun makePerson(frog: Frog): PersonWithReverseTool {
        return PersonWithReverseTool(frog.name)
    }

}
