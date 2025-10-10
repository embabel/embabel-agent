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
 * Tool registry implementation for async servers.
 */
class AsyncToolRegistry(private val server: McpAsyncServer) : ToolRegistry {

    override fun getToolNames(): List<String> {
        return server.toolNames()
    }

    override fun getToolCount(): Int = getToolNames().size

    override fun hasToolNamed(name: String): Boolean = getToolNames().contains(name)

}
