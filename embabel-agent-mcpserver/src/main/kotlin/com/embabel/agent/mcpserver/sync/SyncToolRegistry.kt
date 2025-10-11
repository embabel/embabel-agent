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
package com.embabel.agent.mcpserver.sync

import com.embabel.agent.mcpserver.ToolRegistry
import com.embabel.agent.mcpserver.support.toolNames
import io.modelcontextprotocol.server.McpSyncServer

/**
 * Registry for tools managed by the MCP sync server.
 *
 * Provides access to tool names and counts for synchronous execution mode.
 *
 * @property server the underlying MCP sync server instance
 */
class SyncToolRegistry(private val server: McpSyncServer) : ToolRegistry {

    /**
     * Returns a list of tool names registered in the sync server.
     *
     * @return a list of tool names
     */
    override fun getToolNames(): List<String> {
        return server.toolNames()
    }

    /**
     * Returns the number of tools registered in the sync server.
     *
     * @return the count of tools
     */
    override fun getToolCount(): Int = getToolNames().size

    /**
     * Checks if a tool with the given name exists in the sync server.
     *
     * @param name the name of the tool to check
     * @return `true` if the tool exists, otherwise `false`
     */
    override fun hasToolNamed(name: String): Boolean = getToolNames().contains(name)

}
