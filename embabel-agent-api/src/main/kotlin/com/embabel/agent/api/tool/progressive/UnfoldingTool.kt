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
package com.embabel.agent.api.tool.progressive

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.annotation.MatryoshkaTools
import com.embabel.agent.api.annotation.UnfoldingTools
import com.embabel.agent.api.tool.MatryoshkaTool
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.AgentProcess
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.BeanUtils
import org.springframework.core.KotlinDetector
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.functions
import kotlin.reflect.full.hasAnnotation

/**
 * A [ProgressiveTool] with a fixed set of inner tools that are revealed
 * when invoked, regardless of the agent process context.
 *
 * This pattern is useful for:
 * - Reducing tool set complexity for the LLM
 * - Grouping related tools under a category facade
 * - Progressive disclosure based on LLM intent
 *
 * ## Context Preservation
 *
 * When an UnfoldingTool is expanded, a context tool can be created that
 * preserves the parent's description and optional usage notes. This solves
 * the problem where child tools would lose context about the parent's purpose.
 *
 * The [childToolUsageNotes] field provides additional guidance on how to
 * use the child tools, included only once in the context tool rather than
 * duplicated in each child tool's description.
 *
 * Example:
 * ```kotlin
 * val spotifyTool = UnfoldingTool.of(
 *     name = "spotify_search",
 *     description = "Search Spotify for music data including artists, albums, and tracks.",
 *     innerTools = listOf(vectorSearchTool, textSearchTool, regexSearchTool),
 *     childToolUsageNotes = "Try vector search first for semantic queries like 'upbeat jazz'. " +
 *         "Use text search for exact artist or album names. " +
 *         "Use regex search for pattern matching on metadata."
 * )
 * ```
 *
 * @see ProgressiveTool for context-dependent tool revelation
 */
interface UnfoldingTool : ProgressiveTool {

    /**
     * The inner tools that will be exposed when this tool is invoked.
     * This is a fixed set that does not vary by context.
     */
    val innerTools: List<Tool>

    /**
     * Returns the fixed [innerTools] regardless of process context.
     */
    override fun innerTools(process: AgentProcess): List<Tool> = innerTools

    /**
     * Optional usage notes to guide the LLM on when to invoke the child tools.
     */
    val childToolUsageNotes: String? get() = null

    /**
     * Whether to remove this tool after invocation.
     *
     * When `true` (default), the facade is replaced by its contents.
     * When `false`, the facade remains available for re-invocation
     * (useful for category-based selection with different arguments).
     */
    val removeOnInvoke: Boolean get() = true

    /**
     * Select which inner tools to expose based on invocation input.
     *
     * Override this method to implement category-based or argument-driven
     * tool selection. Default implementation returns all inner tools.
     *
     * @param input The JSON input string provided to this tool
     * @return The tools to expose (subset of [innerTools] or all)
     */
    fun selectTools(input: String): List<Tool> = innerTools

    /**
     * Create a new UnfoldingTool with additional tools added.
     *
     * This enables fluent building of tool groups:
     * ```kotlin
     * val combined = UnfoldingTool.of("tools", "My tools", listOf(baseTool))
     *     .withTools(searchTool, filterTool)
     *     .withToolObject(HelperTools())
     * ```
     *
     * @param tools The tools to add
     * @return A new UnfoldingTool with the combined tools
     */
    fun withTools(vararg tools: Tool): UnfoldingTool = of(
        name = definition.name,
        description = definition.description,
        innerTools = innerTools + tools.toList(),
        removeOnInvoke = removeOnInvoke,
        childToolUsageNotes = childToolUsageNotes,
    )

    /**
     * Create a new UnfoldingTool with tools added from an annotated object.
     *
     * The object should have methods annotated with `@LlmTool`.
     * This enables fluent building of tool groups:
     * ```kotlin
     * val combined = UnfoldingTool.of("tools", "My tools", listOf(baseTool))
     *     .withToolObject(DatabaseTools())
     *     .withToolObject(FileTools())
     * ```
     *
     * @param toolObject An object with `@LlmTool` annotated methods
     * @return A new UnfoldingTool with the combined tools
     */
    fun withToolObject(toolObject: Any): UnfoldingTool {
        val additionalTools = Tool.fromInstance(toolObject)
        return of(
            name = definition.name,
            description = definition.description,
            innerTools = innerTools + additionalTools,
            removeOnInvoke = removeOnInvoke,
            childToolUsageNotes = childToolUsageNotes,
        )
    }

