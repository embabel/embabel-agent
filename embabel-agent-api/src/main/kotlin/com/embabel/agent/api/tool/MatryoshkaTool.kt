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
import com.embabel.agent.api.tool.progressive.UnfoldingTool
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
 * A tool that contains other tools, enabling progressive tool disclosure.
 *
 * Named after Russian nesting dolls, a MatryoshkaTool presents a high-level
 * description to the LLM. When invoked, its inner tools become available and
 * (optionally) the MatryoshkaTool itself is removed.
 *
 * This pattern is useful for:
 * - Reducing tool set complexity for the LLM
 * - Grouping related tools under a category facade
 * - Progressive disclosure based on LLM intent
 *
 * ## Context Preservation
 *
 * When a MatryoshkaTool is expanded (via [MatryoshkaToolInjectionStrategy][com.embabel.agent.spi.loop.MatryoshkaToolInjectionStrategy]),
 * a context tool is created that preserves the parent's description and optional usage notes.
 * This solves the problem where child tools would lose context about the parent's purpose.
 *
 * For example, a "spotify_search" tool with description "Search Spotify for music data"
 * that contains vector_search, text_search, and regex_search tools. Without context preservation,
 * the LLM would only see three generic search tools. With the context tool, it also sees
 * "spotify_search_context" which explains these are Spotify music search tools.
 *
 * The [childToolUsageNotes] field provides additional guidance on how to use the child tools,
 * included only once in the context tool rather than duplicated in each child tool's description.
 *
 * Example:
 * ```kotlin
 * val spotifyTool = MatryoshkaTool.of(
 *     name = "spotify_search",
 *     description = "Search Spotify for music data including artists, albums, and tracks.",
 *     innerTools = listOf(vectorSearchTool, textSearchTool, regexSearchTool),
 *     childToolUsageNotes = "Try vector search first for semantic queries like 'upbeat jazz'. " +
 *         "Use text search for exact artist or album names. " +
 *         "Use regex search for pattern matching on metadata."
 * )
 * ```
 *
 * @see com.embabel.agent.spi.loop.MatryoshkaToolInjectionStrategy
 * @see UnfoldingTool
 */
interface MatryoshkaTool : UnfoldingTool {

