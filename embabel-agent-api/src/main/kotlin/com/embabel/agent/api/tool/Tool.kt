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

import com.embabel.agent.api.tool.Tool.Definition
import com.embabel.agent.api.tool.progressive.UnfoldingTool
import com.embabel.agent.core.DomainType
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Tool information including definition and metadata,
 * without execution logic.
 */
interface ToolInfo {

    /** Tool definition for LLM */
    val definition: Definition

    /** Optional metadata */
    val metadata: Tool.Metadata get() = Tool.Metadata.DEFAULT

}

/**
 * Framework-agnostic tool that can be invoked by an LLM.
 * Adapters in SPI layer bridge to Spring AI ToolCallback or LangChain4j ToolSpecification/ToolExecutor.
 *
 * All nested types are scoped within this interface to avoid naming conflicts with
 * framework-specific types (e.g., Spring AI's ToolDefinition, ToolMetadata).
 */
interface Tool : ToolInfo {


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
            fun of(type: Class<*>): InputSchema =
                TypeBasedInputSchema(type)

            @JvmStatic
            fun of(domainType: DomainType): InputSchema =
                DomainTypeInputSchema(domainType)

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
     * @param properties Nested properties for OBJECT type parameters
     * @param itemType Element type for ARRAY type parameters (e.g., STRING for List<String>)
     */
    data class Parameter @JvmOverloads constructor(
        val name: String,
        val type: ParameterType,
        val description: String = name,
        val required: Boolean = true,
        val enumValues: List<String>? = null,
        val properties: List<Parameter>? = null,
        val itemType: ParameterType? = null,
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

    companion object : TypedToolFactory, MethodToolFactory, ReplanningToolFactory, ArtifactSinkFactory {

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

        @JvmStatic
        override fun <I : Any, O : Any> fromFunction(
            name: String,
            description: String,
            inputType: Class<I>,
            outputType: Class<O>,
            function: java.util.function.Function<I, O>,
        ): Tool = super.fromFunction(name, description, inputType, outputType, function)

        @JvmStatic
        override fun <I : Any, O : Any> fromFunction(
            name: String,
            description: String,
            inputType: Class<I>,
            outputType: Class<O>,
            metadata: Metadata,
            function: java.util.function.Function<I, O>,
        ): Tool = super.fromFunction(name, description, inputType, outputType, metadata, function)

        @JvmStatic
        override fun <I : Any, O : Any> fromFunction(
            name: String,
            description: String,
            inputType: Class<I>,
            outputType: Class<O>,
            metadata: Metadata,
            objectMapper: ObjectMapper,
            function: java.util.function.Function<I, O>,
        ): Tool = super.fromFunction(name, description, inputType, outputType, metadata, objectMapper, function)

        @JvmStatic
        fun fromInstance(instance: Any): List<Tool> =
            super.fromInstance(instance, com.fasterxml.jackson.module.kotlin.jacksonObjectMapper())

        @JvmStatic
        override fun fromInstance(
            instance: Any,
            objectMapper: ObjectMapper,
        ): List<Tool> = super.fromInstance(instance, objectMapper)

        @JvmStatic
        fun safelyFromInstance(instance: Any): List<Tool> =
            super.safelyFromInstance(instance, com.fasterxml.jackson.module.kotlin.jacksonObjectMapper())

        @JvmStatic
        override fun safelyFromInstance(
            instance: Any,
            objectMapper: ObjectMapper,
        ): List<Tool> = super.safelyFromInstance(instance, objectMapper)

        @JvmStatic
        override fun replanAlways(tool: Tool): Tool = super.replanAlways(tool)

        @JvmStatic
        override fun <T> conditionalReplan(
            tool: Tool,
            decider: (t: T, replanContext: ReplanContext) -> ReplanDecision?,
        ): DelegatingTool = super.conditionalReplan(tool, decider)

        @JvmStatic
        override fun <T> replanWhen(
            tool: Tool,
            predicate: (t: T) -> Boolean,
        ): DelegatingTool = super.replanWhen(tool, predicate)

        @JvmStatic
        override fun <T> replanAndAdd(
            tool: Tool,
            valueComputer: (t: T) -> Any?,
        ): DelegatingTool = super.replanAndAdd(tool, valueComputer)

        /**
         * Format a list of tools as an ASCII tree structure.
         * UnfoldingTools are expanded recursively to show their inner tools.
         *
         * @param name The name to display at the root of the tree
         * @param tools The list of tools to format
         * @return A formatted tree string, or a message if no tools are present
         */
        @JvmStatic
        fun formatToolTree(name: String, tools: List<Tool>): String {
            if (tools.isEmpty()) {
                return "$name has no tools"
            }

            val sb = StringBuilder()
            sb.append(name).append("\n")
            formatToolsRecursive(sb, tools, "")
            return sb.toString().trim()
        }

        private fun formatToolsRecursive(sb: StringBuilder, tools: List<Tool>, indent: String) {
            tools.forEachIndexed { i, tool ->
                val isLast = i == tools.size - 1
                val prefix = if (isLast) "└── " else "├── "
                val childIndent = indent + if (isLast) "    " else "│   "

                if (tool is UnfoldingTool) {
                    sb.append(indent).append(prefix).append(tool.definition.name)
                        .append(" (").append(tool.innerTools.size).append(" inner tools)\n")
                    formatToolsRecursive(sb, tool.innerTools, childIndent)
                } else {
                    sb.append(indent).append(prefix).append(tool.definition.name).append("\n")
                }
            }
        }

        @JvmStatic
        override fun <T : Any> sinkArtifacts(
            tool: Tool,
            clazz: Class<T>,
            sink: ArtifactSink,
        ): Tool = super.sinkArtifacts(tool, clazz, sink)

        @JvmStatic
        override fun <T : Any> sinkArtifacts(
            tool: Tool,
            clazz: Class<T>,
            sink: ArtifactSink,
            filter: (T) -> Boolean,
            transform: (T) -> Any,
        ): Tool = super.sinkArtifacts(tool, clazz, sink, filter, transform)

        @JvmStatic
        override fun publishToBlackboard(tool: Tool): Tool = super.publishToBlackboard(tool)

        @JvmStatic
        override fun <T : Any> publishToBlackboard(
            tool: Tool,
            clazz: Class<T>,
        ): Tool = super.publishToBlackboard(tool, clazz)

        @JvmStatic
        override fun <T : Any> publishToBlackboard(
            tool: Tool,
            clazz: Class<T>,
            filter: (T) -> Boolean,
            transform: (T) -> Any,
        ): Tool = super.publishToBlackboard(tool, clazz, filter, transform)

    }

    /**
     * Create a new tool with a different name.
     * Useful for namespacing tools when combining multiple tool sources.
     *
     * @param newName The new name to use
     * @return A new Tool with the updated name
     */
    fun withName(newName: String): Tool = RenamedTool(this, newName)

    /**
     * Create a new tool with a different description.
     * Useful for providing context-specific descriptions while keeping the same functionality.
     *
     * @param newDescription The new description to use
     * @return A new Tool with the updated description
     */
    fun withDescription(newDescription: String): Tool = DescribedTool(this, newDescription)

    /**
     * Create a new tool with an additional note appended to the description.
     * Useful for adding context-specific hints to an existing tool.
     *
     * @param note The note to append to the description
     * @return A new Tool with the note appended to its description
     */
    fun withNote(note: String): Tool = DescribedTool(this, "${definition.description}. $note")
}

/**
 * A tool wrapper that overrides the name while delegating all functionality.
 * Implements [DelegatingTool] to support unwrapping in injection strategies.
 */
private class RenamedTool(
    override val delegate: Tool,
    private val customName: String,
) : DelegatingTool {

    override val definition: Tool.Definition = Tool.Definition(
        name = customName,
        description = delegate.definition.description,
        inputSchema = delegate.definition.inputSchema,
    )

    override val metadata: Tool.Metadata
        get() = delegate.metadata

    override fun call(input: String): Tool.Result = delegate.call(input)
}

/**
 * A tool wrapper that overrides the description while delegating all functionality.
 * Implements [DelegatingTool] to support unwrapping in injection strategies.
 */
private class DescribedTool(
    override val delegate: Tool,
    private val customDescription: String,
) : DelegatingTool {

    override val definition: Tool.Definition = Tool.Definition(
        name = delegate.definition.name,
        description = customDescription,
        inputSchema = delegate.definition.inputSchema,
    )

    override val metadata: Tool.Metadata
        get() = delegate.metadata

    override fun call(input: String): Tool.Result = delegate.call(input)
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

        // For ARRAY types with itemType, add items property
        if (param.type == Tool.ParameterType.ARRAY && param.itemType != null) {
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

        // For OBJECT types with nested properties, add them recursively
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
