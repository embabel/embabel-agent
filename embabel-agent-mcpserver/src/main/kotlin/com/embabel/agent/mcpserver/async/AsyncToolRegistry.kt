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
package com.embabel.agent.mcpserver.async

import com.embabel.agent.mcpserver.ToolRegistry
import com.embabel.agent.mcpserver.support.toolNames
import io.modelcontextprotocol.server.McpAsyncServer

/**
 * Registry for managing tools in an asynchronous MCP server.
 *
 * Provides access to tool names, counts, and existence checks
 * by delegating to the underlying `McpAsyncServer` instance.
 *
 * @param server the asynchronous MCP server instance
 */
class AsyncToolRegistry(private val server: McpAsyncServer) : ToolRegistry {

    /**
     * Returns the list of tool names available in the async server.
     *
     * Delegates to the server's `toolNames()` method.
     *
     * @return a list of tool names
     */
    override fun getToolNames(): List<String> {
        return server.toolNames()
    }

    /**
     * Returns the total number of tools registered in the async server.
     *
     * @return the count of tools
     */
    override fun getToolCount(): Int = getToolNames().size

    /**
     * Checks if a tool with the specified name exists in the async server.
     *
     * @param name the name of the tool to check
     * @return `true` if the tool exists, `false` otherwise
     */
    override fun hasToolNamed(name: String): Boolean = getToolNames().contains(name)

}
