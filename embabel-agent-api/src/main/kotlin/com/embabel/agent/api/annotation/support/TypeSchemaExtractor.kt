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

import com.embabel.agent.core.Action
import com.embabel.agent.core.Blackboard
import com.fasterxml.jackson.databind.ObjectMapper
import java.lang.reflect.ParameterizedType
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties

/**
 * Extracts type schema information for supervisor prompts.
 *
 * This helps the LLM understand what each action produces and what
 * artifacts are available, enabling informed composition decisions
 * without strict type-based constraints.
 */
object TypeSchemaExtractor {

    /**
     * Extract a human-readable schema description for a type.
     * Shows field names and their types.
     */
    @JvmStatic
    fun extractSchema(typeName: String): String {
        return try {
            val clazz = Class.forName(typeName)
            extractSchema(clazz.kotlin)
        } catch (e: ClassNotFoundException) {
            typeName.substringAfterLast(".")
        }
    }

    /**
     * Extract schema from a Kotlin class.
     */
    @JvmStatic
    fun extractSchema(klass: KClass<*>): String {
        val properties = klass.memberProperties
        if (properties.isEmpty()) {
            return klass.simpleName ?: "Unknown"
        }

        val fields = properties.map { prop ->
            val typeName = formatTypeName(prop.returnType.toString())
            "${prop.name}: $typeName"
        }

        return "{ ${fields.joinToString(", ")} }"
    }

    /**
     * Format a type name to be more readable.
     */
    private fun formatTypeName(typeName: String): String {
        return typeName
            .replace("kotlin.", "")
            .replace("java.lang.", "")
            .replace("java.util.", "")
    }

    /**
     * Build action signature with schema info for the LLM.
     */
    @JvmStatic
    fun buildActionSignature(action: Action): String {
        val inputs = action.inputs
            .filter { !isInjectedType(it.type) }
            .map { input ->
                val simpleName = input.type.substringAfterLast(".")
                "${input.name}: $simpleName"
            }

        val outputType = action.outputs.firstOrNull()?.type?.substringAfterLast(".") ?: "Unit"
        val outputSchema = action.outputs.firstOrNull()?.let { extractSchema(it.type) } ?: ""

        val inputStr = inputs.joinToString(", ")
        val schemaStr = if (outputSchema.isNotEmpty() && outputSchema != outputType) {
            "\n    Schema: $outputSchema"
        } else {
            ""
        }

        return "${action.shortName()}($inputStr) -> $outputType$schemaStr"
    }

    /**
     * Check if a type is an injected type (Ai, OperationContext, etc.)
     */
    private fun isInjectedType(typeName: String): Boolean {
        val injectedTypes = setOf(
            "com.embabel.agent.api.common.Ai",
            "com.embabel.agent.api.common.OperationContext",
            "com.embabel.agent.core.AgentProcess",
            "com.embabel.agent.core.ProcessContext",
        )
        return typeName in injectedTypes
    }

    /**
     * Build a summary of current artifacts on the blackboard.
     */
    @JvmStatic
    fun buildArtifactsSummary(
        blackboard: Blackboard,
        objectMapper: ObjectMapper,
        maxValueLength: Int = 500,
    ): String {
        val mapValues = blackboard.expressionEvaluationModel()
            .filterValues { it != null && !isSystemType(it) }

        val objectValues = blackboard.objects
            .filter { !isSystemType(it) }

        if (mapValues.isEmpty() && objectValues.isEmpty()) {
            return "No artifacts yet"
        }

        val artifacts = mutableListOf<String>()

        // Named values from map
        for ((key, value) in mapValues) {
            if (value != null) {
                val typeName = value::class.simpleName ?: "Unknown"
                val valueStr = formatValue(value, objectMapper, maxValueLength)
                artifacts.add("- $key ($typeName): $valueStr")
            }
        }

        // Anonymous objects (not already in map)
        val mapValueSet = mapValues.values.toSet()
        for (obj in objectValues) {
            if (obj !in mapValueSet) {
                val typeName = obj::class.simpleName ?: "Unknown"
                val valueStr = formatValue(obj, objectMapper, maxValueLength)
                artifacts.add("- ($typeName): $valueStr")
            }
        }

        return artifacts.joinToString("\n")
    }

    /**
     * Format a value for display in the prompt.
     */
    private fun formatValue(value: Any, objectMapper: ObjectMapper, maxLength: Int): String {
        return try {
            val json = objectMapper.writeValueAsString(value)
            if (json.length > maxLength) {
                json.take(maxLength) + "..."
            } else {
                json
            }
        } catch (e: Exception) {
            value.toString().take(maxLength)
        }
    }

    /**
     * Check if an object is a system/framework type that shouldn't be shown.
     * Domain types (even in test packages) should be shown.
     */
    private fun isSystemType(obj: Any): Boolean {
        val className = obj::class.qualifiedName ?: return false

        // Always filter out primitive wrappers
        if (obj is Boolean || obj is Number || obj is String) return true

        // Filter Java/Kotlin standard library types
        if (className.startsWith("java.") || className.startsWith("kotlin.")) return true

        // Filter framework internal types, but not domain types in test packages
        // Note: com.embabel.agent.domain.io (UserInput, etc.) should NOT be filtered
        // as these contain user-provided data the LLM needs to see
        val frameworkPrefixes = listOf(
            "com.embabel.agent.core.",
            "com.embabel.agent.spi.",
            "com.embabel.agent.api.common.",
            "com.embabel.agent.api.event.",
        )
        return frameworkPrefixes.any { className.startsWith(it) }
    }
}
