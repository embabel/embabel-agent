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
package com.embabel.agent.mcpserver.support

import io.modelcontextprotocol.server.McpAsyncServer
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.server.McpSyncServer
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(McpAsyncServer::class.java)

/**
 * Sneakily get the names of the tools registered with the given MCP async server.
 *
 * This uses reflection to access the private `tools` field of the MCP async server.
 * If this fails, an empty list is returned and a warning is logged.
 */
fun McpAsyncServer.toolNames(): List<String> {
    try {
        val toolsField = this::class.java.getDeclaredField("tools")
        toolsField.setAccessible(true)
        val tools = toolsField.get(this) as List<McpServerFeatures.AsyncToolSpecification>
        return tools.map { it.tool.name() }
    } catch (t: Throwable) {
        logger.warn("Failed to sneakily get tools from MCP async server: {}", t.message, t)
    }
    return emptyList()
}

/**
 * Retrieves the names of tools registered with this MCP sync server.
 *
 * Delegates to the underlying async server's `toolNames()` extension.
 *
 * @return a list of tool names registered with the async server
 */
fun McpSyncServer.toolNames(): List<String> = this.asyncServer.toolNames()
