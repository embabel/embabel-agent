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
package com.embabel.agent.core.support

import com.embabel.agent.core.Action
import com.embabel.agent.core.ActionQos
import com.embabel.agent.core.IoBinding
import com.embabel.agent.core.ToolGroupRequirement
import com.embabel.plan.CostComputation
import com.embabel.plan.goap.ConditionDetermination
import com.embabel.plan.goap.EffectSpec
import com.fasterxml.jackson.annotation.JsonIgnore
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object Rerun {
    const val HAS_RUN_CONDITION_PREFIX = "hasRun_"

    /**
     * Compute the name of the condition for this action having run
     */
    fun hasRunCondition(action: Action): String {
        return "$HAS_RUN_CONDITION_PREFIX${action.name}"
    }
}

/**
 * Abstract action implementation that computes outputs.
 * @param name the name of the action
 * @param description a description of the action
 * @param pre a list of preconditions. These are additional to the input
 * @param post a list of expected effects. These are additional to the output
 * @param cost the cost of the action
 * @param inputs the input bindings
 * @param outputs the output bindings
 * @param canRerun can we rerun this action?
 * @param qos quality of service requirements
 */
abstract class AbstractAction(
    override val name: String,
    override val description: String = name,
    val pre: List<String> = emptyList(),
    val post: List<String> = emptyList(),
    override val cost: CostComputation = { 0.0 },
    override val value: CostComputation = { 0.0 },
    override val inputs: Set<IoBinding> = emptySet(),
    override val outputs: Set<IoBinding> = emptySet(),
    override val toolGroups: Set<ToolGroupRequirement>,
    override val canRerun: Boolean,
    override val qos: ActionQos = ActionQos(),
) : Action {

    protected val logger: Logger = LoggerFactory.getLogger(javaClass)

    @JsonIgnore
    override val preconditions: EffectSpec =
        run {
            val conditions = pre.associateWith { ConditionDetermination(true) }.toMutableMap()
            inputs.forEach { input ->
                conditions[input.value] = ConditionDetermination(true)
            }
            if (!canRerun) {
                outputs.filter {
                    !inputs.contains(it)
                }.forEach { output ->
                    conditions[output.value] = ConditionDetermination(false)
                }
                conditions[Rerun.hasRunCondition(this)] = ConditionDetermination(false)
            }
            conditions
        }

    override val effects: EffectSpec
        get() {
            val conditions = post.associateWith { ConditionDetermination(true) }.toMutableMap()
            outputs.forEach { output ->
                val jvmType = output.resolveJvmType()
                if (jvmType == null) {
                    // Just take the type at face value
                    conditions[output.value] = ConditionDetermination(true)
                } else {
                    // We have a JVM type. We need to look for subclasses
                    // because these are also possible outputs and hence postconditions
                    val possibleOutputTypes = jvmType.children() + jvmType
                    possibleOutputTypes.forEach { pot ->
                        conditions["${output.name}:${pot.name}"] = ConditionDetermination(true)
                    }
                }
            }
            conditions += Rerun.hasRunCondition(this) to ConditionDetermination(true)
            return conditions
        }
}
