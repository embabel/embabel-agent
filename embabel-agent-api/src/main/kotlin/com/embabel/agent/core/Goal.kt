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
package com.embabel.agent.core

import com.embabel.common.core.types.ZeroToOne
import com.embabel.plan.goap.ConditionDetermination
import com.embabel.plan.goap.EffectSpec
import com.embabel.plan.goap.GoapGoal
import com.fasterxml.jackson.annotation.JsonIgnore

/**
 * Agent platform goal. Exposes GOAP metadata.
 * @param name name of the goal
 * @param description description of the goal. This should be sufficiently detailed to enable goal choice by an LLM.
 * The goal description may also be exposed to MCP clients as a hint for the goal's purpose,
 * so ensure that it is clear and unambiguous.
 * @param pre preconditions for the goal, as a set of strings. These are the conditions that must be true before the goal can be achieved.
 * @param inputs inputs required for the goal, as a set of IoBinding objects. These are the inputs that must be provided to achieve the goal.
 * @param value value of the goal, as a ZeroToOne. This is the value of achieving the goal.
 * @param tags Set of tags describing classes or capabilities for this specific skill.
 *    example: ["cooking", "customer support", "billing"]
 * @param examples The set of example scenarios that the skill can perform.
 * Will be used by the client as a hint to understand how the skill can be used.
 *  example: ["I need a recipe for bread"]
 *  @param startingInputTypes input types that we can prompt the user from to get to this goal.
 *  Useful for MCP prompts. A Goal may not know all possible input types, but
 *  it is still useful to be able to specify some of them.
 */
data class Goal(
    override val name: String,
    override val description: String,
    val pre: Set<String> = emptySet(),
    override val inputs: Set<IoBinding> = emptySet(),
    override val value: ZeroToOne = 0.0,
    val tags: Set<String> = emptySet(),
    val examples: Set<String> = emptySet(),
    val startingInputTypes: Set<Class<*>> = emptySet(),
) : GoapGoal, AgentSystemStep {

    // These methods are for Java, to obviate the builder antipattern
    fun withPrecondition(preconditions: String): Goal {
        return copy(pre = pre + preconditions)
    }

    fun withValue(value: Double): Goal {
        return copy(value = value)
    }

    @JsonIgnore
    override val preconditions: EffectSpec =
        run {
            val conditions = pre.associate { it to ConditionDetermination.Companion(true) }.toMutableMap()
            inputs.forEach { input ->
                conditions[input.value] = ConditionDetermination.Companion(true)
            }
            conditions
        }

    override fun infoString(verbose: Boolean?): String {
        val separator = if (verbose == true) "\n\t\t" else " - "
        return "$description: $name${separator}pre=${preconditions} value=${value}"
    }

    companion object {

        /**
         * Convenient method to create a goal requiring creating an instance of this type.
         * @param description description of the goal
         * @param type type of the instance to create. See [IoBinding].
         * @param name name of the goal, defaults to "Create ${type.simpleName}"
         */
        @JvmStatic
        @JvmOverloads
        fun createInstance(
            description: String,
            type: Class<*>,
            name: String = "Create ${type.simpleName}",
            tags: Set<String> = emptySet(),
            examples: Set<String> = emptySet(),
        ): Goal {
            return invoke(
                name = name,
                description = description,
                satisfiedBy = type,
                tags = tags,
                examples = examples,
            )
        }

        operator fun invoke(
            name: String,
            description: String,
            satisfiedBy: Class<*>? = null,
            requires: Set<Class<*>> = if (satisfiedBy != null) {
                setOf(satisfiedBy)
            } else {
                emptySet()
            },
            inputs: Set<IoBinding> = requires.map {
                IoBinding(
                    type = it,
                )
            }.toSet(),
            pre: List<Condition> = emptyList(),
            value: Double = 0.0,
            tags: Set<String> = emptySet(),
            examples: Set<String> = emptySet(),
        ): Goal {
            return Goal(
                name = name,
                description = description,
                inputs = inputs,
                pre = pre.map { it.name }.toSet(),
                value = value,
                tags = tags,
                examples = examples,
            )
        }
    }

}
