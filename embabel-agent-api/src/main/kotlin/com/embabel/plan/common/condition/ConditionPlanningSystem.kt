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
package com.embabel.plan.common.condition

import com.embabel.common.util.indent
import com.embabel.common.util.indentLines
import com.embabel.plan.PlanningSystem

/**
 * Planning system based on actions and goals based on conditions, such as GOAP.
 */
data class ConditionPlanningSystem(
    override val actions: Set<ConditionAction>,
    override val goals: Set<ConditionGoal>,
) : PlanningSystem {

    constructor(
        actions: Collection<ConditionAction>,
        goal: ConditionGoal,
    ) : this(
        actions = actions.toSet(),
        goals = setOf(goal),
    )

    fun knownPreconditions(): Set<String> {
        return actions.flatMap { it.preconditions.keys }.toSet()
    }

    fun knownEffects(): Set<String> {
        return actions.flatMap { it.effects.keys }.toSet()
    }

    override fun knownConditions(): Set<String> {
        return knownPreconditions() + knownEffects()
    }

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String =
        javaClass.simpleName.indent(indent) + "\n" +
                """|actions:
                   |${actions.joinToString("\n") { it.name.indent(1) }}
                   |goals:
                   |${goals.joinToString("\n") { it.name.indent(1) }}
                   |knownPreconditions:
                   |${knownPreconditions().sortedBy { it }.joinToString("\n") { it.indent(1) }}
                   |knownEffects:
                   |${knownEffects().sortedBy { it }.joinToString("\n") { it.indent(1) }}
                   |"""
                    .trimMargin()
                    .indentLines(indent + 1)
}
