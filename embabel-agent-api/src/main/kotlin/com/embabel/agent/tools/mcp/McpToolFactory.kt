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

import com.embabel.agent.api.tool.MatryoshkaTool
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.spi.support.springai.toEmbabelTool
import com.embabel.common.util.loggerFor
import io.modelcontextprotocol.client.McpSyncClient
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider
import org.springframework.ai.tool.ToolCallback

/**
 * Factory for creating Tools and MatryoshkaTools backed by MCP.
 *
 * Provides methods to:
 * - Get a single MCP tool by name ([toolByName], [requiredToolByName])
 * - Create MatryoshkaTools that act as facades for groups of MCP tools
 *
 * Example usage:
 * ```kotlin
 * val factory = McpToolFactory(mcpSyncClients)
 *
 * // Single tool by name (returns null if not found)
 * val searchTool = factory.toolByName("brave_search")
 *
 * // Single tool by name (throws if not found)
 * val requiredTool = factory.requiredToolByName("brave_search")
 *
 * // MatryoshkaTool with exact tool name whitelist
 * val githubTool = factory.matryoshkaByName(
 *     name = "github_operations",
 *     description = "GitHub operations. Invoke to access GitHub tools.",
 *     toolNames = setOf("create_issue", "list_issues", "get_pull_request")
 * )
 *
 * // MatryoshkaTool with regex pattern matching
 * val dbTool = factory.matryoshkaMatching(
 *     name = "database_operations",
 *     description = "Database operations. Invoke to access database tools.",
 *     patterns = listOf("^db_".toRegex(), "query.*".toRegex())
 * )
 *
 * // MatryoshkaTool with custom filter predicate
 * val webTool = factory.matryoshka(
 *     name = "web_operations",
 *     description = "Web operations. Invoke to access web tools.",
 *     filter = { it.toolDefinition.name().startsWith("web_") }
 * )
 * ```
 */
class McpToolFactory(
    private val clients: List<McpSyncClient>,
) {

    private val logger = loggerFor<McpToolFactory>()

    /**
     * Create a MatryoshkaTool from MCP clients with a filter predicate.
     *
     * @param name Name of the MatryoshkaTool facade
     * @param description Description explaining when to use this tool category
     * @param filter Predicate that returns true to include a tool
     * @param removeOnInvoke Whether to remove the facade after invocation (default true)
     */
    @JvmOverloads
    fun matryoshka(
        name: String,
        description: String,
        filter: (ToolCallback) -> Boolean,
        removeOnInvoke: Boolean = true,
    ): MatryoshkaTool {
        val innerTools = loadTools(clients, filter)
        logger.debug(
            "Created McpMatryoshkaTool '{}' with {} inner tools: {}",
            name,
            innerTools.size,
            innerTools.map { it.definition.name }
        )
        return MatryoshkaTool.of(
            name = name,
            description = description,
            innerTools = innerTools,
            removeOnInvoke = removeOnInvoke,
        )
    }

    /**
     * Create a MatryoshkaTool from MCP clients filtering by tool name regex patterns.
     *
     * @param name Name of the MatryoshkaTool facade
     * @param description Description explaining when to use this tool category
     * @param patterns Regex patterns to match against tool names
     * @param removeOnInvoke Whether to remove the facade after invocation (default true)
     */
    @JvmOverloads
    fun matryoshkaMatching(
        name: String,
        description: String,
        patterns: List<Regex>,
        removeOnInvoke: Boolean = true,
    ): MatryoshkaTool = matryoshka(
        name = name,
        description = description,
        filter = { callback ->
            val toolName = callback.toolDefinition.name()
            patterns.any { pattern -> pattern.containsMatchIn(toolName) }
        },
        removeOnInvoke = removeOnInvoke,
    )

    /**
     * Create a MatryoshkaTool from MCP clients with an exact tool name whitelist.
     *
     * @param name Name of the MatryoshkaTool facade
     * @param description Description explaining when to use this tool category
     * @param toolNames Exact tool names to include
     * @param removeOnInvoke Whether to remove the facade after invocation (default true)
     */
    @JvmOverloads
    fun matryoshkaByName(
        name: String,
        description: String,
        toolNames: Set<String>,
        removeOnInvoke: Boolean = true,
    ): MatryoshkaTool = matryoshka(
        name = name,
        description = description,
        filter = { callback -> callback.toolDefinition.name() in toolNames },
        removeOnInvoke = removeOnInvoke,
    )

    /**
     * Get a single MCP tool by exact name.
     *
     * @param toolName The exact name of the MCP tool
     * @return The tool, or null if not found
     */
    fun toolByName(toolName: String): Tool? {
        val tools = loadTools(clients) { callback ->
            callback.toolDefinition.name() == toolName
        }
        return tools.firstOrNull().also {
            if (it == null) {
                logger.warn("MCP tool '{}' not found", toolName)
            }
        }
    }

    /**
     * Get a single MCP tool by exact name, throwing if not found.
     *
     * @param toolName The exact name of the MCP tool
     * @return The tool
     * @throws IllegalArgumentException if the tool is not found
     */
    fun requiredToolByName(toolName: String): Tool {
        return toolByName(toolName)
            ?: throw IllegalArgumentException(
                buildString {
                    append("MCP tool '$toolName' not found.")
                    val availableTools = loadTools(clients) { true }
                    if (availableTools.isEmpty()) {
                        append(" No MCP tools are available - check MCP client connections.")
                    } else {
                        append(" Available tools: ${availableTools.map { it.definition.name }.sorted().joinToString(", ")}")
                    }
                }
            )
    }

    private fun loadTools(
        clients: List<McpSyncClient>,
        filter: (ToolCallback) -> Boolean,
    ): List<Tool> {
        return try {
            val provider = SyncMcpToolCallbackProvider(clients)
            val filteredCallbacks = provider.toolCallbacks.filter(filter)
            val nativeTools = filteredCallbacks.map { it.toEmbabelTool() }
            logger.debug("Loaded {} MCP tools", nativeTools.size)
            nativeTools
        } catch (e: Exception) {
            logger.error("Failed to load MCP tools: {}", e.message)
            emptyList()
        }
    }
}
