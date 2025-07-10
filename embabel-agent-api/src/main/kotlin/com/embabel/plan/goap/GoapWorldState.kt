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

import com.embabel.common.util.color
import com.embabel.plan.WorldState
import java.time.Instant

private const val LUMON_MEMBRANE_COLOR = 0xbeb780

typealias GoapState = Map<String, ConditionDetermination>

/**
 * Determine the world state: the conditions that drive GOAP planning
 * Our conditions can have 3 values: true, false or unknown.
 * Unknown may be genuinely unknown, or it may mean that the condition has been lazily evaluated
 * and needs to be evaluated again.
 */
interface WorldStateDeterminer {

    /**
     * Determine world state. Optimization is permitted.
     * Implementations may choose to return UNKNOWN for expensive conditions,
     * which the planner should invoke lazily
     */
    fun determineWorldState(): GoapWorldState

    /**
     * Determine an individual condition, disabling any caching.
     * Any previously UNKNOWN condition must be re-evaluated if possible.
     */
    fun determineCondition(condition: String): ConditionDetermination

    companion object {

        fun fromMap(
            map: Map<String, ConditionDetermination> = emptyMap(),
        ): WorldStateDeterminer =
            FromMapWorldStateDeterminer(map)

    }

}

private class FromMapWorldStateDeterminer(
    private val map: Map<String, ConditionDetermination>,
) : WorldStateDeterminer {

    override fun determineWorldState(): GoapWorldState = GoapWorldState(map)

    override fun determineCondition(condition: String): ConditionDetermination {
        return map[condition] ?: ConditionDetermination.UNKNOWN
    }
}

/**
 * Represents the state of the world at any time.
 * World state is just a map. This class exposes operations on the state.
 */
data class GoapWorldState(
    val state: GoapState = emptyMap(),
) : WorldState {

    override val timestamp: Instant = Instant.now()

    fun unknownConditions(): Collection<String> =
        state.entries
            .filter { it.value == ConditionDetermination.UNKNOWN }
            .map { it.key }

    /**
     * Generate variants with different definite values for the given condition
     */
    internal fun variants(unknownCondition: String): Collection<GoapWorldState> {
        return setOf(ConditionDetermination.TRUE, ConditionDetermination.FALSE).map {
            this + (unknownCondition to it)
        }
    }

    /**
     * Generate all possible changes to the world state where only one condition is changed
     * For each existing condition, generate variants where that condition is flipped to the other values
     * (TRUE -> FALSE and UNKNOWN, FALSE -> TRUE and UNKNOWN, UNKNOWN -> TRUE and FALSE)
     */
    fun withOneChange(): Collection<GoapWorldState> {
        val result = mutableListOf<GoapWorldState>()

        for ((condition, currentValue) in state) {
            // Generate variants where this condition has a different value
            when (currentValue) {
                ConditionDetermination.TRUE -> {
                    result.add(GoapWorldState(state + (condition to ConditionDetermination.FALSE)))
                    result.add(GoapWorldState(state + (condition to ConditionDetermination.UNKNOWN)))
                }

                ConditionDetermination.FALSE -> {
                    result.add(GoapWorldState(state + (condition to ConditionDetermination.TRUE)))
                    result.add(GoapWorldState(state + (condition to ConditionDetermination.UNKNOWN)))
                }

                ConditionDetermination.UNKNOWN -> {
                    result.add(GoapWorldState(state + (condition to ConditionDetermination.TRUE)))
                    result.add(GoapWorldState(state + (condition to ConditionDetermination.FALSE)))
                }
            }
        }

        return result
    }

    override fun infoString(verbose: Boolean?): String {
        return if (verbose == true)
            "\n\t" + state.entries.sortedByDescending { it.value }
                .joinToString("\n\t") { (k, v) -> if (v == ConditionDetermination.TRUE) "$k: $v".color(LUMON_MEMBRANE_COLOR) else "$k: $v" }
        else state.toString()
    }

    operator fun plus(pair: Pair<String, ConditionDetermination>): GoapWorldState =
        GoapWorldState(this.state + pair)
}
