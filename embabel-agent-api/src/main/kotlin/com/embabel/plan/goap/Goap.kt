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
package com.embabel.plan.goap

import com.embabel.common.core.types.ZeroToOne
import com.embabel.common.util.indent
import com.embabel.common.util.indentLines
import com.embabel.plan.*
import com.fasterxml.jackson.annotation.JsonIgnore

interface GoapPlanner : Planner<GoapPlanningSystem, GoapWorldState, GoapPlan>

/**
 * Conditions may be true, false or unknown
 */
enum class ConditionDetermination {
    TRUE, FALSE, UNKNOWN;

    /**
     * Treat UNKNOWN as false
     */
    fun asTrueOrFalse(): ConditionDetermination = when (this) {
        TRUE -> TRUE
        else -> FALSE
    }

    // Was it calculated?

    companion object {
        operator fun invoke(value: Boolean?) = when (value) {
            true -> TRUE
            false -> FALSE
            null -> UNKNOWN
        }
    }
}

typealias EffectSpec = Map<String, ConditionDetermination>

private fun preconditionsSatisfied(
    preconditions: EffectSpec,
    currentState: GoapState,
): Boolean =
    preconditions.all { (key, value) -> currentState[key] == value }

interface GoapStep : Step {

    /**
     * Conditions that must be true for this step to execute
     */
    val preconditions: EffectSpec

    /**
     * The names of all conditions that are referenced by this step
     */
    val knownConditions: Set<String>

    /**
     * Whether the step is available in the current world state
     */
    fun isAchievable(currentState: GoapWorldState): Boolean {
        return preconditionsSatisfied(preconditions, currentState.state)
    }

}

/**
 * Action in a GOAP system.
 */
interface GoapAction : GoapStep, Action {

    /**
     * Expected effects of this action.
     * World state should be checked afterwards as these effects may not
     * have been achieved
     */
    val effects: EffectSpec

    override val knownConditions: Set<String>
        get() = preconditions.keys + effects.keys

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String =
        "$name - pre=${preconditions} cost=$cost value=${value}".indent(indent)


    companion object {

        operator fun invoke(
            name: String,
            pre: Collection<String> = emptySet(),
            preconditions: EffectSpec = pre.associateWith { ConditionDetermination.TRUE },
            post: Collection<String> = emptySet(),
            effects: EffectSpec = post.associateWith { ConditionDetermination.TRUE },
            cost: ZeroToOne = 0.0,
            value: ZeroToOne = 0.0,
        ): GoapAction {
            return SimpleGoapAction(
                name = name,
                preconditions,
                effects,
                cost = cost,
                value = value,
            )
        }
    }

}

private data class SimpleGoapAction(
    override val name: String,
    override val preconditions: EffectSpec,
    override val effects: EffectSpec,
    override val cost: ZeroToOne,
    override val value: ZeroToOne,
) : GoapAction

/**
 * Goal in a GOAP system.
 */
interface GoapGoal : GoapStep,
    Goal {

    @get:JsonIgnore
    override val knownConditions: Set<String>
        get() = preconditions.keys

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String = "$name - pre=${preconditions} value=${value}".indent(indent)

    companion object {

        operator fun invoke(
            name: String,
            preconditions: EffectSpec = mapOf(name to ConditionDetermination(true)),
            value: ZeroToOne = 0.0,
        ): GoapGoal {
            return GoapGoalImpl(name, preconditions, value)
        }

        operator fun invoke(
            name: String,
            pre: Collection<String>,
            value: ZeroToOne = 0.0,
        ): GoapGoal {
            return GoapGoalImpl(
                name,
                pre.associateWith { ConditionDetermination.TRUE },
                value,
            )
        }
    }

}

private data class GoapGoalImpl(
    override val name: String,
    override val preconditions: EffectSpec,
    override val value: ZeroToOne = 0.0,
) : GoapGoal

data class GoapPlanningSystem(
    override val actions: Set<GoapAction>,
    override val goals: Set<GoapGoal>,
) : PlanningSystem {

    constructor(
        actions: Collection<GoapAction>,
        goal: GoapGoal,
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

    fun knownConditions(): Set<String> {
        return knownPreconditions() + knownEffects()
    }

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String =
        "GOAP system:".indent(indent) + "\n" +
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

class GoapPlan(
    actions: List<Action>,
    goal: Goal,
    val worldState: GoapWorldState,
) : Plan(actions, goal) {

    override fun toString(): String {
        return infoString(verbose = false)
    }
}