    /**
     * Factory methods for creating UnfoldingTool instances.
     * This is an open class so that subinterface companions can extend it.
     */
    open class Factory {

        /**
         * Create an UnfoldingTool that exposes all inner tools when invoked.
         *
         * @param name Unique name for the tool
         * @param description Description explaining when to use this tool category
         * @param innerTools The tools to expose when invoked
         * @param removeOnInvoke Whether to remove this tool after invocation (default true)
         * @param childToolUsageNotes Optional notes to guide LLM on using the child tools
         */
        @JvmOverloads
        open fun of(
            name: String,
            description: String,
            innerTools: List<Tool>,
            removeOnInvoke: Boolean = true,
            childToolUsageNotes: String? = null,
        ): UnfoldingTool = SimpleUnfoldingTool(
            definition = Tool.Definition(
                name = name,
                description = description,
                inputSchema = Tool.InputSchema.empty(),
            ),
            innerTools = innerTools,
            removeOnInvoke = removeOnInvoke,
            childToolUsageNotes = childToolUsageNotes,
        )

        /**
         * Create an UnfoldingTool with a custom tool selector.
         *
         * The selector receives the JSON input string and returns the tools to expose.
         * This enables category-based tool disclosure.
         *
         * Example:
         * ```kotlin
         * val fileTool = UnfoldingTool.selectable(
         *     name = "file_operations",
         *     description = "File operations. Pass 'category': 'read' or 'write'.",
         *     innerTools = allFileTools,
         *     inputSchema = Tool.InputSchema.of(
         *         Tool.Parameter.string("category", "The category of file operations", required = true)
         *     ),
         * ) { input ->
         *     val json = ObjectMapper().readValue(input, Map::class.java)
         *     val category = json["category"] as? String
         *     when (category) {
         *         "read" -> listOf(readFileTool, listDirTool)
         *         "write" -> listOf(writeFileTool, deleteTool)
         *         else -> allFileTools
         *     }
         * }
         * ```
         *
         * @param name Unique name for the tool
         * @param description Description explaining when to use this tool category
         * @param innerTools All possible inner tools
         * @param inputSchema Schema describing the selection parameters
         * @param removeOnInvoke Whether to remove this tool after invocation
         * @param childToolUsageNotes Optional notes to guide LLM on using the child tools
         * @param selector Function to select tools based on input
         */
        @JvmOverloads
        open fun selectable(
            name: String,
            description: String,
            innerTools: List<Tool>,
            inputSchema: Tool.InputSchema,
            removeOnInvoke: Boolean = true,
            childToolUsageNotes: String? = null,
            selector: (String) -> List<Tool>,
        ): UnfoldingTool = SelectableUnfoldingTool(
            definition = Tool.Definition(
                name = name,
                description = description,
                inputSchema = inputSchema,
            ),
            innerTools = innerTools,
            removeOnInvoke = removeOnInvoke,
            childToolUsageNotes = childToolUsageNotes,
            selector = selector,
        )

        /**
         * Create an UnfoldingTool with category-based selection.
         *
         * @param name Unique name for the tool
         * @param description Description explaining when to use this tool category
         * @param toolsByCategory Map of category names to their tools
         * @param categoryParameter Name of the category parameter (default "category")
         * @param removeOnInvoke Whether to remove this tool after invocation
         * @param childToolUsageNotes Optional notes to guide LLM on using the child tools
         */
        @JvmOverloads
        open fun byCategory(
            name: String,
            description: String,
            toolsByCategory: Map<String, List<Tool>>,
            categoryParameter: String = "category",
            removeOnInvoke: Boolean = true,
            childToolUsageNotes: String? = null,
        ): UnfoldingTool {
            val allTools = toolsByCategory.values.flatten()
            val categoryNames = toolsByCategory.keys.toList()

            return SelectableUnfoldingTool(
                definition = Tool.Definition(
                    name = name,
                    description = description,
                    inputSchema = Tool.InputSchema.of(
                        Tool.Parameter.string(
                            name = categoryParameter,
                            description = "Category to access. Available: ${categoryNames.joinToString(", ")}",
                            required = true,
                            enumValues = categoryNames,
                        )
                    ),
                ),
                innerTools = allTools,
                removeOnInvoke = removeOnInvoke,
                childToolUsageNotes = childToolUsageNotes,
                selector = { input ->
                    val category = extractCategory(input, categoryParameter)
                    toolsByCategory[category] ?: allTools
                },
            )
        }

