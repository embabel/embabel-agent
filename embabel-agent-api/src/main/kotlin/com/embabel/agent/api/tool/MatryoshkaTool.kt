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
 * Example:
 * ```kotlin
 * val databaseTool = MatryoshkaTool.of(
 *     name = "database_operations",
 *     description = "Use this to work with the database. Invoke to see specific operations.",
 *     innerTools = listOf(queryTableTool, insertRecordTool, updateRecordTool)
 * )
 * ```
 *
 * @see com.embabel.agent.spi.loop.MatryoshkaToolInjectionStrategy
 */
interface MatryoshkaTool : Tool {

    /**
     * The inner tools that will be exposed when this tool is invoked.
     */
    val innerTools: List<Tool>

    /**
     * Whether to remove this MatryoshkaTool after invocation.
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

    companion object {

        /**
         * Create a MatryoshkaTool that exposes all inner tools when invoked.
         *
         * @param name Unique name for the tool
         * @param description Description explaining when to use this tool category
         * @param innerTools The tools to expose when invoked
         * @param removeOnInvoke Whether to remove this tool after invocation (default true)
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            name: String,
            description: String,
            innerTools: List<Tool>,
            removeOnInvoke: Boolean = true,
        ): MatryoshkaTool = SimpleMatryoshkaTool(
            definition = Tool.Definition(
                name = name,
                description = description,
                inputSchema = Tool.InputSchema.empty(),
            ),
            innerTools = innerTools,
            removeOnInvoke = removeOnInvoke,
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
            selector: (String) -> List<Tool>,
        ): MatryoshkaTool = SelectableMatryoshkaTool(
            definition = Tool.Definition(
                name = name,
                description = description,
                inputSchema = inputSchema,
            ),
            innerTools = innerTools,
            removeOnInvoke = removeOnInvoke,
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
         */
        @JvmStatic
        @JvmOverloads
        fun byCategory(
            name: String,
            description: String,
            toolsByCategory: Map<String, List<Tool>>,
            categoryParameter: String = "category",
            removeOnInvoke: Boolean = true,
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
                val map = com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(input, Map::class.java) as Map<String, Any?>
                map[paramName] as? String
            } catch (e: Exception) {
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
