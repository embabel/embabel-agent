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
package com.embabel.agent.yml.support

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.core.*
import com.embabel.agent.core.support.AbstractAction
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.yml.PromptedActionDefinition
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.core.types.ZeroToOne
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * Action implementation that executes the relevant metadata
 */
internal open class PromptedActionDefinitionAction(
    val spec: PromptedActionDefinition,
    private val llm: LlmOptions,
    inputs: Set<IoBinding>,
    pre: List<String>,
    post: List<String>,
    cost: ZeroToOne = 0.0,
    value: ZeroToOne = 0.0,
    canRerun: Boolean = false,
    qos: ActionQos = ActionQos(),
    private val outputVarName: String = spec.outputTypeName.decapitalize(),
    toolGroups: Set<ToolGroupRequirement> = spec.toolGroups.map {
        ToolGroupRequirement(it)
    }.toSet(),
    override val domainTypes: Collection<DomainType>,
) : AbstractAction(
    name = spec.name,
    description = spec.description,
    pre = pre,
    post = post,
    cost = { cost },
    value = { value },
    inputs = inputs,
    outputs = setOf(
        IoBinding(
            name = outputVarName,
            type = spec.outputTypeName,
        )
    ),
    toolGroups = toolGroups,
    canRerun = canRerun,
    qos = qos,
) {

    @Suppress("UNCHECKED_CAST")
    override fun execute(
        processContext: ProcessContext,
    ): ActionStatus = ActionRunner.execute(processContext) {
        val domainTypes = this.domainTypes
        val resolved = domainTypes.find { it.name == spec.outputTypeName }
            ?: throw IllegalArgumentException("Output type '${spec.outputTypeName}' not found in agent schema types.")
        val output = when (resolved) {
            is JvmType -> {
                callLlmWithJvmType(processContext, resolved)
            }

            is DynamicType -> {
                callLlmWithDynamicType(processContext, resolved)
            }
        }
        processContext.blackboard[outputVarName] = output
    }

    private fun callLlmWithJvmType(
        processContext: ProcessContext,
        outputType: JvmType,
    ): Any {
        val context = OperationContext(
            processContext = processContext,
            operation = this,
            toolGroups = toolGroups,
        )
        val templateModel = templateModel(processContext)
        logger.info("Input map for action name {}: {}", name, templateModel)

        val prompt = context.agentPlatform().platformServices.templateRenderer
            .renderLiteralTemplate(spec.prompt, templateModel)

        val result = context.ai()
            .withLlm(llm)
            .withId("action:${name}")
            .withToolGroups(toolGroups.map { it.role }.toSet())
            .createObject(prompt, outputType.clazz)
        return result
    }

    private fun callLlmWithDynamicType(
        processContext: ProcessContext,
        outputType: DynamicType,
    ): Any {
        val context = OperationContext(
            processContext = processContext,
            operation = this,
            toolGroups = toolGroups,
        )

        val templateModel = templateModel(processContext)
        logger.info("Input map for action name {}: {}", name, templateModel)

        val outputSchemaType =
            processContext.agentProcess.agent.dynamicTypes.find { it.name == spec.outputTypeName }
                ?: throw IllegalArgumentException("Output type '${spec.outputTypeName}' not found in agent schema types.")
        val jsonSchema = JsonSchemaGenerator.generate(outputSchemaType)
        logger.info("Schema type for action name {}: {}", name, outputSchemaType)
        val prompt = context.agentPlatform().platformServices.templateRenderer
            .renderLiteralTemplate(spec.prompt, templateModel)

        val result = context.ai()
            .withLlm(llm)
            .withId("action:${name}")
            .withToolGroups(toolGroups.map { it.role }.toSet())
            .withPromptContributor(
                PromptContributor.fixed(
                    """
                       Return only JSON.
                       DO NOT INCLUDE ``` or any other formatting.
                    You response must adhere to the following JSON schema:
                    $jsonSchema
                """.trimIndent()
                )
            )
            .generateText(prompt = prompt)

//        logger.info("Result of prompt rendered {}: {}", renderedPrompt, result)
        val output = jacksonObjectMapper().readValue(result, Map::class.java)
        return output
    }

    private fun templateModel(processContext: ProcessContext): Map<String, Any> {
        val templateModel = mutableMapOf<String, Any>()
        for (input in inputs) {
            val inputValue = processContext.blackboard[input.name]
                ?: throw IllegalArgumentException("Input variable '${input.name}' not found in process context.")
            templateModel[input.name] = inputValue
        }
        // Special case for user input, we use the simple name
        processContext.blackboard.last<UserInput>()?.let { userInput ->
            templateModel["userInput"] = userInput
        }
        return templateModel
    }

    override fun referencedInputProperties(variable: String): Set<String> {
        return emptySet()
    }

    override fun toString() =
        "${javaClass.simpleName}: name=$name"
}


object JsonSchemaGenerator {

    fun generate(schema: DynamicType): String {
        val requiredFields = schema.properties.map { "\"${it.name}\"" }
        if (schema.properties.any { it is DomainTypePropertyDefinition }) {
            throw IllegalArgumentException("JSON schema generation for DomainTypePropertyDefinition is not supported.")
        }
        val properties = schema.properties
            .filterIsInstance<SimplePropertyDefinition>()
            .joinToString(",\n    ") {
                "\"${it.name}\": { \"type\": \"${it.type}\" }"
            }
        val requiredSection = if (requiredFields.isNotEmpty()) {
            ",\n  \"required\": [${requiredFields.joinToString(", ")}]"
        } else ""

        return """
{
  "type": "object",
  "properties": {
    $properties
  }$requiredSection
}
        """.trimIndent()
    }
}
