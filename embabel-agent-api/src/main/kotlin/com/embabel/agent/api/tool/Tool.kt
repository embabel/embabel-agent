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
import com.embabel.agent.api.annotation.MatryoshkaTools
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.functions
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.javaType

/**
 * Framework-agnostic tool that can be invoked by an LLM.
 * Adapters in SPI layer bridge to Spring AI ToolCallback or LangChain4j ToolSpecification/ToolExecutor.
 *
 * All nested types are scoped within this interface to avoid naming conflicts with
 * framework-specific types (e.g., Spring AI's ToolDefinition, ToolMetadata).
 */
interface Tool {

    /** Tool definition for LLM */
    val definition: Definition

    /** Optional metadata */
    val metadata: Metadata get() = Metadata.DEFAULT

    /**
     * Execute the tool with JSON input.
     * @param input JSON string matching inputSchema
     * @return Result to send back to LLM
     */
    fun call(input: String): Result

    /**
     * Framework-agnostic tool definition.
     */
    interface Definition {


        /** Unique name for the tool. Used by LLM to invoke it. */
        val name: String

        /** Description explaining what the tool does. Critical for LLM to choose correctly. */
        val description: String

        /** Schema describing the input parameters. */
        val inputSchema: InputSchema

        fun withParameter(parameter: Parameter): Definition =
            SimpleDefinition(
                name = name,
                description = description,
                inputSchema = inputSchema.withParameter(parameter),
            )

        companion object {

            operator fun invoke(
                name: String,
                description: String,
                inputSchema: InputSchema,
            ): Definition = SimpleDefinition(name, description, inputSchema)

            @JvmStatic
            fun create(
                name: String,
                description: String,
                inputSchema: InputSchema,
            ): Definition = SimpleDefinition(name, description, inputSchema)
        }
    }

    /**
     * Input schema for a tool, supporting both simple and complex parameters.
     */
    interface InputSchema {

        /** JSON Schema representation for LLM consumption */
        fun toJsonSchema(): String

        /** Parameter definitions */
        val parameters: List<Parameter>

        fun withParameter(parameter: Parameter): InputSchema =
            SimpleInputSchema(parameters + parameter)

        companion object {

            @JvmStatic
            fun of(vararg parameters: Parameter): InputSchema =
                SimpleInputSchema(parameters.toList())

            @JvmStatic
            fun empty(): InputSchema = SimpleInputSchema(emptyList())
        }
    }

    /**
     * A single parameter for a tool.
     * @param name Parameter name
     * @param type Parameter type
     * @param description Parameter description. Defaults to name if not provided.
     * @param required Whether the parameter is required. Defaults to true.
     * @param enumValues Optional list of allowed values (for enum parameters)
     */
    data class Parameter @JvmOverloads constructor(
        val name: String,
        val type: ParameterType,
        val description: String = name,
        val required: Boolean = true,
        val enumValues: List<String>? = null,
    ) {

        companion object {

            @JvmStatic
            @JvmOverloads
            fun string(
                name: String,
                description: String = name,
                required: Boolean = true,
                enumValues: List<String>? = null,
            ): Parameter = Parameter(name, ParameterType.STRING, description, required, enumValues)

            @JvmStatic
            @JvmOverloads
            fun integer(
                name: String,
                description: String = name,
                required: Boolean = true,
                enumValues: List<String>? = null,
            ): Parameter = Parameter(name, ParameterType.INTEGER, description, required, enumValues)

            @JvmStatic
            @JvmOverloads
            fun double(
                name: String,
                description: String = name,
                required: Boolean = true,
                enumValues: List<String>? = null,
            ): Parameter = Parameter(name, ParameterType.NUMBER, description, required, enumValues)
        }
    }

    /**
     * Supported parameter types.
     */
    enum class ParameterType {
        STRING, INTEGER, NUMBER, BOOLEAN, ARRAY, OBJECT
    }

    /**
     * Optional metadata about a tool's behavior.
     */
    interface Metadata {
        /** Whether to return the result directly without further LLM processing */
        val returnDirect: Boolean get() = false

        /** Provider-specific metadata entries */
        val providerMetadata: Map<String, Any> get() = emptyMap()

        companion object {
            @JvmField
            val DEFAULT: Metadata = object : Metadata {}

            operator fun invoke(
                returnDirect: Boolean = false,
                providerMetadata: Map<String, Any> = emptyMap(),
            ): Metadata = SimpleMetadata(returnDirect, providerMetadata)

            /**
             * Create metadata (Java-friendly).
             */
            @JvmStatic
            @JvmOverloads
            fun create(
                returnDirect: Boolean = false,
                providerMetadata: Map<String, Any> = emptyMap(),
            ): Metadata = SimpleMetadata(returnDirect, providerMetadata)
        }
    }

