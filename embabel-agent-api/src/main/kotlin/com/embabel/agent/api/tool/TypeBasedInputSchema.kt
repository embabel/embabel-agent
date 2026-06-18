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

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaType

/**
 * InputSchema implementation that generates JSON schema from a Class type.
 * Uses reflection to extract properties and their types.
 */
class TypeBasedInputSchema(
    private val type: Class<*>,
) : Tool.InputSchema {

    companion object {
        @JvmStatic
        fun of(type: Class<*>): TypeBasedInputSchema = TypeBasedInputSchema(type)

        @JvmStatic
        fun of(type: KClass<*>): TypeBasedInputSchema = TypeBasedInputSchema(type.java)

        /** Returns the [JsonPropertyDescription] value for [prop], falling back to the property name. */
        private fun propertyDescription(prop: KProperty1<*, *>): String =
            prop.javaField?.getAnnotation(JsonPropertyDescription::class.java)?.value
                ?: prop.findAnnotation<JsonPropertyDescription>()?.value
                ?: prop.name

        /** Returns the [JsonPropertyDescription] value for [field], falling back to the field name. */
        private fun fieldDescription(field: java.lang.reflect.Field): String =
            field.getAnnotation(JsonPropertyDescription::class.java)?.value ?: field.name

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

    override fun toJsonSchema(): String =
        VictoolsSchemaGenerator.generateClassInputSchema(type, requiredFieldNames())

    /**
     * Computes non-nullable field names from Kotlin type metadata.
     * Falls back to all non-static Java fields when Kotlin metadata is absent —
     * e.g. Java records, synthetic classes, or classes compiled without Kotlin metadata.
     */
    private fun requiredFieldNames(): Set<String> {
        return try {
            if (type.isRecord) throw UnsupportedOperationException("Java records: use field fallback")
            type.kotlin.memberProperties
                .filter { !it.returnType.isMarkedNullable }
                .map { it.name }
                .toSet()
        } catch (e: UnsupportedOperationException) {
            javaFieldNames()
        } catch (e: RuntimeException) {
            // Kotlin reflection unavailable (e.g. no kotlin.Metadata on this class)
            javaFieldNames()
        }
    }

    private fun javaFieldNames(): Set<String> =
        type.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

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
                        description = propertyDescription(prop),
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
                        description = fieldDescription(field),
                        required = true,
                    )
                )
            }
        }

        return params
    }
}
