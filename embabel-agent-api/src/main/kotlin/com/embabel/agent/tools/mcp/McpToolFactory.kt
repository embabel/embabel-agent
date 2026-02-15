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
package com.embabel.agent.tools.mcp

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.progressive.UnfoldingTool
import org.springframework.ai.tool.ToolCallback

/**
 * Factory for creating Tools and UnfoldingTools backed by MCP
 * in a consistent way, across MCP providers.
 *
 * Provides methods to:
 * - Get a single MCP tool by name ([toolByName], [requiredToolByName])
 * - Create UnfoldingTools that act as facades for groups of MCP tools
 *
 * Example usage:
 * ```kotlin
 * val factory: McpToolFactory = SpringAiMcpToolFactory(mcpSyncClients)
 *
 * // Single tool by name (returns null if not found)
 * val searchTool = factory.toolByName("brave_search")
 *
 * // Single tool by name (throws if not found)
 * val requiredTool = factory.requiredToolByName("brave_search")
 *
 * // UnfoldingTool with exact tool name whitelist
 * val githubTool = factory.unfoldingByName(
 *     name = "github_operations",
 *     description = "GitHub operations. Invoke to access GitHub tools.",
 *     toolNames = setOf("create_issue", "list_issues", "get_pull_request")
 * )
 *
 * // UnfoldingTool with regex pattern matching
 * val dbTool = factory.unfoldingMatching(
 *     name = "database_operations",
 *     description = "Database operations. Invoke to access database tools.",
 *     patterns = listOf("^db_".toRegex(), "query.*".toRegex())
 * )
 *
 * // UnfoldingTool with custom filter predicate
 * val webTool = factory.unfolding(
 *     name = "web_operations",
 *     description = "Web operations. Invoke to access web tools.",
 *     filter = { it.toolDefinition.name().startsWith("web_") }
 * )
 * ```
 * See [com.embabel.agent.spi.support.springai.SpringAiMcpToolFactory] for a Spring-based implementation that creates tools from MCP clients.
 */
interface McpToolFactory {

    /**
     * Create an UnfoldingTool from MCP clients with a filter predicate.
     *
     * @param name Name of the UnfoldingTool facade
     * @param description Description explaining when to use this tool category
     * @param filter Predicate that returns true to include a tool
     * @param removeOnInvoke Whether to remove the facade after invocation
     */
    fun unfolding(
        name: String,
        description: String,
        filter: (ToolCallback) -> Boolean,
        removeOnInvoke: Boolean,
    ): UnfoldingTool

    /**
     * Create an UnfoldingTool from MCP clients with a filter predicate, with removeOnInvoke=true.
     */
    fun unfolding(
        name: String,
        description: String,
        filter: (ToolCallback) -> Boolean,
    ): UnfoldingTool = unfolding(name, description, filter, true)

    /**
     * Create an UnfoldingTool from MCP clients filtering by tool name regex patterns.
     *
     * @param name Name of the UnfoldingTool facade
     * @param description Description explaining when to use this tool category
     * @param patterns Regex patterns to match against tool names
     * @param removeOnInvoke Whether to remove the facade after invocation
     */
    fun unfoldingMatching(
        name: String,
        description: String,
        patterns: List<Regex>,
        removeOnInvoke: Boolean,
    ): UnfoldingTool = unfolding(
        name = name,
        description = description,
        filter = { callback ->
            val toolName = callback.toolDefinition.name()
            patterns.any { pattern -> pattern.containsMatchIn(toolName) }
        },
        removeOnInvoke = removeOnInvoke,
    )

    /**
     * Create an UnfoldingTool from MCP clients filtering by tool name regex patterns, with removeOnInvoke=true.
     */
    fun unfoldingMatching(
        name: String,
        description: String,
        patterns: List<Regex>,
    ): UnfoldingTool = unfoldingMatching(name, description, patterns, true)

    /**
     * Create an UnfoldingTool from MCP clients with an exact tool name whitelist.
     *
     * @param name Name of the UnfoldingTool facade
     * @param description Description explaining when to use this tool category
     * @param toolNames Exact tool names to include
     * @param removeOnInvoke Whether to remove the facade after invocation
     */
    fun unfoldingByName(
        name: String,
        description: String,
        toolNames: Set<String>,
        removeOnInvoke: Boolean,
    ): UnfoldingTool = unfolding(
        name = name,
        description = description,
        filter = { callback -> callback.toolDefinition.name() in toolNames },
        removeOnInvoke = removeOnInvoke,
    )

    /**
     * Create an UnfoldingTool from MCP clients with an exact tool name whitelist, with removeOnInvoke=true.
     */
    fun unfoldingByName(
        name: String,
        description: String,
        toolNames: Set<String>,
    ): UnfoldingTool = unfoldingByName(name, description, toolNames, true)

    /**
     * Get a single MCP tool by exact name.
     *
     * @param toolName The exact name of the MCP tool
     * @return The tool, or null if not found
     */
    fun toolByName(toolName: String): Tool?

    /**
     * Get a single MCP tool by exact name, throwing if not found.
     *
     * @param toolName The exact name of the MCP tool
     * @return The tool
     * @throws IllegalArgumentException if the tool is not found
     */
    fun requiredToolByName(toolName: String): Tool =
        toolByName(toolName)
            ?: throw IllegalArgumentException("MCP tool '$toolName' not found")

    // region Deprecated methods

    @Deprecated(
        message = "Use unfolding() instead",
        replaceWith = ReplaceWith("unfolding(name, description, filter, removeOnInvoke)")
    )
    fun matryoshka(
        name: String,
        description: String,
        filter: (ToolCallback) -> Boolean,
        removeOnInvoke: Boolean,
    ): UnfoldingTool = unfolding(name, description, filter, removeOnInvoke)

    @Deprecated(
        message = "Use unfolding() instead",
        replaceWith = ReplaceWith("unfolding(name, description, filter)")
    )
    fun matryoshka(
        name: String,
        description: String,
        filter: (ToolCallback) -> Boolean,
    ): UnfoldingTool = unfolding(name, description, filter)

    @Deprecated(
        message = "Use unfoldingMatching() instead",
        replaceWith = ReplaceWith("unfoldingMatching(name, description, patterns, removeOnInvoke)")
    )
    fun matryoshkaMatching(
        name: String,
        description: String,
        patterns: List<Regex>,
        removeOnInvoke: Boolean,
    ): UnfoldingTool = unfoldingMatching(name, description, patterns, removeOnInvoke)

    @Deprecated(
        message = "Use unfoldingMatching() instead",
        replaceWith = ReplaceWith("unfoldingMatching(name, description, patterns)")
    )
    fun matryoshkaMatching(
        name: String,
        description: String,
        patterns: List<Regex>,
    ): UnfoldingTool = unfoldingMatching(name, description, patterns)

    @Deprecated(
        message = "Use unfoldingByName() instead",
        replaceWith = ReplaceWith("unfoldingByName(name, description, toolNames, removeOnInvoke)")
    )
    fun matryoshkaByName(
        name: String,
        description: String,
        toolNames: Set<String>,
        removeOnInvoke: Boolean,
    ): UnfoldingTool = unfoldingByName(name, description, toolNames, removeOnInvoke)

    @Deprecated(
        message = "Use unfoldingByName() instead",
        replaceWith = ReplaceWith("unfoldingByName(name, description, toolNames)")
    )
    fun matryoshkaByName(
        name: String,
        description: String,
        toolNames: Set<String>,
    ): UnfoldingTool = unfoldingByName(name, description, toolNames)

    // endregion
}