        /**
         * Create an UnfoldingTool from an instance annotated with [@MatryoshkaTools][MatryoshkaTools].
         *
         * The instance's class must be annotated with `@MatryoshkaTools` and contain
         * methods annotated with `@LlmTool`. If any `@LlmTool` methods have a `category`
         * specified, a category-based UnfoldingTool is created; otherwise, all tools
         * are exposed when the facade is invoked.
         *
         * Example - Simple facade:
         * ```java
         * @MatryoshkaTools(
         *     name = "database_operations",
         *     description = "Database operations. Invoke to see specific tools."
         * )
         * public class DatabaseTools {
         *     @LlmTool(description = "Execute a SQL query")
         *     public QueryResult query(String sql) { ... }
         *
         *     @LlmTool(description = "Insert a record")
         *     public InsertResult insert(String table, String data) { ... }
         * }
         *
         * UnfoldingTool tool = UnfoldingTool.fromInstance(new DatabaseTools());
         * ```
         *
         * Example - Category-based:
         * ```java
         * @MatryoshkaTools(
         *     name = "file_operations",
         *     description = "File operations. Pass category to select tools."
         * )
         * public class FileTools {
         *     @LlmTool(description = "Read file", category = "read")
         *     public String readFile(String path) { ... }
         *
         *     @LlmTool(description = "Write file", category = "write")
         *     public void writeFile(String path, String content) { ... }
         * }
         *
         * UnfoldingTool tool = UnfoldingTool.fromInstance(new FileTools());
         * // Automatically creates category-based selection with "read" and "write" categories
         * ```
         *
         * @param instance The object instance annotated with `@MatryoshkaTools`
         * @param objectMapper ObjectMapper for JSON parsing (optional)
         * @return An UnfoldingTool wrapping the annotated methods
         * @throws IllegalArgumentException if the class is not annotated with `@MatryoshkaTools`
         *         or has no `@LlmTool` methods
         */
        @JvmOverloads
        open fun fromInstance(
            instance: Any,
            objectMapper: ObjectMapper = jacksonObjectMapper(),
        ): UnfoldingTool =
            if (KotlinDetector.isKotlinReflectPresent())
                fromInstanceKotlin(instance, objectMapper)
            else
                fromInstanceJava(instance, objectMapper)

        /**
         * Safely create an UnfoldingTool from an instance.
         * Returns null if the class is not annotated with `@MatryoshkaTools`
         * or has no `@LlmTool` methods.
         *
         * @param instance The object instance to check
         * @param objectMapper ObjectMapper for JSON parsing (optional)
         * @return An UnfoldingTool if the instance is properly annotated, null otherwise
         */
        @JvmOverloads
        open fun safelyFromInstance(
            instance: Any,
            objectMapper: ObjectMapper = jacksonObjectMapper(),
        ): UnfoldingTool? {
            return try {
                fromInstance(instance, objectMapper)
            } catch (e: IllegalArgumentException) {
                logger.debug(
                    "Instance {} is not a valid UnfoldingTool source: {}",
                    instance::class.simpleName,
                    e.message
                )
                null
            } catch (e: Throwable) {
                logger.debug(
                    "Failed to create UnfoldingTool from {}: {}",
                    instance::class.simpleName,
                    e.message
                )
                null
            }
        }