    /**
     * Result of tool execution with optional artifacts.
     */
    sealed interface Result {

        /** Simple text result */
        data class Text(val content: String) : Result

        /** Result with additional artifact (e.g., generated file, image) */
        data class WithArtifact(
            val content: String,
            val artifact: Any,
        ) : Result

        /** Error result */
        data class Error(
            val message: String,
            val cause: Throwable? = null,
        ) : Result

        companion object {

            @JvmStatic
            fun text(content: String): Result = Text(content)

            @JvmStatic
            fun withArtifact(
                content: String,
                artifact: Any,
            ): Result = WithArtifact(content, artifact)

            @JvmStatic
            @JvmOverloads
            fun error(
                message: String,
                cause: Throwable? = null,
            ): Result = Error(message, cause)
        }
    }

    /**
     * Functional interface for simple tool implementations.
     */
    fun interface Function {
        fun invoke(input: String): Result
    }

    /**
     * Java-friendly functional interface for tool implementations.
     * Uses `handle` method name which is more idiomatic in Java than `invoke`.
     */
    @FunctionalInterface
    fun interface Handler {
        fun handle(input: String): Result
    }

    companion object {
        private val logger = LoggerFactory.getLogger(Tool::class.java)

        /**
         * Create a tool from a function.
         */
        fun of(
            name: String,
            description: String,
            inputSchema: InputSchema,
            metadata: Metadata = Metadata.DEFAULT,
            function: Function,
        ): Tool = FunctionalTool(
            definition = Definition(name, description, inputSchema),
            metadata = metadata,
            function = function,
        )

        /**
         * Create a tool with no parameters.
         */
        fun of(
            name: String,
            description: String,
            metadata: Metadata = Metadata.DEFAULT,
            function: Function,
        ): Tool = of(name, description, InputSchema.empty(), metadata, function)

        /**
         * Create a tool with no parameters (Java-friendly).
         * This method is easier to call from Java as it uses the Handler interface.
         *
         * Example:
         * ```java
         * Tool tool = Tool.create("greet", "Greets user", input -> Tool.Result.text("Hello!"));
         * ```
         *
         * @param name Tool name
         * @param description Tool description
         * @param handler Handler that processes input and returns a result
         * @return A new Tool instance
         */
        @JvmStatic
        fun create(
            name: String,
            description: String,
            handler: Handler,
        ): Tool = FunctionalTool(
            definition = Definition(name, description, InputSchema.empty()),
            metadata = Metadata.DEFAULT,
            function = Function { input -> handler.handle(input) },
        )

        /**
         * Create a tool with custom metadata (Java-friendly).
         *
         * @param name Tool name
         * @param description Tool description
         * @param metadata Tool metadata (e.g., returnDirect)
         * @param handler Handler that processes input and returns a result
         * @return A new Tool instance
         */
        @JvmStatic
        fun create(
            name: String,
            description: String,
            metadata: Metadata,
            handler: Handler,
        ): Tool = FunctionalTool(
            definition = Definition(name, description, InputSchema.empty()),
            metadata = metadata,
            function = Function { input -> handler.handle(input) },
        )

        /**
         * Create a tool with input schema (Java-friendly).
         *
         * @param name Tool name
         * @param description Tool description
         * @param inputSchema Schema describing the input parameters
         * @param handler Handler that processes input and returns a result
         * @return A new Tool instance
         */
        @JvmStatic
        fun create(
            name: String,
            description: String,
            inputSchema: InputSchema,
            handler: Handler,
        ): Tool = FunctionalTool(
            definition = Definition(name, description, inputSchema),
            metadata = Metadata.DEFAULT,
            function = Function { input -> handler.handle(input) },
        )

        /**
         * Create a fully configured tool (Java-friendly).
         *
         * @param name Tool name
         * @param description Tool description
         * @param inputSchema Schema describing the input parameters
         * @param metadata Tool metadata
         * @param handler Handler that processes input and returns a result
         * @return A new Tool instance
         */
        @JvmStatic
        fun create(
            name: String,
            description: String,
            inputSchema: InputSchema,
            metadata: Metadata,
            handler: Handler,
        ): Tool = FunctionalTool(
            definition = Definition(name, description, inputSchema),
            metadata = metadata,
            function = Function { input -> handler.handle(input) },
        )

        /**
         * Create a Tool from a method annotated with [com.embabel.agent.api.annotation.LlmTool].
         *
         * @param instance The object instance containing the method
         * @param method The method to wrap as a tool
         * @param objectMapper ObjectMapper for JSON parsing (optional)
         * @return A Tool that invokes the method
         * @throws IllegalArgumentException if the method is not annotated with @Tool.Method
         */
        fun fromMethod(
            instance: Any,
            method: KFunction<*>,
            objectMapper: ObjectMapper = jacksonObjectMapper(),
        ): Tool {
            val annotation = method.findAnnotation<LlmTool>()
                ?: throw IllegalArgumentException(
                    "Method ${method.name} is not annotated with @Tool.Method"
                )

            return MethodTool(
                instance = instance,
                method = method,
                annotation = annotation,
                objectMapper = objectMapper,
            )
        }

        /**
         * Create Tools from all methods annotated with [LlmTool] on an instance.
         *
         * If the instance's class is annotated with [@MatryoshkaTools][MatryoshkaTools],
         * returns a single [MatryoshkaTool] containing all the inner tools.
         * Otherwise, returns individual tools for each annotated method.
         *
         * @param instance The object instance to scan for annotated methods
         * @param objectMapper ObjectMapper for JSON parsing (optional)
         * @return List of Tools, one for each annotated method (or single MatryoshkaTool if @MatryoshkaTools present)
         * @throws IllegalArgumentException if no methods are annotated with @LlmTool
         */
        @JvmStatic
        @JvmOverloads
        fun fromInstance(
            instance: Any,
            objectMapper: ObjectMapper = jacksonObjectMapper(),
        ): List<Tool> {
            // Check for @MatryoshkaTools annotation first
            if (instance::class.hasAnnotation<MatryoshkaTools>()) {
                return listOf(MatryoshkaTool.fromInstance(instance, objectMapper))
            }

            val tools = instance::class.functions
                .filter { it.hasAnnotation<LlmTool>() }
                .map { fromMethod(instance, it, objectMapper) }

            if (tools.isEmpty()) {
                throw IllegalArgumentException(
                    "No methods annotated with @Tool.Method found on ${instance::class.simpleName}"
                )
            }

            return tools
        }

        /**
         * Safely create Tools from an instance, returning empty list if no annotated methods found.
         * This is useful when you want to scan an object that may or may not have tool methods.
         *
         * @param instance The object instance to scan for annotated methods
         * @param objectMapper ObjectMapper for JSON parsing (optional)
         * @return List of Tools, or empty list if no annotated methods found
         */
        @JvmStatic
        @JvmOverloads
        fun safelyFromInstance(
            instance: Any,
            objectMapper: ObjectMapper = jacksonObjectMapper(),
        ): List<Tool> {
            return try {
                fromInstance(instance, objectMapper)
            } catch (e: IllegalArgumentException) {
                logger.debug("No @LlmTool annotations found on {}: {}", instance::class.simpleName, e.message)
                emptyList()
            } catch (e: Throwable) {
                // Kotlin reflection can fail on some Java classes with KotlinReflectionInternalError (an Error, not Exception)
                logger.debug(
                    "Failed to scan {} for @LlmTool annotations: {}",
                    instance::class.simpleName,
                    e.message,
                )
                emptyList()
            }
        }
    }
}

