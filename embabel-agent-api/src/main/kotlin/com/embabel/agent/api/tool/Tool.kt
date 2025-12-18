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
package com.embabel.agent.api.tool

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.annotation.LlmTool.Param
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.spi.config.spring.executingOperationContextFor
import com.embabel.common.util.loggerFor
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.functions
import kotlin.reflect.full.hasAnnotation
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

        companion object {
            operator fun invoke(
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
     */
    data class Parameter(
        val name: String,
        val type: ParameterType,
        val description: String,
        val required: Boolean = true,
        val enumValues: List<String>? = null,
    )

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

    /**
     * An agentic tool that uses an LLM to orchestrate other tools.
     *
     * Unlike a regular [Tool] which executes deterministic logic, an [Agentic] tool
     * uses an LLM to decide which sub-tools to call based on a prompt.
     *
     * @param definition Tool definition (name, description, input schema)
     * @param metadata Optional tool metadata
     * @param model Optional model name for LLM selection. Takes precedence over role.
     * @param role Optional role for LLM selection. Used if model is not specified.
     * @param tools Sub-tools available for the LLM to orchestrate
     * @param prompt Prompt template for the LLM. Use {input} for the tool input.
     */
    class Agentic(
        override val definition: Definition,
        override val metadata: Metadata = Metadata.DEFAULT,
        val model: String? = null,
        val role: String? = null,
        val tools: List<Tool>,
        val prompt: String,
    ) : Tool {

        init {
            require(tools.isNotEmpty()) { "Agentic tool must have at least one sub-tool" }
            require(prompt.isNotBlank()) { "Agentic tool prompt must not be blank" }
        }

        override fun call(input: String): Result {
            val agentProcess = AgentProcess.get()
                ?: run {
                    loggerFor<Agentic>().error(
                        "No AgentProcess context available for Agentic tool '{}'",
                        definition.name,
                    )
                    return Result.error("No AgentProcess context available for Agentic tool")
                }
            val ai = executingOperationContextFor(agentProcess).ai()
            val promptRunner = when {
                model != null -> ai.withLlm(model)
                role != null -> ai.withLlmByRole(role)
                else -> {
                    loggerFor<Agentic>().warn(
                        "No model or role specified for Agentic tool '{}', using default LLM",
                        definition.name,
                    )
                    ai.withDefaultLlm()
                }
            }
            val output = promptRunner
                .withTools(tools)
                .withSystemPrompt(prompt)
                .generateText(input)

            return Result.text(output)
        }

        /**
         * Create a copy with different model.
         */
        fun withModel(model: String): Agentic = Agentic(
            definition = definition,
            metadata = metadata,
            model = model,
            role = role,
            tools = tools,
            prompt = prompt,
        )

        /**
         * Create a copy with different role.
         */
        fun withRole(role: String): Agentic = Agentic(
            definition = definition,
            metadata = metadata,
            model = model,
            role = role,
            tools = tools,
            prompt = prompt,
        )

        /**
         * Create a copy with additional tools.
         */
        fun withTools(vararg additionalTools: Tool): Agentic = Agentic(
            definition = definition,
            metadata = metadata,
            model = model,
            role = role,
            tools = tools + additionalTools,
            prompt = prompt,
        )

        /**
         * Create a copy with different prompt.
         */
        fun withPrompt(prompt: String): Agentic = Agentic(
            definition = definition,
            metadata = metadata,
            model = model,
            role = role,
            tools = tools,
            prompt = prompt,
        )

        companion object {

            /**
             * Create an agentic tool with the given configuration.
             */
            @JvmStatic
            @JvmOverloads
            fun create(
                name: String,
                description: String,
                tools: List<Tool>,
                prompt: String,
                model: String? = null,
                role: String? = null,
                inputSchema: InputSchema = InputSchema.empty(),
                metadata: Metadata = Metadata.DEFAULT,
            ): Agentic = Agentic(
                definition = Definition(name, description, inputSchema),
                metadata = metadata,
                model = model,
                role = role,
                tools = tools,
                prompt = prompt,
            )

            /**
             * Create an agentic tool with vararg tools.
             */
            @JvmStatic
            fun create(
                name: String,
                description: String,
                prompt: String,
                vararg tools: Tool,
            ): Agentic = create(
                name = name,
                description = description,
                tools = tools.toList(),
                prompt = prompt,
            )

            /**
             * Create an agentic tool with model specified.
             */
            @JvmStatic
            fun withModel(
                name: String,
                description: String,
                model: String,
                tools: List<Tool>,
                prompt: String,
            ): Agentic = create(
                name = name,
                description = description,
                model = model,
                tools = tools,
                prompt = prompt,
            )

            /**
             * Create an agentic tool with role specified.
             */
            @JvmStatic
            fun withRole(
                name: String,
                description: String,
                role: String,
                tools: List<Tool>,
                prompt: String,
            ): Agentic = create(
                name = name,
                description = description,
                role = role,
                tools = tools,
                prompt = prompt,
            )
        }
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

        // Java-friendly factory methods using Handler interface

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
         * @param instance The object instance to scan for annotated methods
         * @param objectMapper ObjectMapper for JSON parsing (optional)
         * @return List of Tools, one for each annotated method
         * @throws IllegalArgumentException if no methods are annotated with @Tool.Method
         */
        fun fromInstance(
            instance: Any,
            objectMapper: ObjectMapper = jacksonObjectMapper(),
        ): List<Tool> {
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
        fun safelyFromInstance(
            instance: Any,
            objectMapper: ObjectMapper = jacksonObjectMapper(),
        ): List<Tool> {
            return try {
                fromInstance(instance, objectMapper)
            } catch (e: IllegalArgumentException) {
                logger.debug("No @Tool.Method annotations found on {}: {}", instance::class.simpleName, e.message)
                emptyList()
            } catch (e: Throwable) {
                // Kotlin reflection can fail on some Java classes with KotlinReflectionInternalError (an Error, not Exception)
                logger.debug(
                    "Failed to scan {} for @Tool.Method annotations: {}",
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

    override fun toJsonSchema(): String {
        if (parameters.isEmpty()) {
            return """{"type": "object", "properties": {}}"""
        }

        val properties = parameters.joinToString(", ") { param ->
            val typeStr = when (param.type) {
                Tool.ParameterType.STRING -> "string"
                Tool.ParameterType.INTEGER -> "integer"
                Tool.ParameterType.NUMBER -> "number"
                Tool.ParameterType.BOOLEAN -> "boolean"
                Tool.ParameterType.ARRAY -> "array"
                Tool.ParameterType.OBJECT -> "object"
            }
            val enumPart = param.enumValues?.let { values ->
                """, "enum": [${values.joinToString(", ") { v -> "\"$v\"" }}]"""
            } ?: ""
            """"${param.name}": {"type": "$typeStr", "description": "${param.description}"$enumPart}"""
        }

        val required = parameters.filter { it.required }
            .joinToString(", ") { "\"${it.name}\"" }

        return """{"type": "object", "properties": {$properties}, "required": [$required]}"""
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
