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
package com.embabel.agent.prompt.persona

import com.embabel.common.ai.prompt.PromptContributor
import com.fasterxml.jackson.databind.annotation.JsonDeserialize

/**
 * Interface for CrewAI style backstory prompt when
 * a concrete class isn't appropriate (eg for some persistence scenarios).
 * Included for users migrating from CrewAI.
 * In Embabel, such structures aren't core to the framework,
 * but merely a PromptContributor that can be used
 * in any action implementation.
 */
@JsonDeserialize(`as` = RoleGoalBackstory::class)
interface RoleGoalBackstorySpec : PromptContributor {

    val goal: String
    val backstory: String

    override fun contribution(): String = """
        Role: $role
        Goal: $goal
        Backstory: $backstory
    """.trimIndent()

    companion object {

        @JvmStatic
        fun create(
            role: String,
            goal: String,
            backstory: String,
        ): RoleGoalBackstorySpec = RoleGoalBackstory(
            role,
            goal,
            backstory,
        )

        operator fun invoke(
            role: String,
            goal: String,
            backstory: String,
        ): RoleGoalBackstorySpec = RoleGoalBackstory(
            role,
            goal,
            backstory,
        )

        /**
         * Convenient Java-friendly way to start building a RoleGoalBackstory in fluent style.
         */
        @JvmStatic
        fun withRole(role: String) = RoleBuilder(role)

    }

    class RoleBuilder(private val role: String) {

        fun andGoal(goal: String): GoalBuilder = GoalBuilder(role, goal)

    }

    class GoalBuilder(
        private val role: String,
        private val goal: String,
    ) {

        fun andBackstory(backstory: String): RoleGoalBackstorySpec =
            RoleGoalBackstory(role, goal, backstory)

    }
}

/**
 * CrewAI style backstory prompt.
 * Included for users migrating from CrewAI.
 * In Embabel, such structures aren't core to the framework,
 * but merely a PromptContributor that can be used
 * in any action implementation.
 */
data class RoleGoalBackstory(
    override val role: String,
    override val goal: String,
    override val backstory: String,
) : RoleGoalBackstorySpec
