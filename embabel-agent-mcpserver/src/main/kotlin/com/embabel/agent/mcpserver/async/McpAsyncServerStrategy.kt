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

import com.embabel.agent.mcpserver.McpServerStrategy
import com.embabel.agent.mcpserver.ToolRegistry
import com.embabel.agent.mcpserver.domain.McpExecutionMode
import io.modelcontextprotocol.server.McpAsyncServer
import io.modelcontextprotocol.server.McpServerFeatures
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono

/**
 * Async server strategy implementation.
 */
class AsyncServerStrategy(
    private val server: McpAsyncServer
) : McpServerStrategy {

    private val logger = LoggerFactory.getLogger(AsyncServerStrategy::class.java)
    override val executionMode = McpExecutionMode.ASYNC

    override fun addTool(toolSpec: Any): Mono<Void> {
        return when (toolSpec) {
            is McpServerFeatures.AsyncToolSpecification -> server.addTool(toolSpec)
            else -> Mono.error(IllegalArgumentException("Expected AsyncToolSpecification, got ${toolSpec::class.simpleName}"))
        }
    }

    override fun removeTool(toolName: String): Mono<Void> {
        return server.removeTool(toolName)
    }

    override fun addResource(resourceSpec: Any): Mono<Void> {
        return when (resourceSpec) {
            is McpServerFeatures.AsyncResourceSpecification -> server.addResource(resourceSpec)
            else -> Mono.error(IllegalArgumentException("Expected AsyncResourceSpecification"))
        }
    }

    override fun addPrompt(promptSpec: Any): Mono<Void> {
        return when (promptSpec) {
            is McpServerFeatures.AsyncPromptSpecification -> server.addPrompt(promptSpec)
            else -> Mono.error(IllegalArgumentException("Expected AsyncPromptSpecification"))
        }
    }

    override fun getToolRegistry(): ToolRegistry {
        return AsyncToolRegistry(server)
    }

}
