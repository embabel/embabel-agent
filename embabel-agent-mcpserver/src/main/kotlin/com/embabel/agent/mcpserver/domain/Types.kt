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
package com.embabel.agent.mcpserver.domain

import io.modelcontextprotocol.spec.McpSchema
import java.time.Instant

/**
 * Represents the execution mode for an MCP server.
 *
 * - `SYNC`: Synchronous execution mode.
 * - `ASYNC`: Asynchronous execution mode.
 */
enum class McpExecutionMode {
    SYNC, ASYNC
}

/**
 * Value object containing information about the MCP server.
 *
 * @property name the server name
 * @property version the server version
 * @property mode the execution mode (`SYNC` or `ASYNC`)
 * @property javaVersion the Java runtime version
 * @property startTime the server start time (default: current instant)
 */
data class ServerInfo(
    val name: String,
    val version: String,
    val mode: McpExecutionMode,
    val javaVersion: String,
    val startTime: Instant = Instant.now()
) {
    /**
     * Returns a list of banner lines summarizing server information.
     *
     * @return a list of formatted strings for display
     */
    fun toBannerLines(): List<String> {
        val separator = "~".repeat(50)
        return listOf(
            separator,
            "Embabel Agent MCP $mode Server",
            "Version: $version",
            "Java: $javaVersion",
            "Started: $startTime",
            separator
        )
    }
}

/**
 * Interface for tool specifications, abstracting over execution mode.
 *
 * @param T the type of handler for the tool
 * @property tool the MCP tool definition
 * @property handler the handler implementation for the tool
 */
interface ToolSpecification<T> {
    val tool: McpSchema.Tool
    val handler: T

    /**
     * Returns the name of the tool.
     *
     * @return the tool name
     */
    fun toolName(): String = tool.name()

    /**
     * Returns the description of the tool, or "No description" if absent.
     *
     * @return the tool description
     */
    fun toolDescription(): String = tool.description() ?: "No description"
}

/**
 * Enumerates the capabilities supported by an MCP server.
 *
 * - `TOOLS`: Tool management
 * - `RESOURCES`: Resource management
 * - `PROMPTS`: Prompt management
 * - `LOGGING`: Logging support
 * - `COMPLETIONS`: Completion operations
 */
enum class McpCapability {
    TOOLS, RESOURCES, PROMPTS, LOGGING, COMPLETIONS
}

/**
 * Value object representing the result of a server health check.
 *
 * @property isHealthy whether the server is healthy
 * @property mode the current execution mode
 * @property toolCount the number of registered tools
 * @property issues a list of health issues, if any
 * @property timestamp the time of the health check (default: current instant)
 */
data class ServerHealthStatus(
    val isHealthy: Boolean,
    val mode: McpExecutionMode,
    val toolCount: Int,
    val issues: List<String>,
    val timestamp: Instant = Instant.now()
)
