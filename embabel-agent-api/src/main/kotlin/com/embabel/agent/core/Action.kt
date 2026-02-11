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
package com.embabel.agent.core

import com.embabel.common.util.indent
import com.embabel.common.util.indentLines
import com.embabel.common.util.loggerFor
import com.embabel.plan.common.condition.ConditionAction
import com.embabel.plan.common.condition.EffectSpec


/**
 * Core Action model in Agent system.
 * An individual action step used in an Agent
 * Not intended for direct use by application code.
 * User applications should use the annotation programming model
 * with @Agentic and @Action or the Kotlin DSL, or a non-code
 * representation such as YML.
 * @qos Quality of Service. Governs retry policy
 */
interface Action : DataFlowStep, ConditionAction, ActionRunner, DataDictionary, ToolGroupConsumer {

    /**
     * Can this action be run again if it has already run in the given AgentProcess?
     * Must be set to true to allow looping style behavior.
     */
    val canRerun: Boolean

    /**
     * Does this action have no external side effects?
     * Read-only actions only analyze data and produce derived objects
     * without modifying external systems (APIs, databases, files, etc.).
     * Used for learning/catchup modes where we want to ingest and understand
     * data without triggering mutations.
     */
    val readOnly: Boolean get() = false

    /**
     * Quality of Service for this action.
     */
    val qos: ActionQos

    override val domainTypes: Collection<DomainType>
        get() =
            (inputs + outputs)
                .map {
                    referencedType(it, this)
                }.toSet()

    private fun referencedType(
        binding: IoBinding,
        action: Action,
    ): DynamicType {
        var type = DynamicType(name = binding.type)
        for (prop in action.referencedInputProperties(binding.name)) {
            loggerFor<Action>().debug("Discovered property {}", prop)
            type = type.withProperty(ValuePropertyDefinition(name = prop))
        }
        loggerFor<Action>().debug(
            "Action {} references variable {} of type {}: {}",
            action.name,
            binding.name,
            binding.type,
            type,
        )

        return type
    }

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String =
        """|name: $name
           |preconditions:
           |${
            preconditions
                .map { it.key to "${it.key}: ${it.value}" }
                .sortedBy { it.first }
                .joinToString("\n") { it.second.indent(1) }
        }
           |postconditions:
           |${
            effects
                .map { it.key to "${it.key}: ${it.value}" }
                .sortedBy { it.first }
                .joinToString("\n") { it.second.indent(1) }
        }
           |"""
            .trimMargin()
            .indentLines(indent)

    fun shortName(): String {
        return name.split('.').lastOrNull() ?: name
    }

}

/**
 * Serializable action metadata
 */
data class ActionMetadata(
    val name: String,
    val description: String,
    val inputs: Set<IoBinding>,
    val outputs: Set<IoBinding>,
    val preconditions: EffectSpec,
    val effects: EffectSpec,
    val canRerun: Boolean,
    val readOnly: Boolean,
    val qos: ActionQos,
) {

    constructor(
        action: Action,
    ) : this(
        name = action.name,
        description = action.description,
        inputs = action.inputs,
        outputs = action.outputs,
        preconditions = action.preconditions,
        effects = action.effects,
        canRerun = action.canRerun,
        readOnly = action.readOnly,
        qos = action.qos,
    )
}
