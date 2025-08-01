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
package com.embabel.agent.api.common.support

import com.embabel.agent.api.common.Aggregation
import com.embabel.agent.api.common.Transformation
import com.embabel.agent.api.common.TransformationActionContext
import com.embabel.agent.core.*
import com.embabel.agent.core.support.AbstractAction
import com.embabel.common.core.types.ZeroToOne
import java.lang.reflect.Modifier


/**
 * Transformation action that runs custom code.
 */
open class TransformationAction<I, O>(
    name: String,
    description: String = name,
    pre: List<String> = emptyList(),
    post: List<String> = emptyList(),
    cost: ZeroToOne = 0.0,
    value: ZeroToOne = 0.0,
    canRerun: Boolean = false,
    qos: ActionQos = ActionQos(),
    private val inputClass: Class<I>,
    val outputClass: Class<O>,
    private val inputVarName: String = IoBinding.DEFAULT_BINDING,
    private val outputVarName: String? = IoBinding.DEFAULT_BINDING,
    private val referencedInputProperties: Set<String>? = null,
    toolGroups: Set<ToolGroupRequirement>,
    private val block: Transformation<I, O>,
) : AbstractAction(
    name = name,
    description = description,
    pre = pre,
    post = post,
    cost = cost,
    value = value,
    inputs = expandInputBindings(inputVarName, inputClass),
    outputs = if (outputVarName == null) emptySet() else setOf(IoBinding(outputVarName, outputClass.name)),
    toolGroups = toolGroups,
    canRerun = canRerun,
    qos = qos,
) {

    override val domainTypes
        get() = setOf(inputClass, outputClass)

    @Suppress("UNCHECKED_CAST")
    override fun execute(
        processContext: ProcessContext,
        action: Action,
    ): ActionStatus = ActionRunner.execute(processContext) {
        val input = processContext.getValue(inputVarName, inputClass.name) as I
        val output = block.transform(
            TransformationActionContext(
                input = input,
                processContext = processContext,
                action = this,
                inputClass = inputClass,
                outputClass = outputClass,
            )
        )
        if (output != null) {
            if (outputVarName != null) {
                processContext.blackboard[outputVarName] = output
            } else {
                processContext.blackboard += output
            }
        }
    }

    override fun referencedInputProperties(variable: String): Set<String> {
        return referencedInputProperties ?: run {
            val fields = inputClass.declaredFields.map { it.name }.toSet()
            fields
        }
    }

    override fun toString() =
        "${javaClass.simpleName}: name=$name"
}

/**
 * Create input binding(s) for the given variable name and type.
 * Allow for megazords (Aggregations) and decompose them into their individual fields.
 */
fun expandInputBindings(
    inputVarName: String,
    inputClass: Class<*>
): Set<IoBinding> {
    if (inputClass == Unit::class.java) {
        // Unit is a special case, we don't want to bind any inputs
        return emptySet()
    }
    if (Aggregation::class.java.isAssignableFrom(inputClass)) {
        return inputClass.declaredFields
            .filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }
            .map { field ->
                // Make field accessible if it's private
                field.isAccessible = true
                IoBinding(
                    type = field.type
                )
            }
            .toSet()
    }

    // Default case: just return the input itself
    return setOf(IoBinding(inputVarName, inputClass.name))
}
