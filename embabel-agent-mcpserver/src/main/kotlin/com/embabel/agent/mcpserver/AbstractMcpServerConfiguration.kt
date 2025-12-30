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

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.mcpserver.domain.McpExecutionMode
import com.embabel.agent.mcpserver.domain.ServerInfo
import com.embabel.agent.mcpserver.domain.ToolSpecification
import com.embabel.agent.spi.support.AgentScanningBeanPostProcessorEvent
import org.slf4j.Logger
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.event.EventListener
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Abstract base configuration for MCP server modes (sync/async).
 *
 * Implements the template method pattern to share initialization logic
 * between sync and async server configurations. Handles tool, resource,
 * and prompt exposure, and provides hooks for subclass customization.
 *
 * @property applicationContext the Spring application context
 */
abstract class AbstractMcpServerConfiguration(
    protected val applicationContext: ConfigurableApplicationContext,
) {

    // Subclasses should set this so they don't get CGLIB proxy names
    protected abstract val logger: Logger

    /**
     * Event listener that triggers MCP server initialization after agent scanning.
     *
     * Initializes the server by exposing tools, resources, and prompts
     * using the strategy provided by the subclass.
     */
    @EventListener(AgentScanningBeanPostProcessorEvent::class)
    fun exposeMcpFunctionality() {
        val strategy = createServerStrategy()

        logger.info("Initializing {} MCP Server", strategy.executionMode)

        try {
            initializeServer(strategy)
                .doOnSuccess {
                    logger.info("{} MCP Server initialization completed successfully", strategy.executionMode)
                }
                .doOnError { error ->
                    logger.error("${strategy.executionMode} MCP Server initialization failed", error)
                }
                .subscribe()
        } catch (e: Exception) {
            logger.error("Failed to initialize MCP server", e)
        }
    }

    /**
     * Initializes the MCP server by cleaning up tools and exposing new ones.
     *
     * @param strategy the server strategy for sync or async mode
     * @return a [Mono] signaling completion
     */
    private fun initializeServer(strategy: McpServerStrategy): Mono<Void> {
        return cleanupExistingTools(strategy)
            .then(exposeTools(strategy))
            .then(exposeResources(strategy))
            .then(exposePrompts(strategy))
    }

    /**
     * Removes existing tools except those that should be preserved.
     *
     * @param strategy the server strategy
     * @return a [Mono] signaling completion
     */
    private fun cleanupExistingTools(strategy: McpServerStrategy): Mono<Void> {
        val toolRegistry = strategy.getToolRegistry()
        val toolsToRemove = toolRegistry.getToolNames()
            .filter { !shouldPreserveTool(it) }

        if (toolsToRemove.isNotEmpty()) {
            logger.debug("Removing {} existing tools: {}", toolsToRemove.size, toolsToRemove.joinToString(", "))

            return Flux.fromIterable(toolsToRemove)
                .flatMap { toolName ->
                    strategy.removeTool(toolName)
                        .doOnError { error ->
                            logger.warn("Failed to remove tool '$toolName'", error)
                        }
                        .onErrorResume { Mono.empty() } // Continue with other tools
                }
                .then()
        }

        return Mono.empty()
    }

    /**
     * Exposes application tools to the MCP server.
     *
     * @param strategy the server strategy
     * @return a [Mono] signaling completion
     */
    private fun exposeTools(strategy: McpServerStrategy): Mono<Void> {
        val toolPublishers = getToolPublishers()
        val allTools = toolPublishers.flatMap { it.toolCallbacks }

        logger.info("Exposing {} tools from {} publishers", allTools.size, toolPublishers.size)

        return Flux.fromIterable(convertToToolSpecifications(allTools))
            .flatMap { toolSpec ->
                strategy.addTool(toolSpec)
                    .doOnSuccess {
                        logger.debug("Added tool: ${getToolName(toolSpec)}")
                    }
                    .doOnError { error ->
                        logger.error("Failed to add tool: ${getToolName(toolSpec)}", error)
                    }
                    .onErrorResume { Mono.empty() } // Continue with other tools
            }
            .then()
    }

    /**
     * Exposes application resources to the MCP server.
     *
     * @param strategy the server strategy
     * @return a [Mono] signaling completion
     */
    private fun exposeResources(strategy: McpServerStrategy): Mono<Void> {
        val resourcePublishers = getResourcePublishers()
        val allResources = resourcePublishers.flatMap { publisher ->
            when (publisher) {
                is com.embabel.agent.mcpserver.sync.McpResourcePublisher -> publisher.resources()
                is com.embabel.agent.mcpserver.async.McpAsyncResourcePublisher -> publisher.resources()
                else -> emptyList()
            }
        }

        logger.info("Exposing {} resources from {} publishers", allResources.size, resourcePublishers.size)

        return Flux.fromIterable(allResources)
            .flatMap { resourceSpec ->
                strategy.addResource(resourceSpec)
                    .doOnSuccess {
                        logger.debug("Added resource: ${getResourceName(resourceSpec)}")
                    }
                    .doOnError { error ->
                        logger.error("Failed to add resource: ${getResourceName(resourceSpec)}", error)
                    }
                    .onErrorResume { Mono.empty() } // Continue with other resources
            }
            .then()
    }

    /**
     * Exposes application prompts to the MCP server.
     *
     * @param strategy the server strategy
     * @return a [Mono] signaling completion
     */
    private fun exposePrompts(strategy: McpServerStrategy): Mono<Void> {
        val promptPublishers = getPromptPublishers()
        val allPrompts = promptPublishers.flatMap { publisher ->
            when (publisher) {
                is com.embabel.agent.mcpserver.sync.McpPromptPublisher -> publisher.prompts()
                is com.embabel.agent.mcpserver.async.McpAsyncPromptPublisher -> publisher.prompts()
                else -> emptyList()
            }
        }

        logger.info("Exposing {} prompts from {} publishers", allPrompts.size, promptPublishers.size)

        return Flux.fromIterable(allPrompts)
            .flatMap { promptSpec ->
                strategy.addPrompt(promptSpec)
                    .doOnSuccess {
                        logger.debug("Added prompt: ${getPromptName(promptSpec)}")
                    }
                    .doOnError { error ->
                        logger.error("Failed to add prompt: ${getPromptName(promptSpec)}", error)
                    }
                    .onErrorResume { Mono.empty() } // Continue with other prompts
            }
            .then()
    }

    /**
     * Creates the server strategy for sync or async mode.
     *
     * @return a [McpServerStrategy] instance
     */
    abstract fun createServerStrategy(): McpServerStrategy

    /**
     * Creates the banner tool callback provider.
     *
     * @return a [ToolCallbackProvider] instance
     */
    abstract fun createBannerTool(): ToolCallbackProvider

    /**
     * Retrieves all tool publishers registered in the application context.
     *
     * @return a list of [McpExportToolCallbackPublisher] beans
     */
    abstract fun getToolPublishers(): List<McpExportToolCallbackPublisher>

    /**
     * Retrieves all resource publishers registered in the application context.
     *
     * @return a list of resource publisher beans (sync or async)
     */
    abstract fun getResourcePublishers(): List<Any> // Can be sync or async publishers

    /**
     * Retrieves all prompt publishers registered in the application context.
     *
     * @return a list of prompt publisher beans (sync or async)
     */
    abstract fun getPromptPublishers(): List<Any> // Can be sync or async publishers

    /**
     * Converts tool callback objects to tool specifications for the server.
     *
     * @param toolCallbacks a list of tool callback objects
     * @return a list of tool specifications
     */
    abstract fun convertToToolSpecifications(toolCallbacks: List<Any>): List<Any>

    /**
     * Determines if a tool should be preserved during cleanup.
     *
     * @param toolName the name of the tool
     * @return `true` if the tool should be preserved, otherwise `false`
     */
    protected open fun shouldPreserveTool(toolName: String): Boolean = toolName == "helloBanner"

    /**
     * Returns the execution mode for this configuration.
     *
     * @return the execution mode string
     */
    protected abstract fun getExecutionMode(): String

    /**
     * Helper to extract the tool name from a specification.
     *
     * @param toolSpec the tool specification object
     * @return the tool name or "Unknown Tool"
     */
    private fun getToolName(toolSpec: Any): String {
        return when (toolSpec) {
            is ToolSpecification<*> -> toolSpec.toolName()
            else -> "Unknown Tool"
        }
    }

    /**
     * Helper to extract the resource name from a specification.
     *
     * @param resourceSpec the resource specification object
     * @return the resource name or "Unknown Resource"
     */
    private fun getResourceName(resourceSpec: Any): String {
        return when (resourceSpec) {
            is io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification -> resourceSpec.resource()
                .name()

            is io.modelcontextprotocol.server.McpServerFeatures.AsyncResourceSpecification -> resourceSpec.resource()
                .name()

            else -> "Unknown Resource"
        }
    }

    /**
     * Helper to extract the prompt name from a specification.
     *
     * @param promptSpec the prompt specification object
     * @return the prompt name or "Unknown Prompt"
     */
    private fun getPromptName(promptSpec: Any): String {
        return when (promptSpec) {
            is io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification -> promptSpec.prompt().name()
            is io.modelcontextprotocol.server.McpServerFeatures.AsyncPromptSpecification -> promptSpec.prompt().name()
            else -> "Unknown Prompt"
        }
    }
}

/**
 * Unified banner tool for displaying server information.
 *
 * Works for both sync and async server modes.
 *
 * @property serverInfo information about the current server instance
 */
class UnifiedBannerTool(private val serverInfo: ServerInfo) {

    /**
     * Displays a welcome banner with server information.
     *
     * @return a map containing banner details
     */
    @LlmTool(
        description = "Display a welcome banner with server information"
    )
    fun helloBanner(): Map<String, Any> {
        return mapOf(
            "type" to "banner",
            "mode" to serverInfo.mode.toString(),
            "lines" to serverInfo.toBannerLines()
        )
    }
}

/**
 * Factory for creating [ServerInfo] instances.
 */
object ServerInfoFactory {
    fun create(mode: McpExecutionMode): ServerInfo {
        return ServerInfo(
            name = "Embabel Agent MCP Server",
            version = ServerInfoFactory::class.java.`package`.implementationVersion ?: "development",
            mode = mode,
            javaVersion = System.getProperty("java.runtime.version")
        )
    }
}
