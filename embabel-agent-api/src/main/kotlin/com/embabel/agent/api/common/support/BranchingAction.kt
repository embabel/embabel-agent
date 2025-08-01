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

import com.embabel.agent.api.common.Transformation
import com.embabel.agent.api.common.TransformationActionContext
import com.embabel.agent.core.*
import com.embabel.agent.core.support.AbstractAction
import com.embabel.common.core.types.ZeroToOne

/**
 * Return type to indicate that the action can return one of two types.
 * Facilitates branching
 */
data class Branch<B, C>(
    val left: B? = null,
    val right: C? = null,
) {

    init {
        if (left == null && right == null) {
            throw IllegalArgumentException("At least one of left or right must be non-null")
        }
    }

    fun get(): Any? {
        return left ?: right
    }
}

/**
 * Action that declares one of two types of output.
 * The code block must return a Branch object, which will be used to determine which output to use.
 */
open class BranchingAction<I, O1, O2>(
    name: String,
    description: String = name,
    pre: List<String> = emptyList(),
    post: List<String> = emptyList(),
    cost: ZeroToOne = 0.0,
    value: ZeroToOne = 0.0,
    canRerun: Boolean = false,
    qos: ActionQos = ActionQos(),
    private val inputClass: Class<I>,
    private val leftOutputClass: Class<O1>,
    private val rightOutputClass: Class<O2>,
    private val inputVarName: String = IoBinding.DEFAULT_BINDING,
    private val outputVarName: String? = IoBinding.DEFAULT_BINDING,
    private val referencedInputProperties: Set<String>? = null,
    toolGroups: Set<ToolGroupRequirement>,
    private val block: Transformation<I, Branch<O1, O2>>,
) : AbstractAction(
    name = name,
    description = description,
    pre = pre,
    post = post,
    cost = cost,
    value = value,
    inputs = expandInputBindings(inputVarName, inputClass),
    outputs = setOf(
        IoBinding(IoBinding.DEFAULT_BINDING, leftOutputClass.name),
        IoBinding(IoBinding.DEFAULT_BINDING, rightOutputClass.name)
    ),
    toolGroups = toolGroups,
    canRerun = canRerun,
    qos = qos,
) {

    override val domainTypes
        get() = setOf(inputClass, leftOutputClass, rightOutputClass)

    @Suppress("UNCHECKED_CAST")
    override fun execute(
        processContext: ProcessContext,
        action: Action,
    ): ActionStatus = ActionRunner.execute(processContext) {
        val input = processContext.getValue(inputVarName, inputClass.name) as I
        val branch = block.transform(
            TransformationActionContext<I, Branch<O1, O2>>(
                input = input,
                processContext = processContext,
                action = this,
                inputClass = inputClass as Class<I>,
                outputClass = Branch::class.java as Class<Branch<O1, O2>>,
            )
        )
        if (branch != null) {
            val output = branch.get()
            if (output != null) {
                if (outputVarName != null) {
                    processContext.blackboard[outputVarName] = output
                } else {
                    processContext.blackboard += output
                }
            }
        }
    }

    override fun referencedInputProperties(variable: String): Set<String> {
        return referencedInputProperties ?: run {
            val fields = inputClass.declaredFields.map { it.name }.toSet()
            fields
        }
    }

    override fun toString(): String =
        "${javaClass.simpleName}: name=$name, left=${leftOutputClass.simpleName}, right=${rightOutputClass.simpleName}"
}
