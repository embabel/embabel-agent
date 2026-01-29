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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.victools.jsonschema.generator.Option
import com.github.victools.jsonschema.generator.OptionPreset
import com.github.victools.jsonschema.generator.SchemaGenerator
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder
import com.github.victools.jsonschema.generator.SchemaVersion
import java.lang.reflect.Type

/**
 * Utility for generating JSON schemas using the victools jsonschema-generator library.
 * This provides proper handling of generic types like List<Double>, ensuring the
 * generated schema includes the `items` property for arrays.
 */
internal object VictoolsSchemaGenerator {

    private val objectMapper = ObjectMapper()

    private val schemaGenerator: SchemaGenerator by lazy {
        val configBuilder = SchemaGeneratorConfigBuilder(
            SchemaVersion.DRAFT_2020_12,
            OptionPreset.PLAIN_JSON
        )
        // Don't include $schema and $id in generated schemas
        configBuilder.without(Option.SCHEMA_VERSION_INDICATOR)
        val config = configBuilder.build()
        SchemaGenerator(config)
    }

    /**
     * Generate a JSON schema for a Java type, properly handling generics.
     * For example, List<Double> will generate {"type":"array","items":{"type":"number"}}
     */
    fun generateSchemaForType(type: Type): JsonNode {
        return schemaGenerator.generateSchema(type)
    }

    /**
     * Generate a complete tool input schema for method parameters.
     *
     * @param parameters List of parameter info (name, type, description, required)
     * @return JSON schema string in the format expected by LLMs
     */
    fun generateToolInputSchema(parameters: List<ParameterInfo>): String {
        val rootNode = objectMapper.createObjectNode()
        rootNode.put("type", "object")

        val propertiesNode = objectMapper.createObjectNode()
        val requiredList = mutableListOf<String>()

        for (param in parameters) {
            val paramSchema = generateSchemaForType(param.type).deepCopy<ObjectNode>()

            // Add description if provided
            if (param.description.isNotEmpty()) {
                paramSchema.put("description", param.description)
            }

            propertiesNode.set<JsonNode>(param.name, paramSchema)

            if (param.required) {
                requiredList.add(param.name)
            }
        }

        rootNode.set<JsonNode>("properties", propertiesNode)

        if (requiredList.isNotEmpty()) {
            val requiredArray = objectMapper.createArrayNode()
            requiredList.forEach { requiredArray.add(it) }
            rootNode.set<JsonNode>("required", requiredArray)
        }

        return objectMapper.writeValueAsString(rootNode)
    }

    /**
     * Information about a method parameter for schema generation.
     */
    data class ParameterInfo(
        val name: String,
        val type: Type,
        val description: String,
        val required: Boolean,
    )
}

/**
 * InputSchema implementation that uses victools jsonschema-generator
 * to properly handle generic types like List<Double>.
 *
 * This schema generates proper JSON Schema with `items` property for arrays,
 * which is required by OpenAI and other LLM providers.
 */
internal class MethodInputSchema(
    private val parameterInfos: List<VictoolsSchemaGenerator.ParameterInfo>,
) : Tool.InputSchema {

    override fun toJsonSchema(): String {
        return VictoolsSchemaGenerator.generateToolInputSchema(parameterInfos)
    }

    override val parameters: List<Tool.Parameter>
        get() = parameterInfos.map { info ->
            Tool.Parameter(
                name = info.name,
                type = mapJavaTypeToParameterType(info.type),
                description = info.description,
                required = info.required,
            )
        }

    private fun mapJavaTypeToParameterType(type: Type): Tool.ParameterType {
        val rawType = when (type) {
            is Class<*> -> type
            is java.lang.reflect.ParameterizedType -> type.rawType as? Class<*>
            else -> null
        }

        return when {
            rawType == String::class.java || rawType == java.lang.String::class.java ->
                Tool.ParameterType.STRING

            rawType == Int::class.java || rawType == Integer::class.java ||
                rawType == Long::class.java || rawType == java.lang.Long::class.java ||
                rawType == Short::class.java || rawType == java.lang.Short::class.java ||
                rawType == Byte::class.java || rawType == java.lang.Byte::class.java ->
                Tool.ParameterType.INTEGER

            rawType == Double::class.java || rawType == java.lang.Double::class.java ||
                rawType == Float::class.java || rawType == java.lang.Float::class.java ->
                Tool.ParameterType.NUMBER

            rawType == Boolean::class.java || rawType == java.lang.Boolean::class.java ->
                Tool.ParameterType.BOOLEAN

            rawType != null && (rawType.isArray ||
                List::class.java.isAssignableFrom(rawType) ||
                Collection::class.java.isAssignableFrom(rawType)) ->
                Tool.ParameterType.ARRAY

            else -> Tool.ParameterType.OBJECT
        }
    }
}
