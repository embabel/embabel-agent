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
import com.embabel.agent.api.annotation.MatryoshkaTools
import com.embabel.agent.api.tool.Tool.Definition
import com.embabel.agent.api.tool.progressive.UnfoldingTool
import com.embabel.agent.core.DomainType
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.core.KotlinDetector
import java.lang.reflect.Method
import kotlin.reflect.KFunction
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.functions
import kotlin.reflect.full.hasAnnotation

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
         * Create a tool with strongly typed input and output (Java-friendly).
         *
         * The input type is used to generate the JSON schema automatically.
         * Input JSON is deserialized to [I], the function is called, and
         * the output [O] is serialized back to JSON.
         *
         * Example (Java):
         * ```java
         * record AddRequest(int a, int b) {}
         * record AddResult(int sum) {}
         *
         * Tool tool = Tool.fromFunction(
         *     "add",
         *     "Add two numbers",
         *     AddRequest.class,
         *     AddResult.class,
         *     input -> new AddResult(input.a() + input.b())
         * );
         * ```
         *
         * @param I Input type - will be deserialized from JSON
         * @param O Output type - will be serialized to JSON
         * @param name Tool name
         * @param description Tool description
         * @param inputType Class of the input type
         * @param outputType Class of the output type
         * @param function Function that processes typed input and returns typed output
         * @return A new Tool instance
         */
        @JvmStatic
        fun <I : Any, O : Any> fromFunction(
            name: String,
            description: String,
            inputType: Class<I>,
            outputType: Class<O>,
            function: java.util.function.Function<I, O>,
        ): Tool = fromFunction(name, description, inputType, outputType, Metadata.DEFAULT, function)

        /**
         * Create a tool with strongly typed input and output with custom metadata (Java-friendly).
         *
         * @param I Input type - will be deserialized from JSON
         * @param O Output type - will be serialized to JSON
         * @param name Tool name
         * @param description Tool description
         * @param inputType Class of the input type
         * @param outputType Class of the output type
         * @param metadata Tool metadata
         * @param function Function that processes typed input and returns typed output
         * @return A new Tool instance
         */
        @JvmStatic
        fun <I : Any, O : Any> fromFunction(
            name: String,
            description: String,
            inputType: Class<I>,
            outputType: Class<O>,
            metadata: Metadata,
            function: java.util.function.Function<I, O>,
        ): Tool = fromFunction(name, description, inputType, outputType, metadata, jacksonObjectMapper(), function)

        /**
         * Create a tool with strongly typed input and output with custom ObjectMapper (Java-friendly).
         *
         * @param I Input type - will be deserialized from JSON
         * @param O Output type - will be serialized to JSON
         * @param name Tool name
         * @param description Tool description
         * @param inputType Class of the input type
         * @param outputType Class of the output type
         * @param metadata Tool metadata
         * @param objectMapper ObjectMapper for JSON serialization/deserialization
         * @param function Function that processes typed input and returns typed output
         * @return A new Tool instance
         */
        @JvmStatic
        fun <I : Any, O : Any> fromFunction(
            name: String,
            description: String,
            inputType: Class<I>,
            outputType: Class<O>,
            metadata: Metadata,
            objectMapper: ObjectMapper,
            function: java.util.function.Function<I, O>,
        ): Tool = FunctionTool(
            name = name,
            description = description,
            inputType = inputType,
            outputType = outputType,
            metadata = metadata,
            objectMapper = objectMapper,
            function = function,
        )

        /**
         * Create a tool with strongly typed input and output (Kotlin-friendly).
         *
         * Example (Kotlin):
         * ```kotlin
         * data class AddRequest(val a: Int, val b: Int)
         * data class AddResult(val sum: Int)
         *
         * val tool = Tool.fromFunction<AddRequest, AddResult>(
         *     name = "add",
         *     description = "Add two numbers",
         * ) { input -> AddResult(input.a + input.b) }
         * ```
         *
         * @param I Input type - will be deserialized from JSON
         * @param O Output type - will be serialized to JSON
         * @param name Tool name
         * @param description Tool description
         * @param metadata Tool metadata (optional)
         * @param objectMapper ObjectMapper for JSON serialization/deserialization (optional)
         * @param function Function that processes typed input and returns typed output
         * @return A new Tool instance
         */
        inline fun <reified I : Any, reified O : Any> fromFunction(
            name: String,
            description: String,
            metadata: Metadata = Metadata.DEFAULT,
            objectMapper: ObjectMapper = jacksonObjectMapper(),
            noinline function: (I) -> O,
        ): Tool = fromFunction(
            name = name,
            description = description,
            inputType = I::class.java,
            outputType = O::class.java,
            metadata = metadata,
            objectMapper = objectMapper,
            function = java.util.function.Function { input -> function(input) },
        )

        // endregion

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

            return KotlinMethodTool(
                instance = instance,
                method = method,
                annotation = annotation,
                objectMapper = objectMapper,
            )
        }

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
            method: Method,
            objectMapper: ObjectMapper = jacksonObjectMapper(),
        ): Tool {
            val annotation = method.getAnnotation(LlmTool::class.java)
                ?: throw IllegalArgumentException(
                    "Method ${method.name} is not annotated with @Tool.Method"
                )

            return JavaMethodTool(
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
         * returns a single [UnfoldingTool] containing all the inner tools.
         * Otherwise, returns individual tools for each annotated method.
         *
         * @param instance The object instance to scan for annotated methods
         * @param objectMapper ObjectMapper for JSON parsing (optional)
         * @return List of Tools, one for each annotated method (or single UnfoldingTool if @MatryoshkaTools present)
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
                return listOf(UnfoldingTool.fromInstance(instance, objectMapper))
            }

            val tools = if (KotlinDetector.isKotlinReflectPresent()) {
                instance::class.functions
                    .filter { it.hasAnnotation<LlmTool>() }
                    .map { fromMethod(instance, it, objectMapper) }
            } else {
                instance.javaClass.methods
                    .filter { it.isAnnotationPresent(LlmTool::class.java) }
                    .map { fromMethod(instance, it, objectMapper) }
            }

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

        /**
         * Make this tool always replan after execution, adding the artifact to the blackboard.
         */
        @JvmStatic
        fun replanAlways(tool: Tool): Tool {
            return ConditionalReplanningTool(tool) { context ->
                ReplanDecision("${tool.definition.name} replans") { bb ->
                    context.artifact?.let { bb.addObject(it) }
                }
            }
        }

        /**
         * When the decider returns a [ReplanDecision], replan after execution, adding the artifact
         * to the blackboard along with any additional updates from the decision.
         * The decider receives the artifact cast to type T and the replan context.
         * If the artifact is null or cannot be cast to T, the decider is not called.
         */
        @JvmStatic
        @Suppress("UNCHECKED_CAST")
        fun <T> conditionalReplan(
            tool: Tool,
            decider: (t: T, replanContext: ReplanContext) -> ReplanDecision?,
        ): DelegatingTool {
            return ConditionalReplanningTool(tool) { replanContext ->
                val artifact = replanContext.artifact ?: return@ConditionalReplanningTool null
                try {
                    val decision = decider(artifact as T, replanContext)
                        ?: return@ConditionalReplanningTool null
                    ReplanDecision(decision.reason) { bb ->
                        bb.addObject(artifact)
                        decision.blackboardUpdater.accept(bb)
                    }
                } catch (_: ClassCastException) {
                    null
                }
            }
        }

        /**
         * When the predicate matches the tool result artifact, replan, adding the artifact to the blackboard.
         * The predicate receives the artifact cast to type T.
         * If the artifact is null or cannot be cast to T, returns normally.
         */
        @JvmStatic
        @Suppress("UNCHECKED_CAST")
        fun <T> replanWhen(
            tool: Tool,
            predicate: (t: T) -> Boolean,
        ): DelegatingTool {
            return ConditionalReplanningTool(tool) { replanContext ->
                val artifact = replanContext.artifact ?: return@ConditionalReplanningTool null
                try {
                    if (predicate(artifact as T)) {
                        ReplanDecision("${tool.definition.name} replans based on result") { bb ->
                            bb.addObject(artifact)
                        }
                    } else {
                        null
                    }
                } catch (_: ClassCastException) {
                    null
                }
            }
        }

        /**
         * Replan and add the object returned by the predicate to the blackboard.
         * @param tool The tool to wrap
         * @param valueComputer Function that takes the artifact of type T and returns an object to add to the blackboard, or null to not replan
         */
        @JvmStatic
        @Suppress("UNCHECKED_CAST")
        fun <T> replanAndAdd(
            tool: Tool,
            valueComputer: (t: T) -> Any?,
        ): DelegatingTool {
            return ConditionalReplanningTool(tool) { replanContext ->
                val artifact = replanContext.artifact ?: return@ConditionalReplanningTool null
                try {
                    val toAdd = valueComputer(artifact as T)
                    if (toAdd != null) {
                        ReplanDecision("${tool.definition.name} replans based on result") { bb ->
                            bb.addObject(toAdd)
                        }
                    } else {
                        null
                    }
                } catch (_: ClassCastException) {
                    null
                }
            }
        }

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

        /**
         * Wrap a tool to sink artifacts of the specified type to the given sink.
         * Handles both single artifacts and Iterables.
         *
         * @param tool The tool to wrap
         * @param clazz The class of artifacts to capture
         * @param sink Where to send captured artifacts
         */
        @JvmStatic
        fun <T : Any> sinkArtifacts(
            tool: Tool,
            clazz: Class<T>,
            sink: ArtifactSink,
        ): Tool = ArtifactSinkingTool(
            delegate = tool,
            clazz = clazz,
            sink = sink,
        )

        /**
         * Wrap a tool to sink artifacts of the specified type to the given sink,
         * with optional filtering and transformation.
         *
         * @param tool The tool to wrap
         * @param clazz The class of artifacts to capture
         * @param sink Where to send captured artifacts
         * @param filter Predicate to filter which artifacts to capture
         * @param transform Function to transform artifacts before sinking
         */
        @JvmStatic
        fun <T : Any> sinkArtifacts(
            tool: Tool,
            clazz: Class<T>,
            sink: ArtifactSink,
            filter: (T) -> Boolean,
            transform: (T) -> Any,
        ): Tool = ArtifactSinkingTool(
            delegate = tool,
            clazz = clazz,
            sink = sink,
            filter = filter,
            transform = transform,
        )

        /**
         * Wrap a tool to publish all artifacts to the blackboard.
         *
         * @param tool The tool to wrap
         */
        @JvmStatic
        fun publishToBlackboard(tool: Tool): Tool = ArtifactSinkingTool(
            delegate = tool,
            clazz = Any::class.java,
            sink = BlackboardSink,
        )

        /**
         * Wrap a tool to publish artifacts of the specified type to the blackboard.
         *
         * @param tool The tool to wrap
         * @param clazz The class of artifacts to publish
         */
        @JvmStatic
        fun <T : Any> publishToBlackboard(
            tool: Tool,
            clazz: Class<T>,
        ): Tool = ArtifactSinkingTool(
            delegate = tool,
            clazz = clazz,
            sink = BlackboardSink,
        )

        /**
         * Wrap a tool to publish artifacts of the specified type to the blackboard,
         * with optional filtering and transformation.
         *
         * @param tool The tool to wrap
         * @param clazz The class of artifacts to publish
         * @param filter Predicate to filter which artifacts to publish
         * @param transform Function to transform artifacts before publishing
         */
        @JvmStatic
        fun <T : Any> publishToBlackboard(
            tool: Tool,
            clazz: Class<T>,
            filter: (T) -> Boolean,
            transform: (T) -> Any,
        ): Tool = ArtifactSinkingTool(
            delegate = tool,
            clazz = clazz,
            sink = BlackboardSink,
            filter = filter,
            transform = transform,
        )
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
