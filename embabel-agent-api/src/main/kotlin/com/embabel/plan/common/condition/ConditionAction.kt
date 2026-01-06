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

import com.embabel.common.core.types.ZeroToOne
import com.embabel.common.util.indent
import com.embabel.plan.Action
import com.embabel.plan.CostComputation
import com.embabel.plan.WorldState

/**
 * Action in a GOAP system.
 */
interface ConditionAction : ConditionStep, Action {

    /**
     * Expected effects of this action.
     * World state should be checked afterward as these effects may not
     * have been achieved
     */
    val effects: EffectSpec

    override val knownConditions: Set<String>
        get() = preconditions.keys + effects.keys

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String =
        "$name - pre=${preconditions}".indent(indent)


    companion object {

        operator fun invoke(
            name: String,
            pre: Collection<String> = emptySet(),
            preconditions: EffectSpec = pre.associateWith { ConditionDetermination.TRUE },
            post: Collection<String> = emptySet(),
            effects: EffectSpec = post.associateWith { ConditionDetermination.TRUE },
            cost: (w: WorldState) -> ZeroToOne = { 0.0 },
            value: (w: WorldState) -> ZeroToOne = { 0.0 },
        ): ConditionAction {
            return SimpleConditionAction(
                name = name,
                preconditions,
                effects,
                cost = cost,
                value = value,
            )
        }
    }

}

private data class SimpleConditionAction(
    override val name: String,
    override val preconditions: EffectSpec,
    override val effects: EffectSpec,
    override val cost: CostComputation,
    override val value: CostComputation,
) : ConditionAction
