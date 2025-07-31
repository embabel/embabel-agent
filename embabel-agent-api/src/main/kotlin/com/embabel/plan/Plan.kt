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
package com.embabel.plan

import com.embabel.common.core.types.HasInfoString
import com.embabel.common.core.types.Named
import com.embabel.common.core.types.ZeroToOne
import com.embabel.common.util.indent
import com.embabel.common.util.indentLines

/**
 * A step in a plan. Can be an action or a goal
 */
interface Step : Named, HasInfoString {

    /**
     * Unique name of the step
     */
    override val name: String

    /**
     * Value of completing this step.
     * From 0 (least valuable) to 1 (most valuable)
     * Steps with 0 value will still be planned if necessary to achieve a result
     */
    val value: ZeroToOne
}

interface Action : Step {

    /**
     * Cost of performing this action
     * Must be between 0 and 1
     * 1 is the most expensive imaginable.
     */
    val cost: ZeroToOne

}

interface Goal : Step

/**
 * Plan to achieve a goal
 * The plan should be reassessed after each action each perform.
 * @param actions The actions to perform, in order
 * @param goal The goal to achieve
 */
open class Plan(
    val actions: List<Action>,
    val goal: Goal,
) : HasInfoString {

    fun isComplete() = actions.isEmpty()

    /**
     * The cost of a plan may be greater than 1.0, even though
     * action costs and all values are 0-1
     */
    val cost: Double get() = actions.sumOf { it.cost }

    val actionsValue: Double get() = actions.sumOf { it.value }

    val netValue: Double get() = goal.value + actionsValue - cost

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        return if (verbose == true) {
            """|${
                actions
                    .mapIndexed { i, a -> i to a.name }
                    .joinToString(" ->\n") {
                        it.second.indent(it.first)
                    }
            }
               |goal: ${goal.name}
               |cost: $cost
               |netValue: $netValue
               |"""
                .trimMargin()
                .indentLines(level = indent)


        } else {
            actions.joinToString(" -> ") { it.name } +
                    "; netValue=$netValue"
        }
    }

}
