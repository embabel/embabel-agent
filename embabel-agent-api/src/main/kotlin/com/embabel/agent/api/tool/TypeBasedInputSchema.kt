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

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaType

/**
 * InputSchema implementation that generates JSON schema from a Class type.
 * Uses reflection to extract properties and their types.
 */
class TypeBasedInputSchema(
    private val type: Class<*>,
) : Tool.InputSchema {

    companion object {
        private val objectMapper = ObjectMapper()

        @JvmStatic
        fun of(type: Class<*>): TypeBasedInputSchema = TypeBasedInputSchema(type)

        @JvmStatic
        fun of(type: KClass<*>): TypeBasedInputSchema = TypeBasedInputSchema(type.java)

        private fun mapJavaTypeToSchemaType(type: Class<*>): String = when {
            type == String::class.java || type == java.lang.String::class.java -> "string"
            type == Int::class.java || type == Integer::class.java ||
                    type == Long::class.java || type == java.lang.Long::class.java ||
                    type == Short::class.java || type == java.lang.Short::class.java ||
                    type == Byte::class.java || type == java.lang.Byte::class.java -> "integer"

            type == Double::class.java || type == java.lang.Double::class.java ||
                    type == Float::class.java || type == java.lang.Float::class.java -> "number"

            type == Boolean::class.java || type == java.lang.Boolean::class.java -> "boolean"
            type.isArray || List::class.java.isAssignableFrom(type) ||
                    Collection::class.java.isAssignableFrom(type) -> "array"

            else -> "object"
        }

        private fun mapPropertyTypeToParameterType(type: Class<*>): Tool.ParameterType = when {
            type == String::class.java || type == java.lang.String::class.java ->
                Tool.ParameterType.STRING

            type == Int::class.java || type == Integer::class.java ||
                    type == Long::class.java || type == java.lang.Long::class.java ->
                Tool.ParameterType.INTEGER

            type == Double::class.java || type == java.lang.Double::class.java ||
                    type == Float::class.java || type == java.lang.Float::class.java ->
                Tool.ParameterType.NUMBER

            type == Boolean::class.java || type == java.lang.Boolean::class.java ->
                Tool.ParameterType.BOOLEAN

            type.isArray || List::class.java.isAssignableFrom(type) ->
                Tool.ParameterType.ARRAY

            else -> Tool.ParameterType.OBJECT
        }
    }

    override val parameters: List<Tool.Parameter> by lazy {
        extractParameters()
    }

    override fun toJsonSchema(): String {
        val properties = mutableMapOf<String, Any>()
        val required = mutableListOf<String>()

        try {
            // Skip Kotlin reflection for Java records - go straight to fallback
            if (type.isRecord) {
                throw UnsupportedOperationException("Java records require fallback reflection")
            }

            val kClass = type.kotlin
            for (prop in kClass.memberProperties) {
                val propName = prop.name
                val propType = (prop.returnType.javaType as? Class<*>) ?: Any::class.java
                val schemaType = mapJavaTypeToSchemaType(propType)

                properties[propName] = mapOf(
                    "type" to schemaType,
                    "description" to propName,
                )

                if (!prop.returnType.isMarkedNullable) {
                    required.add(propName)
                }
            }
        } catch (e: Exception) {
            // Fallback for non-Kotlin classes or reflection failures
            for (field in type.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                val schemaType = mapJavaTypeToSchemaType(field.type)
                properties[field.name] = mapOf(
                    "type" to schemaType,
                    "description" to field.name,
                )
                required.add(field.name)
            }
        }

        val schema = mutableMapOf<String, Any>(
            "type" to "object",
            "properties" to properties,
        )
        if (required.isNotEmpty()) {
            schema["required"] = required
        }

        return objectMapper.writeValueAsString(schema)
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractParameters(): List<Tool.Parameter> {
        val params = mutableListOf<Tool.Parameter>()

        try {
            // Skip Kotlin reflection for Java records - go straight to fallback
            if (type.isRecord) {
                throw UnsupportedOperationException("Java records require fallback reflection")
            }

            val kClass = type.kotlin
            for (prop in kClass.memberProperties) {
                val propType = (prop.returnType.javaType as? Class<*>) ?: Any::class.java
                params.add(
                    Tool.Parameter(
                        name = prop.name,
                        type = mapPropertyTypeToParameterType(propType),
                        description = prop.name,
                        required = !prop.returnType.isMarkedNullable,
                    )
                )
            }
        } catch (e: Exception) {
            // Fallback for non-Kotlin classes
            for (field in type.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                params.add(
                    Tool.Parameter(
                        name = field.name,
                        type = mapPropertyTypeToParameterType(field.type),
                        description = field.name,
                        required = true,
                    )
                )
            }
        }

        return params
    }
}
