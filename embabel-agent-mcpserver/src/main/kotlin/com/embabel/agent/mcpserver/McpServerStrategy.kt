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
package com.embabel.agent.mcpserver

import com.embabel.agent.mcpserver.domain.McpExecutionMode
import reactor.core.publisher.Mono

/**
 * Strategy interface for managing MCP server functionality.
 *
 * Defines operations for adding and removing tools, resources, and prompts,
 * as well as accessing the tool registry. Implementations provide logic
 * for different execution modes (e.g., sync or async).
 *
 * @property executionMode the execution mode of the MCP server
 */
interface McpServerStrategy {
    /**
     * The execution mode for this strategy (e.g., SYNC or ASYNC).
     */
    val executionMode: McpExecutionMode

    /**
     * Adds a tool specification to the MCP server.
     *
     * @param toolSpec the tool specification object
     * @return a [Mono] signaling completion
     */
    fun addTool(toolSpec: Any): Mono<Void>

    /**
     * Removes a tool from the MCP server by name.
     *
     * @param toolName the name of the tool to remove
     * @return a [Mono] signaling completion
     */
    fun removeTool(toolName: String): Mono<Void>

    /**
     * Adds a resource specification to the MCP server.
     *
     * @param resourceSpec the resource specification object
     * @return a [Mono] signaling completion
     */
    fun addResource(resourceSpec: Any): Mono<Void>

    /**
     * Adds a prompt specification to the MCP server.
     *
     * @param promptSpec the prompt specification object
     * @return a [Mono] signaling completion
     */
    fun addPrompt(promptSpec: Any): Mono<Void>

    /**
     * Returns the tool registry for the MCP server.
     *
     * @return a [ToolRegistry] instance
     */
    fun getToolRegistry(): ToolRegistry
}
