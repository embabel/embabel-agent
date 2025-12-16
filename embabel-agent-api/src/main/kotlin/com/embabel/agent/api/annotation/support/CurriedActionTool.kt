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
package com.embabel.agent.api.annotation.support

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.Action
import com.embabel.agent.core.ActionStatusCode
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.Blackboard
import com.embabel.agent.core.IoBinding
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

/**
 * Creates a curried [Tool] from an [Action] based on current blackboard state.
 *
 * This implements function currying for actions: if an action requires parameters
 * (Person, Ai, ComplicatedThing) and ComplicatedThing is already on the blackboard,
 * the tool only exposes Person as a parameter. Ai is always handled automatically
 * as an AI-injected parameter.
 *
 * The exposed tool parameters are determined by:
 * 1. Starting with action's input bindings (already excludes AI-injected types like Ai, OperationContext)
 * 2. Filtering out bindings whose values are already available on the blackboard
 *
 * @param action The action to wrap as a tool
 * @param blackboard The blackboard to check for existing values
 * @param objectMapper Object mapper for JSON parsing
 */
class CurriedActionTool(
    private val action: Action,
    private val blackboard: Blackboard,
    private val objectMapper: ObjectMapper,
) : Tool {

    private val logger = LoggerFactory.getLogger(CurriedActionTool::class.java)

    /**
     * Input bindings that still need values (not yet on blackboard).
     * This is computed at construction time based on current blackboard state.
     */
    private val requiredInputs: Set<IoBinding> = computeRequiredInputs()

    private fun computeRequiredInputs(): Set<IoBinding> {
        // Check both map values and objects list for available inputs
        val mapValues = blackboard.expressionEvaluationModel().values.filterNotNull()
        val objectValues = blackboard.objects
        val allValues = (mapValues + objectValues).distinct()

        return action.inputs.filter { input ->
            // Check if a value of this type exists on the blackboard
            val existsOnBlackboard = allValues.any { value ->
                Companion.isCompatibleType(value, input.type)
            }
            if (existsOnBlackboard) {
                logger.debug(
                    "Action '{}' input '{}' of type '{}' already on blackboard - currying out",
                    action.shortName(),
                    input.name,
                    input.type
                )
            }
            !existsOnBlackboard
        }.toSet()
    }


    override val definition: Tool.Definition = object : Tool.Definition {
        override val name: String = action.shortName()
        override val description: String = buildDescription()
        override val inputSchema: Tool.InputSchema = buildInputSchema()
    }

    override val metadata: Tool.Metadata = Tool.Metadata.DEFAULT

    private fun buildDescription(): String {
        val baseDescription = action.description.ifBlank { "Execute action ${action.shortName()}" }
        return if (requiredInputs.isEmpty()) {
            "$baseDescription (all inputs available on blackboard)"
        } else if (requiredInputs.size < action.inputs.size) {
            val curriedOut = action.inputs.size - requiredInputs.size
            "$baseDescription ($curriedOut inputs already on blackboard)"
        } else {
            baseDescription
        }
    }

    private fun buildInputSchema(): Tool.InputSchema {
        val parameters = requiredInputs.map { input ->
            Tool.Parameter(
                name = input.name,
                type = mapToToolParameterType(input.type),
                description = "Input of type ${input.type}",
                required = true,
            )
        }
        return Tool.InputSchema.of(*parameters.toTypedArray())
    }

    private fun mapToToolParameterType(typeName: String): Tool.ParameterType {
        return when {
            typeName == "kotlin.Int" || typeName == "java.lang.Integer" || typeName == "int" ->
                Tool.ParameterType.INTEGER

            typeName == "kotlin.Long" || typeName == "java.lang.Long" || typeName == "long" ->
                Tool.ParameterType.INTEGER

            typeName == "kotlin.Double" || typeName == "java.lang.Double" || typeName == "double" ||
                typeName == "kotlin.Float" || typeName == "java.lang.Float" || typeName == "float" ->
                Tool.ParameterType.NUMBER

            typeName == "kotlin.Boolean" || typeName == "java.lang.Boolean" || typeName == "boolean" ->
                Tool.ParameterType.BOOLEAN

            typeName == "kotlin.String" || typeName == "java.lang.String" ->
                Tool.ParameterType.STRING

            else -> Tool.ParameterType.OBJECT
        }
    }

    override fun call(input: String): Tool.Result {
        logger.info("CurriedActionTool called for action '{}' with input: {}", action.name, input)

        val agentProcess = AgentProcess.get()
            ?: return Tool.Result.error("No agent process context available")

        // Parse input and bind values to blackboard
        if (requiredInputs.isNotEmpty() && input.isNotBlank()) {
            try {
                parseAndBindInputs(input, agentProcess)
            } catch (e: Exception) {
                logger.warn("Failed to parse tool input: {}", e.message)
                return Tool.Result.error("Failed to parse input: ${e.message}")
            }
        }

        // Execute the action
        val result = action.execute(agentProcess.processContext)

        return when (result.status) {
            ActionStatusCode.SUCCEEDED -> {
                val lastResult = agentProcess.lastResult()
                Tool.Result.text("Action '${action.shortName()}' succeeded. Result: $lastResult")
            }

            ActionStatusCode.FAILED -> {
                Tool.Result.error("Action '${action.shortName()}' failed")
            }

            else -> {
                Tool.Result.text("Action '${action.shortName()}' returned status: ${result.status}")
            }
        }
    }

    private fun parseAndBindInputs(toolInput: String, agentProcess: AgentProcess) {
        @Suppress("UNCHECKED_CAST")
        val inputMap = objectMapper.readValue(toolInput, Map::class.java) as Map<String, Any>

        for (input in requiredInputs) {
            val value = inputMap[input.name]
            if (value != null) {
                // Try to convert the value to the expected type
                val convertedValue = convertToType(value, input.type)
                agentProcess[input.name] = convertedValue
                logger.debug("Bound input '{}' = {} (type: {})", input.name, convertedValue, input.type)
            }
        }
    }

    private fun convertToType(value: Any, targetType: String): Any {
        // If already the right type, return as-is
        if (Companion.isCompatibleType(value, targetType)) {
            return value
        }

        // Try to convert using ObjectMapper
        return try {
            val targetClass = Class.forName(targetType)
            objectMapper.convertValue(value, targetClass)
        } catch (e: Exception) {
            logger.warn("Could not convert {} to {}, using as-is", value, targetType)
            value
        }
    }

    companion object {

        /**
         * Create curried tools for all actions based on current blackboard state.
         *
         * @param actions The actions to create tools for
         * @param blackboard The current blackboard state
         * @param objectMapper Object mapper for JSON handling
         * @return List of curried tools, with tools that have all inputs available first
         */
        @JvmStatic
        fun createTools(
            actions: List<Action>,
            blackboard: Blackboard,
            objectMapper: ObjectMapper,
        ): List<Tool> {
            return actions.map { action ->
                CurriedActionTool(action, blackboard, objectMapper)
            }.sortedBy { tool ->
                // Put tools with fewer required inputs first (more "ready" to run)
                (tool as CurriedActionTool).requiredInputs.size
            }
        }

        /**
         * Check if a tool is ready to execute (all inputs available on blackboard).
         */
        @JvmStatic
        fun isReady(tool: Tool): Boolean {
            return tool is CurriedActionTool && tool.requiredInputs.isEmpty()
        }

        /**
         * Check if a value is compatible with the expected type.
         */
        @JvmStatic
        fun isCompatibleType(value: Any, expectedType: String): Boolean {
            val valueClass = value::class.java
            return try {
                val expectedClass = Class.forName(expectedType)
                expectedClass.isAssignableFrom(valueClass)
            } catch (e: ClassNotFoundException) {
                valueClass.name == expectedType
            }
        }
    }
}
