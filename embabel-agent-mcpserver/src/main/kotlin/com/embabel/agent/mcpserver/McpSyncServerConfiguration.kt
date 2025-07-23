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

import com.embabel.agent.event.logging.LoggingPersonality.Companion.BANNER_WIDTH
import com.embabel.agent.spi.support.AgentScanningBeanPostProcessorEvent
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.server.McpSyncServer
import org.apache.catalina.util.ServerInfo
import org.slf4j.LoggerFactory
import org.springframework.ai.mcp.McpToolUtils
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener


/**
 * Provides a hello banner for the MCP server.
 */
internal class BannerTool {

    @Tool(
        description = "Display a welcome banner with server information"
    )
    fun helloBanner(): String {
        val separator = "~".repeat(HELLO_BANNER_WIDTH)
        return "\n${separator}\n" +
                "Embabel Agent MCP server\n" +
                "Server info: ${ServerInfo.getServerInfo()}\n" +
                "Java info: ${System.getProperty("java.runtime.version")}\n" +
                "${separator}\n"
    }

    companion object {
        private const val HELLO_BANNER_WIDTH = 50
    }
}


/**
 * Configures MCP sync server. Exposes a limited number of tools.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.ANY)
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
        return MethodToolCallbackProvider.builder().toolObjects(BannerTool()).build()
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

        val toolsToRemove = sneakilyGetTools(mcpSyncServer)
        logger.info(
            "Removing {} tools from MCP server: {}", toolsToRemove.size,
            toolsToRemove.joinToString(", "),
        )
        for (tool in toolsToRemove) {
            mcpSyncServer.removeTool(tool)
        }

        val agentTools = McpToolUtils.toSyncToolSpecification(allToolCallbacks)
        for (agentTool in agentTools) {
            mcpSyncServer.addTool(agentTool);
        }
    }

    // We will remove this when we get tool list support in the MCP library
    private fun sneakilyGetTools(mcpSyncServer: McpSyncServer): List<String> {
        val asyncServer = mcpSyncServer.asyncServer
        try {
            //	private final CopyOnWriteArrayList<McpServerFeatures.AsyncToolSpecification> tools = new CopyOnWriteArrayList<>();
            val toolsField = asyncServer.javaClass.getDeclaredField("tools")
            toolsField.setAccessible(true)
            @Suppress("UNCHECKED_CAST")
            val tools = toolsField.get(asyncServer) as List<McpServerFeatures.AsyncToolSpecification>
            return tools.map { it.tool.name() }
        } catch (t: Throwable) {
            logger.warn("Failed to sneakily get tools from MCP server: {}", t.message, t)
        }
        return emptyList()
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
