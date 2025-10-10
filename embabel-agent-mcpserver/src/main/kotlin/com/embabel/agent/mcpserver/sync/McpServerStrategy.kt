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
 * Sync server strategy implementation.
 */
class SyncServerStrategy(
    private val server: McpSyncServer
) : McpServerStrategy {

    private val logger = LoggerFactory.getLogger(SyncServerStrategy::class.java)
    override val executionMode = McpExecutionMode.SYNC

    override fun addTool(toolSpec: Any): Mono<Void> {
        return Mono.fromRunnable {
            when (toolSpec) {
                is McpServerFeatures.SyncToolSpecification -> server.addTool(toolSpec)
                else -> throw IllegalArgumentException("Expected SyncToolSpecification")
            }
        }
    }

    override fun removeTool(toolName: String): Mono<Void> {
        return Mono.fromRunnable {
            server.removeTool(toolName)
        }
    }

    override fun addResource(resourceSpec: Any): Mono<Void> {
        return Mono.fromRunnable {
            when (resourceSpec) {
                is McpServerFeatures.SyncResourceSpecification -> server.addResource(resourceSpec)
                else -> throw IllegalArgumentException("Expected SyncResourceSpecification")
            }
        }
    }

    override fun addPrompt(promptSpec: Any): Mono<Void> {
        return Mono.fromRunnable {
            when (promptSpec) {
                is McpServerFeatures.SyncPromptSpecification -> server.addPrompt(promptSpec)
                else -> throw IllegalArgumentException("Expected SyncPromptSpecification")
            }
        }
    }

    override fun getToolRegistry(): ToolRegistry {
        return SyncToolRegistry(server)
    }

}
