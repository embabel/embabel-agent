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
package com.embabel.agent.spec.model

import com.embabel.agent.core.Action
import com.embabel.agent.core.IoBinding
import com.embabel.agent.core.ToolGroupRequirement
import com.embabel.agent.spec.support.PromptedActionDefinitionAction
import com.embabel.common.ai.model.LlmOptions
import com.fasterxml.jackson.annotation.JsonPropertyDescription

/**
 * Serializable action spec
 * Bridge from one type to another
 * @param outputTypeName output type
 * @param nullable whether the output can be null,
 * which will drive replanning
 */
data class PromptedActionDefinition(
    override val name: String,
    override val description: String,
    val llm: LlmOptions = LlmOptions(),
    val inputTypeNames: Set<String>,
    val outputTypeName: String,
    val prompt: String,
    val toolGroups: List<String> = emptyList(),
    val nullable: Boolean = false,
    @param:JsonPropertyDescription("Type of step, must be 'action'")
    override val stepType: String = "action",
) : ActionDefinition {

    override fun emit(stepContext: StepContext): Action {
        val inputs = inputTypeNames.map { IoBinding(variableNameFor(it), it) }.toSet()
        return PromptedActionDefinitionAction(
            spec = this,
            llm = llm,
            inputs = inputs,
            pre = emptyList(),
            post = emptyList(),
            cost = 0.0,
            value = 0.0,
            canRerun = false,
            outputVarName = variableNameFor(outputTypeName),
            toolGroups = toolGroups.map { ToolGroupRequirement(it) }.toSet(),
            domainTypes = stepContext.dataDictionary.domainTypes,
        )
    }

    companion object {

        internal fun variableNameFor(typeName: String): String {
            // Decapitalize first letter after removing any package prefix
            return typeName.substringAfterLast('.').decapitalize()
        }
    }
}
