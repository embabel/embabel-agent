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
package com.embabel.agent.mcpserver.support

import com.embabel.common.util.NameUtils
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.util.ReflectionUtils
import java.lang.reflect.Method

/**
 * Returns the Kotlin type name for a given Java type.
 *
 * Maps common Java types to their Kotlin equivalents.
 *
 * @param javaType the Java class to map
 * @return the Kotlin type name as a string
 */
private fun getKotlinTypeName(javaType: Class<*>): String {
    return when (javaType) {
        java.lang.Integer::class.java, Integer.TYPE -> "Int"
        java.lang.Long::class.java, java.lang.Long.TYPE -> "Long"
        java.lang.Double::class.java, java.lang.Double.TYPE -> "Double"
        java.lang.Float::class.java, java.lang.Float.TYPE -> "Float"
        java.lang.Boolean::class.java, java.lang.Boolean.TYPE -> "Boolean"
        java.lang.String::class.java -> "String"
        else -> javaType.simpleName
    }
}

/**
 * Retrieves the value of the `JsonPropertyDescription` annotation from a field or its constructor parameter.
 *
 * Attempts to get the annotation from the field first, then from the constructor parameter for Kotlin data classes.
 *
 * @param field the field to inspect
 * @param type the class type containing the field
 * @return the annotation value if present, otherwise null
 */
private fun getPropertyDescription(field: java.lang.reflect.Field, type: Class<*>): String? {
    // First try to get from field annotation
    field.getAnnotation(JsonPropertyDescription::class.java)?.let {
        return it.value
    }

    // For Kotlin data classes, try to get from constructor parameter
    try {
        val constructors = type.declaredConstructors
        for (constructor in constructors) {
            val parameters = constructor.parameters
            for (param in parameters) {
                if (param.name == field.name) {
                    param.getAnnotation(JsonPropertyDescription::class.java)?.let {
                        return it.value
                    }
                }
            }
        }
    } catch (e: Exception) {
        // If reflection fails, continue without constructor parameter annotation
    }

    return null
}

/**
 * Extracts MCP prompt arguments from a given type, excluding fields that match methods in the excluded interfaces.
 *
 * Iterates over the fields of the provided type, skipping synthetic fields and those matching excluded interface methods.
 * Builds a list of `McpSchema.PromptArgument` with name, description, and required flag.
 *
 * @param excludedInterfaces set of interfaces whose methods' property names should be excluded
 * @param type the class type to extract arguments from
 * @return a list of `McpSchema.PromptArgument` objects
 */
internal fun argumentsFromType(excludedInterfaces: Set<Class<*>>, type: Class<*>): List<McpSchema.PromptArgument> {
    val excludedFields: Iterable<Method> = excludedInterfaces.flatMap {
        it.methods.toList()
    }
    val args = mutableListOf<McpSchema.PromptArgument>()
    ReflectionUtils.doWithFields(type) { field ->
        if (field.isSynthetic) {
            return@doWithFields
        }
        if (excludedFields.any { NameUtils.beanMethodToPropertyName(it.name) == field.name }) {
            return@doWithFields
        }
        val name = field.name
        val propertyDescription = getPropertyDescription(field, type)

        // Get the Kotlin-friendly type name
        val typeName = getKotlinTypeName(field.type)

        val description = if (propertyDescription != null) {
            "$propertyDescription: $typeName"
        } else {
            "$name: $typeName"
        }

        args.add(McpSchema.PromptArgument(name, description, true))
    }
    return args
}