// Private implementations

private data class SimpleDefinition(
    override val name: String,
    override val description: String,
    override val inputSchema: Tool.InputSchema,
) : Tool.Definition

private data class SimpleInputSchema(
    override val parameters: List<Tool.Parameter>,
) : Tool.InputSchema {

    companion object {
        private val objectMapper = ObjectMapper()
    }

    override fun toJsonSchema(): String {
        val properties = mutableMapOf<String, Any>()
        parameters.forEach { param ->
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
            properties[param.name] = propMap
        }

        val required = parameters.filter { it.required }.map { it.name }

        val schema = mutableMapOf<String, Any>(
            "type" to "object",
            "properties" to properties,
        )
        if (required.isNotEmpty()) {
            schema["required"] = required
        }

        return objectMapper.writeValueAsString(schema)
    }
}

private data class SimpleMetadata(
    override val returnDirect: Boolean,
    override val providerMetadata: Map<String, Any>,
) : Tool.Metadata

private class FunctionalTool(
    override val definition: Tool.Definition,
    override val metadata: Tool.Metadata,
    private val function: Tool.Function,
) : Tool {
    override fun call(input: String): Tool.Result =
        function.invoke(input)
}

/**
 * Tool implementation that wraps a method annotated with @Tool.Method.
 */
private class MethodTool(
    private val instance: Any,
    private val method: KFunction<*>,
    annotation: LlmTool,
    private val objectMapper: ObjectMapper,
) : Tool {

    private val logger = LoggerFactory.getLogger(MethodTool::class.java)

    override val definition: Tool.Definition = createDefinition(method, annotation)

    override val metadata: Tool.Metadata = Tool.Metadata(returnDirect = annotation.returnDirect)

    override fun call(input: String): Tool.Result {
        return try {
            val args = parseArguments(input)
            val result = invokeMethod(args)
            convertResult(result)
        } catch (e: Exception) {
            // Unwrap InvocationTargetException to get the actual cause
            val actualCause = e.cause ?: e
            val message = actualCause.message ?: e.message ?: "Tool invocation failed"
            logger.error("Error invoking tool '{}': {}", definition.name, message, actualCause)
            Tool.Result.error(message, actualCause)
        }
    }

    private fun createDefinition(
        method: KFunction<*>,
        annotation: LlmTool,
    ): Tool.Definition {
        val name = annotation.name.ifEmpty { method.name }
        val parameters = method.parameters
            .filter { it.kind == KParameter.Kind.VALUE }
            .map { param ->
                val paramAnnotation = param.findAnnotation<Param>()
                Tool.Parameter(
                    name = param.name ?: "arg${param.index}",
                    type = mapKotlinTypeToParameterType(param.type),
                    description = paramAnnotation?.description ?: "",
                    required = paramAnnotation?.required ?: !param.isOptional,
                )
            }

        return Tool.Definition(
            name = name,
            description = annotation.description,
            inputSchema = SimpleInputSchema(parameters),
        )
    }

    private fun mapKotlinTypeToParameterType(type: kotlin.reflect.KType): Tool.ParameterType {
        val classifier = type.classifier
        return when {
            classifier == String::class -> Tool.ParameterType.STRING
            classifier == Int::class || classifier == Long::class -> Tool.ParameterType.INTEGER
            classifier == Double::class || classifier == Float::class -> Tool.ParameterType.NUMBER
            classifier == Boolean::class -> Tool.ParameterType.BOOLEAN
            classifier == List::class || classifier == Array::class -> Tool.ParameterType.ARRAY
            else -> Tool.ParameterType.OBJECT
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

    private fun invokeMethod(args: Map<String, Any?>): Any? {
        val params = method.parameters
        val callArgs = mutableMapOf<KParameter, Any?>()

        for (param in params) {
            when (param.kind) {
                KParameter.Kind.INSTANCE -> callArgs[param] = instance
                KParameter.Kind.VALUE -> {
                    val paramName = param.name ?: continue
                    val value = args[paramName]

                    if (value != null) {
                        // Convert value to expected type if needed
                        val convertedValue = convertToExpectedType(value, param)
                        callArgs[param] = convertedValue
                    } else if (!param.isOptional) {
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

    private fun convertToExpectedType(
        value: Any,
        param: KParameter,
    ): Any? {
        val targetType = param.type.javaType

        // If already correct type, return as-is
        if (targetType is Class<*> && targetType.isInstance(value)) {
            return value
        }

        // Handle numeric conversions from JSON (Jackson often returns Int/Double)
        return when {
            targetType == Int::class.java || targetType == Integer::class.java ->
                (value as? Number)?.toInt() ?: value

            targetType == Long::class.java || targetType == java.lang.Long::class.java ->
                (value as? Number)?.toLong() ?: value

            targetType == Double::class.java || targetType == java.lang.Double::class.java ->
                (value as? Number)?.toDouble() ?: value

            targetType == Float::class.java || targetType == java.lang.Float::class.java ->
                (value as? Number)?.toFloat() ?: value

            targetType == Boolean::class.java || targetType == java.lang.Boolean::class.java ->
                value as? Boolean ?: value.toString().toBoolean()

            targetType == String::class.java ->
                value.toString()

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

    private fun convertResult(result: Any?): Tool.Result {
        return when (result) {
            null -> Tool.Result.text("")
            is String -> Tool.Result.text(result)
            is Tool.Result -> result
            else -> {
                // Convert to JSON string
                try {
                    Tool.Result.text(objectMapper.writeValueAsString(result))
                } catch (e: Exception) {
                    Tool.Result.text(result.toString())
                }
            }
        }
    }
}