        private fun fromInstanceKotlin(
            instance: Any,
            objectMapper: ObjectMapper = jacksonObjectMapper(),
        ): UnfoldingTool {
            val klass = instance::class
            // Check for @UnfoldingTools first (preferred), then fall back to @MatryoshkaTools (deprecated)
            val unfoldingAnnotation = klass.findAnnotation<UnfoldingTools>()
            val matryoshkaAnnotation = klass.findAnnotation<MatryoshkaTools>()

            // Extract annotation values - prefer UnfoldingTools if present
            val (name, description, removeOnInvoke, categoryParameter, childToolUsageNotes) = when {
                unfoldingAnnotation != null -> AnnotationValues(
                    name = unfoldingAnnotation.name,
                    description = unfoldingAnnotation.description,
                    removeOnInvoke = unfoldingAnnotation.removeOnInvoke,
                    categoryParameter = unfoldingAnnotation.categoryParameter,
                    childToolUsageNotes = unfoldingAnnotation.childToolUsageNotes,
                )
                matryoshkaAnnotation != null -> AnnotationValues(
                    name = matryoshkaAnnotation.name,
                    description = matryoshkaAnnotation.description,
                    removeOnInvoke = matryoshkaAnnotation.removeOnInvoke,
                    categoryParameter = matryoshkaAnnotation.categoryParameter,
                    childToolUsageNotes = matryoshkaAnnotation.childToolUsageNotes,
                )
                else -> throw IllegalArgumentException(
                    "Class ${klass.simpleName} is not annotated with @MatryoshkaTools or @UnfoldingTools"
                )
            }

            // Find all @LlmTool methods and create Tool instances
            val toolMethods = klass.functions.filter { it.hasAnnotation<LlmTool>() }

            // Find nested inner classes with @UnfoldingTools or @MatryoshkaTools annotation
            val nestedUnfoldingTools = mutableListOf<UnfoldingTool>()
            // Get all nested classes
            for (nestedClass in klass.nestedClasses) {
                if (nestedClass.hasAnnotation<UnfoldingTools>() || nestedClass.hasAnnotation<MatryoshkaTools>()) {
                    try {
                        // Create an instance of the nested class
                        val nestedInstance = nestedClass.createInstance()
                        val nestedTool = fromInstance(nestedInstance, objectMapper)
                        nestedUnfoldingTools.add(nestedTool)
                        logger.debug(
                            "Found nested UnfoldingTool '{}' in class {}",
                            nestedTool.definition.name,
                            klass.simpleName
                        )
                    } catch (e: Exception) {
                        logger.warn(
                            "Failed to create nested UnfoldingTool from {}: {}",
                            nestedClass.simpleName,
                            e.message
                        )
                    }
                }
            }

            if (toolMethods.isEmpty() && nestedUnfoldingTools.isEmpty()) {
                throw IllegalArgumentException(
                    "Class ${klass.simpleName} has no methods annotated with @LlmTool " +
                            "and no inner classes annotated with @UnfoldingTools"
                )
            }

            // Group tools by category
            val toolsByCategory = mutableMapOf<String, MutableList<Tool>>()
            val uncategorizedTools = mutableListOf<Tool>()

            for (method in toolMethods) {
                val tool = Tool.fromMethod(instance, method, objectMapper)
                val llmToolAnnotation = method.findAnnotation<LlmTool>()!!
                val category = llmToolAnnotation.category

                if (category.isNotEmpty()) {
                    toolsByCategory.getOrPut(category) { mutableListOf() }.add(tool)
                } else {
                    uncategorizedTools.add(tool)
                }
            }

            // Add nested UnfoldingTools to uncategorized tools
            uncategorizedTools.addAll(nestedUnfoldingTools)

            // If we have categories, create a category-based UnfoldingTool
            return if (toolsByCategory.isNotEmpty()) {
                // Add uncategorized tools to all categories
                if (uncategorizedTools.isNotEmpty()) {
                    toolsByCategory.forEach { (_, tools) ->
                        tools.addAll(uncategorizedTools)
                    }
                    // Also add a special "all" category if there are uncategorized tools
                    val allTools = toolsByCategory.values.flatten().toSet() + uncategorizedTools
                    toolsByCategory["all"] = allTools.toMutableList()
                }

                logger.debug(
                    "Creating category-based UnfoldingTool '{}' with categories: {}",
                    name,
                    toolsByCategory.keys
                )

                byCategory(
                    name = name,
                    description = description,
                    toolsByCategory = toolsByCategory,
                    categoryParameter = categoryParameter,
                    removeOnInvoke = removeOnInvoke,
                    childToolUsageNotes = childToolUsageNotes.takeIf { it.isNotEmpty() },
                )
            } else {
                // No categories - create simple UnfoldingTool
                logger.debug(
                    "Creating simple UnfoldingTool '{}' with {} tools ({} nested)",
                    name,
                    uncategorizedTools.size,
                    nestedUnfoldingTools.size
                )

                of(
                    name = name,
                    description = description,
                    innerTools = uncategorizedTools,
                    removeOnInvoke = removeOnInvoke,
                    childToolUsageNotes = childToolUsageNotes.takeIf { it.isNotEmpty() },
                )
            }
        }

