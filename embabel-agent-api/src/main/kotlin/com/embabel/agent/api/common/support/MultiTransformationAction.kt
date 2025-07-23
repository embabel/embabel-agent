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

import com.embabel.agent.api.common.SomeOf
import com.embabel.agent.api.common.Transformation
import com.embabel.agent.api.common.TransformationActionContext
import com.embabel.agent.core.*
import com.embabel.agent.core.support.AbstractAction
import com.embabel.common.core.types.ZeroToOne
import java.lang.reflect.Modifier

/**
 * Transformer that can take multiple inputs.
 * The block takes a List<Any>.
 */
class MultiTransformationAction<O : Any>(
    name: String,
    description: String = name,
    pre: List<String> = emptyList(),
    post: List<String> = emptyList(),
    cost: ZeroToOne = 0.0,
    value: ZeroToOne = 0.0,
    canRerun: Boolean = false,
    qos: ActionQos = ActionQos(),
    inputs: Set<IoBinding>,
    private val inputClasses: List<Class<*>>,
    private val outputClass: Class<O>,
    private val outputVarName: String? = IoBinding.DEFAULT_BINDING,
    private val referencedInputProperties: Set<String>? = null,
    toolGroups: Set<ToolGroupRequirement>,
    private val block: Transformation<List<Any>, O>,
) : AbstractAction(
    name = name,
    description = description,
    pre = pre,
    post = post,
    cost = cost,
    value = value,
    inputs = inputs,
    outputs = calculateOutputs(outputVarName, outputClass),
    toolGroups = toolGroups,
    canRerun = canRerun,
    qos = qos,
) {

    override val domainTypes
        get() = inputClasses + outputClass

    @Suppress("UNCHECKED_CAST")
    override fun execute(
        processContext: ProcessContext,
        action: Action
    ): ActionStatus = ActionRunner.execute(processContext) {
        val inputValues: List<Any> = inputs.map {
            processContext.getValue(variable = it.name, type = it.type)
                ?: throw IllegalArgumentException("Input ${it.name} of type ${it.type} not found in process context")
        }
        logger.debug("Resolved action {} inputs {}", name, inputValues)
        val output = block.transform(
            TransformationActionContext(
                input = inputValues,
                processContext = processContext,
                inputClass = List::class.java as Class<List<Any>>,
                outputClass = outputClass,
                action = this,
            )
        )
        if (output != null) {
            bindOutput(processContext, output)
        }
    }

    private fun bindOutput(
        processContext: ProcessContext,
        output: O
    ) {
        if (!outputClass.isInstance(output)) {
            throw IllegalArgumentException(
                """
                Output of action $name is not of type ${outputClass.name}.
                Did you incorrectly obtain a PromptRunner via 'using' before the end of an action method?
                Take a context object as the last signature of your method signature.
                Return was $output
                """.trimIndent()
            )
        }
        if (SomeOf::class.java.isAssignableFrom(outputClass)) {
            outputClass.declaredFields
                .filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }
                .forEach { field ->
                    field.setAccessible(true)
                    val fieldValue = field.get(output)
                    if (fieldValue != null) {
                        val bindingName = IoBinding.DEFAULT_BINDING // field.name
                        processContext.agentProcess[bindingName] = fieldValue
                        logger.info(
                            "Binding output element of composition action {}: {} to {}",
                            name,
                            bindingName,
                            fieldValue,
                        )
                    } else {
                        logger.info(
                            "Field {} in output of composite action {} is null, not binding",
                            field.name,
                            name,
                        )
                    }
                }
        }
        if (outputVarName != null) {
            logger.debug("Binding output of action {}: {} to {}", name, outputVarName, output)
            processContext.agentProcess[outputVarName] = output
        } else {
            logger.debug("Adding output of action {}: {}", name, output)
            processContext.agentProcess += output
        }
    }

    override fun referencedInputProperties(variable: String): Set<String> {
        return referencedInputProperties ?: run {
            val fields = inputClasses.map { it.declaredFields.map { it.name } }.flatten().toSet()
            fields
        }
    }

    override fun toString(): String {
        return "${javaClass.simpleName}: name=$name"
    }
}

private fun calculateOutputs(outputVarName: String?, outputClass: Class<*>): Set<IoBinding> {
    return if (outputVarName == null) {
        emptySet()
    } else {
        bindingsFrom(outputVarName, outputClass)
    }
}

private fun bindingsFrom(outputVarName: String?, outputClass: Class<*>): Set<IoBinding> {
    if (SomeOf::class.java.isAssignableFrom(outputClass)) {
        return outputClass.declaredFields
            .filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }
            .map { field ->
                IoBinding(
                    // TODO bind to name if requires match
                    name = IoBinding.DEFAULT_BINDING,//field.name,
                    type = field.type.name,
                )
            }
            .toSet()
    }

    return setOf(
        IoBinding(
            name = outputVarName,
            type = outputClass,
        )
    )
}
