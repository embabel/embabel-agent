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
import com.embabel.plan.CostComputation
import com.embabel.plan.Goal
import com.fasterxml.jackson.annotation.JsonIgnore

/**
 * Goal in a GOAP system.
 */
interface ConditionGoal : ConditionStep, Goal {

    @get:JsonIgnore
    override val knownConditions: Set<String>
        get() = preconditions.keys

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String = "$name - pre=${preconditions}".indent(indent)

    companion object {

        operator fun invoke(
            name: String,
            preconditions: EffectSpec = mapOf(name to ConditionDetermination.Companion(true)),
            value: CostComputation = { 0.0 },
        ): ConditionGoal {
            return SimpleConditionGoal(name, preconditions, value)
        }

        operator fun invoke(
            name: String,
            pre: Collection<String>,
            value: CostComputation = { 0.0 },
        ): ConditionGoal {
            return SimpleConditionGoal(
                name,
                pre.associateWith { ConditionDetermination.TRUE },
                value,
            )
        }
    }

}

private data class SimpleConditionGoal(
    override val name: String,
    override val preconditions: EffectSpec,
    override val value: CostComputation,
) : ConditionGoal