        private fun fromInstanceJava(
            instance: Any,
            objectMapper: ObjectMapper = jacksonObjectMapper(),
        ): UnfoldingTool {
            val clazz = instance::class.java
            // Check for @UnfoldingTools first (preferred), then fall back to @MatryoshkaTools (deprecated)
            val unfoldingAnnotation = clazz.getAnnotation(UnfoldingTools::class.java)
            val matryoshkaAnnotation = clazz.getAnnotation(MatryoshkaTools::class.java)

            // Extract annotation values - prefer UnfoldingTools if present
            val (name, description, removeOnInvoke, categoryParameter, childToolUsageNotes) = when {
                unfoldingAnnotation != null -> AnnotationValues(
                    name = unfoldingAnnotation.name,
                    description = unfoldingAnnotation.description,
                    removeOnInvoke = unfoldingAnnotation.removeOnInvoke,
                    categoryParameter = unfoldingAnnotation.categoryParameter,
                    childToolUsageNotes = unfoldingAnnotation.childToolUsageNotes,
                )
                matryoshkaAnnotation != null -> AnnotationValues(
                    name = matryoshkaAnnotation.name,
                    description = matryoshkaAnnotation.description,
                    removeOnInvoke = matryoshkaAnnotation.removeOnInvoke,
                    categoryParameter = matryoshkaAnnotation.categoryParameter,
                    childToolUsageNotes = matryoshkaAnnotation.childToolUsageNotes,
                )
                else -> throw IllegalArgumentException(
                    "Class ${clazz.simpleName} is not annotated with @MatryoshkaTools or @UnfoldingTools"
                )
            }

            // Find all @LlmTool methods and create Tool instances
            val toolMethods = clazz.methods.filter { it.isAnnotationPresent(LlmTool::class.java) }

            // Find nested inner classes with @UnfoldingTools or @MatryoshkaTools annotation
            val nestedUnfoldingTools = mutableListOf<UnfoldingTool>()
            // Get all nested classes
            for (nestedClass in clazz.declaredClasses) {
                if (nestedClass.isAnnotationPresent(UnfoldingTools::class.java) ||
                    nestedClass.isAnnotationPresent(MatryoshkaTools::class.java)
                ) {
                    try {
                        // Create an instance of the nested class
                        val nestedInstance = BeanUtils.instantiateClass(nestedClass)
                        val nestedTool = fromInstance(nestedInstance, objectMapper)
                        nestedUnfoldingTools.add(nestedTool)
                        logger.debug(
                            "Found nested UnfoldingTool '{}' in class {}",
                            nestedTool.definition.name,
                            clazz.simpleName
                        )
                    } catch (e: Exception) {
                        logger.warn(
                            "Failed to create nested UnfoldingTool from {}: {}",
                            nestedClass.simpleName,
                            e.message
                        )
                    }
                }
            }

            if (toolMethods.isEmpty() && nestedUnfoldingTools.isEmpty()) {
                throw IllegalArgumentException(
                    "Class ${clazz.simpleName} has no methods annotated with @LlmTool " +
                            "and no inner classes annotated with @UnfoldingTools"
                )
            }

            // Group tools by category
            val toolsByCategory = mutableMapOf<String, MutableList<Tool>>()
            val uncategorizedTools = mutableListOf<Tool>()

            for (method in toolMethods) {
                val tool = Tool.fromMethod(instance, method, objectMapper)
                val llmToolAnnotation = method.getAnnotation(LlmTool::class.java)!!
                val category = llmToolAnnotation.category

                if (category.isNotEmpty()) {
                    toolsByCategory.getOrPut(category) { mutableListOf() }.add(tool)
                } else {
                    uncategorizedTools.add(tool)
                }
            }

            // Add nested UnfoldingTools to uncategorized tools
            uncategorizedTools.addAll(nestedUnfoldingTools)

            // If we have categories, create a category-based UnfoldingTool
            return if (toolsByCategory.isNotEmpty()) {
                // Add uncategorized tools to all categories
                if (uncategorizedTools.isNotEmpty()) {
                    toolsByCategory.forEach { (_, tools) ->
                        tools.addAll(uncategorizedTools)
                    }
                    // Also add a special "all" category if there are uncategorized tools
                    val allTools = toolsByCategory.values.flatten().toSet() + uncategorizedTools
                    toolsByCategory["all"] = allTools.toMutableList()
                }

                logger.debug(
                    "Creating category-based UnfoldingTool '{}' with categories: {}",
                    name,
                    toolsByCategory.keys
                )

                byCategory(
                    name = name,
                    description = description,
                    toolsByCategory = toolsByCategory,
                    categoryParameter = categoryParameter,
                    removeOnInvoke = removeOnInvoke,
                    childToolUsageNotes = childToolUsageNotes.takeIf { it.isNotEmpty() },
                )
            } else {
                // No categories - create simple UnfoldingTool
                logger.debug(
                    "Creating simple UnfoldingTool '{}' with {} tools ({} nested)",
                    name,
                    uncategorizedTools.size,
                    nestedUnfoldingTools.size
                )

                of(
                    name = name,
                    description = description,
                    innerTools = uncategorizedTools,
                    removeOnInvoke = removeOnInvoke,
                    childToolUsageNotes = childToolUsageNotes.takeIf { it.isNotEmpty() },
                )
            }
        }

