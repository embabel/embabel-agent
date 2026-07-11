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
package com.embabel.agent.mcpserver.health

import com.embabel.agent.mcpserver.async.AsyncToolRegistry
import com.embabel.agent.mcpserver.domain.McpExecutionMode
import com.embabel.agent.mcpserver.domain.ServerHealthStatus
import com.embabel.agent.mcpserver.sync.SyncToolRegistry
import io.modelcontextprotocol.server.McpAsyncServer
import io.modelcontextprotocol.server.McpSyncServer
import org.springframework.context.ConfigurableApplicationContext

/**
 * Builds a [ServerHealthStatus] from initialization state, tool registry, and health properties.
 */
class McpServerHealthEvaluator(
    private val tracker: McpServerInitializationTracker,
    private val properties: McpServerHealthProperties,
    private val applicationContext: ConfigurableApplicationContext,
) {

    fun evaluate(): ServerHealthStatus {
        val mode = currentMode()
        val toolCount = currentToolCount()
        val issues = mutableListOf<String>()
        issues.addAll(tracker.issues)

        when (tracker.state) {
            McpServerInitializationState.NOT_STARTED ->
                issues.add("MCP server has not started initialization")

            McpServerInitializationState.INITIALIZING ->
                issues.add("MCP server is still initializing")

            McpServerInitializationState.FAILED -> {
                if (issues.isEmpty()) {
                    issues.add("MCP server initialization failed")
                }
            }

            McpServerInitializationState.READY -> {
                if (toolCount < properties.minTools) {
                    issues.add(
                        "Registered tool count $toolCount is below minimum ${properties.minTools}",
                    )
                }
            }
        }

        val healthy = tracker.state == McpServerInitializationState.READY &&
            toolCount >= properties.minTools &&
            tracker.issues.isEmpty()

        return ServerHealthStatus(
            isHealthy = healthy,
            mode = mode,
            toolCount = toolCount,
            issues = issues,
        )
    }

    fun currentMode(): McpExecutionMode {
        val type = applicationContext.environment.getProperty("spring.ai.mcp.server.type", "SYNC")
        return if (type.equals("ASYNC", ignoreCase = true)) {
            McpExecutionMode.ASYNC
        } else {
            McpExecutionMode.SYNC
        }
    }

    fun currentToolCount(): Int {
        return when (currentMode()) {
            McpExecutionMode.ASYNC -> {
                val server = applicationContext.getBeanProvider(McpAsyncServer::class.java).ifAvailable
                    ?: return 0
                AsyncToolRegistry(server).getToolCount()
            }

            McpExecutionMode.SYNC -> {
                val server = applicationContext.getBeanProvider(McpSyncServer::class.java).ifAvailable
                    ?: return 0
                SyncToolRegistry(server).getToolCount()
            }
        }
    }
}
