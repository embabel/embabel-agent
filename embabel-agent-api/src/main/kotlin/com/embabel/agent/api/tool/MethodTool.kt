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

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.annotation.LlmTool.Param
import com.embabel.agent.api.tool.VictoolsSchemaGenerator.ParameterInfo
import com.embabel.agent.core.ReplanRequestedException
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.util.ReflectionUtils
import java.lang.reflect.Method
import java.lang.reflect.Type
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.javaType

/**
 * Tool implementation that wraps a method annotated with [@LlmTool].
 */
internal sealed class MethodTool(
    protected val instance: Any,
    annotation: LlmTool,
    private val objectMapper: ObjectMapper,
) : Tool {

    private val logger = LoggerFactory.getLogger(MethodTool::class.java)

    override val metadata: Tool.Metadata = Tool.Metadata(returnDirect = annotation.returnDirect)

    override fun call(input: String): Tool.Result {
        return try {
            val args = parseArguments(input)
            val result = invokeMethod(args)
            convertResult(result)
        } catch (e: Exception) {
            // Unwrap InvocationTargetException to get the actual cause
            val actualCause = e.cause ?: e

            // ReplanRequestedException must propagate - it's a control flow signal, not an error
            if (actualCause is ReplanRequestedException) {
                throw actualCause
            }

            val message = actualCause.message ?: e.message ?: "Tool invocation failed"
            logger.error("Error invoking tool '{}': {}", definition.name, message, actualCause)
            Tool.Result.error(message, actualCause)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseArguments(input: String): Map<String, Any?> {
        if (input.isBlank()) return emptyMap()
        return try {
            objectMapper.readValue(input, Map::class.java) as Map<String, Any?>
        } catch (e: Exception) {
            logger.warn("Failed to parse tool input as JSON: {}", e.message)
            emptyMap()
        }
    }

    protected abstract fun invokeMethod(args: Map<String, Any?>): Any?

    private fun convertResult(result: Any?): Tool.Result {
        return when (result) {
            null -> Tool.Result.text("")
            is String -> Tool.Result.text(result)
            is Tool.Result -> result
            else -> {
                // Convert to JSON string
                try {
                    Tool.Result.text(objectMapper.writeValueAsString(result))
                } catch (_: Exception) {
                    Tool.Result.text(result.toString())
                }
            }
        }
    }

    protected fun convertToExpectedType(
        value: Any,
        targetType: Type,
    ): Any {
        // If already correct type, return as-is
        if (targetType is Class<*> && targetType.isInstance(value)) {
            return value
        }

        // Handle numeric conversions from JSON (Jackson often returns Int/Double)
        return when (targetType) {
            Int::class.java, Integer::class.java -> (value as? Number)?.toInt() ?: value
            Long::class.java, java.lang.Long::class.java -> (value as? Number)?.toLong() ?: value
            Double::class.java, java.lang.Double::class.java -> (value as? Number)?.toDouble() ?: value
            Float::class.java, java.lang.Float::class.java -> (value as? Number)?.toFloat() ?: value
            Boolean::class.java, java.lang.Boolean::class.java -> value as? Boolean ?: value.toString().toBoolean()
            String::class.java -> value.toString()
            else -> {
                // For complex types, try to convert via ObjectMapper
                try {
                    objectMapper.convertValue(value, objectMapper.constructType(targetType))
                } catch (e: Exception) {
                    logger.warn("Failed to convert {} to {}: {}", value, targetType, e.message)
                    value
                }
            }
        }
    }
}


internal class KotlinMethodTool(
    instance: Any,
    private val method: KFunction<*>,
    annotation: LlmTool,
    objectMapper: ObjectMapper,
) : MethodTool(
    instance = instance,
    annotation = annotation,
    objectMapper = objectMapper
) {

    override val definition: Tool.Definition by lazy {
        val name = annotation.name.ifEmpty { method.name }
        // Use victools-based schema generation for proper generic type handling
        val parameterInfos = method.parameters
            .filter { it.kind == KParameter.Kind.VALUE }
            .map { param ->
                val paramAnnotation = param.findAnnotation<Param>()
                ParameterInfo(
                    name = param.name ?: "arg${param.index}",
                    type = param.type.javaType,
                    description = paramAnnotation?.description ?: "",
                    required = paramAnnotation?.required ?: !param.isOptional,
                )
            }
        Tool.Definition(
            name = name,
            description = annotation.description,
            inputSchema = MethodInputSchema(parameterInfos),
        )
    }

    override fun invokeMethod(args: Map<String, Any?>): Any? {
        val params = method.parameters
        val callArgs = mutableMapOf<KParameter, Any?>()


        for (param in params) {
            when (param.kind) {
                KParameter.Kind.INSTANCE -> callArgs[param] = instance
                KParameter.Kind.VALUE -> {
                    val paramAnnotation = param.findAnnotation<Param>()
                    val paramName = param.name ?: continue
                    val value = args[paramName]

                    if (value != null) {
                        // Convert value to expected type if needed
                        val convertedValue = convertToExpectedType(value, param.type.javaType)
                        callArgs[param] = convertedValue
                    } else if (paramAnnotation?.required ?: !param.isOptional) {
                        // Required parameter is missing - use null or throw
                        if (param.type.isMarkedNullable) {
                            callArgs[param] = null
                        }
                        // If not nullable and optional, we skip it to use default value
                    }
                    // If optional and no value provided, skip to use default
                }

                else -> {} // Skip extension receivers etc.
            }
        }

        // Make method accessible for non-public classes/methods (e.g., package-protected Java classes)
        method.javaMethod?.isAccessible = true
        return method.callBy(callArgs)
    }
}

internal class JavaMethodTool(
    instance: Any,
    private val method: Method,
    annotation: LlmTool,
    objectMapper: ObjectMapper,
) : MethodTool(
    instance = instance,
    annotation = annotation,
    objectMapper = objectMapper
) {

    override val definition: Tool.Definition by lazy {
        val name = annotation.name.ifEmpty { method.name }
        // Use victools-based schema generation for proper generic type handling
        val parameterInfos = method.parameters
            .map { param ->
                val paramAnnotation = param.getAnnotation(Param::class.java)
                ParameterInfo(
                    name = param.name,
                    type = param.type,
                    description = paramAnnotation?.description ?: "",
                    required = paramAnnotation?.required ?: true,
                )
            }
        Tool.Definition(
            name = name,
            description = annotation.description,
            inputSchema = MethodInputSchema(parameterInfos),
        )
    }

    override fun invokeMethod(args: Map<String, Any?>): Any? {
        val params = method.parameters
        val callArgs = arrayOfNulls<Any?>(method.parameters.size)

        for ((index, param) in params.withIndex()) {
            val value = args[param.name]
            if (value != null) {
                // Convert value to expected type if needed
                val convertedValue = convertToExpectedType(value, param.type)
                callArgs[index] = convertedValue
            }
        }

        // Make method accessible for non-public classes/methods (e.g., package-protected Java classes)
        method.trySetAccessible()
        return ReflectionUtils.invokeMethod(method, instance, *callArgs)
    }

}