        protected companion object {
            @JvmStatic
            protected val logger = LoggerFactory.getLogger(UnfoldingTool::class.java)

            @JvmStatic
            protected fun extractCategory(input: String, paramName: String): String? {
                if (input.isBlank()) return null
                return try {
                    @Suppress("UNCHECKED_CAST")
                    val map = ObjectMapper()
                        .readValue(input, Map::class.java) as Map<String, Any?>
                    map[paramName] as? String
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    companion object : Factory()
}

/**
 * Simple implementation that exposes all inner tools.
 * Implements MatryoshkaTool for backward compatibility.
 */
internal class SimpleUnfoldingTool(
    override val definition: Tool.Definition,
    override val innerTools: List<Tool>,
    override val removeOnInvoke: Boolean,
    override val childToolUsageNotes: String? = null,
) : MatryoshkaTool {

    override fun call(input: String): Tool.Result {
        val toolNames = innerTools.map { it.definition.name }
        return Tool.Result.text(
            "Enabled ${innerTools.size} tools: ${toolNames.joinToString(", ")}"
        )
    }
}

/**
 * Implementation with custom tool selection logic.
 * Implements MatryoshkaTool for backward compatibility.
 */
internal class SelectableUnfoldingTool(
    override val definition: Tool.Definition,
    override val innerTools: List<Tool>,
    override val removeOnInvoke: Boolean,
    override val childToolUsageNotes: String? = null,
    private val selector: (String) -> List<Tool>,
) : MatryoshkaTool {

    override fun selectTools(input: String): List<Tool> = selector(input)

    override fun call(input: String): Tool.Result {
        val selected = selectTools(input)
        val toolNames = selected.map { it.definition.name }
        return Tool.Result.text(
            "Enabled ${selected.size} tools: ${toolNames.joinToString(", ")}"
        )
    }
}

/**
 * Internal data class to hold extracted annotation values.
 * Supports both @UnfoldingTools and @MatryoshkaTools annotations.
 */
private data class AnnotationValues(
    val name: String,
    val description: String,
    val removeOnInvoke: Boolean,
    val categoryParameter: String,
    val childToolUsageNotes: String,
)
