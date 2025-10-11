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
 * Implements the async server strategy for MCP servers.
 *
 * Delegates tool, resource, and prompt management to the underlying `McpAsyncServer` instance.
 * Provides an async execution mode and access to the tool registry.
 *
 * @property server the asynchronous MCP server instance
 */
class AsyncServerStrategy(
    private val server: McpAsyncServer
) : McpServerStrategy {

    private val logger = LoggerFactory.getLogger(AsyncServerStrategy::class.java)

    /**
     * The execution mode for this strategy, set to ASYNC.
     */
    override val executionMode = McpExecutionMode.ASYNC

    /**
     * Adds a tool to the async server.
     *
     * @param toolSpec the tool specification, must be of type `AsyncToolSpecification`
     * @return a `Mono<Void>` indicating completion or error
     */
    override fun addTool(toolSpec: Any): Mono<Void> {
        return when (toolSpec) {
            is McpServerFeatures.AsyncToolSpecification -> server.addTool(toolSpec)
            else -> Mono.error(IllegalArgumentException("Expected AsyncToolSpecification, got ${toolSpec::class.simpleName}"))
        }
    }

    /**
     * Removes a tool from the async server by name.
     *
     * @param toolName the name of the tool to remove
     * @return a `Mono<Void>` indicating completion
     */
    override fun removeTool(toolName: String): Mono<Void> {
        return server.removeTool(toolName)
    }

    /**
     * Adds a resource to the async server.
     *
     * @param resourceSpec the resource specification, must be of type `AsyncResourceSpecification`
     * @return a `Mono<Void>` indicating completion or error
     */
    override fun addResource(resourceSpec: Any): Mono<Void> {
        return when (resourceSpec) {
            is McpServerFeatures.AsyncResourceSpecification -> server.addResource(resourceSpec)
            else -> Mono.error(IllegalArgumentException("Expected AsyncResourceSpecification"))
        }
    }

    /**
     * Adds a prompt to the async server.
     *
     * @param promptSpec the prompt specification, must be of type `AsyncPromptSpecification`
     * @return a `Mono<Void>` indicating completion or error
     */
    override fun addPrompt(promptSpec: Any): Mono<Void> {
        return when (promptSpec) {
            is McpServerFeatures.AsyncPromptSpecification -> server.addPrompt(promptSpec)
            else -> Mono.error(IllegalArgumentException("Expected AsyncPromptSpecification"))
        }
    }

    /**
     * Returns the tool registry for the async server.
     *
     * @return an instance of `AsyncToolRegistry`
     */
    override fun getToolRegistry(): ToolRegistry {
        return AsyncToolRegistry(server)
    }

}
