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
package com.embabel.agent.api.tool

import com.embabel.agent.core.Cardinality
import com.embabel.agent.core.DomainType
import com.embabel.agent.core.DomainTypePropertyDefinition
import com.embabel.agent.core.PropertyDefinition
import com.embabel.agent.core.ValuePropertyDefinition
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * InputSchema implementation that generates JSON schema from a [DomainType].
 * Maps DomainType properties to Tool.Parameters with appropriate type mappings.
 *
 * Type mappings:
 * - string -> STRING
 * - int, integer, long -> INTEGER
 * - double, float, number -> NUMBER
 * - boolean -> BOOLEAN
 * - LIST/SET cardinality -> ARRAY
 * - DomainTypePropertyDefinition -> OBJECT (with nested properties)
 *
 * Cardinality mappings:
 * - ONE -> required = true
 * - OPTIONAL -> required = false
 * - LIST/SET -> type = ARRAY, required = true
 *
 * Use [Tool.InputSchema.of] to create instances.
 */
internal class DomainTypeInputSchema(
    private val domainType: DomainType,
) : Tool.InputSchema {

    companion object {
        private val objectMapper = ObjectMapper()

        private fun mapValueType(type: String): Tool.ParameterType = when (type.lowercase()) {
            "string" -> Tool.ParameterType.STRING
            "int", "integer", "long", "short", "byte" -> Tool.ParameterType.INTEGER
            "double", "float", "number", "decimal" -> Tool.ParameterType.NUMBER
            "boolean", "bool" -> Tool.ParameterType.BOOLEAN
            else -> Tool.ParameterType.STRING // Default to string for unknown types
        }

        private fun mapPropertyToParameter(property: PropertyDefinition): Tool.Parameter {
            return when (property) {
                is ValuePropertyDefinition -> mapValueProperty(property)
                is DomainTypePropertyDefinition -> mapDomainTypeProperty(property)
                else -> Tool.Parameter(
                    name = property.name,
                    type = Tool.ParameterType.STRING,
                    description = property.description,
                    required = property.cardinality == Cardinality.ONE,
                )
            }
        }

        private fun mapValueProperty(property: ValuePropertyDefinition): Tool.Parameter {
            val isCollection = property.cardinality == Cardinality.LIST ||
                property.cardinality == Cardinality.SET

            return if (isCollection) {
                Tool.Parameter(
                    name = property.name,
                    type = Tool.ParameterType.ARRAY,
                    description = property.description,
                    required = true, // Collections are typically required
                    itemType = mapValueType(property.type),
                )
            } else {
                Tool.Parameter(
                    name = property.name,
                    type = mapValueType(property.type),
                    description = property.description,
                    required = property.cardinality == Cardinality.ONE,
                )
            }
        }

        private fun mapDomainTypeProperty(property: DomainTypePropertyDefinition): Tool.Parameter {
            val nestedProperties = property.type.properties.map { mapPropertyToParameter(it) }
            val isCollection = property.cardinality == Cardinality.LIST ||
                property.cardinality == Cardinality.SET

            return if (isCollection) {
                Tool.Parameter(
                    name = property.name,
                    type = Tool.ParameterType.ARRAY,
                    description = property.description,
                    required = true,
                    properties = nestedProperties,
                )
            } else {
                Tool.Parameter(
                    name = property.name,
                    type = Tool.ParameterType.OBJECT,
                    description = property.description,
                    required = property.cardinality == Cardinality.ONE,
                    properties = nestedProperties,
                )
            }
        }
    }

    override val parameters: List<Tool.Parameter> by lazy {
        domainType.properties.map { mapPropertyToParameter(it) }
    }

    override fun toJsonSchema(): String {
        return objectMapper.writeValueAsString(buildSchemaMap(parameters))
    }

    private fun buildSchemaMap(params: List<Tool.Parameter>): Map<String, Any> {
        val properties = mutableMapOf<String, Any>()
        params.forEach { param ->
            properties[param.name] = buildParameterSchema(param)
        }

        val required = params.filter { it.required }.map { it.name }

        val schema = mutableMapOf<String, Any>(
            "type" to "object",
            "properties" to properties,
        )
        if (required.isNotEmpty()) {
            schema["required"] = required
        }
        return schema
    }

    private fun buildParameterSchema(param: Tool.Parameter): Map<String, Any> {
        val typeStr = when (param.type) {
            Tool.ParameterType.STRING -> "string"
            Tool.ParameterType.INTEGER -> "integer"
            Tool.ParameterType.NUMBER -> "number"
            Tool.ParameterType.BOOLEAN -> "boolean"
            Tool.ParameterType.ARRAY -> "array"
            Tool.ParameterType.OBJECT -> "object"
        }

        val propMap = mutableMapOf<String, Any>(
            "type" to typeStr,
            "description" to param.description,
        )

        param.enumValues?.let { values ->
            propMap["enum"] = values
        }

        // For ARRAY types, add items property
        if (param.type == Tool.ParameterType.ARRAY) {
            if (param.properties != null && param.properties.isNotEmpty()) {
                // Array of objects
                propMap["items"] = buildSchemaMap(param.properties)
            } else if (param.itemType != null) {
                // Array of primitives
                val itemTypeStr = when (param.itemType) {
                    Tool.ParameterType.STRING -> "string"
                    Tool.ParameterType.INTEGER -> "integer"
                    Tool.ParameterType.NUMBER -> "number"
                    Tool.ParameterType.BOOLEAN -> "boolean"
                    Tool.ParameterType.ARRAY -> "array"
                    Tool.ParameterType.OBJECT -> "object"
                }
                propMap["items"] = mapOf("type" to itemTypeStr)
            }
        }

        // For OBJECT types with nested properties
        if (param.type == Tool.ParameterType.OBJECT && !param.properties.isNullOrEmpty()) {
            val nestedProperties = mutableMapOf<String, Any>()
            param.properties.forEach { nested ->
                nestedProperties[nested.name] = buildParameterSchema(nested)
            }
            propMap["properties"] = nestedProperties

            val nestedRequired = param.properties.filter { it.required }.map { it.name }
            if (nestedRequired.isNotEmpty()) {
                propMap["required"] = nestedRequired
            }
        }

        return propMap
    }
}
