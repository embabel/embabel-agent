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
package com.embabel.agent.spi.support.springai

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.progressive.UnfoldingTool
import com.embabel.agent.tools.mcp.McpToolFactory
import com.embabel.common.util.loggerFor
import io.modelcontextprotocol.client.McpSyncClient
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider
import org.springframework.ai.tool.ToolCallback

class SpringAiMcpToolFactory(
    private val clients: List<McpSyncClient>,
) : McpToolFactory {

    private val logger = loggerFor<SpringAiMcpToolFactory>()

    override fun unfolding(
        name: String,
        description: String,
        filter: (ToolCallback) -> Boolean,
        removeOnInvoke: Boolean,
    ): UnfoldingTool {
        val innerTools = loadTools(clients, filter)
        logger.debug(
            "Created McpUnfoldingTool '{}' with {} inner tools: {}",
            name,
            innerTools.size,
            innerTools.map { it.definition.name }
        )
        return UnfoldingTool.of(
            name = name,
            description = description,
            innerTools = innerTools,
            removeOnInvoke = removeOnInvoke,
        )
    }

    override fun toolByName(toolName: String): Tool? {
        val tools = loadTools(clients) { callback ->
            callback.toolDefinition.name() == toolName
        }
        return tools.firstOrNull().also {
            if (it == null) {
                logger.warn("MCP tool '{}' not found", toolName)
            }
        }
    }

    override fun requiredToolByName(toolName: String): Tool {
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
