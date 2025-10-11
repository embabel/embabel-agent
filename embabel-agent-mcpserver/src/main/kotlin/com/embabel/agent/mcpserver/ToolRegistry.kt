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

/**
 * Registry interface for managing tools in the MCP server.
 *
 * Provides methods to access tool names, count, and existence checks.
 * Implementations should supply logic for synchronous or asynchronous modes.
 */
interface ToolRegistry {
    /**
     * Returns a list of tool names registered in the server.
     *
     * @return a list of tool names
     */
    fun getToolNames(): List<String>

    /**
     * Returns the number of tools registered in the server.
     *
     * @return the count of tools
     */
    fun getToolCount(): Int

    /**
     * Checks if a tool with the given name exists in the server.
     *
     * @param name the name of the tool to check
     * @return `true` if the tool exists, otherwise `false`
     */
    fun hasToolNamed(name: String): Boolean
}
