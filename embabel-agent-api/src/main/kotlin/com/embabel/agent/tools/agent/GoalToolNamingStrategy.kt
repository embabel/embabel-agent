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
package com.embabel.agent.tools.agent

import com.embabel.agent.core.Goal

/**
 * Names published tools.
 */
interface GoalToolNamingStrategy {
    fun nameForGoal(goal: Goal): String
}

/**
 * A simple naming strategy that uses the last two segments of the goal name
 * to create a tool name.
 * For example, "com.myco.MyAgent.myGoal" becomes "MyAgent_myGoal".
 */
object SimpleGoalClassAndNameToolNamingStrategy : GoalToolNamingStrategy {
    override fun nameForGoal(goal: Goal): String {
        return goal.name.split(".").takeLast(2).joinToString("_")
    }
}

open class PrefixedGoalToolNamingStrategy(
    private val prefix: String,
) : GoalToolNamingStrategy {
    override fun nameForGoal(goal: Goal): String {
        return "${prefix}_${SimpleGoalClassAndNameToolNamingStrategy.nameForGoal(goal)}"
    }
}

/**
 * Prefix tool names with the application name.
 */
class ApplicationNameGoalToolNamingStrategy(
    applicationName: String,
) : PrefixedGoalToolNamingStrategy(
    // Sanitize the application name to ensure it is a valid tool name
    applicationName.replace(Regex("[^a-zA-Z0-9]"), "_")
)
