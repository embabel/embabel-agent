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
package com.embabel.ux.form

import org.springframework.lang.Nullable
import java.time.LocalDate
import java.time.LocalTime
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Indicates that this is a form field. Only necessary for renaming and other customization.
 * IMPORTANT: In Kotlin this annotation must be on the property, as in @property:FormField("controlId") myProperty: String
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class FormField(val controlId: String)

/**
 * Indicates that this is not a form field
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class NoFormField

/**
 * Form binder system that maps form submission values to Kotlin data classes and Java classes
 */
class FormBinder<T : Any>(private val targetClass: KClass<T>) {

    constructor (targetClass: Class<T>) : this(targetClass.kotlin)

    class FormBindingException(message: String) : Exception(message)

    class ValidationException(val errors: Map<String, String>) : Exception("Form validation failed")

    /**
     * Binds a FormSubmissionResult to the target data class
     * @throws FormBindingException if there's an error mapping values
     * @throws ValidationException if the form submission has validation errors
     */
    fun bind(submissionResult: FormSubmissionResult): T {
        if (!submissionResult.valid) {
            throw ValidationException(submissionResult.validationErrors)
        }

        return try {
            bindKotlinDataClass(submissionResult)
        } catch (_: IllegalArgumentException) {
            bindJavaClass(submissionResult)
        }
    }

    private fun bindJavaClass(submissionResult: FormSubmissionResult): T {
        val javaClass = targetClass.java

        // Try to bind as Java Record first
        if (javaClass.isRecord) {
            return bindJavaRecord(submissionResult, javaClass)
        }
        return bindJavaConstructor(submissionResult, javaClass)
    }

    private fun bindJavaRecord(submissionResult: FormSubmissionResult, javaClass: Class<T>): T {
        val recordComponents = javaClass.recordComponents
            ?: throw FormBindingException("Failed to get record components for ${javaClass.simpleName}")

        val parameterValues = mutableListOf<Any?>()

        recordComponents.forEach { component ->
            // We need to look on the field for the annotations, not the component itself
            val field = javaClass.getDeclaredField(component.name)
            val formFieldAnnotation = field.getAnnotation(FormField::class.java)
            val noFormFieldAnnotation = field.getAnnotation(NoFormField::class.java)

            if (noFormFieldAnnotation != null) {
                throw FormBindingException("Record component ${component.name} is marked with @NoFormField but is required")
            }

            val controlId = formFieldAnnotation?.controlId ?: component.name
            val controlValue = submissionResult.values[controlId]
                ?: throw FormBindingException(
                    "No value found for control id '$controlId' in form submission binding to Java record ${
                        javaClass.name
                    }: formFieldAnnotation=$formFieldAnnotation, noFormFieldAnnotation=$noFormFieldAnnotation, component=$component"
                )

            val convertedValue = convertToJavaType(controlValue, component.type, component.genericType)
            parameterValues.add(convertedValue)
        }

