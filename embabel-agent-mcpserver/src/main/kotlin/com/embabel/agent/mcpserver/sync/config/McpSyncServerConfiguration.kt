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
package com.embabel.agent.mcpserver.sync.config

import com.embabel.agent.event.logging.LoggingPersonality.Companion.BANNER_WIDTH
import com.embabel.agent.mcpserver.support.toolNames
import com.embabel.agent.mcpserver.sync.McpPromptPublisher
import com.embabel.agent.mcpserver.sync.McpResourcePublisher
import com.embabel.agent.mcpserver.McpToolExportCallbackPublisher
import com.embabel.agent.spi.support.AgentScanningBeanPostProcessorEvent
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.server.McpSyncServer
import org.apache.catalina.util.ServerInfo
import org.slf4j.LoggerFactory
import org.springframework.ai.mcp.McpToolUtils
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.*
import org.springframework.context.event.EventListener
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * Provides a hello banner for the MCP server.
 */
internal class McpSyncBanner {

    @Tool(
        description = "Display a Embabel welcome banner with server information"
    )
    fun helloBanner(): Map<String, Any> {
        val separator = "~".repeat(HELLO_BANNER_WIDTH)
        return mapOf(
            "type" to "banner",
            "lines" to listOf(
                separator,
                "Embabel Agent MCP Sync Server",
                "Server info: ${ServerInfo.getServerInfo()}",
                "Java info: ${System.getProperty("java.runtime.version")}",
                separator
            )
        )
    }

    companion object {
        private const val HELLO_BANNER_WIDTH = 50
    }
}

/**
 * Condition that checks if MCP server is enabled and of type SYNC.
 */
class McpSyncServerCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        val environment = context.environment
        val enabled = environment.getProperty("embabel.agent.mcpserver.enabled", Boolean::class.java, false)
        val type = environment.getProperty("embabel.agent.mcpserver.type", "SYNC")

        return enabled && type == "SYNC"
    }
}

/**
 * Configures MCP sync server. Exposes a limited number of tools.
 */
@Configuration
@Conditional(McpSyncServerCondition::class)
class McpSyncServerConfiguration(
    private val applicationContext: ConfigurableApplicationContext,
) {

    private val logger = LoggerFactory.getLogger(McpSyncServerConfiguration::class.java)

    /**
     * Currently MCP Server is configured by AutoConfiguration, which requires
     * at least one ToolCallbackProvider bean to be present in the context in order
     * to build it with Tools Capability.
     *
     * Provides a simple banner tool callback to display a welcome message.
     */
    @Bean
    fun helloBannerCallback(): ToolCallbackProvider {
        return MethodToolCallbackProvider.builder().toolObjects(McpSyncBanner()).build()
    }

    /**
     * Configures and initializes MCP server tool callbacks, prompts and resources when the agent scanning process completes.
     *
     * This event-driven approach ensures that all tool callbacks are properly registered only after
     * the application context is fully initialized and all agent beans have been processed and deployed.
     * Without this synchronization, the MCP server might start without access to all available tools.
     */
    @EventListener(AgentScanningBeanPostProcessorEvent::class)
    fun exposeMcpFunctionality() {
        val mcpSyncServer = applicationContext.getBean(McpSyncServer::class.java)
        exposeMcpTools(mcpSyncServer)
        exposeMcpPrompts(mcpSyncServer)
        exposeMcpResources(mcpSyncServer)
    }

    private fun exposeMcpResources(mcpSyncServer: McpSyncServer) {
        val mcpResourcePublishers =
            applicationContext.getBeansOfType(McpResourcePublisher::class.java).values.toList()
        val allResources = mcpResourcePublishers.flatMap { it.resources() }
        logger.info(
            "Exposing a total of {} MCP server resources:\n\t{}",
            allResources.size,
            allResources.joinToString("\n\t") { "${it.resource.name}: ${it.resource.description}" }
        )
        for (resource in allResources) {
            mcpSyncServer.addResource(resource)
        }
    }

    private fun exposeMcpTools(mcpSyncServer: McpSyncServer) {
        val mcpToolExportCallbackPublishers =
            applicationContext.getBeansOfType(McpToolExportCallbackPublisher::class.java).values.toList()
        val allToolCallbacks = mcpToolExportCallbackPublishers.flatMap { it.toolCallbacks }
        val separator = "~ MCP " + "~".repeat(BANNER_WIDTH - 6)
        logger.info(
            "\n${separator}\n{} MCP tool exporters: {}\nExposing a total of {} MCP server tools:\n\t{}\n${separator}",
            mcpToolExportCallbackPublishers.size,
            mcpToolExportCallbackPublishers.map { it.infoString(verbose = true) },
            allToolCallbacks.size,
            allToolCallbacks.joinToString(
                "\n\t"
            ) { "${it.toolDefinition.name()}: ${it.toolDefinition.description()}" }
        )

        val toolsToRemove = mcpSyncServer.toolNames()
            .filter { it != "helloBanner" } // keep the hello banner tool
        logger.info(
            "Removing {} tools from MCP server: {}", toolsToRemove.size,
            toolsToRemove.joinToString(", "),
        )
        for (tool in toolsToRemove) {
            mcpSyncServer.removeTool(tool)
        }

        val agentTools = McpToolUtils.toSyncToolSpecification(allToolCallbacks)
        for (agentTool in agentTools) {
            mcpSyncServer.addTool(agentTool)
        }
    }

    private fun exposeMcpPrompts(mcpSyncServer: McpSyncServer) {
        val mcpPromptPublishers =
            applicationContext.getBeansOfType(McpPromptPublisher::class.java).values.toList()
        val allPrompts = mcpPromptPublishers.flatMap { it.prompts() }
        logger.info(
            "Exposing a total of {} MCP server prompts:\n\t{}",
            allPrompts.size,
            allPrompts.joinToString("\n\t") { "${it.prompt.name}: ${it.prompt.description}" }
        )
        for (prompts in allPrompts) {
            mcpSyncServer.addPrompt(prompts)
        }
    }

}
