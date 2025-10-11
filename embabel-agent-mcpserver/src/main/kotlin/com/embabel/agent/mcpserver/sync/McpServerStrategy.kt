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

import com.embabel.agent.mcpserver.McpServerStrategy
import com.embabel.agent.mcpserver.ToolRegistry
import com.embabel.agent.mcpserver.domain.McpExecutionMode
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.server.McpSyncServer
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono

/**
 * Strategy implementation for the MCP sync server.
 *
 * Handles registration and management of tools, resources, and prompts
 * for synchronous execution mode using [McpSyncServer].
 *
 * @property server the underlying MCP sync server instance
 */
class SyncServerStrategy(
    private val server: McpSyncServer
) : McpServerStrategy {

    private val logger = LoggerFactory.getLogger(SyncServerStrategy::class.java)

    /**
     * The execution mode for this strategy, set to synchronous.
     */
    override val executionMode = McpExecutionMode.SYNC

    /**
     * Adds a tool specification to the sync server.
     *
     * @param toolSpec the tool specification to add; must be [McpServerFeatures.SyncToolSpecification]
     * @return a [Mono] signaling completion
     * @throws IllegalArgumentException if the specification type is incorrect
     */
    override fun addTool(toolSpec: Any): Mono<Void> {
        return Mono.fromRunnable {
            when (toolSpec) {
                is McpServerFeatures.SyncToolSpecification -> server.addTool(toolSpec)
                else -> throw IllegalArgumentException("Expected SyncToolSpecification")
            }
        }
    }

    /**
     * Removes a tool from the sync server by name.
     *
     * @param toolName the name of the tool to remove
     * @return a [Mono] signaling completion
     */
    override fun removeTool(toolName: String): Mono<Void> {
        return Mono.fromRunnable {
            server.removeTool(toolName)
        }
    }

    /**
     * Adds a resource specification to the sync server.
     *
     * @param resourceSpec the resource specification to add; must be [McpServerFeatures.SyncResourceSpecification]
     * @return a [Mono] signaling completion
     * @throws IllegalArgumentException if the specification type is incorrect
     */
    override fun addResource(resourceSpec: Any): Mono<Void> {
        return Mono.fromRunnable {
            when (resourceSpec) {
                is McpServerFeatures.SyncResourceSpecification -> server.addResource(resourceSpec)
                else -> throw IllegalArgumentException("Expected SyncResourceSpecification")
            }
        }
    }

    /**
     * Adds a prompt specification to the sync server.
     *
     * @param promptSpec the prompt specification to add; must be [McpServerFeatures.SyncPromptSpecification]
     * @return a [Mono] signaling completion
     * @throws IllegalArgumentException if the specification type is incorrect
     */
    override fun addPrompt(promptSpec: Any): Mono<Void> {
        return Mono.fromRunnable {
            when (promptSpec) {
                is McpServerFeatures.SyncPromptSpecification -> server.addPrompt(promptSpec)
                else -> throw IllegalArgumentException("Expected SyncPromptSpecification")
            }
        }
    }

    /**
     * Returns the tool registry for the sync server.
     *
     * @return a [ToolRegistry] instance for synchronous tools
     */
    override fun getToolRegistry(): ToolRegistry {
        return SyncToolRegistry(server)
    }

}