        return try {
            val constructor = javaClass.declaredConstructors.first()
            constructor.trySetAccessible()
            constructor.newInstance(*parameterValues.toTypedArray()) as T
        } catch (e: Exception) {
            throw FormBindingException("Failed to construct Java record ${javaClass.simpleName}: ${e.message}")
        }
    }

    private fun bindJavaConstructor(submissionResult: FormSubmissionResult, javaClass: Class<T>): T {
        // Find constructors and try them in order of parameter count (prefer more specific)
        val constructors = javaClass.declaredConstructors.sortedByDescending { it.parameterCount }

        for (constructor in constructors) {
            try {
                val parameters = constructor.parameters
                val parameterValues = mutableListOf<Any?>()
                var allParametersFound = true

                parameters.forEach { parameter ->
                    val formFieldAnnotation = parameter.getAnnotation(FormField::class.java)
                    val noFormFieldAnnotation = parameter.getAnnotation(NoFormField::class.java)
                    if (noFormFieldAnnotation != null) {
                        throw FormBindingException("Constructor parameter ${parameter.name} is marked with @NoFormField but is required")
                    }

                    val controlId = formFieldAnnotation?.controlId ?: parameter.name
                    val controlValue = submissionResult.values[controlId]

                    if (controlValue == null) {
                        // Check if parameter is nullable or has default value
                        if (parameter.type.isPrimitive ||
                            !parameter.isAnnotationPresent(Nullable::class.java)
                        ) {
                            allParametersFound = false
                            return@forEach
                        }
                        parameterValues.add(null)
                    } else {
                        val convertedValue =
                            convertToJavaType(controlValue, parameter.type, parameter.parameterizedType)
                        parameterValues.add(convertedValue)
                    }
                }

                if (allParametersFound) {
                    constructor.isAccessible = true
                    return constructor.newInstance(*parameterValues.toTypedArray()) as T
                }
            } catch (e: Exception) {
                // Try next constructor
                continue
            }
        }

        throw FormBindingException("No suitable constructor found for Java class ${javaClass.simpleName}")
    }

    private fun convertToJavaType(
        controlValue: ControlValue,
        targetType: Class<*>,
        genericType: java.lang.reflect.Type
    ): Any? {
        return when (controlValue) {
            is ControlValue.TextValue -> convertJavaTextValue(controlValue.value, targetType)
            is ControlValue.BooleanValue -> {
                when (targetType) {
                    Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> controlValue.value
                    String::class.java -> controlValue.value.toString()
                    else -> controlValue.value
                }
            }

            is ControlValue.NumberValue -> convertJavaNumberValue(controlValue.value, targetType)
            is ControlValue.DateValue -> {
                when (targetType) {
                    LocalDate::class.java -> controlValue.value
                    String::class.java -> controlValue.value.toString()
                    else -> controlValue.value
                }
            }

            is ControlValue.TimeValue -> {
                when (targetType) {
                    LocalTime::class.java -> controlValue.value
                    String::class.java -> controlValue.value.toString()
                    else -> controlValue.value
                }
            }

            is ControlValue.OptionValue -> controlValue.value
            is ControlValue.MultiOptionValue -> {
                when {
                    targetType.isAssignableFrom(List::class.java) -> controlValue.values
                    targetType.isArray -> {
                        val componentType = targetType.componentType
                        when (componentType) {
                            String::class.java -> controlValue.values.toTypedArray()
                            else -> controlValue.values.toTypedArray()
                        }
                    }

                    else -> controlValue.values
                }
            }

            is ControlValue.FileValue -> controlValue.fileIds
            is ControlValue.EmptyValue -> null
        }
    }

    private fun convertJavaTextValue(value: String, targetType: Class<*>): Any? {
        return when (targetType) {
            String::class.java -> value
            Int::class.javaPrimitiveType, Int::class.javaObjectType -> value.toIntOrNull()
            Long::class.javaPrimitiveType, Long::class.javaObjectType -> value.toLongOrNull()
            Double::class.javaPrimitiveType, Double::class.javaObjectType -> value.toDoubleOrNull()
            Float::class.javaPrimitiveType, Float::class.javaObjectType -> value.toFloatOrNull()
            Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> value.toBoolean()
            LocalDate::class.java -> LocalDate.parse(value)
            LocalTime::class.java -> LocalTime.parse(value)
            else -> value
        }
    }

    private fun convertJavaNumberValue(value: Double, targetType: Class<*>): Any? {
        return when (targetType) {
            Int::class.javaPrimitiveType, Int::class.javaObjectType -> value.toInt()
            Long::class.javaPrimitiveType, Long::class.javaObjectType -> value.toLong()
            Float::class.javaPrimitiveType, Float::class.javaObjectType -> value.toFloat()
            Double::class.javaPrimitiveType, Double::class.javaObjectType -> value
            String::class.java -> value.toString()
            else -> value
        }
    }

    private fun bindKotlinDataClass(submissionResult: FormSubmissionResult): T {
        // Get the primary constructor for the data class
        val constructor = targetClass.primaryConstructor
            ?: throw IllegalArgumentException("Target class ${targetClass.simpleName} must be a data class with a primary constructor")

        // Map of parameter name to its corresponding type
        val parameterMap = constructor.parameters.associateBy { it.name }

        // Map to hold parameter values for the constructor
        val parameterValues = mutableMapOf<KParameter, Any?>()

        // Get properties with FormField annotation
        val formFields = getPropertiesInDeclarationOrder(targetClass)
            .filterNot { it.annotations.any { annotation -> annotation is NoFormField } }

        // Map each annotated property to its parameter and value from the form submission
        formFields.forEach { property ->
            val formFieldAnnotation = property.annotations
                .filterIsInstance<FormField>()
                .firstOrNull()

            val controlId = formFieldAnnotation?.controlId ?: property.name
            val controlValue = submissionResult.values[controlId]
                ?: throw FormBindingException("No value found for control id '$controlId' in form submission: $submissionResult")

            val parameter = parameterMap[property.name]
                ?: throw FormBindingException("No matching constructor parameter for property: ${property.name}")

            // Convert the ControlValue to the appropriate type for the parameter
            parameterValues[parameter] = convertToParameterType(controlValue, parameter)
        }

        // Check if all required parameters are provided
        constructor.parameters
            .filter { it.isOptional.not() && it.name !in parameterValues.keys.mapNotNull { param -> param.name } }
            .forEach {
                throw FormBindingException("Missing required parameter: ${it.name}")
            }

        // Create instance of the data class using the constructor
        return try {
            constructor.callBy(parameterValues)
        } catch (e: Exception) {
            throw FormBindingException("Failed to construct ${targetClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Converts a ControlValue to the appropriate type for the parameter
     */
    private fun convertToParameterType(controlValue: ControlValue, parameter: KParameter): Any? {
        return when (controlValue) {
            is ControlValue.TextValue -> convertTextValue(controlValue.value, parameter)
            is ControlValue.BooleanValue -> controlValue.value
            is ControlValue.NumberValue -> convertNumberValue(controlValue.value, parameter)
            is ControlValue.DateValue -> controlValue.value
            is ControlValue.TimeValue -> controlValue.value
            is ControlValue.OptionValue -> controlValue.value
            is ControlValue.MultiOptionValue -> controlValue.values
            is ControlValue.FileValue -> controlValue.fileIds
            is ControlValue.EmptyValue -> null
        }
    }

    /**
     * Converts text values to appropriate types based on parameter type
     */
    private fun convertTextValue(value: String, parameter: KParameter): Any? {
        return when (parameter.type.classifier) {
            String::class -> value
            Int::class -> value.toIntOrNull()
            Long::class -> value.toLongOrNull()
            Double::class -> value.toDoubleOrNull()
            Float::class -> value.toFloatOrNull()
            Boolean::class -> value.toBoolean()
            LocalDate::class -> LocalDate.parse(value)
            LocalTime::class -> LocalTime.parse(value)
            else -> value
        }
    }

    /**
     * Converts number values to appropriate numeric types
     */
    private fun convertNumberValue(value: Double, parameter: KParameter): Any? {
        return when (parameter.type.classifier) {
            Int::class -> value.toInt()
            Long::class -> value.toLong()
            Float::class -> value.toFloat()
            Double::class -> value
            String::class -> value.toString()
            else -> value
        }
    }

    /**
     * Gets properties in declaration order for consistent binding
     */
    private fun getPropertiesInDeclarationOrder(kClass: KClass<*>): List<KProperty1<*, *>> {
        return kClass.memberProperties.toList()
    }
}

/**
 * Extension function to make binding more convenient
 */
inline fun <reified T : Any> FormSubmissionResult.bindTo(): T {
    return FormBinder(T::class).bind(this)
}

/**
 * Extension function for Java class binding
 */
fun <T : Any> FormSubmissionResult.bindTo(javaClass: Class<T>): T {
    return FormBinder(javaClass).bind(this)
}
