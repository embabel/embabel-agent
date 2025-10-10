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

import com.embabel.agent.mcpserver.domain.McpExecutionMode
import com.embabel.agent.mcpserver.domain.ServerInfo
import com.embabel.agent.mcpserver.domain.ToolSpecification
import com.embabel.agent.spi.support.AgentScanningBeanPostProcessorEvent
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.event.EventListener
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Abstract base configuration that implements the template method pattern.
 * Eliminates duplication between sync and async configurations.
 */
abstract class AbstractMcpServerConfiguration(
    protected val applicationContext: ConfigurableApplicationContext
) {

    protected val logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Template method that defines the initialization sequence.
     * Subclasses implement specific steps while common logic is shared.
     */
    @EventListener(AgentScanningBeanPostProcessorEvent::class)
    fun exposeMcpFunctionality() {
        val strategy = createServerStrategy()
        val separator = createLogSeparator()

        logger.info("\n$separator\nInitializing ${strategy.executionMode} MCP Server\n$separator")

        try {
            initializeServer(strategy)
                .doOnSuccess {
                    logger.info("${strategy.executionMode} MCP Server initialization completed successfully")
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
     * Template method for server initialization sequence.
     */
    private fun initializeServer(strategy: McpServerStrategy): Mono<Void> {
        return cleanupExistingTools(strategy)
            .then(exposeTools(strategy))
            .then(exposeResources(strategy))
            .then(exposePrompts(strategy))
    }

    /**
     * Remove existing tools except those that should be preserved.
     */
    private fun cleanupExistingTools(strategy: McpServerStrategy): Mono<Void> {
        val toolRegistry = strategy.getToolRegistry()
        val toolsToRemove = toolRegistry.getToolNames()
            .filter { !shouldPreserveTool(it) }

        if (toolsToRemove.isNotEmpty()) {
            logger.info("Removing {} existing tools: {}", toolsToRemove.size, toolsToRemove.joinToString(", "))

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
     * Expose application tools to MCP server.
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
     * Expose application resources to MCP server.
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
     * Expose application prompts to MCP server.
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

    // Abstract methods that subclasses must implement
    abstract fun createServerStrategy(): McpServerStrategy
    abstract fun createBannerTool(): ToolCallbackProvider
    abstract fun getToolPublishers(): List<McpToolExportCallbackPublisher>
    abstract fun getResourcePublishers(): List<Any> // Can be sync or async publishers
    abstract fun getPromptPublishers(): List<Any> // Can be sync or async publishers
    abstract fun convertToToolSpecifications(toolCallbacks: List<Any>): List<Any>

    // Hook methods with default implementations
    protected open fun shouldPreserveTool(toolName: String): Boolean = toolName == "helloBanner"
    protected open fun createLogSeparator(): String = "~ MCP ${getExecutionMode()} " + "~".repeat(40)
    protected abstract fun getExecutionMode(): String

    // Helper methods for logging
    private fun getToolName(toolSpec: Any): String {
        return when (toolSpec) {
            is ToolSpecification<*> -> toolSpec.toolName()
            else -> "Unknown Tool"
        }
    }

    private fun getResourceName(resourceSpec: Any): String {
        return when (resourceSpec) {
            is io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification -> resourceSpec.resource()
                .name()

            is io.modelcontextprotocol.server.McpServerFeatures.AsyncResourceSpecification -> resourceSpec.resource()
                .name()

            else -> "Unknown Resource"
        }
    }

    private fun getPromptName(promptSpec: Any): String {
        return when (promptSpec) {
            is io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification -> promptSpec.prompt().name()
            is io.modelcontextprotocol.server.McpServerFeatures.AsyncPromptSpecification -> promptSpec.prompt().name()
            else -> "Unknown Prompt"
        }
    }
}

/**
 * Unified banner tool that works for both sync and async modes.
 */
class UnifiedBannerTool(private val serverInfo: ServerInfo) {

    @org.springframework.ai.tool.annotation.Tool(
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
 * Factory for creating server information.
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