    companion object {

        /**
         * Create a MatryoshkaTool that exposes all inner tools when invoked.
         *
         * @param name Unique name for the tool
         * @param description Description explaining when to use this tool category
         * @param innerTools The tools to expose when invoked
         * @param removeOnInvoke Whether to remove this tool after invocation (default true)
         * @param childToolUsageNotes Optional notes to guide LLM on using the child tools
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            name: String,
            description: String,
            innerTools: List<Tool>,
            removeOnInvoke: Boolean = true,
            childToolUsageNotes: String? = null,
        ): MatryoshkaTool = SimpleMatryoshkaTool(
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
         * Create a MatryoshkaTool with a custom tool selector.
         *
         * The selector receives the JSON input string and returns the tools to expose.
         * This enables category-based tool disclosure.
         *
         * Example:
         * ```kotlin
         * val fileTool = MatryoshkaTool.selectable(
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
        @JvmStatic
        @JvmOverloads
        fun selectable(
            name: String,
            description: String,
            innerTools: List<Tool>,
            inputSchema: Tool.InputSchema,
            removeOnInvoke: Boolean = true,
            childToolUsageNotes: String? = null,
            selector: (String) -> List<Tool>,
        ): MatryoshkaTool = SelectableMatryoshkaTool(
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
         * Create a MatryoshkaTool with category-based selection.
         *
         * @param name Unique name for the tool
         * @param description Description explaining when to use this tool category
         * @param toolsByCategory Map of category names to their tools
         * @param categoryParameter Name of the category parameter (default "category")
         * @param removeOnInvoke Whether to remove this tool after invocation
         * @param childToolUsageNotes Optional notes to guide LLM on using the child tools
         */
        @JvmStatic
        @JvmOverloads
        fun byCategory(
            name: String,
            description: String,
            toolsByCategory: Map<String, List<Tool>>,
            categoryParameter: String = "category",
            removeOnInvoke: Boolean = true,
            childToolUsageNotes: String? = null,
        ): MatryoshkaTool {
            val allTools = toolsByCategory.values.flatten()
            val categoryNames = toolsByCategory.keys.toList()

            return SelectableMatryoshkaTool(
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

        private fun extractCategory(input: String, paramName: String): String? {
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

        private val logger = LoggerFactory.getLogger(MatryoshkaTool::class.java)

        /**
         * Create a MatryoshkaTool from an instance annotated with [@MatryoshkaTools][MatryoshkaTools].
         *
         * The instance's class must be annotated with `@MatryoshkaTools` and contain
         * methods annotated with `@LlmTool`. If any `@LlmTool` methods have a `category`
         * specified, a category-based MatryoshkaTool is created; otherwise, all tools
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
         * MatryoshkaTool tool = MatryoshkaTool.fromInstance(new DatabaseTools());
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
         * MatryoshkaTool tool = MatryoshkaTool.fromInstance(new FileTools());
         * // Automatically creates category-based selection with "read" and "write" categories
         * ```
         *
         * @param instance The object instance annotated with `@MatryoshkaTools`
         * @param objectMapper ObjectMapper for JSON parsing (optional)
         * @return A MatryoshkaTool wrapping the annotated methods
         * @throws IllegalArgumentException if the class is not annotated with `@MatryoshkaTools`
         *         or has no `@LlmTool` methods
         */
        @JvmStatic
        @JvmOverloads
        fun fromInstance(
            instance: Any,
            objectMapper: ObjectMapper = jacksonObjectMapper(),
        ): MatryoshkaTool =
            if (KotlinDetector.isKotlinReflectPresent())
                fromInstanceKotlin(instance, objectMapper)
            else
                fromInstanceJava(instance, objectMapper)

        private fun fromInstanceKotlin(
            instance: Any,
            objectMapper: ObjectMapper = jacksonObjectMapper(),
        ): MatryoshkaTool {
            val klass = instance::class
            val annotation = klass.findAnnotation<MatryoshkaTools>()
                ?: throw IllegalArgumentException(
                    "Class ${klass.simpleName} is not annotated with @MatryoshkaTools"
                )

            // Find all @LlmTool methods and create Tool instances
            val toolMethods = klass.functions.filter { it.hasAnnotation<LlmTool>() }

            // Find nested inner classes with @MatryoshkaTools annotation
            val nestedMatryoshkaTools = mutableListOf<MatryoshkaTool>()
            // Get all nested classes
            for (nestedClass in klass.nestedClasses) {
                if (nestedClass.hasAnnotation<MatryoshkaTools>()) {
                    try {
                        // Create an instance of the nested class
                        val nestedInstance = nestedClass.createInstance()
                        val nestedMatryoshka = fromInstance(nestedInstance, objectMapper)
                        nestedMatryoshkaTools.add(nestedMatryoshka)
                        logger.debug(
                            "Found nested MatryoshkaTool '{}' in class {}",
                            nestedMatryoshka.definition.name,
                            klass.simpleName
                        )
                    } catch (e: Exception) {
                        logger.warn(
                            "Failed to create nested MatryoshkaTool from {}: {}",
                            nestedClass.simpleName,
                            e.message
                        )
                    }
                }
            }

            if (toolMethods.isEmpty() && nestedMatryoshkaTools.isEmpty()) {
                throw IllegalArgumentException(
                    "Class ${klass.simpleName} has no methods annotated with @LlmTool " +
                            "and no inner classes annotated with @MatryoshkaTools"
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

            // Add nested MatryoshkaTools to uncategorized tools
            uncategorizedTools.addAll(nestedMatryoshkaTools)

            // If we have categories, create a category-based MatryoshkaTool
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
                    "Creating category-based MatryoshkaTool '{}' with categories: {}",
                    annotation.name,
                    toolsByCategory.keys
                )

                byCategory(
                    name = annotation.name,
                    description = annotation.description,
                    toolsByCategory = toolsByCategory,
                    categoryParameter = annotation.categoryParameter,
                    removeOnInvoke = annotation.removeOnInvoke,
                    childToolUsageNotes = annotation.childToolUsageNotes.takeIf { it.isNotEmpty() },
                )
            } else {
                // No categories - create simple MatryoshkaTool
                logger.debug(
                    "Creating simple MatryoshkaTool '{}' with {} tools ({} nested)",
                    annotation.name,
                    uncategorizedTools.size,
                    nestedMatryoshkaTools.size
                )

                of(
                    name = annotation.name,
                    description = annotation.description,
                    innerTools = uncategorizedTools,
                    removeOnInvoke = annotation.removeOnInvoke,
                    childToolUsageNotes = annotation.childToolUsageNotes.takeIf { it.isNotEmpty() },
                )
            }
        }

        private fun fromInstanceJava(
            instance: Any,
            objectMapper: ObjectMapper = jacksonObjectMapper(),
        ): MatryoshkaTool {
            val clazz = instance::class.java
            val annotation = clazz.getAnnotation(MatryoshkaTools::class.java)
                ?: throw IllegalArgumentException(
                    "Class ${clazz.simpleName} is not annotated with @MatryoshkaTools"
                )

            // Find all @LlmTool methods and create Tool instances
            val toolMethods = clazz.methods.filter { it.isAnnotationPresent(LlmTool::class.java) }

            // Find nested inner classes with @MatryoshkaTools annotation
            val nestedMatryoshkaTools = mutableListOf<MatryoshkaTool>()
            // Get all nested classes
            for (nestedClass in clazz.declaredClasses) {
                if (nestedClass.isAnnotationPresent(MatryoshkaTools::class.java)) {
                    try {
                        // Create an instance of the nested class
                        val nestedInstance = BeanUtils.instantiateClass(nestedClass)
                        val nestedMatryoshka = fromInstance(nestedInstance, objectMapper)
                        nestedMatryoshkaTools.add(nestedMatryoshka)
                        logger.debug(
                            "Found nested MatryoshkaTool '{}' in class {}",
                            nestedMatryoshka.definition.name,
                            clazz.simpleName
                        )
                    } catch (e: Exception) {
                        logger.warn(
                            "Failed to create nested MatryoshkaTool from {}: {}",
                            nestedClass.simpleName,
                            e.message
                        )
                    }
                }
            }

            if (toolMethods.isEmpty() && nestedMatryoshkaTools.isEmpty()) {
                throw IllegalArgumentException(
                    "Class ${clazz.simpleName} has no methods annotated with @LlmTool " +
                            "and no inner classes annotated with @MatryoshkaTools"
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

            // Add nested MatryoshkaTools to uncategorized tools
            uncategorizedTools.addAll(nestedMatryoshkaTools)

            // If we have categories, create a category-based MatryoshkaTool
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
                    "Creating category-based MatryoshkaTool '{}' with categories: {}",
                    annotation.name,
                    toolsByCategory.keys
                )

                byCategory(
                    name = annotation.name,
                    description = annotation.description,
                    toolsByCategory = toolsByCategory,
                    categoryParameter = annotation.categoryParameter,
                    removeOnInvoke = annotation.removeOnInvoke,
                    childToolUsageNotes = annotation.childToolUsageNotes.takeIf { it.isNotEmpty() },
                )
            } else {
                // No categories - create simple MatryoshkaTool
                logger.debug(
                    "Creating simple MatryoshkaTool '{}' with {} tools ({} nested)",
                    annotation.name,
                    uncategorizedTools.size,
                    nestedMatryoshkaTools.size
                )

                of(
                    name = annotation.name,
                    description = annotation.description,
                    innerTools = uncategorizedTools,
                    removeOnInvoke = annotation.removeOnInvoke,
                    childToolUsageNotes = annotation.childToolUsageNotes.takeIf { it.isNotEmpty() },
                )
            }
        }

        /**
         * Safely create a MatryoshkaTool from an instance.
         * Returns null if the class is not annotated with `@MatryoshkaTools`
         * or has no `@LlmTool` methods.
         *
         * @param instance The object instance to check
         * @param objectMapper ObjectMapper for JSON parsing (optional)
         * @return A MatryoshkaTool if the instance is properly annotated, null otherwise
         */
        @JvmStatic
        @JvmOverloads
        fun safelyFromInstance(
            instance: Any,
            objectMapper: ObjectMapper = jacksonObjectMapper(),
        ): MatryoshkaTool? {
            return try {
                fromInstance(instance, objectMapper)
            } catch (e: IllegalArgumentException) {
                logger.debug(
                    "Instance {} is not a valid MatryoshkaTool source: {}",
                    instance::class.simpleName,
                    e.message
                )
                null
            } catch (e: Throwable) {
                logger.debug(
                    "Failed to create MatryoshkaTool from {}: {}",
                    instance::class.simpleName,
                    e.message
                )
                null
            }
        }
    }
}

/**
 * Simple implementation that exposes all inner tools.
 */
private class SimpleMatryoshkaTool(
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
 */
private class SelectableMatryoshkaTool(
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
